package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.HttpException
import okhttp3.Headers.Companion.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `fetchEpisodeVideos`, `fetchHosterVideos` and `resolveEpisodeVideo` over fake HTTP sources. */
class AnimeVideosApiTest : AnimeApiTest() {
    /** A lib-16 style source: sorted hosters, a failing one, a lazy one, and videos that need resolving. */
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
            )

        override fun List<Hoster>.sortHosters(): List<Hoster> = sortedBy { it.hosterName != "Main" }

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            when (hoster.hosterName) {
                "Bad" -> {
                    throw HttpException(503)
                }

                else -> {
                    listOf(
                        Video(
                            videoUrl = "",
                            videoTitle = "${hoster.hosterName} 1080p",
                            resolution = 1080,
                            preferred = true,
                            headers = headersOf("Referer", "https://hosters.example.org/"),
                            subtitleTracks = listOf(Track("https://subs/en.vtt", "English")),
                            audioTracks = listOf(Track("https://audio/ja.m4a", "Japanese")),
                            timestamps = listOf(TimeStamp(0.0, 90.0, "Opening")),
                            mpvArgs = listOf("demuxer-max-bytes" to "50M"),
                        ),
                    )
                }
            }

        override suspend fun resolveVideo(video: Video): Video {
            resolveCalls++
            return video.copy(videoUrl = "https://cdn.example.org/resolved/${video.videoTitle.replace(' ', '-')}.m3u8")
        }
    }

    /** An extension whose code needs a host API that isn't there. */
    private class GapSource : AnimeHttpSource() {
        override val name = "Fake gap"
        override val lang = "en"
        override val baseUrl = "https://gap.example.org"
        override val supportsLatest = false

        @Deprecated("Use the hoster version instead")
        override suspend fun getVideoList(episode: SEpisode): List<Video> = throw NoSuchMethodError("android.view.View.setEnabled(Z)V")
    }

    private val videoFields =
        "index videoTitle videoUrl initialized preferred resolution headers { name value } subtitleTracks { url lang } " +
            "audioTracks { url lang } timestamps { start end name type } mpvArgs { name value }"

    private fun episodeOf(source: AnimeHttpSource): Int {
        register(source)
        return episodeRow(animeRow(source.id, "/anime"), "/episode/1")
    }

    private fun fetchVideos(episodeId: Int) =
        graphql(
            """mutation { fetchEpisodeVideos(input: {episodeId: $episodeId}) {
                episode { id url } hosters { index name url lazy state errorCode errorMessage videos { $videoFields } } } }""",
        )

    @Test
    fun `hosters come back sorted, a lazy one idle and a failing one with its code, videos with headers and tracks`() {
        val source = HosterSource()
        val episodeId = episodeOf(source)

        val payload = fetchVideos(episodeId).assertNoErrors().dataPath("fetchEpisodeVideos")
        assertEquals("/episode/1", payload.path("episode", "url"))
        val hosters = payload.path("hosters").asList
        assertEquals(listOf("Main", "Bad", "Lazy"), hosters.map { it.path("name") })
        assertEquals(listOf(0, 1, 2), hosters.map { it.path("index") })
        assertEquals(listOf("READY", "ERROR", "IDLE"), hosters.map { it.path("state") })
        assertEquals("SOURCE_FAILED", hosters[1].path("errorCode"))
        assertEquals(true, hosters[2].path("lazy"))

        val video = hosters[0].path("videos", "0")
        assertEquals("Main 1080p", video.path("videoTitle"))
        assertEquals(false, video.path("initialized"))
        assertEquals(true, video.path("preferred"))
        assertEquals(1080, video.path("resolution"))
        assertEquals(listOf(mapOf("name" to "Referer", "value" to "https://hosters.example.org/")), video.path("headers"))
        assertEquals(listOf(mapOf("url" to "https://subs/en.vtt", "lang" to "English")), video.path("subtitleTracks"))
        assertEquals(listOf(mapOf("url" to "https://audio/ja.m4a", "lang" to "Japanese")), video.path("audioTracks"))
        assertEquals(listOf(mapOf("start" to 0.0, "end" to 90.0, "name" to "Opening", "type" to "Other")), video.path("timestamps"))
        assertEquals(listOf(mapOf("name" to "demuxer-max-bytes", "value" to "50M")), video.path("mpvArgs"))
    }

    @Test
    fun `a lazy hoster loads on request and a video resolves before playing`() {
        val source = HosterSource()
        val episodeId = episodeOf(source)
        fetchVideos(episodeId).assertNoErrors()

        val lazy =
            graphql(
                """mutation { fetchHosterVideos(input: {episodeId: $episodeId, hosterIndex: 2}) {
                    hoster { name state videos { videoTitle } } } }""",
            ).assertNoErrors().dataPath("fetchHosterVideos", "hoster")
        assertEquals("READY", lazy.path("state"))
        assertEquals("Lazy 1080p", lazy.path("videos", "0", "videoTitle"))

        val resolved =
            graphql(
                """mutation { resolveEpisodeVideo(input: {episodeId: $episodeId, hosterIndex: 0, videoIndex: 0}) {
                    video { $videoFields } } }""",
            ).assertNoErrors().dataPath("resolveEpisodeVideo", "video")
        assertEquals("https://cdn.example.org/resolved/Main-1080p.m3u8", resolved.path("videoUrl"))
        assertEquals(true, resolved.path("initialized"))
        assertEquals(1, source.resolveCalls)
    }

    @Test
    fun `an index out of range is BAD_REQUEST, an unknown episode NOT_FOUND`() {
        val source = HosterSource()
        val episodeId = episodeOf(source)
        fetchVideos(episodeId).assertNoErrors()

        val noHoster = graphql("""mutation { fetchHosterVideos(input: {episodeId: $episodeId, hosterIndex: 9}) { hoster { name } } }""")
        assertEquals("BAD_REQUEST", noHoster.errorCode)
        val noVideo =
            graphql(
                """mutation { resolveEpisodeVideo(input: {episodeId: $episodeId, hosterIndex: 0, videoIndex: 5}) {
                    video { videoUrl } } }""",
            )
        assertEquals("BAD_REQUEST", noVideo.errorCode)
        assertEquals("NOT_FOUND", fetchVideos(123_456).errorCode)
    }

    @Test
    fun `an API gap in the extension is EXTENSION_INCOMPATIBLE, with the details`() {
        val source = GapSource()
        val episodeId = episodeOf(source)

        val response = fetchVideos(episodeId)
        assertEquals("EXTENSION_INCOMPATIBLE", response.errorCode)
        val details = response.errors!!.first().extensions!!["details"] as Map<*, *>
        assertEquals("MISSING_MEMBER", details["kind"])
        assertEquals("videos", details["operation"])
        assertTrue((details["missing"] as String).contains("android.view.View.setEnabled"))
    }
}
