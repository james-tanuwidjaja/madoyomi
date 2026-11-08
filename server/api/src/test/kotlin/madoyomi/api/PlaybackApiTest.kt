package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import madoyomi.core.player.MpvCommand
import madoyomi.core.player.MpvLocator
import madoyomi.core.player.PlaybackSessions
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `playEpisode`, `stopPlayback` and `playbackSession` (Phase 4 Stage 1) with a fake mpv ([FakeMpv], a small Java
 * program started as mpv would be): the video resolved before the launch, the command line and include file, one
 * session at a time, mpv exiting by itself, a missing mpv, and the orphans of a crashed backend.
 */
class PlaybackApiTest : AnimeApiTest() {
    private class PlaySource : AnimeHttpSource() {
        override val name = "Fake player source"
        override val lang = "en"
        override val baseUrl = "https://play.example.org"
        override val supportsLatest = false
        var resolveCalls = 0

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
            listOf(Hoster(hosterUrl = "https://h/main", hosterName = "Main"))

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            listOf(
                Video(
                    videoUrl = "",
                    videoTitle = "Main 1080p",
                    resolution = 1080,
                    headers =
                        headersOf(
                            "Referer",
                            "https://play.example.org/",
                            "sec-ch-ua",
                            "\"Chromium\";v=\"130\"",
                        ),
                    subtitleTracks = listOf(Track("https://subs/en.vtt", "English")),
                    audioTracks = listOf(Track("https://audio/ja.m4a", "Japanese")),
                    mpvArgs = listOf("demuxer-max-bytes" to "50M", "scripts-append" to "evil.lua"),
                ),
            )

        override suspend fun resolveVideo(video: Video): Video {
            resolveCalls++
            return video.copy(videoUrl = "https://cdn.example.org/resolved/$resolveCalls.m3u8")
        }
    }

    private val java =
        ProcessHandle
            .current()
            .info()
            .command()
            .get()
    private val fakeClasses =
        File(
            FakeMpv::class.java.protectionDomain.codeSource.location
                .toURI(),
        ).path
    private val records = File(System.getProperty("java.io.tmpdir"), "madoyomi-fake-mpv")
    private val defaultExecutable = PlaybackSessions.executable

    private fun fake(main: String = "madoyomi.api.FakeMpv") {
        PlaybackSessions.executable = { listOf(java, "-cp", fakeClasses, main) }
    }

    @BeforeEach
    fun useFakeMpv() = fake()

    @AfterEach
    fun stopAndRestore() {
        PlaybackSessions.stop()
        PlaybackSessions.executable = defaultExecutable
    }

    private val sessionFields = "id episodeId animeId title videoTitle state startedAt endedAt exitCode"

    private fun play(
        episodeId: Int,
        start: Double? = null,
    ) = graphql(
        """mutation(${'$'}start: Float) {
            playEpisode(input: {episodeId: $episodeId, hosterIndex: 0, videoIndex: 0, startSeconds: ${'$'}start}) {
                session { $sessionFields } } }""",
        mapOf("start" to start),
    )

    private fun currentSession() = graphql("query { playbackSession { $sessionFields } }").assertNoErrors().dataPath("playbackSession")

