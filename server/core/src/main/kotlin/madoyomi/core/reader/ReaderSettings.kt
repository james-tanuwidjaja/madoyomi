package madoyomi.core.reader

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
 * The reader settings the backend applies (Phase 5 Stage 1, Settings → Reader): the page cache's size cap
 * ([PageCache]). They are kept in the data dir (`reader-settings.json`), like the player settings. The reader's own
 * preferences (mode, direction, fit, …) are the app's: per device, and per manga in the manga's meta (Phase 5 §9).
 */
@Serializable
data class ReaderSettings(
    /** The page cache's cap in MiB: [MIN_CACHE_MB] to [MAX_CACHE_MB] (the user's decision: 1 GiB by default). */
    val pageCacheLimitMb: Int = DEFAULT_CACHE_MB,
) {
    /** Only the fields that aren't null change ([ReaderSettingsStore.update]). */
    data class Patch(
        val pageCacheLimitMb: Int? = null,
    )

    val pageCacheLimitBytes: Long get() = pageCacheLimitMb * MIB

    companion object {
        const val DEFAULT_CACHE_MB = 1024
        const val MIN_CACHE_MB = 256
        const val MAX_CACHE_MB = 10240
        const val MIB = 1024L * 1024L
    }
}

/**
 * Where the [ReaderSettings] live: `<data dir>/reader-settings.json`, read once per data dir and written on every
 * change (to a temporary file, then moved over). A missing file is the defaults; one that can't be read is logged and
 * the defaults used. Unknown keys are ignored, missing ones are defaults, a value out of range is the default.
 */
object ReaderSettingsStore {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }

    const val FILE_NAME = "reader-settings.json"

    /** Tests: another file than the data dir's. */
    @Volatile
    var fileOverride: File? = null

    private var loaded: Pair<File, ReaderSettings>? = null

    fun file(): File = fileOverride ?: File(applicationDirs.dataRoot, FILE_NAME)

    /** The settings in use (read from the file the first time for a data dir). */
    @Synchronized
    fun current(): ReaderSettings {
        val file = file().absoluteFile
        loaded?.let { (f, s) -> if (f == file) return s }
        val settings = read(file)
        loaded = file to settings
        return settings
    }

    /** Applies [patch] (validated first: nothing changes if a field is wrong), writes the file, returns the result. */
    @Synchronized
    fun update(patch: ReaderSettings.Patch): ReaderSettings {
        val now = current()
        val next =
            ReaderSettings(
                pageCacheLimitMb = patch.pageCacheLimitMb?.let(::cacheLimit) ?: now.pageCacheLimitMb,
            )
        if (next == now) return now
        write(file().absoluteFile, next)
        loaded = file().absoluteFile to next
        logger.info { "reader settings changed: pageCacheLimitMb=${next.pageCacheLimitMb}" }
        return next
    }

    /** Tests: forget what was read, so the next [current] reads the file again. */
    @Synchronized
    fun reload() {
        loaded = null
    }

    private fun read(file: File): ReaderSettings {
        if (!file.isFile) return ReaderSettings()
        return runCatching {
            val s = json.decodeFromString(ReaderSettings.serializer(), file.readText(Charsets.UTF_8))
            ReaderSettings(pageCacheLimitMb = runCatching { cacheLimit(s.pageCacheLimitMb) }.getOrDefault(ReaderSettings.DEFAULT_CACHE_MB))
        }.onFailure { logger.warn(it) { "could not read ${file.path}; using the default reader settings" } }
            .getOrDefault(ReaderSettings())
    }

    private fun write(
        file: File,
        settings: ReaderSettings,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(ReaderSettings.serializer(), settings) + "\n", Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cacheLimit(v: Int): Int {
        if (v < ReaderSettings.MIN_CACHE_MB || v > ReaderSettings.MAX_CACHE_MB) {
            throw MadoyomiException(
                ErrorCode.BAD_REQUEST,
                "pageCacheLimitMb: must be ${ReaderSettings.MIN_CACHE_MB} to ${ReaderSettings.MAX_CACHE_MB}",
                mapOf("field" to "pageCacheLimitMb", "value" to v),
            )
        }
        return v
    }
}
