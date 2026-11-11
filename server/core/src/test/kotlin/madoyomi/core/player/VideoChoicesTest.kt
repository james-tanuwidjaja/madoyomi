package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.AnimeVideos.EpisodeHoster
import madoyomi.core.anime.AnimeVideos.EpisodeVideo
import madoyomi.core.anime.AnimeVideos.HosterState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding a video again after a refetch, the next video to offer, and the HTTP status in mpv's log (Stage 4). */
class VideoChoicesTest {
    private fun video(
        index: Int,
        title: String,
        resolution: Int? = null,
    ) = EpisodeVideo(
        index = index,
        videoTitle = title,
        videoUrl = "https://cdn/$index",
        videoPageUrl = "",
        resolution = resolution,
        bitrate = null,
        headers = emptyList(),
        preferred = false,
        initialized = true,
        subtitleTracks = emptyList(),
        audioTracks = emptyList(),
        timestamps = emptyList(),
        mpvArgs = emptyList(),
    )

    private fun hoster(
        index: Int,
        name: String,
        vararg videos: EpisodeVideo,
        state: HosterState = HosterState.READY,
    ) = EpisodeHoster(
        index,
        name,
        "https://h/$index",
        lazy = false,
        state = state,
        videos = videos.toList(),
        errorCode = null,
        errorMessage = null,
    )

    private val hosters =
        listOf(
            hoster(0, "Main", video(0, "Main 1080p", 1080), video(1, "Main 720p", 720)),
            hoster(1, "Broken", state = HosterState.ERROR),
            hoster(2, "Lazy", state = HosterState.IDLE),
            hoster(3, "Backup", video(0, "Backup 480p", 480)),
        )

    private fun ref(
        hoster: String,
        title: String,
        resolution: Int? = null,
    ) = VideoRef(hoster, title, resolution)

    @Test
    fun `find matches the hoster by name, then the video by title, else by resolution`() {
        assertEquals(VideoChoices.Position(0, 1, ref("Main", "Main 720p", 720)), VideoChoices.find(hosters, ref("Main", "Main 720p", 720)))
        // the title changed between fetches (a token in it, say): the resolution finds it
        assertEquals(
            VideoChoices.Position(0, 0, ref("Main", "Main 1080p", 1080)),
            VideoChoices.find(hosters, ref("Main", "Main 1080p (abc)", 1080)),
        )
        assertEquals(
            VideoChoices.Position(3, 0, ref("Backup", "Backup 480p", 480)),
            VideoChoices.find(hosters, ref("Backup", "Backup 480p")),
        )
    }

    @Test
    fun `find gives nothing rather than another video`() {
        assertNull(VideoChoices.find(hosters, ref("Main", "Main 360p", 360)), "no such title or resolution")
        assertNull(VideoChoices.find(hosters, ref("Main", "Main 360p")), "no resolution to fall back on")
        assertNull(VideoChoices.find(hosters, ref("Gone", "Main 1080p", 1080)), "no such hoster")
        assertNull(VideoChoices.find(hosters, ref("Broken", "x")), "a failed hoster has no video")
    }

    @Test
    fun `next is the following video in the source's order, across hosters, skipping those without videos`() {
        assertEquals("Main 720p", VideoChoices.next(hosters, ref("Main", "Main 1080p", 1080))?.ref?.videoTitle)
        val afterMain = VideoChoices.next(hosters, ref("Main", "Main 720p", 720))
        assertEquals(VideoChoices.Position(3, 0, ref("Backup", "Backup 480p", 480)), afterMain)
    }

    @Test
    fun `next wraps around to the first video, never offers the failed one, and is null when there is no other`() {
        assertEquals("Main 1080p", VideoChoices.next(hosters, ref("Backup", "Backup 480p", 480))?.ref?.videoTitle)
        val single = listOf(hoster(0, "Main", video(0, "Main 1080p", 1080)))
        assertNull(VideoChoices.next(single, ref("Main", "Main 1080p", 1080)))
        assertNull(VideoChoices.next(emptyList(), ref("Main", "Main 1080p", 1080)))
    }

    @Test
    fun `next without the failed video in the list is the first video`() {
        assertEquals("Main 1080p", VideoChoices.next(hosters, ref("Gone", "Gone 1080p", 1080))?.ref?.videoTitle)
    }

    @Test
    fun `the HTTP status is the last HTTP error mpv logged`() {
        val log =
            """
            [ffmpeg] https: HTTP error 404 Not Found
            [ffmpeg] https: HTTP error 403 Forbidden
            [stream] Failed to open https://cdn.example.org/v.mp4.
            """.trimIndent()
        assertEquals(403, MpvLog.httpStatus(log))
        assertNull(MpvLog.httpStatus("[ffmpeg/demuxer] mov: moov atom not found"))
        assertNull(MpvLog.httpStatus(""))
    }

    @Test
    fun `an eof more than 10 s before a known end ended early`() {
        assertTrue(MpvLog.endedEarly(900.0, 1500.0))
        assertFalse(MpvLog.endedEarly(1495.0, 1500.0), "the end, give or take")
        assertFalse(MpvLog.endedEarly(null, 1500.0))
        assertFalse(MpvLog.endedEarly(900.0, null), "no duration: nothing to compare with")
    }

    @Test
    fun `the log's tail is its last bytes, and empty for a missing file`() {
        val file = File.createTempFile("mpv", ".log").apply { deleteOnExit() }
        file.writeText("x".repeat(100) + "HTTP error 410 Gone\n")
        assertEquals(410, MpvLog.httpStatus(MpvLog.tail(file, maxBytes = 40)))
        assertEquals(20, MpvLog.tail(file, maxBytes = 20).length)
        assertEquals("", MpvLog.tail(File(file.parentFile, "missing-${System.nanoTime()}.log")))
    }
}