    /** What the fake mpv of session [id] recorded: its arguments and the include file. */
    private fun recordOf(id: String): Pair<List<String>, String> {
        val file = File(records, "madoyomi-mpv-$id.txt")
        val deadline = System.currentTimeMillis() + 20_000
        while (!file.isFile) {
            check(System.currentTimeMillis() < deadline) { "the fake mpv of $id recorded nothing" }
            Thread.sleep(50)
        }
        val text = file.readText()
        file.delete()
        val args =
            text
                .lineSequence()
                .takeWhile { it != "INCLUDE" }
                .filter { it.startsWith("ARG ") }
                .map { it.removePrefix("ARG ") }
        return args.toList() to text.substringAfter("INCLUDE\n")
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun pidOf(id: String): Long {
        val record = File(PlaybackSessions.dirs().sessions, "$id.session")
        val props = Properties().apply { record.reader().use(::load) }
        return props.getProperty("pid").toLong()
    }

    private fun episode(source: PlaySource): Int {
        register(source)
        return episodeRow(animeRow(source.id, "/anime", title = "One Piece"), "/episode/1")
    }

    @Test
    fun `playEpisode resolves the video and starts mpv with its headers, tracks and options, from the start time`() {
        val source = PlaySource()
        val episodeId = episode(source)

        val session = play(episodeId, start = 83.5).assertNoErrors().dataPath("playEpisode", "session")
        val id = session.path("id") as String
        assertEquals(episodeId, session.path("episodeId"))
        assertEquals("One Piece - Episode 1", session.path("title"))
        assertEquals("Main 1080p", session.path("videoTitle"))
        assertEquals("RUNNING", session.path("state"))
        assertNull(session.path("endedAt"))
        assertEquals(1, source.resolveCalls)

        val (args, include) = recordOf(id)
        val dirs = PlaybackSessions.dirs()
        assertEquals(
            listOf(
                "--config-dir=${dirs.config.absolutePath}",
                "--include=${File(dirs.sessions, "$id.conf").absolutePath}",
                "--input-ipc-server=${MpvCommand.pipePath("madoyomi-mpv-$id")}",
                "--ytdl=no",
                "--idle=no",
                "--force-window=immediate",
                "--msg-level=all=warn",
                "--start=83.5",
                "--",
                "https://cdn.example.org/resolved/1.m3u8",
            ),
            args,
        )
        assertTrue("force-media-title=%21%One Piece - Episode 1\n" in include, include)
        assertTrue("referrer=%25%https://play.example.org/\n" in include, include)
        assertTrue("http-header-fields-append=%29%sec-ch-ua: \"Chromium\";v=\"130\"\n" in include, include)
        assertTrue("sub-files-append=%19%https://subs/en.vtt\n" in include, include)
        assertTrue("audio-files-append=%20%https://audio/ja.m4a\n" in include, include)
        assertTrue("demuxer-max-bytes=%3%50M\n" in include, include)
        assertFalse("evil.lua" in include, include)

        assertEquals(id, currentSession().path("id"))
        assertEquals("RUNNING", currentSession().path("state"))
    }

    @Test
    fun `one session at a time - a new one stops the old, and stopPlayback closes mpv and its files`() {
        val episodeId = episode(PlaySource())
        val first = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
        recordOf(first)
        val firstPid = pidOf(first)
        assertTrue(alive(firstPid))

        val second = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
        assertNotEquals(first, second)
        recordOf(second)
        val secondPid = pidOf(second)
        assertFalse(alive(firstPid), "the first mpv was stopped")
        assertFalse(File(PlaybackSessions.dirs().sessions, "$first.conf").exists())
        assertTrue(File(PlaybackSessions.dirs().sessions, "$second.conf").exists())
        assertEquals(second, currentSession().path("id"))

        val stopped = graphql("mutation { stopPlayback(input: {}) { session { $sessionFields } } }").assertNoErrors()
        assertEquals(second, stopped.dataPath("stopPlayback", "session", "id"))
        assertEquals("STOPPED", stopped.dataPath("stopPlayback", "session", "state"))
        assertNotNull(stopped.dataPath("stopPlayback", "session", "endedAt"))
        assertFalse(alive(secondPid))
        assertFalse(File(PlaybackSessions.dirs().sessions, "$second.conf").exists())
        assertFalse(File(PlaybackSessions.dirs().sessions, "$second.session").exists())
        assertEquals("STOPPED", currentSession().path("state"))

        val none = graphql("mutation { stopPlayback(input: {}) { session { id } } }").assertNoErrors()
        assertNull(none.dataPath("stopPlayback", "session"))
    }

    @Test
    fun `mpv exiting by itself ends the session with its exit code`() {
        fake("madoyomi.api.FakeMpv\$Exits")
        val episodeId = episode(PlaySource())
        val id = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String

        val deadline = System.currentTimeMillis() + 20_000
        while (currentSession().path("state") == "RUNNING") {
            check(System.currentTimeMillis() < deadline) { "the session never ended" }
            Thread.sleep(50)
        }
        val ended = currentSession()
        assertEquals(id, ended.path("id"))
        assertEquals("EXITED", ended.path("state"))
        assertEquals(3, ended.path("exitCode"))
        assertFalse(File(PlaybackSessions.dirs().sessions, "$id.conf").exists())
        File(records, "madoyomi-mpv-$id.txt").delete()
    }

    @Test
    fun `without mpv playEpisode is PLAYER_NOT_FOUND, and the running session keeps playing`() {
        val episodeId = episode(PlaySource())
        val running = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
        recordOf(running)

        PlaybackSessions.executable = { listOf(MpvLocator.locate(override = File("C:/nowhere/mpv.exe").path).path) }
        val response = play(episodeId)
        assertEquals("PLAYER_NOT_FOUND", response.errorCode)
        assertTrue(alive(pidOf(running)))
        assertEquals(running, currentSession().path("id"))
        assertEquals("RUNNING", currentSession().path("state"))
    }

    @Test
    fun `an mpv that a crashed backend left running is ended at startup, and nothing else is`() {
        val dir = PlaybackSessions.dirs().sessions
        val orphan =
            ProcessBuilder(java, "-cp", fakeClasses, "madoyomi.api.FakeMpv", "--input-ipc-server=orphan-test")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        val info = orphan.toHandle().info()
        File(dir, "orphan.session").writer().use {
            Properties()
                .apply {
                    setProperty("id", "orphan")
                    setProperty("pid", orphan.pid().toString())
                    setProperty("command", info.command().get())
                    setProperty(
                        "startedAt",
                        info
                            .startInstant()
                            .get()
                            .toEpochMilli()
                            .toString(),
                    )
                }.store(it, null)
        }
        // a record whose pid now belongs to another program (this test JVM) must not be acted on
        File(dir, "reused.session").writer().use {
            Properties()
                .apply {
                    setProperty("id", "reused")
                    setProperty("pid", ProcessHandle.current().pid().toString())
                    setProperty("command", "C:\\Program Files\\MPV Player\\mpv.exe")
                }.store(it, null)
        }
        File(dir, "orphan.conf").writeText("referrer=%1%x\n")

        assertEquals(1, PlaybackSessions.reapOrphans())
        assertTrue(orphan.waitFor(10, TimeUnit.SECONDS), "the orphan was ended")
        assertTrue(ProcessHandle.current().isAlive)
        assertEquals(
            emptyList(),
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.startsWith("orphan") || it.startsWith("reused") },
        )
        File(records, "orphan-test.txt").delete()
    }
}
