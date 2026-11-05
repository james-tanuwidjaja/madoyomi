package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.schema.DataFetchingEnvironment
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.DoubleFilter
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.IntFilter
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.graphql.queries.filter.OpAnd
import suwayomi.tachidesk.graphql.queries.filter.StringFilter
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompare
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareEntity
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareString
import suwayomi.tachidesk.graphql.queries.filter.applyOps
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Order
import suwayomi.tachidesk.graphql.server.primitives.OrderBy
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.PaginationNeeds
import suwayomi.tachidesk.graphql.server.primitives.QueryResults
import suwayomi.tachidesk.graphql.server.primitives.applyBeforeAfter
import suwayomi.tachidesk.graphql.server.primitives.applySortAndGetPaginationInfo
import suwayomi.tachidesk.graphql.server.primitives.greaterNotUnique
import suwayomi.tachidesk.graphql.server.primitives.lessNotUnique
import suwayomi.tachidesk.server.database.dbTransaction

/** Stored episodes: upstream's `chapters` query for `EPISODE` (no per-user tables). */
class EpisodeQuery {
    enum class EpisodeOrderBy(
        override val column: Column<*>,
    ) : OrderBy<EpisodeType> {
        ID(EpisodeTable.id),
        SOURCE_ORDER(EpisodeTable.sourceOrder),
        NAME(EpisodeTable.name),
        UPLOAD_DATE(EpisodeTable.dateUpload),
        EPISODE_NUMBER(EpisodeTable.episodeNumber),
        FETCHED_AT(EpisodeTable.fetchedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> EpisodeTable.id greater cursor.value.toInt()
                SOURCE_ORDER -> greaterNotUnique(EpisodeTable.sourceOrder, EpisodeTable.id, cursor, String::toInt)
                NAME -> greaterNotUnique(EpisodeTable.name, EpisodeTable.id, cursor, String::toString)
                UPLOAD_DATE -> greaterNotUnique(EpisodeTable.dateUpload, EpisodeTable.id, cursor, String::toLong)
                EPISODE_NUMBER -> greaterNotUnique(EpisodeTable.episodeNumber, EpisodeTable.id, cursor, String::toFloat)
                FETCHED_AT -> greaterNotUnique(EpisodeTable.fetchedAt, EpisodeTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> EpisodeTable.id less cursor.value.toInt()
                SOURCE_ORDER -> lessNotUnique(EpisodeTable.sourceOrder, EpisodeTable.id, cursor, String::toInt)
                NAME -> lessNotUnique(EpisodeTable.name, EpisodeTable.id, cursor, String::toString)
                UPLOAD_DATE -> lessNotUnique(EpisodeTable.dateUpload, EpisodeTable.id, cursor, String::toLong)
                EPISODE_NUMBER -> lessNotUnique(EpisodeTable.episodeNumber, EpisodeTable.id, cursor, String::toFloat)
                FETCHED_AT -> lessNotUnique(EpisodeTable.fetchedAt, EpisodeTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: EpisodeType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    SOURCE_ORDER -> type.id.toString() + "-" + type.sourceOrder
                    NAME -> type.id.toString() + "-" + type.name
                    UPLOAD_DATE -> type.id.toString() + "-" + type.uploadDate
                    EPISODE_NUMBER -> type.id.toString() + "-" + type.episodeNumber
                    FETCHED_AT -> type.id.toString() + "-" + type.fetchedAt
                }
            return Cursor(value)
        }
    }

    data class EpisodeOrder(
        override val by: EpisodeOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<EpisodeOrderBy>

    data class EpisodeCondition(
        val id: Int? = null,
        val url: String? = null,
        val name: String? = null,
        val uploadDate: Long? = null,
        val episodeNumber: Float? = null,
        val scanlator: String? = null,
        val animeId: Int? = null,
        val isWatched: Boolean? = null,
        val isBookmarked: Boolean? = null,
        val lastSecondSeen: Long? = null,
        val sourceOrder: Int? = null,
        val fetchedAt: Long? = null,
        val fillermark: Boolean? = null,
    ) : HasGetOp<Any?> {
        override fun getOp(value: Any?): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, EpisodeTable.id)
            opAnd.eq(url, EpisodeTable.url)
            opAnd.eq(name, EpisodeTable.name)
            opAnd.eq(uploadDate, EpisodeTable.dateUpload)
            opAnd.eq(episodeNumber, EpisodeTable.episodeNumber)
            opAnd.eq(scanlator, EpisodeTable.scanlator)
            opAnd.eq(animeId, EpisodeTable.anime)
            opAnd.eq(isWatched, EpisodeTable.watched)
            opAnd.eq(isBookmarked, EpisodeTable.bookmark)
            opAnd.eq(lastSecondSeen, EpisodeTable.lastSecondSeen)
            opAnd.eq(sourceOrder, EpisodeTable.sourceOrder)
            opAnd.eq(fetchedAt, EpisodeTable.fetchedAt)
            opAnd.eq(fillermark, EpisodeTable.fillermark)
            return opAnd.op
        }
    }

