package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import eu.kanade.tachiyomi.animesource.model.FetchType
import graphql.schema.DataFetchingEnvironment
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.ComparableScalarFilter
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
import java.util.concurrent.CompletableFuture

/** Stored anime entries: upstream's `manga`/`mangas` queries for `ANIME` (no per-user tables). */
class AnimeQuery {
    @RequireAuth
    fun anime(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<AnimeType> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, AnimeType?>("AnimeDataLoader", id).thenApply {
            it ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No anime with id $id", mapOf("animeId" to id))
        }

    enum class AnimeOrderBy(
        override val column: Column<*>,
    ) : OrderBy<AnimeType> {
        ID(AnimeTable.id),
        TITLE(AnimeTable.title),
        IN_LIBRARY_AT(AnimeTable.addedAt),
        LAST_FETCHED_AT(AnimeTable.lastFetchedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> AnimeTable.id greater cursor.value.toInt()
                TITLE -> greaterNotUnique(AnimeTable.title, AnimeTable.id, cursor, String::toString)
                IN_LIBRARY_AT -> greaterNotUnique(AnimeTable.addedAt, AnimeTable.id, cursor, String::toLong)
                LAST_FETCHED_AT -> greaterNotUnique(AnimeTable.lastFetchedAt, AnimeTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> AnimeTable.id less cursor.value.toInt()
                TITLE -> lessNotUnique(AnimeTable.title, AnimeTable.id, cursor, String::toString)
                IN_LIBRARY_AT -> lessNotUnique(AnimeTable.addedAt, AnimeTable.id, cursor, String::toLong)
                LAST_FETCHED_AT -> lessNotUnique(AnimeTable.lastFetchedAt, AnimeTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: AnimeType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    TITLE -> type.id.toString() + "-" + type.title
                    IN_LIBRARY_AT -> type.id.toString() + "-" + type.inLibraryAt.toString()
                    LAST_FETCHED_AT -> type.id.toString() + "-" + type.lastFetchedAt.toString()
                }
            return Cursor(value)
        }
    }

    data class AnimeOrder(
        override val by: AnimeOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<AnimeOrderBy>

    data class AnimeCondition(
        val id: Int? = null,
        val sourceId: Long? = null,
        val url: String? = null,
        val title: String? = null,
        val initialized: Boolean? = null,
        val artist: String? = null,
        val author: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: AnimeStatus? = null,
        val inLibrary: Boolean? = null,
        val inLibraryAt: Long? = null,
        val lastFetchedAt: Long? = null,
        val fetchType: FetchType? = null,
        val parentId: Int? = null,
    ) : HasGetOp<Any?> {
        override fun getOp(value: Any?): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, AnimeTable.id)
            opAnd.eq(sourceId, AnimeTable.sourceReference)
            opAnd.eq(url, AnimeTable.url)
            opAnd.eq(title, AnimeTable.title)
            opAnd.eq(initialized, AnimeTable.initialized)
            opAnd.eq(artist, AnimeTable.artist)
            opAnd.eq(author, AnimeTable.author)
            opAnd.eq(description, AnimeTable.description)
            opAnd.andWhereAll(genre) { AnimeTable.genre like "%$it%" }
            opAnd.eq(status?.value, AnimeTable.status)
            opAnd.eq(inLibrary, AnimeTable.inLibrary)
            opAnd.eq(inLibraryAt, AnimeTable.addedAt)
            opAnd.eq(lastFetchedAt, AnimeTable.lastFetchedAt)
            opAnd.eq(fetchType, AnimeTable.fetchType)
            opAnd.andWhere(parentId) { AnimeTable.parentId eq it }
            return opAnd.op
        }
    }

    data class AnimeStatusFilter(
        override val isNull: Boolean? = null,
        override val equalTo: AnimeStatus? = null,
        override val notEqualTo: AnimeStatus? = null,
        override val notEqualToAll: List<AnimeStatus>? = null,
        override val notEqualToAny: List<AnimeStatus>? = null,
        override val distinctFrom: AnimeStatus? = null,
        override val distinctFromAll: List<AnimeStatus>? = null,
        override val distinctFromAny: List<AnimeStatus>? = null,
        override val notDistinctFrom: AnimeStatus? = null,
        override val `in`: List<AnimeStatus>? = null,
        override val notIn: List<AnimeStatus>? = null,
        override val lessThan: AnimeStatus? = null,
        override val lessThanOrEqualTo: AnimeStatus? = null,
        override val greaterThan: AnimeStatus? = null,
        override val greaterThanOrEqualTo: AnimeStatus? = null,
    ) : ComparableScalarFilter<AnimeStatus> {
        fun asIntFilter() =
            IntFilter(
                equalTo = equalTo?.value,
                notEqualTo = notEqualTo?.value,
                notEqualToAll = notEqualToAll?.map { it.value },
                notEqualToAny = notEqualToAny?.map { it.value },
                distinctFrom = distinctFrom?.value,
                distinctFromAll = distinctFromAll?.map { it.value },
                distinctFromAny = distinctFromAny?.map { it.value },
                notDistinctFrom = notDistinctFrom?.value,
                `in` = `in`?.map { it.value },
                notIn = notIn?.map { it.value },
                lessThan = lessThan?.value,
                lessThanOrEqualTo = lessThanOrEqualTo?.value,
                greaterThan = greaterThan?.value,
                greaterThanOrEqualTo = greaterThanOrEqualTo?.value,
            )
    }

    data class AnimeFilter(
        val id: IntFilter? = null,
        val sourceId: LongFilter? = null,
        val url: StringFilter? = null,
        val title: StringFilter? = null,
        val initialized: BooleanFilter? = null,
        val artist: StringFilter? = null,
        val author: StringFilter? = null,
        val description: StringFilter? = null,
        val genre: StringFilter? = null,
        val status: AnimeStatusFilter? = null,
        val inLibrary: BooleanFilter? = null,
        val inLibraryAt: LongFilter? = null,
        val lastFetchedAt: LongFilter? = null,
        override val and: List<AnimeFilter>? = null,
        override val or: List<AnimeFilter>? = null,
        override val not: AnimeFilter? = null,
    ) : Filter<AnimeFilter, Any?> {
        override fun getOpList(value: Any?): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(AnimeTable.id, id),
                andFilterWithCompare(AnimeTable.sourceReference, sourceId),
                andFilterWithCompareString(AnimeTable.url, url),
                andFilterWithCompareString(AnimeTable.title, title),
                andFilterWithCompare(AnimeTable.initialized, initialized),
                andFilterWithCompareString(AnimeTable.artist, artist),
                andFilterWithCompareString(AnimeTable.author, author),
                andFilterWithCompareString(AnimeTable.description, description),
                andFilterWithCompareString(AnimeTable.genre, genre),
                andFilterWithCompare(AnimeTable.status, status?.asIntFilter()),
                andFilterWithCompare(AnimeTable.inLibrary, inLibrary),
                andFilterWithCompare(AnimeTable.addedAt, inLibraryAt),
                andFilterWithCompare(AnimeTable.lastFetchedAt, lastFetchedAt),
            )
    }

    @RequireAuth
    fun animes(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: AnimeCondition? = null,
        filter: AnimeFilter? = null,
        order: List<AnimeOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): AnimeNodeList {
        val queryResults =
            dbTransaction {
                val res = AnimeTable.selectAll().applyOps(null, condition, filter)

                val actualSort = order.orEmpty() + AnimeOrder(AnimeOrderBy.ID, SortOrder.ASC)
                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, AnimeTable.id, PaginationNeeds.of(dataFetchingEnvironment))

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: AnimeOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (AnimeType) -> Cursor = (order?.firstOrNull()?.by ?: AnimeOrderBy.ID)::asCursor
        val results = queryResults.results.map(::AnimeType)

        return AnimeNodeList(
            results,
            if (results.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    AnimeNodeList.AnimeEdge(getAsCursor(results.first()), results.first()),
                    AnimeNodeList.AnimeEdge(getAsCursor(results.last()), results.last()),
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
