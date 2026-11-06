package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.anime.RealAnimeSources
import madoyomi.core.extension.TestApks
import madoyomi.core.extension.TestRepo
import madoyomi.exthost.ExtensionKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Phase 2 exit criterion: filters and preferences round-trip through the API for real extensions that use them
 * (`docs/phases/phase-2-anime-sources.md` §6). No network: the APKs come from `tools/apks/` and
 * `tools/compat-test/apks/` (skipped when missing), and a filtered search stops at the source.
 *
 * - Filters (kickassanime, aniwave, hanime): the filters as GraphQL lists them, every one moved off its default
 *   (group children one by one) as `FilterChange`s of a `fetchSourceAnime(SEARCH)`, and the list the real source's
 *   `getSearchAnime` receives has exactly those states.
 * - Preferences (AnimePahe, streamingcommunity's four sources, hanime, kickassanime): installed from a loopback repo
 *   (trusted by its key), a preference changed with `updateAnimeSourcePreference` is read back from the new instance
 *   the next use loads.
 */
class AnimeRoundTripApiTest : AnimeApiTest() {
    @TempDir
    lateinit var tmp: Path

    private val real by lazy { RealAnimeSources(tmp) }

    @AfterTest
    fun closeLoaders() {
        real.close()
    }

    // --- filters ---

    /** The real source, except that a search returns nothing and keeps the filter list it got. */
    private class CapturingSource(
        real: AnimeCatalogueSource,
    ) : AnimeCatalogueSource by real {
        var searched: AnimeFilterList? = null

        override suspend fun getSearchAnime(
            page: Int,
            query: String,
            filters: AnimeFilterList,
        ): AnimesPage {
            searched = filters
            return AnimesPage(emptyList(), false)
        }
    }

    private val leafFields =
        """__typename
            ... on HeaderFilter { name }
            ... on SeparatorFilter { name }
            ... on SelectFilter { name values selectDefault: default }
            ... on TextFilter { name textDefault: default }
            ... on CheckBoxFilter { name checkBoxDefault: default }
            ... on TriStateFilter { name triDefault: default }
            ... on SortFilter { name values sortDefault: default { index ascending } }"""

    private fun filters(sourceId: Long): List<*> =
        graphql(
            """{ animeSources(condition: {id: "$sourceId"}) { nodes { filters {
                $leafFields
                ... on GroupFilter { name filters { $leafFields } } } } } }""",
        ).assertNoErrors()
            .dataPath("animeSources", "nodes", "0", "filters")
            .asList

    /** A filter's change away from its GraphQL default (a `FilterChange` body without the position), or null. */
    private sealed interface Expected {
        data class Select(
            val index: Int,
        ) : Expected

        data class Text(
            val text: String,
        ) : Expected

        data class CheckBox(
            val checked: Boolean,
        ) : Expected

        data class Tri(
            val state: Int,
        ) : Expected

        data class Sort(
            val index: Int,
            val ascending: Boolean,
        ) : Expected
    }

    private val triStates = listOf("IGNORE", "INCLUDE", "EXCLUDE") // AnimeFilter.TriState.STATE_IGNORE/INCLUDE/EXCLUDE

    private fun filterChangeOf(filter: Any?): Pair<String, Expected>? =
        when (filter.path("__typename")) {
            "SelectFilter" -> {
                val values = filter.path("values") as List<*>
                if (values.size < 2) {
                    null
                } else {
                    val next = ((filter.path("selectDefault") as Int) + 1) % values.size
                    "selectState: $next" to Expected.Select(next)
                }
            }

            "TextFilter" -> {
                val text = (filter.path("textDefault") as String) + "madoyomi"
                "textState: \"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\"" to Expected.Text(text)
            }

            "CheckBoxFilter" -> {
                val checked = !(filter.path("checkBoxDefault") as Boolean)
                "checkBoxState: $checked" to Expected.CheckBox(checked)
            }

            "TriStateFilter" -> {
                val next = (triStates.indexOf(filter.path("triDefault")) + 1) % triStates.size
                "triState: ${triStates[next]}" to Expected.Tri(next)
            }

            "SortFilter" -> {
                val values = filter.path("values") as List<*>
                val default = filter.path("sortDefault")
                val index = (((default?.path("index") as Int?) ?: -1) + 1) % values.size
                val ascending = !((default?.path("ascending") as Boolean?) ?: false)
                "sortState: {index: $index, ascending: $ascending}" to Expected.Sort(index, ascending)
            }

            else -> {
                null
            }
        }

    /** Every filter moved off its default: the `FilterChange` literals and, per position path, the state expected. */
    private fun changesOf(filters: List<*>): Pair<List<String>, Map<List<Int>, Expected>> {
        val literals = mutableListOf<String>()
        val expected = mutableMapOf<List<Int>, Expected>()
        filters.forEachIndexed { position, filter ->
            if (filter.path("__typename") == "GroupFilter") {
                (filter.path("filters") as List<*>).forEachIndexed { child, inner ->
                    filterChangeOf(inner)?.let { (body, state) ->
                        literals += "{position: $position, groupChange: {position: $child, $body}}"
                        expected[listOf(position, child)] = state
                    }
                }
            } else {
                filterChangeOf(filter)?.let { (body, state) ->
                    literals += "{position: $position, $body}"
                    expected[listOf(position)] = state
                }
            }
        }
        return literals to expected
    }

    private fun stateOf(
        filter: AnimeFilter<*>,
        expected: Expected,
    ): Any? =
        when (expected) {
            is Expected.Select -> (filter as AnimeFilter.Select<*>).state
            is Expected.Text -> (filter as AnimeFilter.Text).state
            is Expected.CheckBox -> (filter as AnimeFilter.CheckBox).state
            is Expected.Tri -> (filter as AnimeFilter.TriState).state
            is Expected.Sort -> (filter as AnimeFilter.Sort).state?.let { Expected.Sort(it.index, it.ascending) }
        }

    private fun valueOf(expected: Expected): Any =
        when (expected) {
            is Expected.Select -> expected.index
            is Expected.Text -> expected.text
            is Expected.CheckBox -> expected.checked
            is Expected.Tri -> expected.state
            is Expected.Sort -> expected
        }

    private fun filterRoundTrip(glob: String) {
        val source = CapturingSource(real.compatApk(glob).single() as AnimeCatalogueSource)
        register(source)
        val listed = filters(source.id)
        assertTrue(listed.isNotEmpty(), "${source.name} has filters")
        val (changes, expected) = changesOf(listed)

        graphql(
            """mutation { fetchSourceAnime(input: {source: "${source.id}", type: SEARCH, page: 1, query: "",
                filters: [${changes.joinToString()}]}) { hasNextPage animes { id } } }""",
        ).assertNoErrors()

        val searched = assertNotNull(source.searched, "the search reached the source")
        assertEquals(listed.size, searched.size)
        expected.forEach { (path, state) ->
            val top = searched[path[0]]
            val filter = if (path.size == 1) top else (top as AnimeFilter.Group<*>).state[path[1]] as AnimeFilter<*>
            assertEquals(valueOf(state), stateOf(filter, state), "${source.name}: filter at $path ('${filter.name}')")
        }
        println("${source.name}: ${listed.size} filters, ${changes.size} changes round-tripped through fetchSourceAnime")
    }

    @Test
    fun `kickassanime's filters round-trip through fetchSourceAnime`() = filterRoundTrip("aniyomi-en.kickassanime-v*.apk")

    @Test
    fun `aniwave's filters round-trip through fetchSourceAnime`() = filterRoundTrip("aniyomi-en.aniwave-v*.apk")

    @Test
    fun `hanime's filters round-trip through fetchSourceAnime (sort and tri-states)`() = filterRoundTrip("aniyomi-en.hanime-v*.apk")

    // --- preferences ---

    private fun compatApk(glob: String): TestApks.Apk {
        val dir = RealAnimeSources.compatApks
        val found = if (dir.isDirectory()) dir.listDirectoryEntries(glob).maxOrNull() else null
        assumeTrue(found != null, "No $glob in ${dir.toAbsolutePath().normalize()}: run tools/compat-test/scan.ps1")
        return TestApks.Apk(found!!)
    }

    /** Installs [apk] from a loopback repo signed with its own key (so it is trusted and loaded); its live source ids. */
    private fun install(apk: TestApks.Apk): List<Long> {
        val repo = TestRepo(repoServer.routes, ExtensionKind.ANIME, "${repoServer.base}/anime-${UUID.randomUUID()}", apk.hash)
        repo.serve(TestRepo.Entry(apk))
        graphql(
            """mutation { addExtensionStore(input: {indexUrl: "${repo.listUrl}", type: ANIME, format: JSON}) {
                extensionStore { indexUrl } } }""",
        ).assertNoErrors()
        val installed =
            graphql("""mutation { updateExtension(input: {id: "${apk.pkg}", patch: {install: true}}) { extension { isLoaded } } }""")
                .assertNoErrors()
        assertEquals(true, installed.dataPath("updateExtension", "extension", "isLoaded"))
        return graphql("""{ animeSources(condition: {extensionPkgName: "${apk.pkg}"}) { nodes { id } } }""")
            .assertNoErrors()
            .dataPath("animeSources", "nodes")
            .asList
            .map { (it.path("id") as String).toLong() }
    }

    private val preferenceFields =
        """__typename
            ... on SwitchPreference { key switchValue: currentValue }
            ... on CheckBoxPreference { key checkBoxValue: currentValue }
            ... on ListPreference { key listValue: currentValue entryValues }
            ... on MultiSelectListPreference { key multiValue: currentValue entryValues }
            ... on EditTextPreference { key textValue: currentValue }"""

    private fun preferences(sourceId: Long): List<*> {
        val node =
            graphql("""{ animeSources(condition: {id: "$sourceId"}) { nodes { isConfigurable preferences { $preferenceFields } } } }""")
                .assertNoErrors()
                .dataPath("animeSources", "nodes", "0")
        assertEquals(true, node.path("isConfigurable"))
        return node.path("preferences").asList
    }

    private fun clearPreferences(sourceIds: List<Long>) {
        sourceIds.forEach { id ->
            (AnimeGetSource.cached(id) as? ConfigurableAnimeSource)
                ?.getSourcePreferences()
                ?.edit()
                ?.clear()
                ?.commit()
        }
    }

    /** One change away from the current value (the `SourcePreferenceChange` body and the value read back), or null. */
    private fun preferenceChangeOf(preference: Any?): Pair<String, Any>? =
        when (preference.path("__typename")) {
            "SwitchPreference" -> {
                (!(preference.path("switchValue") as Boolean)).let { "switchState: $it" to it }
            }

            "CheckBoxPreference" -> {
                (!(preference.path("checkBoxValue") as Boolean)).let { "checkBoxState: $it" to it }
            }

            "ListPreference" -> {
                (preference.path("entryValues") as List<*>)
                    .firstOrNull { it != preference.path("listValue") }
                    ?.let { "listState: \"$it\"" to it }
            }

            else -> {
                null
            }
        }

    /**
     * For each source of [apk]: lists its preferences, changes the first switch, check box or list (their values are
     * the extension's own, so no listener refuses them), and reads it back from the source the next use loads.
     */
    private fun preferenceRoundTrip(
        apk: TestApks.Apk,
        sources: Int = 1,
    ) {
        val ids = install(apk)
        assertEquals(sources, ids.size, "${apk.pkg} provides $sources sources")
        clearPreferences(ids)
        try {
            ids.forEach { sourceId ->
                val before = preferences(sourceId)
                val (index, change) =
                    before
                        .withIndex()
                        .firstNotNullOfOrNull { (i, p) -> preferenceChangeOf(p)?.let { i to it } }
                        .let { assertNotNull(it, "${apk.pkg} $sourceId has a switch, check box or list: $before") }
                val instance = AnimeGetSource.cached(sourceId)
                graphql(
                    """mutation { updateAnimeSourcePreference(input: {source: "$sourceId", change: {position: $index, ${change.first}}}) {
                        source { id } } }""",
                ).assertNoErrors()

                val after = preferences(sourceId)
                assertNotSame(instance, AnimeGetSource.cached(sourceId), "the change re-instantiated the source")
                val read = after[index].let { it.path("switchValue") ?: it.path("checkBoxValue") ?: it.path("listValue") }
                assertEquals(change.second, read, "${apk.pkg} $sourceId: '${before[index].path("key")}' persisted")
                assertEquals(before.size, after.size)
                println("${apk.pkg} $sourceId: ${before.size} preferences, '${before[index].path("key")}' -> ${change.second}")
            }
        } finally {
            clearPreferences(ids)
        }
    }

    @Test
    fun `AnimePahe's preferences round-trip`() = preferenceRoundTrip(TestApks.apk(TestApks.ANIMEPAHE))

    @Test
    fun `streamingcommunity's four factory sources each round-trip their preferences`() =
        preferenceRoundTrip(compatApk("aniyomi-all.streamingcommunity-v*.apk"), sources = 4)

    @Test
    fun `hanime's preferences round-trip`() = preferenceRoundTrip(compatApk("aniyomi-en.hanime-v*.apk"))

    @Test
    fun `kickassanime's preferences round-trip`() = preferenceRoundTrip(compatApk("aniyomi-en.kickassanime-v*.apk"))
}
