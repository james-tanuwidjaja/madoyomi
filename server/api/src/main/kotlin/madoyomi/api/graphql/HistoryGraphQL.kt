package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.library.AnimeHistory
import madoyomi.core.library.MangaHistory
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

/*
 * History: one item per entry, its episode or chapter played or read last, newest first, paged;
 * delete per entry and clear all. Anime history is `ANIME_HISTORY`, manga history upstream's
 * `CHAPTER_USER.last_read_at` (madoyomi.core.library). Deleting keeps the watched and read marks and the positions.
 */

data class AnimeHistoryItemType(
    val anime: AnimeType,
    @GraphQLDescription("The episode played last")
    val episode: EpisodeType,
    @GraphQLDescription("Epoch seconds when it was last played")
    val lastSeenAt: Long,
    @GraphQLDescription("What to resume: the episode itself, or if it is watched the first unwatched one after it, else it again")
    val resume: EpisodeType,
)

data class AnimeHistoryPageType(
    val items: List<AnimeHistoryItemType>,
    val hasMore: Boolean,
)

data class MangaHistoryItemType(
    val manga: MangaType,
    @GraphQLDescription("The chapter read last")
    val chapter: ChapterType,
    @GraphQLDescription("Epoch seconds when it was last read (upstream's lastReadAt)")
    val lastReadAt: Long,
    @GraphQLDescription("What to resume: the chapter itself, or if it is read the first unread one after it, else it again")
    val resume: ChapterType,
)

data class MangaHistoryPageType(
    val items: List<MangaHistoryItemType>,
    val hasMore: Boolean,
)

private fun userOf(env: DataFetchingEnvironment): Int = env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()

class HistoryQuery {
    @RequireAuth
    @GraphQLDescription("Watch history: one item per anime, the one played last first")
    fun animeHistory(
        limit: Int? = null,
        offset: Int? = null,
    ): CompletableFuture<AnimeHistoryPageType> =
        future {
            val page = AnimeHistory.page(limit ?: DEFAULT_LIMIT, offset ?: 0)
            dbTransaction {
                val anime = AnimeType.byIds(page.items.map { it.animeId }.distinct()).associateBy { it.id }
                val episodeIds = page.items.flatMap { listOf(it.episodeId, it.resumeEpisodeId) }.distinct()
                val episodes =
                    EpisodeTable.selectAll().where { EpisodeTable.id inList episodeIds }.associate {
                        it[EpisodeTable.id].value to EpisodeType(it)
                    }
                AnimeHistoryPageType(
                    page.items.mapNotNull { item ->
                        AnimeHistoryItemType(
                            anime = anime[item.animeId] ?: return@mapNotNull null,
                            episode = episodes[item.episodeId] ?: return@mapNotNull null,
                            lastSeenAt = item.lastSeen,
                            resume = episodes[item.resumeEpisodeId] ?: return@mapNotNull null,
                        )
                    },
                    page.hasMore,
                )
            }
        }

    @RequireAuth
    @GraphQLDescription("Reading history: one item per manga, the one read last first")
    fun mangaHistory(
        dataFetchingEnvironment: DataFetchingEnvironment,
        limit: Int? = null,
        offset: Int? = null,
    ): CompletableFuture<MangaHistoryPageType> {
        val userId = userOf(dataFetchingEnvironment)
        return future {
            val page = MangaHistory.page(userId, limit ?: DEFAULT_LIMIT, offset ?: 0)
            dbTransaction {
                val mangaIds = page.items.map { it.mangaId }.distinct()
                val manga =
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id inList mangaIds }
                        .associate { it[MangaTable.id].value to MangaType(it) }
                val chapterIds = page.items.flatMap { listOf(it.chapterId, it.resumeChapterId) }.distinct()
                val chapters =
                    ChapterTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { ChapterTable.id inList chapterIds }
                        .associate { it[ChapterTable.id].value to ChapterType(it) }
                MangaHistoryPageType(
                    page.items.mapNotNull { item ->
                        MangaHistoryItemType(
                            manga = manga[item.mangaId] ?: return@mapNotNull null,
                            chapter = chapters[item.chapterId] ?: return@mapNotNull null,
                            lastReadAt = item.lastReadAt,
                            resume = chapters[item.resumeChapterId] ?: return@mapNotNull null,
                        )
                    },
                    page.hasMore,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

class HistoryMutation {
    data class DeleteAnimeHistoryInput(
        val clientMutationId: String? = null,
        val animeIds: List<Int>,
    )

    data class DeleteMangaHistoryInput(
        val clientMutationId: String? = null,
        val mangaIds: List<Int>,
    )

    data class ClearHistoryInput(
        val clientMutationId: String? = null,
    )

    data class HistoryChangePayload(
        val clientMutationId: String?,
        @GraphQLDescription("How many episodes or chapters lost their history")
        val removed: Int,
    )

    @RequireAuth
    @GraphQLDescription("Removes these anime from the watch history (every episode's); the watched marks and positions stay")
    fun deleteAnimeHistory(input: DeleteAnimeHistoryInput): CompletableFuture<HistoryChangePayload?> =
        future { HistoryChangePayload(input.clientMutationId, AnimeHistory.delete(input.animeIds.distinct())) }

    @RequireAuth
    @GraphQLDescription("Clears the watch history; the watched marks and positions stay")
    fun clearAnimeHistory(input: ClearHistoryInput): CompletableFuture<HistoryChangePayload?> =
        future { HistoryChangePayload(input.clientMutationId, AnimeHistory.clear()) }

    @RequireAuth
    @GraphQLDescription("Removes these manga from the reading history (every chapter's lastReadAt to 0); the read marks and pages stay")
    fun deleteMangaHistory(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteMangaHistoryInput,
    ): CompletableFuture<HistoryChangePayload?> {
        val userId = userOf(dataFetchingEnvironment)
        return future { HistoryChangePayload(input.clientMutationId, MangaHistory.delete(userId, input.mangaIds.distinct())) }
    }

    @RequireAuth
    @GraphQLDescription("Clears the reading history (every lastReadAt to 0); the read marks and pages stay")
    fun clearMangaHistory(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ClearHistoryInput,
    ): CompletableFuture<HistoryChangePayload?> {
        val userId = userOf(dataFetchingEnvironment)
        return future { HistoryChangePayload(input.clientMutationId, MangaHistory.clear(userId)) }
    }
}

/** `AnimeType.unwatchedCount` and `lastSeenAt` for a whole library list in two grouped queries. */
class AnimeStatsDataLoader : KotlinDataLoader<Int, AnimeHistory.Stats> {
    override val dataLoaderName = "AnimeStatsDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, AnimeHistory.Stats> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val stats = AnimeHistory.stats(ids)
                ids.map { stats[it] ?: AnimeHistory.Stats(0, 0L) }
            }
        }
}
