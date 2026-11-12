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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.player.FakeMpvServer
import madoyomi.core.player.MpvCommand
import madoyomi.core.player.MpvIpcClient
import madoyomi.core.player.MpvLocator
import madoyomi.core.player.PlaybackSessions
import madoyomi.core.player.PlayerSettings
import madoyomi.core.player.PlayerSettingsStore
import okhttp3.Headers.Companion.headersOf
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import suwayomi.tachidesk.server.database.dbTransaction
import java.io.File
import java.util.Collections
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
 *
 * Stage 2, over IPC with a fake mpv that serves a real named pipe ([FakeMpvServer] as a process, on Windows): progress
 * saved into `EPISODE` while playing and at the end, the watched mark, the external tracks with their titles, a
 * graceful `quit`, and the `playbackEvents` subscription (ENDED with the next episode, ERROR, CLOSED).
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

    private val sessionFields =
        "id episodeId animeId title videoTitle state startedAt endedAt exitCode positionSeconds durationSeconds paused"

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

    private fun episode(source: AnimeHttpSource): Int {
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
        // the tracks are added over IPC with their labels (Stage 2), not from the include file
        assertFalse("sub-files-append" in include || "audio-files-append" in include, include)
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
    fun `mpv exiting by itself ends the session with its exit code, and an early exit is retried once`() {
        fake("madoyomi.api.FakeMpv\$Exits")
        val source = PlaySource()
        val episodeId = episode(source)
        val id = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String

        // it exited before playing (Stage 4): the videos are fetched again and a second session starts, which exits
        // the same way, and that is final
        val deadline = System.currentTimeMillis() + 30_000
        while (currentSession().path("id") == id || currentSession().path("state") == "RUNNING") {
            check(System.currentTimeMillis() < deadline) { "the sessions never ended" }
            Thread.sleep(50)
        }
        val ended = currentSession()
        val retry = ended.path("id") as String
        assertNotEquals(id, retry)
        assertEquals("EXITED", ended.path("state"))
        assertEquals(3, ended.path("exitCode"))
        assertEquals(2, source.resolveCalls, "resolved again after the refetch")
        Thread.sleep(500)
        assertEquals(retry, currentSession().path("id"), "no second retry")
        for (s in listOf(id, retry)) {
            assertFalse(File(PlaybackSessions.dirs().sessions, "$s.conf").exists())
            File(records, "madoyomi-mpv-$s.txt").delete()
        }
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

    // Stage 2: over IPC, with FakeMpvServer (core's tests) as mpv

    private val ipcClasspath =
        listOf(FakeMpvServer::class.java, com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java)
            .map {
                File(
                    it.protectionDomain.codeSource.location
                        .toURI(),
                ).path
            }.distinct()
            .joinToString(File.pathSeparator)

    /** mpv is [FakeMpvServer]: a fake file of [duration] seconds played at [speed]× real time. */
    private fun fakeIpc(
        duration: Double,
        speed: Double,
        mode: String = "play",
        loadMillis: Long = 300,
    ) {
        PlaybackSessions.executable = {
            listOf(
                java,
                "-Dfakempv.duration=$duration",
                "-Dfakempv.speed=$speed",
                "-Dfakempv.mode=$mode",
                "-Dfakempv.loadMillis=$loadMillis",
                "-cp",
                ipcClasspath,
                "madoyomi.core.player.FakeMpvServer",
            )
        }
    }

    private val eventFields =
        "type nextEpisodeId reason message httpStatus retried failedVideo { hosterName videoTitle resolution } " +
            "nextVideo { hosterName videoTitle resolution } session { $sessionFields }"

    /**
     * Runs [block] while `playbackEvents(episodeId)` collects, until [until] holds for the events seen (or a minute
     * passes); returns [block]'s result and the events.
     */
    private fun <T> withEvents(
        episodeId: Int,
        until: (List<Any?>) -> Boolean = { events -> events.any { it.path("type") == "CLOSED" } },
        block: () -> T,
    ): Pair<T, List<Any?>> =
        runBlocking {
            val events = Collections.synchronizedList(mutableListOf<Any?>())
            val before = PlaybackSessions.eventSubscribers.value
            val job =
                launch(Dispatchers.Default) {
                    subscribe("subscription { playbackEvents(episodeId: $episodeId) { $eventFields } }").collect { response ->
                        response.assertNoErrors()
                        events += response.dataPath("playbackEvents")
                    }
                }
            withTimeout(10_000) { while (PlaybackSessions.eventSubscribers.value <= before) delay(10) }
            val result = withContext(Dispatchers.IO) { block() }
            withTimeout(60_000) { while (!until(events.toList())) delay(20) }
            job.cancel()
            result to events.toList()
        }

    private fun progressOf(episodeId: Int) =
        dbTransaction {
            EpisodeTable.selectAll().where { EpisodeTable.id eq episodeId }.single().let {
                Triple(it[EpisodeTable.lastSecondSeen], it[EpisodeTable.totalSeconds], it[EpisodeTable.watched])
            }
        }

    /** The lines the fake mpv of session [id] was sent (written when it exits). */
    private fun receivedBy(id: String): List<String> {
        val file = File(records, "madoyomi-mpv-$id.received")
        val deadline = System.currentTimeMillis() + 10_000
        while (!file.isFile) {
            check(System.currentTimeMillis() < deadline) { "the fake mpv of $id wrote nothing" }
            Thread.sleep(50)
        }
        return file.readLines().also { file.delete() }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `over IPC the progress is saved, the episode marked watched once, and the end of the file is ENDED with the next episode`() {
        fakeIpc(duration = 20.0, speed = 5.0)
        val source = PlaySource()
        register(source)
        val anime = animeRow(source.id, "/anime", title = "One Piece")
        val first = episodeRow(anime, "/episode/1", order = 1)
        val second = episodeRow(anime, "/episode/2", order = 2)

        val (id, events) =
            withEvents(first) { play(first, start = 2.0).assertNoErrors().dataPath("playEpisode", "session", "id") as String }
        recordOf(id)
        val types = events.map { it.path("type") }
        assertEquals("STARTED", types.first(), types.toString())
        assertEquals("CLOSED", types.last(), types.toString())
        assertTrue("PROGRESS" in types, types.toString())
        assertEquals(1, types.count { it == "WATCHED" }, types.toString())
        assertTrue(types.indexOf("WATCHED") < types.indexOf("ENDED"), types.toString())
        assertFalse("ERROR" in types, types.toString())
        val ended = events.single { it.path("type") == "ENDED" }
        assertEquals(second, ended.path("nextEpisodeId"))
        assertEquals("eof", ended.path("reason"))
        val watched = events.single { it.path("type") == "WATCHED" }
        assertTrue((watched.path("session", "positionSeconds") as Double) >= 17.0)

        val closed = events.last().path("session")
        assertEquals("EXITED", closed.path("state"))
        assertEquals(0, closed.path("exitCode"))
        assertEquals(20.0, closed.path("durationSeconds"))
        assertEquals(Triple(20_000L, 20_000L, true), progressOf(first), "milliseconds, as Aniyomi stores them")
        assertEquals(Triple(0L, 0L, false), progressOf(second))

        // the external tracks went over IPC, with their labels as titles; then (Stage 5, no language set) the first
        // subtitle track selected, and the first audio track too, since the fake's file has no audio of its own
        val sent = receivedBy(id)
        assertTrue(
            sent.any { "\"sub-add\",\"https://subs/en.vtt\",\"auto\",\"English\"" in it && "\"async\":true" in it },
            sent.toString(),
        )
        assertTrue(sent.any { "\"audio-add\",\"https://audio/ja.m4a\",\"auto\",\"Japanese\"" in it }, sent.toString())
        assertTrue(sent.any { "\"set_property\",\"sid\",1" in it }, sent.toString())
        assertTrue(sent.any { "\"set_property\",\"aid\",1" in it }, sent.toString())
        madoyomi.core.player.PlaybackTracker.OBSERVED.forEach { name ->
            assertTrue(
                sent.any {
                    "\"observe_property\"" in it &&
                        "\"$name\"" in it
                },
                name,
            )
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `stopPlayback quits mpv over IPC and keeps the position, with no ENDED`() {
        fakeIpc(duration = 1000.0, speed = 1.0)
        val episodeId = episode(PlaySource())

        val (stopped, events) =
            withEvents(episodeId) {
                val id = play(episodeId, start = 100.0).assertNoErrors().dataPath("playEpisode", "session", "id") as String
                recordOf(id)
                val deadline = System.currentTimeMillis() + 20_000
                while (((currentSession().path("positionSeconds") as? Double) ?: 0.0) < 101.5) {
                    check(System.currentTimeMillis() < deadline) { "no position over IPC" }
                    Thread.sleep(50)
                }
                // a second IPC client on the same pipe (mpv allows several) sees the titled tracks
                val tracks =
                    assertNotNull(MpvIpcClient.connect("madoyomi-mpv-$id", 5_000, onEvent = {})).use { client ->
                        client.command("get_property", "track-list").get(5, TimeUnit.SECONDS) as JsonArray
                    }
                assertEquals(
                    listOf("English", "Japanese"),
                    tracks.map { it as JsonObject }.filter { it.containsKey("title") }.map { it["title"]?.jsonPrimitive?.content },
                )
                graphql("mutation { stopPlayback(input: {}) { session { $sessionFields } } }")
                    .assertNoErrors()
                    .dataPath("stopPlayback", "session")
            }
        assertEquals("STOPPED", stopped.path("state"))
        assertEquals(0, stopped.path("exitCode"), "mpv quit (TerminateProcess would give 1)")
        val types = events.map { it.path("type") }
        assertEquals("CLOSED", types.last(), types.toString())
        assertFalse("ENDED" in types || "ERROR" in types || "WATCHED" in types, types.toString())
        val (position, duration, watched) = progressOf(episodeId)
        assertTrue(position in 101_500L..120_000L, "position $position")
        assertEquals(1_000_000L, duration)
        assertFalse(watched)
        assertTrue(receivedBy(stopped.path("id") as String).any { "\"quit\"" in it })
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a video that fails to load is fetched again once, and when it fails again it is ERROR, then CLOSED`() {
        fakeIpc(duration = 100.0, speed = 1.0, mode = "error", loadMillis = 2_000)
        val episodeId = episode(PlaySource())
        val (id, events) =
            withEvents(episodeId, until = closedTwice) {
                play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
            }
        recordOf(id)
        receivedBy(id)
        assertEquals(listOf("STARTED", "RETRYING", "CLOSED", "STARTED", "ERROR", "CLOSED"), events.map { it.path("type") })
        assertEquals("mpv could not play the video: loading failed", events[1].path("message"))
        assertEquals(id, events[2].path("session", "id"))
        val retry = events[3].path("session", "id") as String
        assertNotEquals(id, retry)
        recordOf(retry)
        receivedBy(retry)
        val error = events[4]
        assertEquals(retry, error.path("session", "id"))
        assertEquals(true, error.path("retried"))
        assertEquals("Main 1080p", error.path("failedVideo", "videoTitle"))
        assertNull(error.path("nextVideo"), "the episode has no other video")
        assertEquals("EXITED", events.last().path("session", "state"))
        assertEquals(2, events.last().path("session", "exitCode"))
        assertEquals(Triple(0L, 0L, false), progressOf(episodeId))
    }

    /**
     * Stage 4's source: hoster Main with 1080p and 720p, hoster Backup with 480p, already resolved. [urls] gives each
     * fetch's URL query by its number (from 1), so a fetch can hand the fake mpv a URL that fails.
     */
    private class RetrySource(
        val urls: (fetch: Int, title: String) -> String?,
    ) : AnimeHttpSource() {
        override val name = "Fake retry source"
        override val lang = "en"
        override val baseUrl = "https://retry.example.org"
        override val supportsLatest = false

        @Volatile
        var fetches = 0

        @Volatile
        var fetchDelayMillis = 0L

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
            fetches++
            if (fetches > 1 && fetchDelayMillis > 0) delay(fetchDelayMillis)
            return listOf(
                Hoster(hosterUrl = "https://h/main", hosterName = "Main"),
                Hoster(hosterUrl = "https://h/backup", hosterName = "Backup"),
            )
        }

        override suspend fun getVideoList(hoster: Hoster): List<Video> {
            val n = fetches
            val titles = if (hoster.hosterName == "Main") listOf("Main 1080p" to 1080, "Main 720p" to 720) else listOf("Backup 480p" to 480)
            return titles.mapNotNull { (title, res) ->
                val query = urls(n, title) ?: return@mapNotNull null
                Video(
                    videoUrl = "https://cdn.example.org/$n/${title.replace(' ', '-')}.mp4$query",
                    videoTitle = title,
                    resolution = res,
                    initialized = true,
                )
            }
        }
    }

    private val closedTwice: (List<Any?>) -> Boolean = { events -> events.count { it.path("type") == "CLOSED" } >= 2 }

    private fun argsOf(id: String) = recordOf(id).first

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a refused URL is fetched again once, and the same video plays from where it was`() {
        fakeIpc(duration = 40.0, speed = 10.0)
        // the first fetch's URLs are refused (403) at once; the second fetch's play
        val source = RetrySource { n, _ -> if (n == 1) "?fakempv-error=403" else "" }
        val episodeId = episode(source)

        val (id, events) =
            withEvents(episodeId, until = closedTwice) {
                play(episodeId, start = 30.0).assertNoErrors().dataPath("playEpisode", "session", "id") as String
            }
        val types = events.map { it.path("type") }
        assertEquals(listOf("STARTED", "RETRYING", "CLOSED", "STARTED"), types.take(4), types.toString())
        assertEquals("ENDED", types[types.size - 2], types.toString())
        assertFalse("ERROR" in types, types.toString())
        val retrying = events[1]
        assertEquals(403, retrying.path("httpStatus"))
        assertEquals("mpv could not play the video: loading failed (HTTP 403)", retrying.path("message"))
        assertEquals("Main", retrying.path("failedVideo", "hosterName"))
        assertEquals("Main 1080p", retrying.path("failedVideo", "videoTitle"))
        assertEquals(1080, retrying.path("failedVideo", "resolution"))
        assertEquals(2, source.fetches, "fetched again once")

        assertTrue(argsOf(id).last().endsWith("/1/Main-1080p.mp4?fakempv-error=403"))
        val retry = events[3].path("session", "id") as String
        val retryArgs = argsOf(retry)
        assertEquals("https://cdn.example.org/2/Main-1080p.mp4", retryArgs.last(), "the same video, from the new fetch")
        assertTrue("--start=30" in retryArgs, "where the failed one started: it never played. $retryArgs")
        assertEquals("Main 1080p", events[3].path("session", "videoTitle"))
        receivedBy(id)
        receivedBy(retry)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a URL that expires while playing is fetched again, and plays on from the position it failed at`() {
        fakeIpc(duration = 60.0, speed = 5.0)
        val source = RetrySource { n, _ -> if (n == 1) "?fakempv-fail-at=12&fakempv-status=403" else "" }
        val episodeId = episode(source)

        val (id, events) =
            withEvents(episodeId, until = { e ->
                e.any {
                    it.path("type") == "STARTED" &&
                        it.path("session", "id") != e.first().path("session", "id")
                }
            }) {
                play(episodeId, start = 2.0).assertNoErrors().dataPath("playEpisode", "session", "id") as String
            }
        val types = events.map { it.path("type") }
        assertTrue(types.indexOf("RETRYING") > types.indexOf("PROGRESS"), types.toString())
        assertEquals(403, events.single { it.path("type") == "RETRYING" }.path("httpStatus"))
        val retry = events.last().path("session", "id") as String
        val start =
            argsOf(retry)
                .single { it.startsWith("--start=") }
                .removePrefix("--start=")
                .toDouble()
        assertTrue(start in 11.5..13.0, "the failed session's last position, $start")
        assertTrue(progressOf(episodeId).first in 11_500L..40_000L, "saved from where it failed on")
        receivedBy(id)
        PlaybackSessions.stop()
        receivedBy(retry)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `an end of file long before the end, after an HTTP error, is an expired URL - fetched again, from there`() {
        fakeIpc(duration = 60.0, speed = 5.0)
        // as mpv does when a range request gets 403: the HTTP error in its log, then end-file eof, exit 0
        val source = RetrySource { n, _ -> if (n == 1) "?fakempv-eof-at=12&fakempv-status=403" else "" }
        val episodeId = episode(source)

        val (id, events) =
            withEvents(episodeId, until = { e ->
                e.any {
                    it.path("type") == "STARTED" &&
                        it.path("session", "id") != e.first().path("session", "id")
                }
            }) {
                play(episodeId, start = 2.0).assertNoErrors().dataPath("playEpisode", "session", "id") as String
            }
        val types = events.map { it.path("type") }
        assertFalse("ENDED" in types, "not the end of the episode: $types")
        val retrying = events.single { it.path("type") == "RETRYING" }
        assertEquals(403, retrying.path("httpStatus"))
        assertTrue(
            (retrying.path("message") as String).startsWith("mpv stopped reading the video at 0:1"),
            retrying.path("message") as String,
        )
        val retry = events.last().path("session", "id") as String
        val start =
            argsOf(retry)
                .single { it.startsWith("--start=") }
                .removePrefix("--start=")
                .toDouble()
        assertTrue(start in 11.5..13.0, "where it stopped, $start")
        receivedBy(id)
        PlaybackSessions.stop()
        receivedBy(retry)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `an early end of file without an HTTP error stays mpv's end of the file`() {
        fakeIpc(duration = 60.0, speed = 10.0)
        val source = RetrySource { _, _ -> "?fakempv-eof-at=20" }
        val episodeId = episode(source)
        val (id, events) = withEvents(episodeId) { play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String }
        val types = events.map { it.path("type") }
        assertEquals("ENDED", types[types.size - 2], types.toString())
        assertFalse("RETRYING" in types || "ERROR" in types, types.toString())
        recordOf(id)
        receivedBy(id)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `when the video fails again after the refetch, ERROR offers the next video in the source's order`() {
        fakeIpc(duration = 40.0, speed = 10.0)
        val source = RetrySource { _, _ -> "?fakempv-error=404" }
        val episodeId = episode(source)

        val (id, events) =
            withEvents(episodeId, until = closedTwice) {
                play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
            }
        assertEquals(listOf("STARTED", "RETRYING", "CLOSED", "STARTED", "ERROR", "CLOSED"), events.map { it.path("type") })
        val error = events[4]
        assertEquals(true, error.path("retried"))
        assertEquals(404, error.path("httpStatus"))
        assertEquals("Main 1080p", error.path("failedVideo", "videoTitle"))
        assertEquals("Main", error.path("nextVideo", "hosterName"))
        assertEquals("Main 720p", error.path("nextVideo", "videoTitle"))
        assertEquals(720, error.path("nextVideo", "resolution"))
        assertEquals(2, source.fetches, "fetched again only once")
        recordOf(id)
        receivedBy(id)
        val retry = events[3].path("session", "id") as String
        recordOf(retry)
        receivedBy(retry)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `when the refetch no longer has the video, ERROR offers the first one, and nothing plays`() {
        fakeIpc(duration = 40.0, speed = 10.0)
        // the second fetch has no 1080p (no such title or resolution)
        val source =
            RetrySource { n, title ->
                if (n == 1) {
                    "?fakempv-error=403"
                } else if (title == "Main 1080p") {
                    null
                } else {
                    ""
                }
            }
        val episodeId = episode(source)

        val (id, events) = withEvents(episodeId) { play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String }
        assertEquals(listOf("STARTED", "RETRYING", "ERROR", "CLOSED"), events.map { it.path("type") })
        val error = events[2]
        assertEquals(id, error.path("session", "id"))
        assertEquals(true, error.path("retried"))
        assertTrue((error.path("message") as String).contains("isn't among the episode's videos any more"), error.path("message") as String)
        assertEquals("Main 720p", error.path("nextVideo", "videoTitle"))
        assertEquals("EXITED", currentSession().path("state"))
        recordOf(id)
        receivedBy(id)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a stream that never played a frame is fetched again once, though mpv reported its start position (Stage 6)`() {
        fakeIpc(duration = 40.0, speed = 10.0)
        // as mpv with an HLS stream whose playlist opens but whose segments all fail: time-pos at the start, then
        // "no audio or video data played", exit 2, no HTTP error in its log
        val source = RetrySource { n, _ -> if (n == 1) "?fakempv-nothing=1" else "" }
        val episodeId = episode(source)

        val (id, events) =
            withEvents(episodeId, until = closedTwice) {
                play(episodeId, start = 30.0).assertNoErrors().dataPath("playEpisode", "session", "id") as String
            }
        val types = events.map { it.path("type") }
        assertTrue(types.indexOf("RETRYING") in 1..2, types.toString())
        assertFalse("ERROR" in types, types.toString())
        val retrying = events.single { it.path("type") == "RETRYING" }
        assertEquals("mpv could not play the video: no audio or video data played", retrying.path("message"))
        assertNull(retrying.path("httpStatus"))
        assertEquals(2, source.fetches, "fetched again once")
        val retry = events.last { it.path("type") == "STARTED" }.path("session", "id") as String
        assertNotEquals(id, retry)
        val retryArgs = argsOf(retry)
        assertEquals("https://cdn.example.org/2/Main-1080p.mp4", retryArgs.last(), "the same video, from the new fetch")
        assertTrue("--start=30" in retryArgs, "where the failed one was to start. $retryArgs")
        receivedBy(id)
        receivedBy(retry)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a failure after playback started without an HTTP error is ERROR at once, with the next video`() {
        fakeIpc(duration = 40.0, speed = 10.0)
        val source = RetrySource { n, _ -> if (n == 1) "?fakempv-fail-at=5" else "" }
        val episodeId = episode(source)

        val (id, events) = withEvents(episodeId) { play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String }
        val types = events.map { it.path("type") }
        assertFalse("RETRYING" in types, types.toString())
        val error = events.single { it.path("type") == "ERROR" }
        assertEquals(false, error.path("retried"))
        assertNull(error.path("httpStatus"))
        assertEquals("Main 720p", error.path("nextVideo", "videoTitle"))
        assertEquals(1, source.fetches)
        recordOf(id)
        receivedBy(id)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `stopPlayback while the videos are fetched again cancels the retry`() {
        fakeIpc(duration = 40.0, speed = 10.0)
        val source = RetrySource { n, _ -> if (n == 1) "?fakempv-error=403" else "" }
        source.fetchDelayMillis = 3_000
        val episodeId = episode(source)

        val (id, events) =
            withEvents(episodeId) {
                val id = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
                val deadline = System.currentTimeMillis() + 20_000
                while (source.fetches < 2) {
                    check(System.currentTimeMillis() < deadline) { "no refetch" }
                    Thread.sleep(20)
                }
                assertNull(
                    graphql("mutation { stopPlayback(input: {}) { session { id } } }").assertNoErrors().dataPath("stopPlayback", "session"),
                    "nothing was playing",
                )
                id
            }
        assertEquals(listOf("STARTED", "RETRYING", "CLOSED"), events.map { it.path("type") })
        Thread.sleep(4_000)
        assertEquals(id, currentSession().path("id"), "the retry played nothing")
        recordOf(id)
        receivedBy(id)
    }

    /** Stage 5: KickAssAnime's shape, two subtitle and two audio tracks, plus a Japanese audio track of the file's own. */
    private class LanguageSource : AnimeHttpSource() {
        override val name = "Fake language source"
        override val lang = "en"
        override val baseUrl = "https://lang.example.org"
        override val supportsLatest = false

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
            listOf(Hoster(hosterUrl = "https://h/main", hosterName = "VidStreaming"))

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            listOf(
                Video(
                    videoUrl = "https://cdn.example.org/ep1.m3u8?fakempv-audio=jpn",
                    videoTitle = "VidStreaming 1080p",
                    resolution = 1080,
                    subtitleTracks = listOf(Track("https://subs/en.vtt", "English (eng)"), Track("https://subs/es.vtt", "Spanish (spa)")),
                    audioTracks = listOf(Track("https://audio/ja.m4a", "Japanese (jpn)"), Track("https://audio/en.m4a", "English (eng)")),
                ),
            )
    }

    /** Plays [episodeId] on a fake mpv and waits until mpv's `sid` and `aid` satisfy [until] (20 s at most); the session id. */
    private fun playAndReadTracks(
        episodeId: Int,
        until: (Any?, Any?) -> Boolean,
    ): Triple<Any?, Any?, String> {
        val id = play(episodeId).assertNoErrors().dataPath("playEpisode", "session", "id") as String
        recordOf(id)
        return assertNotNull(MpvIpcClient.connect("madoyomi-mpv-$id", 10_000, onEvent = {})).use { client ->
            val deadline = System.currentTimeMillis() + 20_000
            var tracks: Pair<Any?, Any?>
            do {
                Thread.sleep(100)
                tracks =
                    client
                        .command("get_property", "sid")
                        .get(5, TimeUnit.SECONDS)
                        ?.jsonPrimitive
                        ?.content to
                    client
                        .command("get_property", "aid")
                        .get(5, TimeUnit.SECONDS)
                        ?.jsonPrimitive
                        ?.content
            } while (!until(tracks.first, tracks.second) && System.currentTimeMillis() < deadline)
            Triple(tracks.first, tracks.second, id)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the subtitle and audio languages select mpv's tracks - the source's and the file's own (Stage 5)`() {
        fakeIpc(duration = 1000.0, speed = 1.0)
        val episodeId = episode(LanguageSource())

        // no language: Stage 2's rule, the source's first subtitle; the file's own audio stays
        val plain = playAndReadTracks(episodeId) { sid, aid -> sid == "1" && aid == "1" }
        assertEquals("1" to "1", plain.first to plain.second)

        // fakempv numbers the tracks per type: subtitles 1 English, 2 Spanish; audio 1 its own (jpn), 2 Japanese, 3 English
        PlayerSettingsStore.update(PlayerSettings.Patch(subtitleLanguage = "es", audioLanguage = "en"))
        val chosen = playAndReadTracks(episodeId) { sid, aid -> sid == "2" && aid == "3" }
        assertEquals("2" to "3", chosen.first to chosen.second)

        // subtitles off; Japanese audio: the file's own comes first
        PlayerSettingsStore.update(PlayerSettings.Patch(subtitleLanguage = "off", audioLanguage = "ja"))
        val off = playAndReadTracks(episodeId) { sid, aid -> sid == "false" && aid == "1" }
        assertEquals("false" to "1", off.first to off.second)
        PlaybackSessions.stop()
        val sent = receivedBy(off.third)
        assertTrue(sent.any { "\"set_property\",\"sid\",\"no\"" in it }, sent.toString())
        assertTrue(sent.any { "\"set_property\",\"aid\",1" in it }, sent.toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the watched threshold of the settings marks the episode (Stage 5)`() {
        fakeIpc(duration = 20.0, speed = 5.0)
        PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = 0.5))
        val episodeId = episode(PlaySource())
        val (_, events) = withEvents(episodeId) { play(episodeId, start = 2.0).assertNoErrors() }
        val watched = events.single { it.path("type") == "WATCHED" }
        val at = watched.path("session", "positionSeconds") as Double
        assertTrue(at in 10.0..14.0, "marked at $at s of 20 s, at 50 % and not 85 %")
    }

    @Test
    fun `a wrong mpv path in the settings is PLAYER_NOT_FOUND naming the setting, and clearing it falls back (Stage 5)`() {
        PlaybackSessions.executable = defaultExecutable
        val missing = File(System.getProperty("java.io.tmpdir"), "madoyomi-no-mpv/mpv.exe").absolutePath
        PlayerSettingsStore.update(PlayerSettings.Patch(mpvPath = missing))
        val episodeId = episode(PlaySource())

        val response = play(episodeId)
        assertEquals("PLAYER_NOT_FOUND", response.errorCode)
        val error = response.errors!!.first()
        assertTrue(error.message.contains("Settings → Player"), error.message)
        assertEquals("SETTINGS", error.extensions?.get("details").path("origin"))

        val check = graphql("query { mpvCheck { ok path origin errorCode message } }").assertNoErrors().dataPath("mpvCheck")
        assertEquals(false, check.path("ok"))
        assertEquals("SETTINGS", check.path("origin"))
        assertEquals("PLAYER_NOT_FOUND", check.path("errorCode"))

        // cleared: MADOYOMI_MPV or PATH again (whatever this machine has), never the setting
        PlayerSettingsStore.update(PlayerSettings.Patch(mpvPath = ""))
        val fallback = graphql("query { mpvCheck { ok origin } }").assertNoErrors().dataPath("mpvCheck")
        assertNotEquals("SETTINGS", fallback.path("origin"))
    }
}
