package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import madoyomi.core.player.MpvCommand.Track.Kind
import madoyomi.core.player.TrackSelection.Choice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which subtitle and audio track mpv plays, from its track-list (Phase 4 Stage 5). */
class TrackSelectionTest {
    private fun track(
        id: Int,
        type: String,
        title: String? = null,
        lang: String? = null,
        url: String? = null,
        forced: Boolean = false,
    ) = buildJsonObject {
        put("id", id)
        put("type", type)
        title?.let { put("title", it) }
        lang?.let { put("lang", it) }
        put("external", url != null)
        url?.let { put("external-filename", it) }
        put("forced", forced)
    }

    // KickAssAnime's HLS: no audio of its own; subtitles and audio come from the source. mpv numbers them in the order
    // they finished loading, so its ids don't follow the source's order.
    private val externals =
        listOf(
            MpvCommand.Track(Kind.SUBTITLE, "https/en.vtt", "English (eng)"),
            MpvCommand.Track(Kind.SUBTITLE, "https/es.vtt", "Spanish (spa)"),
            MpvCommand.Track(Kind.SUBTITLE, "https/pt.vtt", "Português (Brasil)"),
            MpvCommand.Track(Kind.AUDIO, "https/ja.m4a", "Japanese (jpn)"),
            MpvCommand.Track(Kind.AUDIO, "https/en.m4a", "English (eng)"),
        )
    private val kaa =
        JsonArray(
            listOf(
                track(1, "video"),
                track(1, "sub", "Spanish (spa)", url = "https/es.vtt"),
                track(2, "sub", "English (eng)", url = "https/en.vtt"),
                track(3, "sub", "Português (Brasil)", url = "https/pt.vtt"),
                track(1, "audio", "English (eng)", url = "https/en.m4a"),
                track(2, "audio", "Japanese (jpn)", url = "https/ja.m4a"),
            ),
        )

    private fun choose(
        list: JsonArray,
        settings: PlayerSettings,
        ext: List<MpvCommand.Track> = externals,
    ) = TrackSelection.choose(list, ext, settings)

    @Test
    fun `without languages, Stage 2's rule - the source's first subtitle, and its first audio track without audio of its own`() {
        assertEquals(TrackSelection.Selection(Choice.Track(2), Choice.Track(2)), choose(kaa, PlayerSettings()))
    }

    @Test
    fun `a language selects the first track that matches it`() {
        val s = choose(kaa, PlayerSettings(subtitleLanguage = "pt", audioLanguage = "en"))
        assertEquals(TrackSelection.Selection(Choice.Track(3), Choice.Track(1)), s)
        assertEquals(Choice.Track(1), choose(kaa, PlayerSettings(subtitleLanguage = "es")).subtitle)
    }

    @Test
    fun `nothing matching falls back to Stage 2's rule, and off turns the subtitles off`() {
        val s = choose(kaa, PlayerSettings(subtitleLanguage = "de", audioLanguage = "ko"))
        assertEquals(TrackSelection.Selection(Choice.Track(2), Choice.Track(2)), s)
        assertEquals(Choice.Off, choose(kaa, PlayerSettings(subtitleLanguage = "off")).subtitle)
    }

    // an MKV: its own audio and subtitles (by lang code), no source tracks
    private val mkv =
        JsonArray(
            listOf(
                track(1, "video"),
                track(1, "audio", lang = "jpn"),
                track(2, "audio", lang = "eng", title = "English dub"),
                track(1, "sub", lang = "eng", title = "Signs & Songs", forced = true),
                track(2, "sub", lang = "eng", title = "Full"),
                track(3, "sub", lang = "spa"),
            ),
        )

    @Test
    fun `the file's own tracks match by their lang code, and a forced track only when nothing else does`() {
        val s = choose(mkv, PlayerSettings(subtitleLanguage = "en", audioLanguage = "en"), emptyList())
        assertEquals(TrackSelection.Selection(Choice.Track(2), Choice.Track(2)), s)
        val forcedOnly = JsonArray(listOf(track(1, "sub", lang = "eng", forced = true)))
        assertEquals(Choice.Track(1), choose(forcedOnly, PlayerSettings(subtitleLanguage = "en"), emptyList()).subtitle)
    }

    @Test
    fun `with audio of its own and no match, mpv's choice stays, and without source subtitles too`() {
        val s = choose(mkv, PlayerSettings(subtitleLanguage = "fr", audioLanguage = "fr"), emptyList())
        assertEquals(TrackSelection.Selection(Choice.Keep, Choice.Keep), s)
        assertEquals(TrackSelection.Selection(Choice.Keep, Choice.Keep), choose(mkv, PlayerSettings(), emptyList()))
    }

    @Test
    fun `the file's own tracks come before the source's of the same language`() {
        val both =
            JsonArray(
                listOf(
                    track(1, "audio", lang = "eng"),
                    track(2, "audio", "English (eng)", url = "https/en.m4a"),
                    track(1, "sub", "English (eng)", url = "https/en.vtt"),
                    track(2, "sub", lang = "eng"),
                ),
            )
        val s = choose(both, PlayerSettings(subtitleLanguage = "en", audioLanguage = "en"))
        assertEquals(TrackSelection.Selection(Choice.Track(2), Choice.Track(1)), s)
    }

    @Test
    fun `there is something to choose with source tracks, or with a language or subtitles off`() {
        assertTrue(TrackSelection.needed(externals, PlayerSettings()))
        assertFalse(TrackSelection.needed(emptyList(), PlayerSettings()))
        assertTrue(TrackSelection.needed(emptyList(), PlayerSettings(subtitleLanguage = "off")))
        assertTrue(TrackSelection.needed(emptyList(), PlayerSettings(audioLanguage = "ja")))
    }
}
