package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import madoyomi.core.anime.AnimeBrowse
import madoyomi.core.anime.AnimeDetails
import madoyomi.core.anime.AnimeEntries
import madoyomi.core.anime.AnimeSourcePreferences
import madoyomi.core.anime.AnimeVideos
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.graphql.MadoyomiGraphQLErrors
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.directives.RequirePermissions
import suwayomi.tachidesk.graphql.mutations.SourceMutation.SourcePreferenceChange
import suwayomi.tachidesk.graphql.types.FilterChange
import suwayomi.tachidesk.graphql.types.Preference
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.UserPermission
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

/**
 * The anime counterparts of upstream's `fetchSourceManga`, `fetchMangaAndChapters`, `fetchChapterPages` and
 * `updateSourcePreference`, over the core services (`madoyomi.core.anime`). Permissions as upstream's.
 */
class AnimeMutation {
    enum class FetchSourceAnimeType {
        SEARCH,
        POPULAR,
        LATEST,
    }

    data class FetchSourceAnimeInput(
        val clientMutationId: String? = null,
        val source: Long,
        val type: FetchSourceAnimeType,
        @GraphQLDescription("From 1")
        val page: Int,
        val query: String? = null,
        @GraphQLDescription("Changes to the source's default filters (AnimeSourceType.filters), SEARCH only")
        val filters: List<FilterChange>? = null,
    )

    data class FetchSourceAnimePayload(
        val clientMutationId: String?,
        @GraphQLDescription("The page's entries in page order, stored with stable ids")
        val animes: List<AnimeType>,
        val hasNextPage: Boolean,
    )

    @RequireAuth
    @GraphQLDescription("Browses an anime source (popular, latest, search) and stores the page's entries")
    fun fetchSourceAnime(input: FetchSourceAnimeInput): CompletableFuture<FetchSourceAnimePayload?> =
        future {
            val page =
                AnimeBrowse.fetch(
                    sourceId = input.source,
                    type = AnimeBrowse.Type.valueOf(input.type.name),
                    page = input.page,
                    query = input.query,
                    filters = input.filters,
                )
            FetchSourceAnimePayload(input.clientMutationId, AnimeType.byIds(page.animeIds), page.hasNextPage)
        }

    data class FetchAnimeAndEpisodesInput(
        val clientMutationId: String? = null,
        val id: Int,
        val fetchAnime: Boolean,
        @GraphQLDescription("The episodes, or the seasons of an entry whose fetchType is Seasons")
        val fetchEpisodes: Boolean,
    )

    data class FetchAnimeAndEpisodesPayload(
        val clientMutationId: String?,
        val anime: AnimeType,
        @GraphQLDescription("Oldest first (sourceOrder ascending)")
        val episodes: List<EpisodeType>,
        val seasons: List<AnimeType>,
    )

    @RequireAuth
    @GraphQLDescription(
        "Refreshes an anime's details and episodes (or seasons) from its source. A source failure still returns " +
            "the stored data, with the error in errors[]",
    )
    fun fetchAnimeAndEpisodes(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchAnimeAndEpisodesInput,
    ): CompletableFuture<DataFetcherResult<FetchAnimeAndEpisodesPayload?>> {
        val path = dataFetchingEnvironment.executionStepInfo.path
        return future {
            AnimeEntries.animeRow(input.id) // NOT_FOUND for an unknown id, without data
            val error =
                try {
                    AnimeDetails.fetch(input.id, fetchDetails = input.fetchAnime, fetchChildren = input.fetchEpisodes)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }

            val payload =
                dbTransaction {
                    FetchAnimeAndEpisodesPayload(
                        clientMutationId = input.clientMutationId,
                        anime = AnimeType(AnimeEntries.animeRow(input.id)),
                        episodes =
                            EpisodeTable
                                .selectAll()
                                .where { EpisodeTable.anime eq input.id }
                                .orderBy(EpisodeTable.sourceOrder to SortOrder.ASC)
                                .map(::EpisodeType),
                        seasons =
                            AnimeTable
                                .selectAll()
                                .where { AnimeTable.parentId eq input.id }
                                .orderBy(AnimeTable.seasonSourceOrder to SortOrder.ASC)
                                .map(::AnimeType),
                    )
                }
            @Suppress("UNCHECKED_CAST")
            DataFetcherResult
                .newResult<FetchAnimeAndEpisodesPayload>()
                .data(payload)
                .also { result -> error?.let { result.error(MadoyomiGraphQLErrors.errorOf(it, path)) } }
                .build() as DataFetcherResult<FetchAnimeAndEpisodesPayload?>
        }
    }

