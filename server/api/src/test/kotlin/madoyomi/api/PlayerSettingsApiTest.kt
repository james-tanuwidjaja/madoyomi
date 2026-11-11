package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import madoyomi.core.player.PlayerSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player settings through GraphQL (Phase 4 Stage 5): `playerSettings`, `setPlayerSettings`, `playerLanguages`,
 * `mpvCheck`, and the order of `fetchEpisodeVideos` by the preferred server and quality.
 */
class PlayerSettingsApiTest : AnimeApiTest() {
    private val fields = "watchedThreshold preferredQuality preferredServer subtitleLanguage audioLanguage mpvPath"

    private fun settings() = graphql("query { playerSettings { $fields } }").assertNoErrors().dataPath("playerSettings")

    private fun set(patch: String) = graphql("mutation { setPlayerSettings(input: {patch: {$patch}}) { settings { $fields } } }")

    @Test
    fun `the defaults, a change that is kept in the data dir, and the languages`() {
        assertEquals(
            mapOf(
                "watchedThreshold" to 0.85,
                "preferredQuality" to "SOURCE",
                "preferredServer" to "",
                "subtitleLanguage" to "",
                "audioLanguage" to "",
                "mpvPath" to "",
            ),
            settings(),
        )
        val changed =
            set(
                """watchedThreshold: 0.7, preferredQuality: P720, preferredServer: "Filemoon", subtitleLanguage: "off", audioLanguage: "ja"""",
            ).assertNoErrors()
                .dataPath("setPlayerSettings", "settings")
        assertEquals(0.7, changed.path("watchedThreshold"))
        assertEquals("P720", changed.path("preferredQuality"))
        assertEquals(changed, settings())
        assertTrue(PlayerSettingsStore.file().readText().contains("\"preferredServer\": \"Filemoon\""))

        // only what is given changes
        set("""mpvPath: "C:/mpv/mpv.exe"""").assertNoErrors()
        assertEquals("Filemoon", settings().path("preferredServer"))
        assertEquals("C:/mpv/mpv.exe", settings().path("mpvPath"))

        val languages = graphql("query { playerLanguages { code name } }").assertNoErrors().dataPath("playerLanguages").asList
        assertTrue(mapOf("code" to "en", "name" to "English") in languages, languages.toString())
        assertTrue(mapOf("code" to "ja", "name" to "Japanese") in languages, languages.toString())
    }

    @Test
    fun `a wrong value is BAD_REQUEST and changes nothing`() {
        val response = set("""watchedThreshold: 0.3, audioLanguage: "en"""")
        assertEquals("BAD_REQUEST", response.errorCode)
        assertEquals(
            "watchedThreshold",
            response.errors!!
                .first()
                .extensions
                ?.get("details")
                .path("field"),
        )
        assertEquals("BAD_REQUEST", set("""subtitleLanguage: "klingon"""").errorCode)
        assertEquals(0.85, settings().path("watchedThreshold"))
        assertEquals("", settings().path("audioLanguage"))
    }

    @Test
    fun `mpvCheck says why a path won't do`() {
        val check =
            graphql("""query { mpvCheck(path: "C:/nowhere/mpv.exe") { ok path origin version errorCode message } }""")
                .assertNoErrors()
                .dataPath("mpvCheck")
        assertEquals(false, check.path("ok"))
        assertEquals("SETTINGS", check.path("origin"))
        assertEquals("PLAYER_NOT_FOUND", check.path("errorCode"))
        assertTrue((check.path("message") as String).contains("Settings → Player"))
    }

    /** Two hosters in the source's order (Doodstream, VidStreaming), each with 1080p, 720p and 480p. */
    private class ServerSource : AnimeHttpSource() {
        override val name = "Fake servers"
        override val lang = "en"
        override val baseUrl = "https://servers.example.org"
        override val supportsLatest = false

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
            listOf("Doodstream", "VidStreaming").map { Hoster(hosterUrl = "https://h/$it", hosterName = it) }

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            listOf(1080, 720, 480).map {
                Video(videoUrl = "https://cdn/${hoster.hosterName}/$it.mp4", videoTitle = "${hoster.hosterName} ${it}p", resolution = it)
            }
    }

    private fun fetchVideos(episodeId: Int) =
        graphql(
            """mutation { fetchEpisodeVideos(input: {episodeId: $episodeId}) {
                hosters { index sourceIndex name videos { index sourceIndex videoTitle matchesPreference } } } }""",
        ).assertNoErrors().dataPath("fetchEpisodeVideos", "hosters").asList

    @Test
    fun `fetchEpisodeVideos is sorted by the preferred server, then quality, and its indexes play what they show`() {
        val source = ServerSource()
        register(source)
        val episodeId = episodeRow(animeRow(source.id, "/anime"), "/episode/1")

        val plain = fetchVideos(episodeId)
        assertEquals(listOf("Doodstream", "VidStreaming"), plain.map { it.path("name") })
        assertEquals(listOf(0, 1), plain.map { it.path("sourceIndex") })
        assertEquals(listOf(false, false, false), plain[0].path("videos").asList.map { it.path("matchesPreference") })

        set("""preferredServer: "vidstream", preferredQuality: P720""").assertNoErrors()
        val sorted = fetchVideos(episodeId)
        assertEquals(listOf("VidStreaming", "Doodstream"), sorted.map { it.path("name") })
        assertEquals(listOf(0, 1), sorted.map { it.path("index") })
        assertEquals(listOf(1, 0), sorted.map { it.path("sourceIndex") })
        val first = sorted[0].path("videos").asList
        assertEquals(listOf("VidStreaming 720p", "VidStreaming 1080p", "VidStreaming 480p"), first.map { it.path("videoTitle") })
        assertEquals(listOf(1, 0, 2), first.map { it.path("sourceIndex") })
        assertEquals(listOf(true, true, true), first.map { it.path("matchesPreference") }, "the preferred server's videos")
        val other = sorted[1].path("videos").asList
        assertEquals(listOf("Doodstream 720p", "Doodstream 1080p", "Doodstream 480p"), other.map { it.path("videoTitle") })
        assertEquals(listOf(true, false, false), other.map { it.path("matchesPreference") }, "only its preferred quality")

        // the indexes are those of the sorted list, for this fetch
        val resolved =
            graphql(
                "mutation { resolveEpisodeVideo(input: {episodeId: $episodeId, hosterIndex: 1, videoIndex: 0}) { video { videoTitle } } }",
            ).assertNoErrors()
                .dataPath("resolveEpisodeVideo", "video", "videoTitle")
        assertEquals("Doodstream 720p", resolved)
    }
}
