package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * The languages the subtitle and audio preferences can name (Phase 4 Stage 5), and how a track's label is matched to
 * one. Sources label tracks freely ("English", "English (eng)", "English [CC]", "Português (Brasil)", "jpn", "en-US"),
 * and mpv's own tracks carry a `lang` code ("eng", "jpn") and maybe a `title`. A label matches a language when:
 * - one of its words is the language's English or native name ("english", "português"), or one of its ISO 639-2
 *   codes ("eng"; both the bibliographic and terminology codes, "fre"/"fra"); or
 * - the whole label is its ISO 639-1 code or a 639-1/639-2 code with a region ("en", "en-US", "pt_BR", "eng-US").
 *
 * Two-letter codes only count as the whole label, so a word like "it" or "id" in a title doesn't match Italian or
 * Indonesian. Words are runs of letters (any script), compared ignoring case.
 */
object PlayerLanguages {
    data class Language(
        /** ISO 639-1 (639-2 for a language without one, "fil"): what the settings store. */
        val code: String,
        /** The English name, shown in Settings. */
        val name: String,
        /** ISO 639-2 codes. */
        val codes3: Set<String>,
        /** Names a label may use, lowercase: the English name and the native ones. */
        val names: Set<String>,
    )

    private fun lang(
        code: String,
        name: String,
        codes3: String,
        vararg names: String,
    ) = Language(code, name, codes3.split(',').toSet(), (listOf(name) + names).map { it.lowercase() }.toSet())

    val ALL: List<Language> =
        listOf(
            lang("en", "English", "eng"),
            lang("ja", "Japanese", "jpn", "日本語", "nihongo"),
            lang("es", "Spanish", "spa", "español", "espanol", "castellano", "latino", "castilian"),
            lang("pt", "Portuguese", "por", "português", "portugues"),
            lang("fr", "French", "fra,fre", "français", "francais"),
            lang("de", "German", "deu,ger", "deutsch"),
            lang("it", "Italian", "ita", "italiano"),
            lang("ru", "Russian", "rus", "русский"),
            lang("ar", "Arabic", "ara", "العربية", "عربي"),
            lang("id", "Indonesian", "ind", "indonesia"),
            lang("ms", "Malay", "msa,may", "melayu"),
            lang("ko", "Korean", "kor", "한국어"),
            lang("zh", "Chinese", "zho,chi", "中文", "mandarin", "简体中文", "繁體中文"),
            lang("hi", "Hindi", "hin", "हिन्दी"),
            lang("th", "Thai", "tha", "ไทย"),
            lang("vi", "Vietnamese", "vie", "tiếng", "việt"),
            lang("tr", "Turkish", "tur", "türkçe", "turkce"),
            lang("pl", "Polish", "pol", "polski"),
            lang("nl", "Dutch", "nld,dut", "nederlands"),
            lang("uk", "Ukrainian", "ukr", "українська"),
            lang("fil", "Filipino", "fil,tgl", "tagalog"),
        ).sortedBy { it.name }

    fun byCode(code: String): Language? = ALL.firstOrNull { it.code == code.lowercase() }

    private val WORD = Regex("""\p{L}+""")
    private val CODE = Regex("""^([a-z]{2,3})(?:[-_][a-z0-9]+)*$""")

    /** Whether [label] (a track's label, title or `lang`) is in [language]. */
    fun matches(
        label: String?,
        language: Language,
    ): Boolean {
        val text = label?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        CODE.matchEntire(text)?.let { m ->
            val c = m.groupValues[1]
            if (c == language.code || c in language.codes3) return true
        }
        val words = WORD.findAll(text).map { it.value }.toList()
        return words.any { it in language.names || it in language.codes3 }
    }
}
