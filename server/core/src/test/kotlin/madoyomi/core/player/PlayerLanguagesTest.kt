package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a track's label is matched to a language (Phase 4 Stage 5). */
class PlayerLanguagesTest {
    private fun lang(code: String) = PlayerLanguages.byCode(code)!!

    private fun matches(
        label: String?,
        code: String,
    ) = PlayerLanguages.matches(label, lang(code))

    @Test
    fun `labels as sources and mpv write them`() {
        listOf("English", "English (eng)", "english", "English [CC]", "eng", "en", "en-US", "EN_gb", "Full English Subs")
            .forEach { assertTrue(matches(it, "en"), it) }
        listOf("Japanese", "jpn", "ja", "日本語").forEach { assertTrue(matches(it, "ja"), it) }
        listOf("Português (Brasil)", "Portuguese (Brazil)", "pt-BR", "por").forEach { assertTrue(matches(it, "pt"), it) }
        listOf("Español (Latino)", "Spanish", "spa", "Castellano").forEach { assertTrue(matches(it, "es"), it) }
        listOf("Français", "fre", "fra").forEach { assertTrue(matches(it, "fr"), it) }
        listOf("简体中文", "中文", "chi", "zh-Hans").forEach { assertTrue(matches(it, "zh"), it) }
    }

    @Test
    fun `other languages, a two-letter word inside a label, and nothing don't match`() {
        assertFalse(matches("Japanese", "en"))
        assertFalse(matches("English (eng)", "ja"))
        assertFalse(matches("Sign it", "it"))
        assertFalse(matches("id 3", "id"))
        assertFalse(matches("Englishman", "en"))
        assertFalse(matches("", "en"))
        assertFalse(matches(null, "en"))
        assertTrue(matches("it", "it"))
    }

    @Test
    fun `codes are found by their two-letter code, ignoring case, and listed by name`() {
        assertEquals("Japanese", PlayerLanguages.byCode("JA")?.name)
        assertNull(PlayerLanguages.byCode("xx"))
        assertEquals(PlayerLanguages.ALL.map { it.name }.sorted(), PlayerLanguages.ALL.map { it.name })
        assertEquals(
            PlayerLanguages.ALL.size,
            PlayerLanguages.ALL
                .map { it.code }
                .toSet()
                .size,
        )
    }
}
