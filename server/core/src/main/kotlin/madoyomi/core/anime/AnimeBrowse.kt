package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.graphql.types.FilterChange

/**
 * Browsing an anime source (popular, latest, search with filters), the counterpart of upstream's `fetchSourceManga`.
 * Every entry of a page is upserted into `ANIME` by `(source, url)` ([AnimeEntries.upsert]), so it keeps its id
 * across pages, calls and restarts.
 */
object AnimeBrowse {
    enum class Type { POPULAR, LATEST, SEARCH }

    /** The ids of a page's entries in page order (each once), and whether the source has a next page. */
    data class Page(
        val animeIds: List<Int>,
        val hasNextPage: Boolean,
    )

    /**
     * Page [page] (from 1) of [type] for the source [sourceId]. [query] and [filters] only apply to `SEARCH`.
     * Throws `BAD_REQUEST` for a page below 1 or `LATEST` on a source without it, the source's access errors
     * ([AnimeSourceAccess]), and `EXTENSION_INCOMPATIBLE` for an API gap; the source's own failures pass through.
     */
    suspend fun fetch(
        sourceId: Long,
        type: Type,
        page: Int,
        query: String? = null,
        filters: List<FilterChange>? = null,
    ): Page {
        if (page < 1) throw MadoyomiException(ErrorCode.BAD_REQUEST, "Pages start at 1, not $page", mapOf("page" to page))
        val source = AnimeSourceAccess.require(sourceId)
        val result =
            when (type) {
                Type.POPULAR -> {
                    AnimeSourceCalls.call(source, "popular") { source.getPopularAnime(page) }
                }

                Type.LATEST -> {
                    if (!AnimeSourceCalls.callBlocking(source, "latest") { source.supportsLatest }) {
                        throw MadoyomiException(
                            ErrorCode.BAD_REQUEST,
                            "Anime source $sourceId has no latest updates",
                            mapOf("sourceId" to sourceId),
                        )
                    }
                    AnimeSourceCalls.call(source, "latest") { source.getLatestUpdates(page) }
                }

                Type.SEARCH -> {
                    val filterList = AnimeFilters.apply(source, filters)
                    AnimeSourceCalls.call(source, "search") { source.getSearchAnime(page, query.orEmpty(), filterList) }
                }
            }
        return Page(AnimeEntries.upsert(sourceId, result.animes), result.hasNextPage)
    }
}
