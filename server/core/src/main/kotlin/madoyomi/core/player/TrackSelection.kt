package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Which subtitle and audio track mpv plays (Phase 4 Stage 5), chosen from mpv's `track-list` once the video's external
 * tracks are loaded: the file's own tracks (an MKV's, an HLS stream's) and the source's, added over IPC with their
 * labels as titles. A track's language is matched by [PlayerLanguages.matches] on its `title` and its `lang`.
 *
 * - **Subtitles:** [PlayerSettings.SUBTITLES_OFF] turns them off. A language selects the first matching track, the
 *   file's own first, then the source's in the source's order; a forced (signs-only) track only if no other matches.
 *   No language, or nothing matching: Stage 2's rule, the source's first subtitle track, else mpv's own choice.
 * - **Audio:** a language selects the first matching track (the same order). No language, or nothing matching: the
 *   file's own audio as mpv chose it, else (an HLS stream whose audio comes only as the source's tracks) the source's
 *   first audio track (Stage 2's rule).
 */
object TrackSelection {
    sealed interface Choice {
        /** Leave mpv's selection as it is. */
        data object Keep : Choice

        /** No track (`sid=no`). */
        data object Off : Choice

        /** mpv's track id (per type). */
        data class Track(
            val id: Int,
        ) : Choice
    }

    data class Selection(
        val subtitle: Choice,
        val audio: Choice,
    )

    private data class Entry(
        val id: Int,
        val type: String,
        val title: String?,
        val lang: String?,
        val external: Boolean,
        val url: String?,
        val forced: Boolean,
    )

    fun choose(
        trackList: JsonElement?,
        externals: List<MpvCommand.Track>,
        settings: PlayerSettings,
    ): Selection {
        val entries =
            (trackList as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Entry(
                    id = o["id"].asInt() ?: return@mapNotNull null,
                    type = o["type"].asText() ?: return@mapNotNull null,
                    title = o["title"].asText(),
                    lang = o["lang"].asText(),
                    external = o["external"].asBoolean() == true,
                    url = o["external-filename"].asText(),
                    forced = o["forced"].asBoolean() == true,
                )
            }

        fun ordered(type: String): Pair<List<Entry>, List<Entry>> {
            val own = entries.filter { it.type == type && !it.external }
            val sourceOrder = externals.map { it.url }
            val ext =
                entries
                    .filter { it.type == type && it.external }
                    .sortedBy { e -> sourceOrder.indexOf(e.url).let { if (it < 0) Int.MAX_VALUE else it } }
            return own to ext
        }

        fun labelOf(e: Entry): String? = e.title ?: externals.firstOrNull { it.url == e.url }?.title

        fun match(
            candidates: List<Entry>,
            code: String,
        ): Entry? {
            val language = PlayerLanguages.byCode(code) ?: return null
            val matching =
                candidates.filter {
                    PlayerLanguages.matches(
                        labelOf(it),
                        language,
                    ) || PlayerLanguages.matches(it.lang, language)
                }
            return matching.firstOrNull { !it.forced } ?: matching.firstOrNull()
        }

        val (ownSubs, extSubs) = ordered("sub")
        val subtitle: Choice =
            when (val pref = settings.subtitleLanguage) {
                PlayerSettings.SUBTITLES_OFF -> {
                    Choice.Off
                }

                else -> {
                    val hit = pref.takeIf { it.isNotBlank() }?.let { match(ownSubs + extSubs, it) }
                    when {
                        hit != null -> Choice.Track(hit.id)
                        extSubs.isNotEmpty() -> Choice.Track(extSubs.first().id)
                        else -> Choice.Keep
                    }
                }
            }

        val (ownAudio, extAudio) = ordered("audio")
        val hit = settings.audioLanguage.takeIf { it.isNotBlank() }?.let { match(ownAudio + extAudio, it) }
        val audio: Choice =
            when {
                hit != null -> Choice.Track(hit.id)
                ownAudio.isNotEmpty() -> Choice.Keep
                extAudio.isNotEmpty() -> Choice.Track(extAudio.first().id)
                else -> Choice.Keep
            }
        return Selection(subtitle, audio)
    }

    /** Whether there is anything to choose without external tracks: a language, or subtitles off. */
    fun needed(
        externals: List<MpvCommand.Track>,
        settings: PlayerSettings,
    ): Boolean = externals.isNotEmpty() || settings.subtitleLanguage.isNotBlank() || settings.audioLanguage.isNotBlank()
}
