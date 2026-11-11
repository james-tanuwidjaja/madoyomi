package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PlaybackTracker] on scripted IPC messages and a fake clock: when progress is saved (every 5 s while playing, on
 * pause, after a seek, at the threshold, at the end), the watched mark (once per session), progress events, and how a
 * session ended.
 */
class PlaybackTrackerTest {
    private var now = 1_000_000L
    private val saves = mutableListOf<Pair<Long, Long>>()
    private val progress = mutableListOf<PlaybackTracker.Snapshot>()
    private var watchedEvents = 0
    private var markCalls = 0
    private var alreadyWatched = false
    private var fileLoaded = 0

    private val hooks =
        object : PlaybackTracker.Hooks {
            override fun save(
                positionMillis: Long,
                durationMillis: Long,
            ) {
                saves += positionMillis to durationMillis
            }

            override fun markWatched(): Boolean {
                markCalls++
                if (alreadyWatched) return false
                alreadyWatched = true
                return true
            }

            override fun progress(snapshot: PlaybackTracker.Snapshot) {
                progress += snapshot
            }

            override fun watched(snapshot: PlaybackTracker.Snapshot) {
                watchedEvents++
            }

            override fun fileLoaded() {
                fileLoaded++
            }
        }

    private val tracker = PlaybackTracker(hooks, clock = { now })

    private fun msg(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun prop(
        name: String,
        value: Any?,
    ) = tracker.onMessage(msg("""{"event":"property-change","id":1,"name":"$name","data":${value ?: "null"}}"""))

    private fun event(
        name: String,
        extra: String = "",
    ) = tracker.onMessage(msg("""{"event":"$name"$extra}"""))

    /** Plays from [from] to [to] seconds in steps of [step], the clock moving with it. */
    private fun play(
        from: Double,
        to: Double,
        step: Double = 0.5,
    ) {
        var t = from
        while (t <= to + 1e-9) {
            prop("time-pos", t)
            t += step
            now += (step * 1000).toLong()
        }
    }

    @Test
    fun `progress is saved at the first position, then every 5 s while playing, in milliseconds`() {
        prop("duration", 1500.0)
        play(60.0, 72.0)
        assertEquals(listOf(60_000L, 65_000L, 70_000L), saves.map { it.first })
        assertTrue(saves.all { it.second == 1_500_000L })
        assertEquals(PlaybackTracker.Snapshot(72.0, 1500.0, false), tracker.snapshot())
    }

    @Test
    fun `a pause saves at once and stops the periodic saves, a seek saves the new position once playback restarts`() {
        prop("duration", 1500.0)
        play(10.0, 12.0)
        saves.clear()
        prop("pause", true)
        assertEquals(listOf(12_000L to 1_500_000L), saves)
        now += 60_000
        prop("time-pos", 12.0)
        assertEquals(1, saves.size, "no save while paused (and none of the same values)")
        prop("pause", false)

        event("seek")
        prop("time-pos", 13.0) // mpv may report positions while seeking
        event("playback-restart")
        prop("time-pos", 400.0)
        assertEquals(400_000L, saves.last().first)
    }

    @Test
    fun `the watched mark happens once, at the threshold, and is not tried again after the user unmarks`() {
        prop("duration", 100.0)
        play(80.0, 84.5)
        assertEquals(0, markCalls)
        prop("time-pos", 85.0)
        assertEquals(1, markCalls)
        assertEquals(1, watchedEvents)
        assertEquals(85_000L, saves.last().first, "saved at the threshold")
        assertTrue(tracker.reachedThreshold)

        // the user unmarks it while it plays; seeking back and past the threshold again doesn't mark it again
        alreadyWatched = false
        event("seek")
        event("playback-restart")
        play(10.0, 11.0)
        play(86.0, 90.0)
        assertEquals(1, markCalls)
        assertEquals(1, watchedEvents)
    }

    @Test
    fun `an episode already watched is not marked again and gets no watched event`() {
        alreadyWatched = true
        prop("duration", 100.0)
        play(90.0, 91.0)
        assertEquals(1, markCalls)
        assertEquals(0, watchedEvents)
    }

    @Test
    fun `no threshold without a duration`() {
        play(0.0, 2000.0, step = 100.0)
        assertEquals(0, markCalls)
        assertTrue(saves.all { it.second == 0L })
    }

    @Test
    fun `progress events at most once a second while playing, and at once on pause and resume`() {
        prop("duration", 100.0)
        play(0.0, 4.75, step = 0.25)
        assertEquals(5, progress.size)
        prop("pause", true)
        prop("pause", false)
        assertEquals(listOf(true, false), progress.drop(5).map { it.paused })
    }

    @Test
    fun `file-loaded, or a non-empty track-list when the IPC connected late, opens the file once`() {
        prop("track-list", "[]")
        assertEquals(0, fileLoaded)
        prop("track-list", """[{"id":1,"type":"video"}]""")
        event("file-loaded")
        assertEquals(1, fileLoaded)
    }

    @Test
    fun `the end of the file is ENDED, with the last position saved`() {
        prop("duration", 100.0)
        play(98.0, 100.0)
        prop("eof-reached", true)
        event("end-file", ""","reason":"eof"""")
        assertEquals(PlaybackTracker.Outcome.Ended("eof"), tracker.finish(stopped = false, exitCode = 0))
        assertEquals(100_000L, saves.last().first)
    }

    @Test
    fun `a quit before the threshold is CLOSED, past it ENDED, and a stop by Madoyomi always CLOSED`() {
        prop("duration", 100.0)
        play(40.0, 50.0)
        event("end-file", ""","reason":"quit"""")
        assertEquals(PlaybackTracker.Outcome.Closed, tracker.finish(stopped = false, exitCode = 0))
        assertEquals(50_000L, saves.last().first)

        val late = PlaybackTracker(hooks, clock = { now })
        late.onMessage(msg("""{"event":"property-change","name":"duration","data":100}"""))
        late.onMessage(msg("""{"event":"property-change","name":"time-pos","data":95}"""))
        late.onMessage(msg("""{"event":"end-file","reason":"quit"}"""))
        assertEquals(PlaybackTracker.Outcome.Ended("quit"), late.finish(stopped = false, exitCode = 0))

        val stopped = PlaybackTracker(hooks, clock = { now })
        stopped.onMessage(msg("""{"event":"property-change","name":"duration","data":100}"""))
        stopped.onMessage(msg("""{"event":"property-change","name":"time-pos","data":100}"""))
        stopped.onMessage(msg("""{"event":"end-file","reason":"eof"}"""))
        assertEquals(PlaybackTracker.Outcome.Closed, stopped.finish(stopped = true, exitCode = 0))
    }

    @Test
    fun `a failed load or an exit with an error is an early end`() {
        event("end-file", ""","reason":"error","file_error":"loading failed"""")
        assertEquals(
            PlaybackTracker.Outcome.Failed("mpv could not play the video: loading failed"),
            tracker.finish(stopped = false, exitCode = 2),
        )
        val killed = PlaybackTracker(hooks, clock = { now })
        assertEquals(PlaybackTracker.Outcome.Failed("mpv exited with code 1"), killed.finish(stopped = false, exitCode = 1))
        assertEquals(PlaybackTracker.Outcome.Closed, killed.finish(stopped = false, exitCode = 1), "finish runs once")
    }
}
