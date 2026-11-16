package madoyomi.core.backup

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/** What a backup file is: Madoyomi's own, or a Mihon/Suwayomi/Aniyomi `.tachibk` (its manga part only). */
enum class BackupFormat { MADOYOMI, TACHIYOMI }

/** A file in the backups folder. */
data class BackupFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    /** Epoch milliseconds. */
    val modifiedAt: Long,
    val automatic: Boolean,
    val format: BackupFormat,
)

/**
 * Encodes and decodes `.madoyomi-backup` files (gzipped protobuf) and keeps them in `<data dir>/backups`, the folder
 * upstream's own backups used (`ApplicationDirs.automatedBackupRoot`). Manual backups are `Madoyomi_<date>`, scheduled
 * ones `Madoyomi_auto_<date>`, and only those are pruned. A `.tachibk` or `.proto.gz` there is listed too: it can be
 * restored (its manga part).
 */
object BackupFiles {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    const val EXTENSION = ".madoyomi-backup"
    private val OTHER_EXTENSIONS = listOf(".tachibk", ".proto.gz")
    private const val PREFIX = "Madoyomi_"
    private const val AUTO_PREFIX = "Madoyomi_auto_"

    /** The largest file accepted (an upload, a file to restore): far above any real library. */
    const val MAX_BYTES = 256L * 1024 * 1024

    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf

    /** Tests: another folder than the data dir's. */
    @Volatile
    var dirOverride: File? = null

    fun dir(): File = (dirOverride ?: File(applicationDirs.automatedBackupRoot)).absoluteFile

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(backup: MadoyomiBackup): ByteArray {
        val bytes = protoBuf.encodeToByteArray(MadoyomiBackup.serializer(), backup)
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    }

    /** Decodes a backup, or `BAD_REQUEST` when [bytes] aren't one. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(bytes: ByteArray): Pair<MadoyomiBackup, BackupFormat> {
        val notABackup = { why: String ->
            MadoyomiException(ErrorCode.BAD_REQUEST, "Not a backup file: $why", mapOf("reason" to why))
        }
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) throw notABackup("it isn't gzipped")
        val raw =
            try {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            } catch (e: ZipException) {
                throw notABackup("it can't be unzipped (${e.message})")
            } catch (e: java.io.EOFException) {
                throw notABackup("it is cut off")
            }
        val backup =
            try {
                protoBuf.decodeFromByteArray(MadoyomiBackup.serializer(), raw)
            } catch (e: SerializationException) {
                throw notABackup("it isn't a Madoyomi, Mihon or Aniyomi backup (${e.message})")
            } catch (e: IllegalArgumentException) {
                throw notABackup("it isn't a Madoyomi, Mihon or Aniyomi backup (${e.message})")
            } catch (e: IndexOutOfBoundsException) {
                throw notABackup("it isn't a Madoyomi, Mihon or Aniyomi backup")
            }
        return when {
            backup.info != null -> backup to BackupFormat.MADOYOMI
            backup.backupManga.isNotEmpty() || backup.backupCategories.isNotEmpty() -> backup to BackupFormat.TACHIYOMI
            else -> throw notABackup("it holds no library")
        }
    }

    fun list(): List<BackupFileInfo> =
        dir()
            .listFiles()
            .orEmpty()
            .filter { it.isFile && formatOf(it.name) != null }
            .map(::info)
            .sortedByDescending { it.modifiedAt }

    fun info(file: File): BackupFileInfo =
        BackupFileInfo(
            name = file.name,
            path = file.absolutePath,
            size = file.length(),
            modifiedAt = file.lastModified(),
            automatic = file.name.startsWith(AUTO_PREFIX),
            format = formatOf(file.name) ?: BackupFormat.MADOYOMI,
        )

    private fun formatOf(name: String): BackupFormat? =
        when {
            name.endsWith(EXTENSION) -> BackupFormat.MADOYOMI
            OTHER_EXTENSIONS.any(name::endsWith) -> BackupFormat.TACHIYOMI
            else -> null
        }

    /** The file [name] in the folder, or `NOT_FOUND` (a name with a path in it is `BAD_REQUEST`). */
    fun resolve(name: String): File {
        val file = File(dir(), checkedName(name))
        if (!file.isFile) throw MadoyomiException(ErrorCode.NOT_FOUND, "No backup named $name", mapOf("name" to name))
        return file
    }

    fun read(name: String): ByteArray {
        val file = resolve(name)
        if (file.length() > MAX_BYTES) throw MadoyomiException(ErrorCode.BAD_REQUEST, "$name is too large", mapOf("name" to name))
        return file.readBytes()
    }

    /** Writes a new backup file and returns it. */
    fun write(
        bytes: ByteArray,
        automatic: Boolean,
        now: Long = System.currentTimeMillis(),
    ): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date(now))
        return store(bytes, (if (automatic) AUTO_PREFIX else PREFIX) + stamp + EXTENSION)
    }

    /**
     * Keeps a file the user picked (an upload): checked to be a backup first, stored under its own name (with " (2)"
     * and so on when that is taken). Returns it.
     */
    fun import(
        name: String,
        input: InputStream,
    ): File {
        val bytes = readCapped(input)
        decode(bytes)
        val base = File(checkedName(name)).name
        val withExtension = if (formatOf(base) == null) base + EXTENSION else base
        return store(bytes, withExtension)
    }

    fun delete(name: String) {
        val file = resolve(name)
        if (!file.delete()) throw MadoyomiException(ErrorCode.INTERNAL, "Could not delete $name", mapOf("name" to name))
        logger.info { "deleted backup ${file.name}" }
    }

    /** Deletes the scheduled backups beyond the newest [keep]; returns their names. */
    fun prune(keep: Int): List<String> {
        val old =
            dir()
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith(AUTO_PREFIX) && it.name.endsWith(EXTENSION) }
                .sortedByDescending { it.name }
                .drop(keep.coerceAtLeast(1))
        return old.mapNotNull { file -> file.name.takeIf { file.delete() } }.also {
            if (it.isNotEmpty()) logger.info { "pruned ${it.size} scheduled backups: $it" }
        }
    }

    private fun store(
        bytes: ByteArray,
        name: String,
    ): File {
        val dir = dir().also { it.mkdirs() }
        var target = File(dir, name)
        var n = 2
        while (target.exists()) {
            val stem = name.removeSuffix(suffixOf(name))
            target = File(dir, "$stem ($n)${suffixOf(name)}")
            n++
        }
        val part = File(dir, ".${target.name}.part")
        part.writeBytes(bytes)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun suffixOf(name: String): String =
        (listOf(EXTENSION) + OTHER_EXTENSIONS).firstOrNull(name::endsWith) ?: name.substringAfterLast('.', "").let {
            if (it.isEmpty()) "" else ".$it"
        }

    private fun checkedName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..") || trimmed.contains(':')) {
            throw MadoyomiException(ErrorCode.BAD_REQUEST, "Not a backup file name: $name", mapOf("name" to name))
        }
        return trimmed
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw MadoyomiException(ErrorCode.BAD_REQUEST, "The file is too large", emptyMap())
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