    data class FetchEpisodeVideosInput(
        val clientMutationId: String? = null,
        val episodeId: Int,
    )

    data class FetchEpisodeVideosPayload(
        val clientMutationId: String?,
        val episode: EpisodeType,
        @GraphQLDescription("In the source's order; lazy hosters are IDLE until fetchHosterVideos, failed ones ERROR")
        val hosters: List<EpisodeHosterType>,
    )

    @RequireAuth
    @GraphQLDescription("The hosters and videos of an episode (never stored: video URLs expire)")
    fun fetchEpisodeVideos(input: FetchEpisodeVideosInput): CompletableFuture<FetchEpisodeVideosPayload?> =
        future {
            val hosters = AnimeVideos.fetchEpisodeVideos(input.episodeId)
            FetchEpisodeVideosPayload(
                input.clientMutationId,
                EpisodeType(AnimeEntries.episodeRow(input.episodeId)),
                hosters.map(::EpisodeHosterType),
            )
        }

    data class FetchHosterVideosInput(
        val clientMutationId: String? = null,
        val episodeId: Int,
        @GraphQLDescription("EpisodeHosterType.index")
        val hosterIndex: Int,
    )

    data class FetchHosterVideosPayload(
        val clientMutationId: String?,
        val hoster: EpisodeHosterType,
    )

    @RequireAuth
    @GraphQLDescription("Loads one hoster of the episode's last fetchEpisodeVideos (a lazy one, or a retry of a failed one)")
    fun fetchHosterVideos(input: FetchHosterVideosInput): CompletableFuture<FetchHosterVideosPayload?> =
        future {
            FetchHosterVideosPayload(
                input.clientMutationId,
                EpisodeHosterType(AnimeVideos.fetchHosterVideos(input.episodeId, input.hosterIndex)),
            )
        }

    data class ResolveEpisodeVideoInput(
        val clientMutationId: String? = null,
        val episodeId: Int,
        val hosterIndex: Int,
        @GraphQLDescription("EpisodeVideoType.index within the hoster")
        val videoIndex: Int,
    )

    data class ResolveEpisodeVideoPayload(
        val clientMutationId: String?,
        val video: EpisodeVideoType,
    )

    @RequireAuth
    @GraphQLDescription("The video ready to play: resolved by the source if it isn't initialized (from the last fetchEpisodeVideos)")
    fun resolveEpisodeVideo(input: ResolveEpisodeVideoInput): CompletableFuture<ResolveEpisodeVideoPayload?> =
        future {
            ResolveEpisodeVideoPayload(
                input.clientMutationId,
                EpisodeVideoType(AnimeVideos.resolveVideo(input.episodeId, input.hosterIndex, input.videoIndex)),
            )
        }

    data class UpdateAnimeSourcePreferenceInput(
        val clientMutationId: String? = null,
        val source: Long,
        val change: SourcePreferenceChange,
    )

    data class UpdateAnimeSourcePreferencePayload(
        val clientMutationId: String?,
        val preferences: List<Preference>,
        @GraphQLDescription("Null only for a source without an ANIME_SOURCE row")
        val source: AnimeSourceType?,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.MANAGE_SOURCE_PREFERENCES)
    @GraphQLDescription("Changes one preference of an anime source (the extension's change listener may refuse it)")
    fun updateAnimeSourcePreference(input: UpdateAnimeSourcePreferenceInput): CompletableFuture<UpdateAnimeSourcePreferencePayload?> =
        future {
            val preferences = AnimeSourcePreferences.update(input.source, input.change)
            UpdateAnimeSourcePreferencePayload(input.clientMutationId, preferences, AnimeSourceType.find(input.source))
        }
}
