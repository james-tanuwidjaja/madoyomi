package madoyomi.core.backup

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
import suwayomi.tachidesk.util.HAScheduler
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date

/** The scheduled backups (Settings → Backup), in `<data dir>/backup-settings.json`. */
@Serializable
data class BackupSettings(
    /** Hours between scheduled backups: 0 (off on a fresh install, the user's decision), 24 (daily) or 168 (weekly). */
    val intervalHours: Int = 0,
    /** How many scheduled backups are kept (the newest; manual ones are never pruned). */
    val keep: Int = 7,
    /** Epoch milliseconds the schedule counts from (the last scheduled backup, or when it was turned on). */
    val scheduleAnchorAt: Long = 0,
) {
    data class Patch(
        val intervalHours: Int? = null,
        val keep: Int? = null,
    )

    companion object {
        val INTERVALS = listOf(0, 24, 168)
        val KEEP = 1..50
    }
}

/** Where the [BackupSettings] live; read once per data dir, written on every change (as the other settings files). */
object BackupSettingsStore {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }

    const val FILE_NAME = "backup-settings.json"

    /** Tests: another file than the data dir's. */
    @Volatile
    var fileOverride: File? = null

    private var loaded: Pair<File, BackupSettings>? = null

    /** Called after a change to the interval, outside the lock (the scheduler reschedules). */
    @Volatile
    var onChange: ((BackupSettings) -> Unit)? = null

    fun file(): File = fileOverride ?: File(applicationDirs.dataRoot, FILE_NAME)

    @Synchronized
    fun current(): BackupSettings {
        val file = file().absoluteFile
        loaded?.let { (f, s) -> if (f == file) return s }
        val settings = read(file)
        loaded = file to settings
        return settings
    }

    fun update(
        patch: BackupSettings.Patch,
        now: Long = System.currentTimeMillis(),
    ): BackupSettings {
        patch.intervalHours?.let {
            if (it !in BackupSettings.INTERVALS) bad("intervalHours", it, "must be one of ${BackupSettings.INTERVALS.joinToString()}")
        }
        patch.keep?.let { if (it !in BackupSettings.KEEP) bad("keep", it, "must be 1 to 50") }
        val (before, next) =
            synchronized(this) {
                val before = current()
                val interval = patch.intervalHours ?: before.intervalHours
                val next =
                    before.copy(
                        intervalHours = interval,
                        keep = patch.keep ?: before.keep,
                        scheduleAnchorAt = if (before.intervalHours != interval && interval != 0) now else before.scheduleAnchorAt,
                    )
                if (next != before) store(next)
                before to next
            }
        if (next != before) {
            logger.info { "backup settings changed: intervalHours=${next.intervalHours} keep=${next.keep}" }
            if (next.intervalHours != before.intervalHours) onChange?.invoke(next)
            if (next.keep < before.keep) BackupFiles.prune(next.keep)
        }
        return next
    }

    @Synchronized
    fun anchor(at: Long): BackupSettings {
        val next = current().copy(scheduleAnchorAt = at)
        store(next)
        return next
    }

    /** Forget what was read (tests, and after a restore wrote the file). */
    @Synchronized
    fun reload() {
        loaded = null
    }

    private fun bad(
        field: String,
        value: Int,
        why: String,
    ): Nothing = throw MadoyomiException(ErrorCode.BAD_REQUEST, "$field: $why", mapOf("field" to field, "value" to value))

    private fun store(settings: BackupSettings) {
        val file = file().absoluteFile
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(BackupSettings.serializer(), settings) + "\n", Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        loaded = file to settings
    }

    private fun read(file: File): BackupSettings {
        if (!file.isFile) return BackupSettings()
        return runCatching {
            val s = json.decodeFromString(BackupSettings.serializer(), file.readText(Charsets.UTF_8))
            s.copy(
                intervalHours = s.intervalHours.takeIf { it in BackupSettings.INTERVALS } ?: 0,
                keep = s.keep.coerceIn(BackupSettings.KEEP),
            )
        }.onFailure { logger.warn(it) { "could not read ${file.path}; using the default backup settings" } }
            .getOrDefault(BackupSettings())
    }
}

/**
 * The scheduled backup: every [BackupSettings.intervalHours] (off by default), counted from the anchor kept in the
 * settings file as the library update schedule is ([madoyomi.core.library.LibraryUpdateScheduler]); one that came due
 * while the app was closed runs [startupDelayMs] after the start. Each run writes `Madoyomi_auto_<date>` and prunes
 * the scheduled ones beyond [BackupSettings.keep]. `MADOYOMI_BACKUP_INTERVAL_SECONDS` (env) or
 * `madoyomi.backupIntervalSeconds` replaces the interval while the schedule is on, for live checks.
 */
object BackupScheduler {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var startupDelayMs: Long = 90_000

    /** Tests: the interval in milliseconds while the schedule is on. */
    @Volatile
    var intervalOverrideMs: Long? = null

    /** The user the scheduled backups are made for (Madoyomi has one: upstream's built-in admin). */
    @Volatile
    var userId: Int = 1

    private var taskId: String? = null

    @Volatile
    var nextAt: Long? = null
        private set

    /** The last scheduled run's error, cleared by a run that works. */
    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        BackupSettingsStore.onChange = { reschedule() }
        reschedule()
    }

    @Synchronized
    fun stop() {
        taskId?.let(HAScheduler::deschedule)
        taskId = null
        nextAt = null
        BackupSettingsStore.onChange = null
    }

    @Synchronized
    fun reschedule(now: Long = System.currentTimeMillis()) {
        taskId?.let(HAScheduler::deschedule)
        taskId = null
        nextAt = null
        var settings = BackupSettingsStore.current()
        val interval = intervalMs(settings) ?: return logger.info { "scheduled backup: off" }
        if (settings.scheduleAnchorAt == 0L) settings = BackupSettingsStore.anchor(now)
        val due = settings.scheduleAnchorAt + interval - now
        val delay = if (due <= 0) startupDelayMs.coerceAtMost(interval) else due
        nextAt = now + delay
        taskId = HAScheduler.schedule(::fire, interval, delay, "madoyomi-backup")
        logger.info { "scheduled backup: every ${interval / 1000} s, keep ${settings.keep}, next at ${Date(now + delay)}" }
    }

    fun intervalMs(settings: BackupSettings): Long? {
        if (settings.intervalHours == 0) return null
        intervalOverrideMs?.let { return it }
        val seconds =
            (System.getProperty("madoyomi.backupIntervalSeconds") ?: System.getenv("MADOYOMI_BACKUP_INTERVAL_SECONDS"))
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        return seconds?.times(1000) ?: (settings.intervalHours * 3_600_000L)
    }

    /** Makes a scheduled backup now and prunes; returns its file. */
    fun runOnce(now: Long = System.currentTimeMillis()): File {
        val file = BackupFiles.write(BackupFiles.encode(MadoyomiBackupExport.create(userId, automatic = true, now = now)), true, now)
        BackupFiles.prune(BackupSettingsStore.current().keep)
        logger.info { "scheduled backup written: ${file.name} (${file.length()} bytes)" }
        return file
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        BackupSettingsStore.anchor(now)
        nextAt = intervalMs(BackupSettingsStore.current())?.let { now + it }
        try {
            runOnce(now)
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            logger.error(e) { "scheduled backup failed" }
        }
    }
}
