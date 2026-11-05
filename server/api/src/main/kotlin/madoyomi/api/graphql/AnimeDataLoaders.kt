package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import madoyomi.api.graphql.AnimeNodeList.Companion.toNodeList
import madoyomi.api.graphql.EpisodeNodeList.Companion.toNodeList
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.server.primitives.inIds
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction

/** The data loaders of Madoyomi's anime types, registered through `MadoyomiSchema.dataLoaders`. */
val animeDataLoaders: List<KotlinDataLoader<*, *>> =
    listOf(
        AnimeDataLoader(),
        EpisodesForAnimeDataLoader(),
        SeasonsForAnimeDataLoader(),
        AnimeSourceDataLoader(),
        AnimeForAnimeSourceDataLoader(),
    )

/** An anime by id; null (through the Java DataLoader, as upstream's MangaDataLoader) for an unknown id. */
class AnimeDataLoader : KotlinDataLoader<Int, AnimeType> {
    override val dataLoaderName = "AnimeDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, AnimeType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val byId =
                    dbTransaction {
                        AnimeTable
                            .selectAll()
                            .where { AnimeTable.id inIds ids }
                            .map(::AnimeType)
                            .associateBy { it.id }
                    }
                ids.map { byId[it] }
            }
        }
}

/** An anime's episodes in the source's order, oldest first (`source_order` ascending, as upstream's chapters). */
class EpisodesForAnimeDataLoader : KotlinDataLoader<Int, EpisodeNodeList> {
    override val dataLoaderName = "EpisodesForAnimeDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, EpisodeNodeList> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val byAnime =
                    dbTransaction {
                        EpisodeTable
                            .selectAll()
                            .where { EpisodeTable.anime inIds ids }
                            .orderBy(EpisodeTable.sourceOrder to SortOrder.ASC)
                            .map(::EpisodeType)
                            .groupBy { it.animeId }
                    }
                ids.map { (byAnime[it] ?: emptyList()).toNodeList() }
            }
        }
}

/** An anime's seasons in the source's order (`season_source_order`). */
class SeasonsForAnimeDataLoader : KotlinDataLoader<Int, AnimeNodeList> {
    override val dataLoaderName = "SeasonsForAnimeDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, AnimeNodeList> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val byParent =
                    dbTransaction {
                        AnimeTable
                            .selectAll()
                            .where { AnimeTable.parentId inList ids }
                            .orderBy(AnimeTable.seasonSourceOrder to SortOrder.ASC)
                            .map(::AnimeType)
                            .groupBy { it.parentId }
                    }
                ids.map { (byParent[it] ?: emptyList()).toNodeList() }
            }
        }
}

/** An anime source by id (`ANIME_SOURCE` with its `EXTENSION`), null if there is no such row. */
class AnimeSourceDataLoader : KotlinDataLoader<Long, AnimeSourceType> {
    override val dataLoaderName = "AnimeSourceDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, AnimeSourceType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val byId =
                    dbTransaction {
                        AnimeSourceTable
                            .innerJoin(ExtensionTable)
                            .selectAll()
                            .where { AnimeSourceTable.id inIds ids }
                            .map(::AnimeSourceType)
                            .associateBy { it.id }
                    }
                ids.map { byId[it] }
            }
        }
}

/** The stored entries of an anime source. */
class AnimeForAnimeSourceDataLoader : KotlinDataLoader<Long, AnimeNodeList> {
    override val dataLoaderName = "AnimeForAnimeSourceDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, AnimeNodeList> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val bySource =
                    dbTransaction {
                        AnimeTable
                            .selectAll()
                            .where { AnimeTable.sourceReference inIds ids }
                            .orderBy(AnimeTable.id to SortOrder.ASC)
                            .map(::AnimeType)
                            .groupBy { it.sourceId }
                    }
                ids.map { (bySource[it] ?: emptyList()).toNodeList() }
            }
        }
}
