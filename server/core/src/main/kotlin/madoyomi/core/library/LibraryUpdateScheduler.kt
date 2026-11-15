package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.global.model.table.UserAccountTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.util.HAScheduler
import java.util.Date

/**
 * The scheduled library update check (Phase 6 Stage 3): every [LibraryUpdateSettings.intervalHours] (off by default,
 * the user's decision), both kinds, every user's manga library. Upstream's `HAScheduler`, which also catches up on a
 * check missed while the machine slept.
 *
 * The schedule counts from [LibraryUpdateSettings.scheduleAnchorAt] (the last scheduled check, or when the schedule
 * was turned on), kept in the settings file, so a restart doesn't push the next check back: one that came due while
 * the app was closed starts [startupDelayMs] after the start. A check that comes due while one runs is skipped.
 *
 * `MADOYOMI_UPDATE_INTERVAL_SECONDS` (env) or `madoyomi.updateIntervalSeconds` (system property) replaces the interval
 * while the schedule is on: for live checks with a short interval.
 */
object LibraryUpdateScheduler {
    private val logger = KotlinLogging.logger {}

    /** How long after the start an overdue check waits (the extensions load first). */
    @Volatile
    var startupDelayMs: Long = 60_000

    /** Tests: the interval in milliseconds while the schedule is on, in place of the settings' and the override's. */
    @Volatile
    var intervalOverrideMs: Long? = null

    private var taskId: String? = null

    /** Epoch milliseconds of the next scheduled check, or null while the schedule is off. */
    @Volatile
    var nextAt: Long? = null
        private set

    /** Schedules from the settings, and again after every change to the interval. */
    fun start() {
        LibraryUpdateSettingsStore.onChange = { reschedule() }
        reschedule()
    }

    /** Stops scheduling (tests). */
    @Synchronized
    fun stop() {
        taskId?.let(HAScheduler::deschedule)
        taskId = null
        nextAt = null
        LibraryUpdateSettingsStore.onChange = null
    }

    @Synchronized
    fun reschedule(now: Long = System.currentTimeMillis()) {
        taskId?.let(HAScheduler::deschedule)
        taskId = null
        nextAt = null
        var settings = LibraryUpdateSettingsStore.current()
        val interval = intervalMs(settings) ?: return logger.info { "scheduled library update: off" }
        if (settings.scheduleAnchorAt == 0L) settings = LibraryUpdateSettingsStore.anchor(now)
        val delay = nextDelay(now, settings.scheduleAnchorAt, interval, startupDelayMs)
        nextAt = now + delay
        taskId = HAScheduler.schedule(::fire, interval, delay, "madoyomi-library-update")
        logger.info { "scheduled library update: every ${interval / 1000} s, next at ${Date(now + delay)}" }
    }

    /** The interval in milliseconds while the schedule is on, else null. */
    fun intervalMs(settings: LibraryUpdateSettings): Long? {
        if (settings.intervalHours == 0) return null
        intervalOverrideMs?.let { return it }
        val seconds =
            (System.getProperty("madoyomi.updateIntervalSeconds") ?: System.getenv("MADOYOMI_UPDATE_INTERVAL_SECONDS"))
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        return seconds?.times(1000) ?: (settings.intervalHours * 3_600_000L)
    }

    /**
     * The delay before the next check: one [interval] after [anchor], or [startupDelay] when that is past (or nearer
     * than [startupDelay] would be, so a start never runs a check sooner than it).
     */
    fun nextDelay(
        now: Long,
        anchor: Long,
        interval: Long,
        startupDelay: Long,
    ): Long {
        val due = anchor + interval - now
        return if (due <= 0) startupDelay.coerceAtMost(interval) else due
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        LibraryUpdateSettingsStore.anchor(now)
        val interval = intervalMs(LibraryUpdateSettingsStore.current())
        nextAt = interval?.let { now + it }
        try {
            if (LibraryUpdater.status.value.running) {
                logger.info { "scheduled library update skipped: a check is running" }
                return
            }
            val users = dbTransaction { UserAccountTable.selectAll().map { it[UserAccountTable.id].value } }
            val targets =
                listOf(LibraryUpdater.Target(ExtensionKind.ANIME)) + users.map { LibraryUpdater.Target(ExtensionKind.MANGA, userId = it) }
            LibraryUpdater.start(targets, LibraryUpdater.Trigger.SCHEDULED)
        } catch (e: Exception) {
            logger.error(e) { "scheduled library update failed to start" }
        }
    }
}
