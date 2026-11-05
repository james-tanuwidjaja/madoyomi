package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.graphql.types.CheckBoxFilter
import suwayomi.tachidesk.graphql.types.Filter
import suwayomi.tachidesk.graphql.types.FilterChange
import suwayomi.tachidesk.graphql.types.GroupFilter
import suwayomi.tachidesk.graphql.types.HeaderFilter
import suwayomi.tachidesk.graphql.types.SelectFilter
import suwayomi.tachidesk.graphql.types.SeparatorFilter
import suwayomi.tachidesk.graphql.types.SortFilter
import suwayomi.tachidesk.graphql.types.TextFilter
import suwayomi.tachidesk.graphql.types.TriState
import suwayomi.tachidesk.graphql.types.TriStateFilter
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [AnimeFilters]: every filter type to upstream's `Filter` union and back through `FilterChange`. */
class AnimeFiltersTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            ApplicationTest.testingSetup()
        }
    }

    @TempDir
    lateinit var tmp: Path

    private val real by lazy { RealAnimeSources(tmp) }

    @AfterEach
    fun closeLoaders() {
        real.close()
    }

    private class Quality : AnimeFilter.Select<String>("Quality", arrayOf("Any", "720p", "1080p"), 1)

    private class Query : AnimeFilter.Text("Studio")

    private class Dubbed : AnimeFilter.CheckBox("Dubbed", true)

    private class Genre(
        name: String,
    ) : AnimeFilter.TriState(name)

    private class Genres : AnimeFilter.Group<Genre>("Genres", listOf(Genre("Action"), Genre("Drama")))

    private class Nested : AnimeFilter.Group<AnimeFilter<*>>("Nested", listOf(Dubbed(), Genres()))

    private class Order : AnimeFilter.Sort("Order", arrayOf("Name", "Date"), Selection(1, false))

    private fun fakeList() =
        AnimeFilterList(
            AnimeFilter.Header("Options"),
            AnimeFilter.Separator(),
            Quality(),
            Query(),
            Dubbed(),
            Genres(),
            Order(),
            Nested(),
        )

    private val fake = FakeAnimeSource(id = 7_331L).apply { filters = ::fakeList }

    @Test
    fun `every filter type maps to upstream's union`() {
        val expected =
            listOf(
                HeaderFilter("Options"),
                SeparatorFilter(""),
                SelectFilter("Quality", listOf("Any", "720p", "1080p"), 1),
                TextFilter("Studio", ""),
                CheckBoxFilter("Dubbed", true),
                GroupFilter("Genres", listOf(TriStateFilter("Action", TriState.IGNORE), TriStateFilter("Drama", TriState.IGNORE))),
                SortFilter("Order", listOf("Name", "Date"), SortFilter.SortSelection(1, false)),
                GroupFilter(
                    "Nested",
                    listOf(
                        CheckBoxFilter("Dubbed", true),
                        GroupFilter("Genres", listOf(TriStateFilter("Action", TriState.IGNORE), TriStateFilter("Drama", TriState.IGNORE))),
                    ),
                ),
            )
        assertEquals(expected, AnimeFilters.filtersOf(fake))
    }

    @Test
    fun `changes apply by position, nested groups included, on a fresh list`() {
        val changes =
            listOf(
                FilterChange(position = 0, textState = "ignored: a header has no state"),
                FilterChange(position = 2, selectState = 2),
                FilterChange(position = 3, textState = "MAPPA"),
                FilterChange(position = 4, checkBoxState = false),
                FilterChange(position = 5, groupChange = FilterChange(position = 1, triState = TriState.EXCLUDE)),
                FilterChange(position = 5, groupChange = FilterChange(position = 0, triState = TriState.INCLUDE)),
                FilterChange(position = 6, sortState = SortFilter.SortSelection(0, true)),
                FilterChange(position = 7, groupChange = FilterChange(1, groupChange = FilterChange(0, triState = TriState.INCLUDE))),
            )

        val list = AnimeFilters.apply(fake, changes)

        assertEquals(2, (list[2] as Quality).state)
        assertEquals("MAPPA", (list[3] as Query).state)
        assertEquals(false, (list[4] as Dubbed).state)
        assertEquals(
            listOf(AnimeFilter.TriState.STATE_INCLUDE, AnimeFilter.TriState.STATE_EXCLUDE),
            (list[5] as Genres).state.map { it.state },
        )
        assertEquals(AnimeFilter.Sort.Selection(0, true), (list[6] as Order).state)
        assertEquals(AnimeFilter.TriState.STATE_INCLUDE, ((list[7] as Nested).state[1] as Genres).state[0].state)
        // the source's own list is untouched: the next use starts from its defaults
        assertEquals(1, (AnimeFilters.apply(fake, null)[2] as Quality).state)
    }

    @Test
    fun `a change that doesn't fit is BAD_REQUEST with its path`() {
        fun rejected(vararg changes: FilterChange): MadoyomiException =
            assertFailsWith<MadoyomiException> { AnimeFilters.apply(fake, changes.toList()) }.also {
                assertEquals(ErrorCode.BAD_REQUEST, it.code)
            }

        assertEquals("8", rejected(FilterChange(position = 8, checkBoxState = true)).details["path"])
        assertEquals("2", rejected(FilterChange(position = 2, textState = "720p")).details["path"])
        assertEquals("2", rejected(FilterChange(position = 2, selectState = 3)).details["path"])
        assertEquals("6", rejected(FilterChange(position = 6, sortState = SortFilter.SortSelection(2, true))).details["path"])
        assertEquals("5", rejected(FilterChange(position = 5)).details["path"])
        assertEquals(
            "7.1.4",
            rejected(
                FilterChange(7, groupChange = FilterChange(1, groupChange = FilterChange(4, triState = TriState.INCLUDE))),
            ).details["path"],
        )
    }

    // --- real extensions: kickassanime (SAFE: select, checkbox, group, header, separator), aniwave (SAFE: + text),
    // hanime (#1 of the top 20, NSFW: the only one with sort and tri-state)

    @Test
    fun `kickassanime's filters round-trip`() = roundTrip(real.compatApk("aniyomi-en.kickassanime-v*.apk").single())

    @Test
    fun `aniwave's filters round-trip`() = roundTrip(real.compatApk("aniyomi-en.aniwave-v*.apk").single())

    @Test
    fun `hanime's filters round-trip`() = roundTrip(real.compatApk("aniyomi-en.hanime-v*.apk").single())

    /**
     * Changes every filter of [source] (group children one by one) away from its default, applies the changes to a
     * fresh list and checks that the mapped result is exactly the expected one, and that the source's defaults are
     * untouched afterwards.
     */
    private fun roundTrip(source: AnimeSource) {
        val defaults = AnimeFilters.filtersOf(source)
        assertTrue(defaults.isNotEmpty(), "${source.name} has filters")

        val changes = defaults.flatMapIndexed { index, filter -> changesOf(filter).map { it.at(index) } }
        val applied = AnimeFilters.apply(source, changes).map(AnimeFilters::filterOf)

        assertEquals(defaults.map(::changed), applied)
        assertEquals(defaults, AnimeFilters.filtersOf(source), "a fresh list has the defaults again")
        println("${source.name}: ${defaults.size} filters, ${changes.size} changes round-tripped (${kinds(defaults)})")
    }

    private fun kinds(filters: List<Filter>): String =
        filters
            .flatMap { if (it is GroupFilter) listOf(it) + it.filters else listOf(it) }
            .groupingBy { it.javaClass.simpleName }
            .eachCount()
            .entries
            .joinToString { "${it.value} ${it.key}" }

    /** A change at position 0 (re-positioned with [at]) for each state of [filter] that can change. */
    private fun changesOf(filter: Filter): List<FilterChange> =
        when (filter) {
            is HeaderFilter, is SeparatorFilter -> {
                emptyList()
            }

            is SelectFilter -> {
                if (filter.values.size >
                    1
                ) {
                    listOf(FilterChange(0, selectState = (filter.default + 1) % filter.values.size))
                } else {
                    emptyList()
                }
            }

            is TextFilter -> {
                listOf(FilterChange(0, textState = filter.default + "madoyomi"))
            }

            is CheckBoxFilter -> {
                listOf(FilterChange(0, checkBoxState = !filter.default))
            }

            is TriStateFilter -> {
                listOf(FilterChange(0, triState = next(filter.default)))
            }

            is SortFilter -> {
                listOf(FilterChange(0, sortState = nextSort(filter)))
            }

            is GroupFilter -> {
                filter.filters.flatMapIndexed { index, child -> changesOf(child).map { FilterChange(0, groupChange = it.at(index)) } }
            }
        }

    private fun FilterChange.at(position: Int) = copy(position = position)

    /** [filter] as [changesOf] leaves it. */
    private fun changed(filter: Filter): Filter =
        when (filter) {
            is HeaderFilter, is SeparatorFilter -> filter
            is SelectFilter -> if (filter.values.size > 1) filter.copy(default = (filter.default + 1) % filter.values.size) else filter
            is TextFilter -> filter.copy(default = filter.default + "madoyomi")
            is CheckBoxFilter -> filter.copy(default = !filter.default)
            is TriStateFilter -> filter.copy(default = next(filter.default))
            is SortFilter -> filter.copy(default = nextSort(filter))
            is GroupFilter -> filter.copy(filters = filter.filters.map(::changed))
        }

    private fun next(state: TriState) = TriState.entries[(state.ordinal + 1) % TriState.entries.size]

    private fun nextSort(filter: SortFilter) =
        SortFilter.SortSelection(((filter.default?.index ?: -1) + 1) % filter.values.size, !(filter.default?.ascending ?: false))
}
