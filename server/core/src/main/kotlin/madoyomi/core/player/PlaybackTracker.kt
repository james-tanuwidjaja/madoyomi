package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What one session's mpv reports over IPC, turned into saved progress and events (Phase 4 steps 5–8). Fed every IPC
 * event by [onMessage] (the reader thread), and once more by [finish] when the session ends. No I/O of its own: it
 * calls [Hooks].
 *
 * - **Observed** ([OBSERVED]): `time-pos`, `duration`, `pause`, `eof-reached`, `track-list`. Events: `seek`,
 *   `playback-restart`, `file-loaded`, `end-file`.
 * - **Saved** ([Hooks.save], milliseconds as Aniyomi stores them): every [saveIntervalMillis] while playing, at once on
 *   pause, after a seek (at the first position once playback restarts), when the threshold is reached, at the end of
 *   the file, and in [finish]. A save that would write the same values is skipped.
 * - **Watched** once per session: the first time `time-pos / duration` reaches [watchedThreshold] (read at every
 *   position, so a change in the player settings applies to the session that plays), [Hooks.markWatched]
 *   (which leaves an already-watched episode alone). It is not tried again in this session, so an episode the user
 *   unmarks while it plays stays unmarked.
 * - **Progress events** ([Hooks.progress]) at most every [progressEventMillis] while the position moves, and at once
 *   on pause, resume and seek.
 */
class PlaybackTracker(
    private val hooks: Hooks,
    private val watchedThreshold: () -> Double = { DEFAULT_WATCHED_THRESHOLD },
    private val saveIntervalMillis: Long = 5_000,
    private val progressEventMillis: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Hooks {
        /** Writes the position and duration, in milliseconds (the duration 0 while unknown). */
        fun save(
            positionMillis: Long,
            durationMillis: Long,
        )

        /** Marks the episode watched; false if it already was (nothing changed). */
        fun markWatched(): Boolean

        fun progress(snapshot: Snapshot) {}

        fun watched(snapshot: Snapshot) {}

        /** The file is open: time to add the external tracks. Called once. */
        fun fileLoaded() {}
    }

    data class Snapshot(
        val positionSeconds: Double?,
        val durationSeconds: Double?,
        val paused: Boolean,
    )

    /** How a session ended, from [finish]. */
    sealed interface Outcome {
        /** The end of the file, or a quit past the threshold. [reason] is mpv's end-file reason (`eof`, `quit`). */
        data class Ended(
            val reason: String,
        ) : Outcome

        /** mpv ended early: the file failed to play, or mpv exited with an error. */
        data class Failed(
            val message: String,
        ) : Outcome

        /** Quit by the user before the end, or stopped by Madoyomi. */
        data object Closed : Outcome
    }

    private var position: Double? = null
    private var duration: Double? = null
    private var paused = false
    private var eof = false
    private var endReason: String? = null
    private var fileError: String? = null
    private var lastSaved: Pair<Long, Long>? = null
    private var lastSaveAt = Long.MIN_VALUE / 2
    private var lastProgressAt = Long.MIN_VALUE / 2
    private var seekPending = false
    private var seekArmed = false
    private var watchedTried = false
    private var fileLoaded = false
    private var finished = false

    @Synchronized
    fun snapshot() = Snapshot(position, duration, paused)

    /** Whether the threshold was reached in this session (the mark was tried). */
    @get:Synchronized
    val reachedThreshold: Boolean get() = watchedTried

    @Synchronized
    fun onMessage(message: JsonObject) {
        if (finished) return
        when (message["event"].asText()) {
            "property-change" -> {
                onProperty(message["name"].asText(), message)
            }

            "seek" -> {
                seekPending = true
            }

            "playback-restart" -> {
                if (seekPending) {
                    seekPending = false
                    seekArmed = true
                }
            }

            "file-loaded" -> {
                loaded()
            }

            "end-file" -> {
                endReason = message["reason"].asText()
                fileError = message["file_error"].asText()
                if (endReason == "eof") eof = true
                save()
            }
        }
    }

    private fun onProperty(
        name: String?,
        message: JsonObject,
    ) {
        val data = message["data"]
        when (name) {
            "time-pos" -> {
                data.asDouble()?.let(::onPosition)
            }

            "duration" -> {
                duration = data.asDouble()?.takeIf { it > 0 }
            }

            "pause" -> {
                data.asBoolean()?.let {
                    if (it == paused) return
                    paused = it
                    if (it) save()
                    progress(force = true)
                }
            }

            "eof-reached" -> {
                if (data.asBoolean() == true) eof = true
            }

            // observing track-list sends its current value: a non-empty list means the file is open already (the
            // IPC connected after file-loaded)
            "track-list" -> {
                if ((data as? JsonArray)?.isNotEmpty() == true) loaded()
            }
        }
    }

    private fun onPosition(value: Double) {
        position = value
        val total = duration
        if (!watchedTried && total != null && value / total >= watchedThreshold()) {
            watchedTried = true
            save()
            if (hooks.markWatched()) hooks.watched(snapshot())
        }
        if (seekArmed) {
            seekArmed = false
            save()
            progress(force = true)
            return
        }
        if (!paused && clock() - lastSaveAt >= saveIntervalMillis) save()
        progress(force = false)
    }

    private fun loaded() {
        if (fileLoaded) return
        fileLoaded = true
        hooks.fileLoaded()
    }

    private fun save() {
        val pos = position ?: return
        val values = (pos * 1000).toLong().coerceAtLeast(0) to ((duration ?: 0.0) * 1000).toLong()
        lastSaveAt = clock()
        if (values == lastSaved) return
        lastSaved = values
        hooks.save(values.first, values.second)
    }

    private fun progress(force: Boolean) {
        val now = clock()
        if (!force && now - lastProgressAt < progressEventMillis) return
        lastProgressAt = now
        hooks.progress(snapshot())
    }

    /**
     * The session is over: saves the last position and says how it ended. [stopped] is a stop by Madoyomi
     * (`stopPlayback`, a new session), never an end or an error; [exitCode] is mpv's, if it exited.
     */
    @Synchronized
    fun finish(
        stopped: Boolean,
        exitCode: Int?,
    ): Outcome {
        if (finished) return Outcome.Closed
        save()
        finished = true
        if (stopped) return Outcome.Closed
        val pos = position
        val total = duration
        val nearEnd = pos != null && total != null && pos / total >= watchedThreshold()
        return when {
            eof -> Outcome.Ended("eof")
            endReason == "error" -> Outcome.Failed(fileError?.let { "mpv could not play the video: $it" } ?: "mpv could not play the video")
            exitCode != null && exitCode != 0 -> Outcome.Failed("mpv exited with code $exitCode")
            nearEnd -> Outcome.Ended(endReason ?: "quit")
            else -> Outcome.Closed
        }
    }

    companion object {
        const val DEFAULT_WATCHED_THRESHOLD = 0.85

        /** `observe_property` ids and names. */
        val OBSERVED = listOf("time-pos", "duration", "pause", "eof-reached", "track-list")
    }
}
