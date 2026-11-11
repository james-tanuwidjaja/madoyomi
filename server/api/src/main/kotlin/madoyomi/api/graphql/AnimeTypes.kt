package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import graphql.schema.DataFetchingEnvironment
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.server.database.dbTransaction
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** `SAnime.status` (the same numbers as upstream's `MangaStatus`, plus Aniyomi's `UPCOMING`). */
enum class AnimeStatus(
    val value: Int,
) {
    UNKNOWN(0),
    ONGOING(1),
    COMPLETED(2),
    LICENSED(3),
    PUBLISHING_FINISHED(4),
    CANCELLED(5),
    ON_HIATUS(6),
    UPCOMING(7),
    ;

    companion object {
        fun valueOf(value: Int): AnimeStatus = entries.find { it.value == value } ?: UNKNOWN
    }
}

/** An anime entry (`ANIME`), the counterpart of upstream's `MangaType`. A season is an entry with a [parentId]. */
class AnimeType(
    val id: Int,
    val sourceId: Long,
    val url: String,
    val title: String,
    @GraphQLDescription("The proxied thumbnail (/api/v1/anime/<id>/thumbnail, with the source's headers), null if the source gave none")
    val thumbnailUrl: String?,
    @GraphQLDescription("The source's background image URL, as given (not proxied)")
    val backgroundUrl: String?,
    val initialized: Boolean,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genre: List<String>,
    val status: AnimeStatus,
    val updateStrategy: AnimeUpdateStrategy,
    @GraphQLDescription("Episodes: the entry has episodes; Seasons: it has seasons (other entries), see seasons")
    val fetchType: FetchType,
    val seasonNumber: Double,
    val inLibrary: Boolean,
    @GraphQLDescription("Epoch seconds when it was added to the library")
    val inLibraryAt: Long,
    @GraphQLDescription("Epoch seconds of the last details fetch")
    val lastFetchedAt: Long,
    @GraphQLDescription("For a season: the entry whose season list it came from")
    val parentId: Int?,
    @GraphQLDescription("For a season: its position in the parent's season list (from 1)")
    val seasonSourceOrder: Int,
) : Node {
    constructor(row: ResultRow) : this(
        id = row[AnimeTable.id].value,
        sourceId = row[AnimeTable.sourceReference],
        url = row[AnimeTable.url],
        title = row[AnimeTable.title],
        thumbnailUrl = row[AnimeTable.thumbnailUrl]?.let { proxyThumbnailUrl(row[AnimeTable.id].value) },
        backgroundUrl = row[AnimeTable.backgroundUrl],
        initialized = row[AnimeTable.initialized],
        artist = row[AnimeTable.artist],
        author = row[AnimeTable.author],
        description = row[AnimeTable.description],
        genre = row[AnimeTable.genre].toGenreList(),
        status = AnimeStatus.valueOf(row[AnimeTable.status]),
        updateStrategy = row[AnimeTable.updateStrategy],
        fetchType = row[AnimeTable.fetchType],
        seasonNumber = row[AnimeTable.seasonNumber],
        inLibrary = row[AnimeTable.inLibrary],
        inLibraryAt = row[AnimeTable.addedAt],
        lastFetchedAt = row[AnimeTable.lastFetchedAt],
        parentId = row[AnimeTable.parentId]?.value,
        seasonSourceOrder = row[AnimeTable.seasonSourceOrder],
    )

    fun source(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<AnimeSourceType?> =
        dataFetchingEnvironment.getValueFromDataLoader("AnimeSourceDataLoader", sourceId)

    @GraphQLDescription("The episodes in the source's order, oldest first (empty for a Seasons entry)")
    fun episodes(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<EpisodeNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader("EpisodesForAnimeDataLoader", id)

    @GraphQLDescription("The seasons in the source's order (for a Seasons entry)")
    fun seasons(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<AnimeNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader("SeasonsForAnimeDataLoader", id)

    fun parent(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<AnimeType?> =
        parentId?.let { dataFetchingEnvironment.getValueFromDataLoader("AnimeDataLoader", it) }
            ?: CompletableFuture.completedFuture(null)

    @GraphQLDescription("Seconds since the last details fetch, null if never fetched")
    fun age(): Long? = lastFetchedAt.takeIf { it > 0 }?.let { Instant.now().epochSecond - it }

    companion object {
        fun proxyThumbnailUrl(animeId: Int): String = "/api/v1/anime/$animeId/thumbnail"

        // upstream's toGenreList (internal to core)
        private fun String?.toGenreList(): List<String> =
            this
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        /** The entries [ids], in that order (missing ids are left out). */
        fun byIds(ids: List<Int>): List<AnimeType> {
            if (ids.isEmpty()) return emptyList()
            val byId =
                dbTransaction {
                    AnimeTable.selectAll().where { AnimeTable.id inList ids }.associate { it[AnimeTable.id].value to AnimeType(it) }
                }
            return ids.mapNotNull(byId::get)
        }
    }
}

/** An episode (`EPISODE`), the counterpart of upstream's `ChapterType`. */
class EpisodeType(
    val id: Int,
    val url: String,
    val name: String,
    @GraphQLDescription("Epoch milliseconds")
    val uploadDate: Long,
    val episodeNumber: Float,
    val scanlator: String?,
    val animeId: Int,
    @GraphQLDescription("Position in the source's list, oldest = 1")
    val sourceOrder: Int,
    @GraphQLDescription("Epoch seconds when it was first fetched")
    val fetchedAt: Long,
    val isWatched: Boolean,
    val isBookmarked: Boolean,
    @GraphQLDescription("Playback position in milliseconds, as the Aniyomi app stores it despite the name")
    val lastSecondSeen: Long,
    @GraphQLDescription("Duration in milliseconds (as Aniyomi's, despite the name), 0 if unknown")
    val totalSeconds: Long,
    val fillermark: Boolean,
    val summary: String?,
    val previewUrl: String?,
) : Node {
    constructor(row: ResultRow) : this(
        id = row[EpisodeTable.id].value,
        url = row[EpisodeTable.url],
        name = row[EpisodeTable.name],
        uploadDate = row[EpisodeTable.dateUpload],
        episodeNumber = row[EpisodeTable.episodeNumber],
        scanlator = row[EpisodeTable.scanlator],
        animeId = row[EpisodeTable.anime].value,
        sourceOrder = row[EpisodeTable.sourceOrder],
        fetchedAt = row[EpisodeTable.fetchedAt],
        isWatched = row[EpisodeTable.watched],
        isBookmarked = row[EpisodeTable.bookmark],
        lastSecondSeen = row[EpisodeTable.lastSecondSeen],
        totalSeconds = row[EpisodeTable.totalSeconds],
        fillermark = row[EpisodeTable.fillermark],
        summary = row[EpisodeTable.summary],
        previewUrl = row[EpisodeTable.previewUrl],
    )

    fun anime(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<AnimeType> =
        dataFetchingEnvironment.getValueFromDataLoader("AnimeDataLoader", animeId)
}

data class AnimeNodeList(
    override val nodes: List<AnimeType>,
    override val edges: List<AnimeEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class AnimeEdge(
        override val cursor: Cursor,
        override val node: AnimeType,
    ) : Edge()

    companion object {
        /** All of [this] as one page (upstream's `MangaNodeList.toNodeList`). */
        fun List<AnimeType>.toNodeList(): AnimeNodeList =
            AnimeNodeList(
                nodes = this,
                edges =
                    if (isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            AnimeEdge(Cursor("0"), first()),
                            AnimeEdge(Cursor(lastIndex.toString()), last()),
                        )
                    },
                pageInfo = onePage(lastIndex),
                totalCount = size,
            )
    }
}

data class EpisodeNodeList(
    override val nodes: List<EpisodeType>,
    override val edges: List<EpisodeEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class EpisodeEdge(
        override val cursor: Cursor,
        override val node: EpisodeType,
    ) : Edge()

    companion object {
        /** All of [this] as one page (upstream's `ChapterNodeList.toNodeList`). */
        fun List<EpisodeType>.toNodeList(): EpisodeNodeList =
            EpisodeNodeList(
                nodes = this,
                edges =
                    if (isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            EpisodeEdge(Cursor("0"), first()),
                            EpisodeEdge(Cursor(lastIndex.toString()), last()),
                        )
                    },
                pageInfo = onePage(lastIndex),
                totalCount = size,
            )
    }
}

internal fun onePage(lastIndex: Int) =
    PageInfo(
        hasNextPage = false,
        hasPreviousPage = false,
        startCursor = Cursor(0.toString()),
        endCursor = Cursor(lastIndex.toString()),
    )
