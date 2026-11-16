package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The download settings (Phase 6 Stage 4, Settings → Downloads), for both kinds, kept by the backend in
 * `<data dir>/downloads.json` because the backend applies them.
 */
@Serializable
data class DownloadSettings(
    /** Downloads at once, all sources and both kinds together (1 to 10). */
    val maxParallel: Int = 3,
    /** Downloads at once from one source (1 to 3). */
    val perSource: Int = 1,
    /**
     * Queue the new chapters a library update check finds, for the entries in categories with Downloads on (upstream's
     * `includeInDownload` rule). Off on a fresh install (the user's decision).
     */
    val autoDownload: Boolean = false,
) {
    /** Only the fields that aren't null change ([DownloadSettingsStore.update]). */
    data class Patch(
        val maxParallel: Int? = null,
        val perSource: Int? = null,
        val autoDownload: Boolean? = null,
    )

    companion object {
        val PARALLEL = 1..10
        val PER_SOURCE = 1..3
    }
}

/**
 * Where the [DownloadSettings] live: `<data dir>/downloads.json`, read once per data dir and written on every change
 * (to a temporary file, then moved over), as the other settings files. A missing file is the defaults; one that can't
 * be read is logged and the defaults used; a limit out of range is brought into it.
 */
object DownloadSettingsStore {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }

    const val FILE_NAME = "downloads.json"

    /** Tests: another file than the data dir's. */
    @Volatile
    var fileOverride: File? = null

    private var loaded: Pair<File, DownloadSettings>? = null

    /** Called after every change, outside the lock (the queue starts what the new limits allow). */
    @Volatile
    var onChange: ((DownloadSettings) -> Unit)? = null

    fun file(): File = fileOverride ?: File(applicationDirs.dataRoot, FILE_NAME)

    @Synchronized
    fun current(): DownloadSettings {
        val file = file().absoluteFile
        loaded?.let { (f, s) -> if (f == file) return s }
        val settings = read(file)
        loaded = file to settings
        return settings
    }

    /** Applies [patch] (validated first: nothing changes if a field is wrong), writes the file, returns the result. */
    fun update(patch: DownloadSettings.Patch): DownloadSettings {
        patch.maxParallel?.let { check("maxParallel", it, DownloadSettings.PARALLEL) }
        patch.perSource?.let { check("perSource", it, DownloadSettings.PER_SOURCE) }
        val (before, next) =
            synchronized(this) {
                val before = current()
                val next =
                    before.copy(
                        maxParallel = patch.maxParallel ?: before.maxParallel,
                        perSource = patch.perSource ?: before.perSource,
                        autoDownload = patch.autoDownload ?: before.autoDownload,
                    )
                if (next != before) {
                    write(file().absoluteFile, next)
                    loaded = file().absoluteFile to next
                }
                before to next
            }
        if (next != before) {
            logger.info {
                "download settings changed: maxParallel=${next.maxParallel} perSource=${next.perSource} autoDownload=${next.autoDownload}"
            }
            onChange?.invoke(next)
        }
        return next
    }

    /** Tests: forget what was read, so the next [current] reads the file again. */
    @Synchronized
    fun reload() {
        loaded = null
    }

    private fun check(
        field: String,
        value: Int,
        range: IntRange,
    ) {
        if (value !in range) {
            throw MadoyomiException(
                ErrorCode.BAD_REQUEST,
                "$field: must be ${range.first} to ${range.last}",
                mapOf("field" to field, "value" to value),
            )
        }
    }

    private fun read(file: File): DownloadSettings {
        if (!file.isFile) return DownloadSettings()
        return runCatching {
            val s = json.decodeFromString(DownloadSettings.serializer(), file.readText(Charsets.UTF_8))
            s.copy(
                maxParallel = s.maxParallel.coerceIn(DownloadSettings.PARALLEL),
                perSource = s.perSource.coerceIn(DownloadSettings.PER_SOURCE),
            )
        }.onFailure { logger.warn(it) { "could not read ${file.path}; using the default download settings" } }
            .getOrDefault(DownloadSettings())
    }

    private fun write(
        file: File,
        settings: DownloadSettings,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(DownloadSettings.serializer(), settings) + "\n", Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
