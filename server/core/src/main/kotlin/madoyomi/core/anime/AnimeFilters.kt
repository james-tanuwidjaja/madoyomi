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

/**
 * An anime source's filters in the shape of upstream's GraphQL `Filter` union and `FilterChange` input (the types
 * are upstream's, `graphql/types/SourceType.kt`), so both kinds of source share one shape and one renderer.
 *
 * A change addresses a filter by its index in the list (`position`) and, inside a group, by the child's index in a
 * nested `groupChange`, as upstream does. Unlike upstream, a change that doesn't fit (no such position, a state of the
 * wrong kind, a select or sort index out of range) is `BAD_REQUEST` with the position path in `details.path`
 * (e.g. `"3.1"`), and a change to a header or separator is ignored.
 */
object AnimeFilters {
    /** [source]'s filters as they are now (a fresh list from the source). */
    fun filtersOf(source: AnimeSource): List<Filter> = freshList(source).map(::filterOf)

    /** A fresh filter list of [source] with [changes] applied, for a search. */
    fun apply(
        source: AnimeSource,
        changes: List<FilterChange>?,
    ): AnimeFilterList {
        val list = freshList(source)
        if (!changes.isNullOrEmpty()) {
            AnimeSourceCalls.callBlocking(source, "filters") { applyChanges(list, changes, "") }
        }
        return list
    }

    // filters are mutable objects: every use starts from a new list
    private fun freshList(source: AnimeSource): AnimeFilterList =
        AnimeSourceCalls.callBlocking(source, "filters") { source.getFilterList() }

    fun filterOf(filter: AnimeFilter<*>): Filter =
        when (filter) {
            is AnimeFilter.Header -> {
                HeaderFilter(filter.name)
            }

            is AnimeFilter.Separator -> {
                SeparatorFilter(filter.name)
            }

            is AnimeFilter.Select<*> -> {
                SelectFilter(filter.name, filter.values.map { it.toString() }, filter.state)
            }

            is AnimeFilter.Text -> {
                TextFilter(filter.name, filter.state)
            }

            is AnimeFilter.CheckBox -> {
                CheckBoxFilter(filter.name, filter.state)
            }

            is AnimeFilter.TriState -> {
                TriStateFilter(filter.name, triStateOf(filter.state))
            }

            is AnimeFilter.Group<*> -> {
                GroupFilter(filter.name, filter.state.map(::childOf))
            }

            is AnimeFilter.Sort -> {
                SortFilter(
                    filter.name,
                    filter.values.asList(),
                    filter.state?.let { SortFilter.SortSelection(it.index, it.ascending) },
                )
            }
        }

    // a group's children are filters in every known extension; anything else is shown as a read-only header
    private fun childOf(child: Any?): Filter = (child as? AnimeFilter<*>)?.let(::filterOf) ?: HeaderFilter(child.toString())

    private fun triStateOf(state: Int): TriState =
        when (state) {
            AnimeFilter.TriState.STATE_INCLUDE -> TriState.INCLUDE
            AnimeFilter.TriState.STATE_EXCLUDE -> TriState.EXCLUDE
            else -> TriState.IGNORE
        }

    private fun applyChanges(
        filters: List<*>,
        changes: List<FilterChange>,
        parent: String,
    ) {
        changes.forEach { change ->
            val path = if (parent.isEmpty()) "${change.position}" else "$parent.${change.position}"
            val filter =
                filters.getOrNull(change.position) as? AnimeFilter<*>
                    ?: throw badChange(path, "there is no filter at position ${change.position} (${filters.size} filters)")
            applyChange(filter, change, path)
        }
    }

    private fun applyChange(
        filter: AnimeFilter<*>,
        change: FilterChange,
        path: String,
    ) {
        when (filter) {
            is AnimeFilter.Header, is AnimeFilter.Separator -> {
                // no state to change
            }

            is AnimeFilter.Select<*> -> {
                val index = change.selectState ?: throw expected(path, "selectState", filter)
                if (index !in filter.values.indices) throw badChange(path, "select index $index is out of range")
                filter.state = index
            }

            is AnimeFilter.Text -> {
                filter.state = change.textState ?: throw expected(path, "textState", filter)
            }

            is AnimeFilter.CheckBox -> {
                filter.state = change.checkBoxState ?: throw expected(path, "checkBoxState", filter)
            }

            is AnimeFilter.TriState -> {
                filter.state = (change.triState ?: throw expected(path, "triState", filter)).ordinal
            }

            is AnimeFilter.Group<*> -> {
                val child = change.groupChange ?: throw expected(path, "groupChange", filter)
                applyChanges(filter.state, listOf(child), path)
            }

            is AnimeFilter.Sort -> {
                val sort = change.sortState ?: throw expected(path, "sortState", filter)
                if (sort.index !in filter.values.indices) throw badChange(path, "sort index ${sort.index} is out of range")
                filter.state = AnimeFilter.Sort.Selection(sort.index, sort.ascending)
            }
        }
    }

    private fun expected(
        path: String,
        field: String,
        filter: AnimeFilter<*>,
    ) = badChange(path, "the filter \"${filter.name}\" (${kindOf(filter)}) needs $field")

    private fun kindOf(filter: AnimeFilter<*>): String =
        when (filter) {
            is AnimeFilter.Header -> "header"
            is AnimeFilter.Separator -> "separator"
            is AnimeFilter.Select<*> -> "select"
            is AnimeFilter.Text -> "text"
            is AnimeFilter.CheckBox -> "checkbox"
            is AnimeFilter.TriState -> "tri-state"
            is AnimeFilter.Group<*> -> "group"
            is AnimeFilter.Sort -> "sort"
        }

    private fun badChange(
        path: String,
        message: String,
    ) = MadoyomiException(ErrorCode.BAD_REQUEST, "Filter change at $path: $message", mapOf("path" to path))
}