    data class EpisodeFilter(
        val id: IntFilter? = null,
        val url: StringFilter? = null,
        val name: StringFilter? = null,
        val uploadDate: LongFilter? = null,
        val episodeNumber: DoubleFilter? = null,
        val scanlator: StringFilter? = null,
        val animeId: IntFilter? = null,
        val isWatched: BooleanFilter? = null,
        val isBookmarked: BooleanFilter? = null,
        val lastSecondSeen: LongFilter? = null,
        val sourceOrder: IntFilter? = null,
        val fetchedAt: LongFilter? = null,
        val fillermark: BooleanFilter? = null,
        val inLibrary: BooleanFilter? = null,
        override val and: List<EpisodeFilter>? = null,
        override val or: List<EpisodeFilter>? = null,
        override val not: EpisodeFilter? = null,
    ) : Filter<EpisodeFilter, Any?> {
        override fun getOpList(value: Any?): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(EpisodeTable.id, id),
                andFilterWithCompareString(EpisodeTable.url, url),
                andFilterWithCompareString(EpisodeTable.name, name),
                andFilterWithCompare(EpisodeTable.dateUpload, uploadDate),
                andFilterWithCompare(EpisodeTable.episodeNumber, episodeNumber?.toFloatFilter()),
                andFilterWithCompareString(EpisodeTable.scanlator, scanlator),
                andFilterWithCompareEntity(EpisodeTable.anime, animeId),
                andFilterWithCompare(EpisodeTable.watched, isWatched),
                andFilterWithCompare(EpisodeTable.bookmark, isBookmarked),
                andFilterWithCompare(EpisodeTable.lastSecondSeen, lastSecondSeen),
                andFilterWithCompare(EpisodeTable.sourceOrder, sourceOrder),
                andFilterWithCompare(EpisodeTable.fetchedAt, fetchedAt),
                andFilterWithCompare(EpisodeTable.fillermark, fillermark),
            )

        /** The anime's library flag (upstream's `ChapterFilter.getLibraryOp`); needs the join with `ANIME`. */
        fun getLibraryOp() = andFilterWithCompare(AnimeTable.inLibrary, inLibrary)
    }

    @RequireAuth
    fun episodes(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: EpisodeCondition? = null,
        filter: EpisodeFilter? = null,
        order: List<EpisodeOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): EpisodeNodeList {
        val queryResults =
            dbTransaction {
                val res = EpisodeTable.selectAll()

                val libraryOp = filter?.getLibraryOp()
                if (libraryOp != null) {
                    res.adjustColumnSet { innerJoin(AnimeTable) }
                    res.andWhere { libraryOp }
                }

                res.applyOps(null, condition, filter)

                val actualSort = order.orEmpty() + EpisodeOrder(EpisodeOrderBy.ID, SortOrder.ASC)
                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(
                        actualSort,
                        before,
                        last,
                        EpisodeTable.id,
                        PaginationNeeds.of(dataFetchingEnvironment),
                    )

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: EpisodeOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (EpisodeType) -> Cursor = (order?.firstOrNull()?.by ?: EpisodeOrderBy.ID)::asCursor
        val results = queryResults.results.map(::EpisodeType)

        return EpisodeNodeList(
            results,
            if (results.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    EpisodeNodeList.EpisodeEdge(getAsCursor(results.first()), results.first()),
                    EpisodeNodeList.EpisodeEdge(getAsCursor(results.last()), results.last()),
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey != results.lastOrNull()?.id,
                    hasPreviousPage = queryResults.firstKey != results.firstOrNull()?.id,
                    startCursor = results.firstOrNull()?.let(getAsCursor),
                    endCursor = results.lastOrNull()?.let(getAsCursor),
                ),
            totalCount = queryResults.total.toInt(),
        )
    }
}
