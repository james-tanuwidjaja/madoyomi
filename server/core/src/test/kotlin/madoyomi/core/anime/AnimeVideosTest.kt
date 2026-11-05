package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.NO_HOSTER_LIST
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import okhttp3.Headers.Companion.headersOf
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AnimeVideos]: Aniyomi's hoster and video steps over fake HTTP sources. */
class AnimeVideosTest {
    companion object {
        private lateinit var db: ScratchDatabase

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
            db = ScratchDatabase()
        }

        @AfterAll
        @JvmStatic
        fun afterAll() {
            db.close()
        }
    }

    /** A lib-14 style source: no hoster methods, videos straight from the episode. */
    private class VideoListSource : AnimeHttpSource() {
        override val name = "Fake videos"
        override val lang = "en"
        override val baseUrl = "https://videos.example.org"
        override val supportsLatest = false
        var videos: List<Video> = emptyList()
        var lastEpisodeUrl: String? = null

        @Deprecated("Use the hoster version instead")
        override suspend fun getVideoList(episode: SEpisode): List<Video> {
            lastEpisodeUrl = episode.url
            return videos
        }

        override fun List<Video>.sortVideos(): List<Video> = sortedByDescending { it.resolution }

        override suspend fun getVideoUrl(video: Video): String = "https://cdn.example.org/from-page${video.url}"
    }

    /** A lib-16 style source with hosters, lazy ones and resolvable videos. */
    private class HosterSource : AnimeHttpSource() {
        override val name = "Fake hosters"
        override val lang = "en"
        override val baseUrl = "https://hosters.example.org"
        override val supportsLatest = false
        var resolveCalls = 0

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
            listOf(
                Hoster(hosterUrl = "https://h/bad", hosterName = "Bad"),
                Hoster(hosterUrl = "https://h/lazy", hosterName = "Lazy", lazy = true),
                Hoster(hosterUrl = "https://h/main", hosterName = "Main"),
                Hoster(hosterName = "Inline", videoList = listOf(Video(videoUrl = "https://inline/1.mp4", videoTitle = "Inline 1"))),
            )

        override fun List<Hoster>.sortHosters(): List<Hoster> = sortedBy { it.hosterName != "Main" }

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            when (hoster.hosterName) {
                "Bad" -> throw HttpException(503)
                else -> listOf(Video(videoUrl = "", videoTitle = "${hoster.hosterName} 1080p", resolution = 1080, preferred = true))
            }

        override suspend fun resolveVideo(video: Video): Video {
            resolveCalls++
            return video.copy(videoUrl = "https://cdn.example.org/resolved/${video.videoTitle.replace(' ', '-')}.m3u8")
        }
    }

    private val videoSource = VideoListSource()
    private val hosterSource = HosterSource()

    @BeforeEach
    fun setUp() {
        listOf(videoSource, hosterSource).forEach {
            FakeAnimeSource.clearRows(it.id)
            AnimeGetSource.registerSource(it)
        }
    }

    @AfterEach
    fun tearDown() {
        listOf(videoSource, hosterSource).forEach {
            AnimeGetSource.unregisterSource(it.id)
            FakeAnimeSource.clearRows(it.id)
        }
    }

    private fun episodeOf(source: AnimeHttpSource): Int =
        dbTransaction {
            val animeId =
                AnimeTable.insertAndGetId {
                    it[url] = "/anime"
                    it[title] = "Anime"
                    it[sourceReference] = source.id
                }
            EpisodeTable
                .insertAndGetId {
                    it[url] = "/episode/1"
                    it[name] = "Episode 1"
                    it[sourceOrder] = 1
                    it[anime] = animeId
                }.value
        }

    @Test
    fun `a source without hosters gets one hoster with its sorted videos, headers and tracks`() {
        val episodeId = episodeOf(videoSource)
        videoSource.videos =
            listOf(
                Video(
                    videoUrl = "https://cdn.example.org/480.m3u8",
                    videoTitle = "480p",
                    resolution = 480,
                    headers = headersOf("Referer", "https://videos.example.org/"),
                    subtitleTracks = listOf(Track("https://subs/en.vtt", "English")),
                    audioTracks = listOf(Track("https://audio/ja.m4a", "Japanese")),
                ),
                Video(videoUrl = "https://cdn.example.org/1080.m3u8", videoTitle = "1080p", resolution = 1080),
            )

        val hosters = runBlocking { AnimeVideos.fetchEpisodeVideos(episodeId) }

        assertEquals("/episode/1", videoSource.lastEpisodeUrl)
        val hoster = hosters.single()
        assertEquals(NO_HOSTER_LIST, hoster.name)
        assertEquals(AnimeVideos.HosterState.READY, hoster.state)
        assertEquals(listOf("1080p", "480p"), hoster.videos.map { it.videoTitle }, "sortVideos applied")
        val low = hoster.videos[1]
        assertEquals(listOf(AnimeVideos.VideoHeader("Referer", "https://videos.example.org/")), low.headers)
        assertEquals(listOf(AnimeVideos.VideoTrack("https://subs/en.vtt", "English")), low.subtitleTracks)
        assertEquals(listOf(AnimeVideos.VideoTrack("https://audio/ja.m4a", "Japanese")), low.audioTracks)
        // no headers of its own: the source's (its User-Agent)
        assertTrue(hoster.videos[0].headers.any { it.name == "User-Agent" })
    }

    @Test
    fun `a lib-14 video without a URL gets it from getVideoUrl`() {
        val episodeId = episodeOf(videoSource)
        @Suppress("DEPRECATION")
        videoSource.videos = listOf(Video(url = "/page/1", quality = "720p", videoUrl = null, subtitleTracks = emptyList()))

        val video = runBlocking { AnimeVideos.fetchEpisodeVideos(episodeId) }.single().videos.single()

        assertEquals("https://cdn.example.org/from-page/page/1", video.videoUrl)
        assertEquals("720p", video.videoTitle)
    }

    @Test
    fun `hosters load in parallel, sorted, with lazy ones idle and a failing one reporting its error`() {
        val episodeId = episodeOf(hosterSource)

        val hosters = runBlocking { AnimeVideos.fetchEpisodeVideos(episodeId) }

        assertEquals(listOf("Main", "Bad", "Lazy", "Inline"), hosters.map { it.name }, "sortHosters applied")
        assertEquals(listOf(0, 1, 2, 3), hosters.map { it.index })
        val (main, bad, lazy, inline) = hosters
        assertEquals(AnimeVideos.HosterState.READY, main.state)
        assertEquals("Main 1080p", main.videos.single().videoTitle)
        assertFalse(main.videos.single().initialized)
        assertEquals(AnimeVideos.HosterState.ERROR, bad.state)
        assertEquals(ErrorCode.SOURCE_FAILED, bad.errorCode)
        assertTrue(bad.videos.isEmpty())
        assertEquals(AnimeVideos.HosterState.IDLE, lazy.state)
        assertTrue(lazy.lazy)
        assertEquals("https://inline/1.mp4", inline.videos.single().videoUrl, "a hoster's own videoList is used")

        val loaded = runBlocking { AnimeVideos.fetchHosterVideos(episodeId, lazy.index) }
        assertEquals(AnimeVideos.HosterState.READY, loaded.state)
        assertEquals("Lazy 1080p", loaded.videos.single().videoTitle)
    }

    @Test
    fun `resolveVideo resolves a video of the last fetch once`() {
        val episodeId = episodeOf(hosterSource)
        val main = runBlocking { AnimeVideos.fetchEpisodeVideos(episodeId) }.first()

        val resolved = runBlocking { AnimeVideos.resolveVideo(episodeId, main.index, 0) }

        assertEquals("https://cdn.example.org/resolved/Main-1080p.m3u8", resolved.videoUrl)
        assertTrue(resolved.initialized)
        assertEquals(1, hosterSource.resolveCalls)
        assertEquals(resolved, runBlocking { AnimeVideos.resolveVideo(episodeId, main.index, 0) })
        assertEquals(1, hosterSource.resolveCalls, "an initialized video is not resolved again")
    }

    @Test
    fun `resolveVideo of a failing hoster reports its error, bad indices are BAD_REQUEST`() {
        val episodeId = episodeOf(hosterSource)
        runBlocking { AnimeVideos.fetchEpisodeVideos(episodeId) }

        val failed = assertFailsWith<MadoyomiException> { runBlocking { AnimeVideos.resolveVideo(episodeId, 1, 0) } }
        assertEquals(ErrorCode.SOURCE_FAILED, failed.code)
        val badHoster = assertFailsWith<MadoyomiException> { runBlocking { AnimeVideos.resolveVideo(episodeId, 9, 0) } }
        assertEquals(ErrorCode.BAD_REQUEST, badHoster.code)
        val badVideo = assertFailsWith<MadoyomiException> { runBlocking { AnimeVideos.resolveVideo(episodeId, 0, 5) } }
        assertEquals(ErrorCode.BAD_REQUEST, badVideo.code)
    }

    @Test
    fun `an unknown episode is NOT_FOUND`() {
        val e = assertFailsWith<MadoyomiException> { runBlocking { AnimeVideos.fetchEpisodeVideos(Int.MAX_VALUE) } }
        assertEquals(ErrorCode.NOT_FOUND, e.code)
        assertNull(e.cause)
    }
}
