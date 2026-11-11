package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The preferred server and quality's order (Phase 4 step 9): stable, after the source's own. */
class VideoPreferencesTest {
    private data class V(
        val title: String,
        val resolution: Int? = null,
    )

    private fun sort(
        videos: List<V>,
        settings: PlayerSettings,
        hoster: String = "no_hoster_list",
    ) = VideoPreferences.sortVideos(videos, settings, hoster, { it.title }, { it.resolution })

    private fun titles(ranked: List<VideoPreferences.Ranked<V>>) = ranked.map { it.item.title }

    private val animegg =
        listOf(
            V("[SUBBED] AnimeGG:1080p"),
            V("[SUBBED] AnimeGG:720p"),
            V("[SUBBED] AnimeGG:480p"),
            V("[SUBBED] AnimeGG:360p"),
            V("[DUBBED] AnimeGG:360p"),
        )

    @Test
    fun `a video's height is its resolution, else the last NNNp in its title`() {
        assertEquals(1080, VideoPreferences.heightOf("Main", 1080))
        assertEquals(360, VideoPreferences.heightOf("[DUBBED] AnimeGG:360p", null))
        assertEquals(720, VideoPreferences.heightOf("Server 1080p - 720p", 0))
        assertEquals(2160, VideoPreferences.heightOf("4K 2160P", null))
        assertNull(VideoPreferences.heightOf("Filemoon", null))
        assertNull(VideoPreferences.heightOf("1080pixels", null))
        assertNull(VideoPreferences.heightOf("12345p", null))
    }

    @Test
    fun `the target is the preferred height, else the highest below it, else the lowest above it`() {
        val heights = listOf(1080, 720, 480)
        assertEquals(720, VideoPreferences.targetHeight(heights, QualityPreference.P720))
        assertEquals(1080, VideoPreferences.targetHeight(heights, QualityPreference.P2160))
        assertEquals(480, VideoPreferences.targetHeight(heights, QualityPreference.P360))
        assertEquals(1080, VideoPreferences.targetHeight(heights, QualityPreference.HIGHEST))
        assertNull(VideoPreferences.targetHeight(heights, QualityPreference.SOURCE))
        assertNull(VideoPreferences.targetHeight(emptyList(), QualityPreference.P720))
    }

    @Test
    fun `without a preference nothing moves and nothing matches`() {
        val ranked = sort(animegg, PlayerSettings())
        assertEquals(titles(animegg.map { VideoPreferences.Ranked(it, 0, false) }), titles(ranked))
        assertEquals(listOf(0, 1, 2, 3, 4), ranked.map { it.sourceIndex })
        assertEquals(List(5) { false }, ranked.map { it.matchesPreference })
    }

    @Test
    fun `the preferred quality goes first, in the source's order, the rest after it as they were`() {
        val ranked = sort(animegg, PlayerSettings(preferredQuality = QualityPreference.P360))
        assertEquals(
            listOf(
                "[SUBBED] AnimeGG:360p",
                "[DUBBED] AnimeGG:360p",
                "[SUBBED] AnimeGG:1080p",
                "[SUBBED] AnimeGG:720p",
                "[SUBBED] AnimeGG:480p",
            ),
            titles(ranked),
        )
        assertEquals(listOf(3, 4, 0, 1, 2), ranked.map { it.sourceIndex })
        assertEquals(listOf(true, true, false, false, false), ranked.map { it.matchesPreference })

        // 2160p isn't there: the highest below it, 1080p, which is already first
        assertEquals("[SUBBED] AnimeGG:1080p", titles(sort(animegg, PlayerSettings(preferredQuality = QualityPreference.P2160))).first())
    }

    @Test
    fun `the preferred server goes before the quality, and a video without a height never matches the quality`() {
        val videos =
            listOf(
                V("Vidstream 1080p"),
                V("Vidstream 720p"),
                V("Filemoon 720p"),
                V("Filemoon 1080p"),
                V("Doodstream"),
            )
        val settings = PlayerSettings(preferredQuality = QualityPreference.P1080, preferredServer = "filemoon")
        val ranked = sort(videos, settings)
        assertEquals(listOf("Filemoon 1080p", "Filemoon 720p", "Vidstream 1080p", "Vidstream 720p", "Doodstream"), titles(ranked))
        assertEquals(listOf(true, true, true, false, false), ranked.map { it.matchesPreference })
    }

    @Test
    fun `a hoster named after the preferred server makes all its videos the server's`() {
        val settings = PlayerSettings(preferredQuality = QualityPreference.P720, preferredServer = "VidStreaming")
        val ranked = sort(listOf(V("1080p", 1080), V("720p", 720)), settings, hoster = "VidStreaming")
        assertEquals(listOf("720p", "1080p"), titles(ranked))
        assertEquals(listOf(true, true), ranked.map { it.matchesPreference })
    }

    @Test
    fun `hosters whose name contains the server go first, ignoring case, the rest in the source's order`() {
        val hosters = listOf("Doodstream", "VidStreaming", "Filemoon", "vidstreaming backup")
        val ranked = VideoPreferences.sortHosters(hosters, PlayerSettings(preferredServer = " vidSTREAMING "), { it })
        assertEquals(listOf("VidStreaming", "vidstreaming backup", "Doodstream", "Filemoon"), ranked.map { it.item })
        assertEquals(listOf(1, 3, 0, 2), ranked.map { it.sourceIndex })
        assertEquals(listOf(true, true, false, false), ranked.map { it.matchesPreference })

        val unsorted = VideoPreferences.sortHosters(hosters, PlayerSettings(preferredQuality = QualityPreference.P720), { it })
        assertEquals(hosters, unsorted.map { it.item })
    }
}
