package madoyomi.core.library

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
 * The library update settings (Phase 6 Stage 3, Settings → Updates), one set for both kinds, kept by the backend in
 * `<data dir>/library-update.json` because the backend applies them: the schedule and which entries a check skips.
 * The toast is shown by the app; [notify] is kept here so a backup carries it with the rest.
 */
@Serializable
data class LibraryUpdateSettings(
    /** Hours between scheduled checks: 0 (off, the user's decision), 6, 12 or 24. */
    val intervalHours: Int = 0,
    /** Skip entries whose status is completed (upstream's and Mihon's rule: on). */
    val skipCompleted: Boolean = true,
    /** Skip entries with unwatched episodes or unread chapters (off: upstream's default is on). */
    val skipUnseen: Boolean = false,
    /** Skip entries with nothing watched or read yet (off: upstream's default is on). */
    val skipNotStarted: Boolean = false,
    /** A Windows toast when a check finds new items and the window isn't focused (off, the user's decision). */
    val notify: Boolean = false,
    /**
     * Epoch milliseconds the schedule counts from: the last scheduled check, or when the schedule was turned on. Not
     * a setting: [LibraryUpdateScheduler] keeps it here so a restart doesn't push the next check back.
     */
    val scheduleAnchorAt: Long = 0,
) {
    /** Only the fields that aren't null change ([LibraryUpdateSettingsStore.update]). */
    data class Patch(
        val intervalHours: Int? = null,
        val skipCompleted: Boolean? = null,
        val skipUnseen: Boolean? = null,
        val skipNotStarted: Boolean? = null,
        val notify: Boolean? = null,
    )

    companion object {
        val INTERVALS = listOf(0, 6, 12, 24)
    }
}

/**
 * Where the [LibraryUpdateSettings] live: `<data dir>/library-update.json`, read once per data dir and written on every
 * change (to a temporary file, then moved over), as the player and reader settings. A missing file is the defaults; one
 * that can't be read is logged and the defaults used; an interval that isn't one of [LibraryUpdateSettings.INTERVALS]
 * is off.
 */
object LibraryUpdateSettingsStore {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }

    const val FILE_NAME = "library-update.json"

    /** Tests: another file than the data dir's. */
    @Volatile
    var fileOverride: File? = null

    private var loaded: Pair<File, LibraryUpdateSettings>? = null

    /** Called after every change, outside the lock (the scheduler reschedules). */
    @Volatile
    var onChange: ((LibraryUpdateSettings) -> Unit)? = null

    fun file(): File = fileOverride ?: File(applicationDirs.dataRoot, FILE_NAME)

    /** The settings in use (read from the file the first time for a data dir). */
    @Synchronized
    fun current(): LibraryUpdateSettings {
        val file = file().absoluteFile
        loaded?.let { (f, s) -> if (f == file) return s }
        val settings = read(file)
        loaded = file to settings
        return settings
    }

    /**
     * Applies [patch] (validated first: nothing changes if a field is wrong), writes the file, returns the result.
     * Turning the schedule on (from off) anchors it at [now]: the first scheduled check comes one interval later.
     */
    fun update(
        patch: LibraryUpdateSettings.Patch,
        now: Long = System.currentTimeMillis(),
    ): LibraryUpdateSettings {
        val (before, next) =
            synchronized(this) {
                val before = current()
                val interval = patch.intervalHours?.let(::interval) ?: before.intervalHours
                val next =
                    before.copy(
                        intervalHours = interval,
                        skipCompleted = patch.skipCompleted ?: before.skipCompleted,
                        skipUnseen = patch.skipUnseen ?: before.skipUnseen,
                        skipNotStarted = patch.skipNotStarted ?: before.skipNotStarted,
                        notify = patch.notify ?: before.notify,
                        scheduleAnchorAt = if (before.intervalHours == 0 && interval != 0) now else before.scheduleAnchorAt,
                    )
                if (next != before) store(next)
                before to next
            }
        if (next != before) {
            logger.info {
                "library update settings changed: intervalHours=${next.intervalHours} skipCompleted=${next.skipCompleted} " +
                    "skipUnseen=${next.skipUnseen} skipNotStarted=${next.skipNotStarted} notify=${next.notify}"
            }
            if (next.intervalHours != before.intervalHours) onChange?.invoke(next)
        }
        return next
    }

    /** Moves the schedule's anchor (a scheduled check ran, or the schedule started counting). */
    @Synchronized
    fun anchor(at: Long): LibraryUpdateSettings {
        val next = current().copy(scheduleAnchorAt = at)
        store(next)
        return next
    }

    /** Tests: forget what was read, so the next [current] reads the file again. */
    @Synchronized
    fun reload() {
        loaded = null
    }

    private fun store(settings: LibraryUpdateSettings) {
        write(file().absoluteFile, settings)
        loaded = file().absoluteFile to settings
    }

    private fun read(file: File): LibraryUpdateSettings {
        if (!file.isFile) return LibraryUpdateSettings()
        return runCatching {
            val s = json.decodeFromString(LibraryUpdateSettings.serializer(), file.readText(Charsets.UTF_8))
            if (s.intervalHours in LibraryUpdateSettings.INTERVALS) s else s.copy(intervalHours = 0)
        }.onFailure { logger.warn(it) { "could not read ${file.path}; using the default library update settings" } }
            .getOrDefault(LibraryUpdateSettings())
    }

    private fun write(
        file: File,
        settings: LibraryUpdateSettings,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(LibraryUpdateSettings.serializer(), settings) + "\n", Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun interval(v: Int): Int {
        if (v !in LibraryUpdateSettings.INTERVALS) {
            throw MadoyomiException(
                ErrorCode.BAD_REQUEST,
                "intervalHours: must be one of ${LibraryUpdateSettings.INTERVALS.joinToString()}",
                mapOf("field" to "intervalHours", "value" to v),
            )
        }
        return v
    }
}
