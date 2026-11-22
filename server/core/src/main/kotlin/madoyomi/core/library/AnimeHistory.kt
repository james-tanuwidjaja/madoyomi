package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.model.table.AnimeHistoryTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.server.database.dbTransaction
import java.time.Instant

/**
 * Watch history in `ANIME_HISTORY`, one row per episode with the epoch seconds it was last played
 * (`last_seen`), as Aniyomi's `animehistory`. Written from the progress: the player's saves ([stamp] from
 * `EpisodeProgress.save`) and `updateEpisode(s)` given a position, never from a watched mark alone (upstream's rule for
 * `isRead`, which doesn't stamp `last_read_at`). Deleting history keeps the watched marks and positions in `EPISODE`.
 *
 * Manga history is upstream's `CHAPTER_USER.last_read_at` ([MangaHistory]).
 */
object AnimeHistory {
    /** Stamps [episodeIds] as played at [at] (epoch seconds, default now). Inside the caller's transaction, if any. */
    fun stamp(
        episodeIds: Collection<Int>,
        at: Long = Instant.now().epochSecond,
    ) {
        if (episodeIds.isEmpty()) return
        dbTransaction {
            episodeIds.distinct().forEach { id ->
                AnimeHistoryTable.upsert(AnimeHistoryTable.episode) {
                    it[episode] = id
                    it[lastSeen] = at
                }
            }
        }
    }

    fun stamp(
        episodeId: Int,
        at: Long = Instant.now().epochSecond,
    ) = stamp(listOf(episodeId), at)

    /** The epoch seconds [episodeIds] were last played, for those that have history. */
    fun lastSeenOf(episodeIds: Collection<Int>): Map<Int, Long> {
        if (episodeIds.isEmpty()) return emptyMap()
        return dbTransaction {
            AnimeHistoryTable
                .select(AnimeHistoryTable.episode, AnimeHistoryTable.lastSeen)
                .where { (AnimeHistoryTable.episode inList episodeIds) and (AnimeHistoryTable.lastSeen greater 0L) }
                .associate { it[AnimeHistoryTable.episode].value to it[AnimeHistoryTable.lastSeen] }
        }
    }

    /**
     * One history item per anime: its episode played last, when, and what "resume" opens: the episode itself unless
     * it is watched, else the first unwatched one after it in the source's order, else the episode itself again.
     */
    data class Item(
        val animeId: Int,
        val episodeId: Int,
        val lastSeen: Long,
        val resumeEpisodeId: Int,
    )

    data class Page(
        val items: List<Item>,
        val hasMore: Boolean,
    )

    /** The anime with history, the one played last first, [limit] from [offset]. */
    fun page(
        limit: Int,
        offset: Int,
    ): Page {
        val size = limit.coerceIn(1, 500)
        return dbTransaction {
            val latest = AnimeHistoryTable.lastSeen.max()
            val animeIds =
                (AnimeHistoryTable innerJoin EpisodeTable)
                    .select(EpisodeTable.anime, latest)
                    .where { AnimeHistoryTable.lastSeen greater 0L }
                    .groupBy(EpisodeTable.anime)
                    .orderBy(latest to SortOrder.DESC, EpisodeTable.anime to SortOrder.DESC)
                    .limit(size + 1)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map { it[EpisodeTable.anime].value }
            val shown = animeIds.take(size)
            if (shown.isEmpty()) return@dbTransaction Page(emptyList(), false)

            val episodes =
                EpisodeTable
                    .select(EpisodeTable.id, EpisodeTable.anime, EpisodeTable.sourceOrder, EpisodeTable.watched)
                    .where { EpisodeTable.anime inList shown }
                    .groupBy { it[EpisodeTable.anime].value }
            val seen =
                (AnimeHistoryTable innerJoin EpisodeTable)
                    .select(AnimeHistoryTable.episode, AnimeHistoryTable.lastSeen, EpisodeTable.anime, EpisodeTable.sourceOrder)
                    .where { (EpisodeTable.anime inList shown) and (AnimeHistoryTable.lastSeen greater 0L) }
                    .orderBy(AnimeHistoryTable.lastSeen to SortOrder.DESC, EpisodeTable.sourceOrder to SortOrder.DESC)
                    .groupBy { it[EpisodeTable.anime].value }
            val items =
                shown.mapNotNull { animeId ->
                    val top = seen[animeId]?.firstOrNull() ?: return@mapNotNull null
                    val episodeId = top[AnimeHistoryTable.episode].value
                    val list = episodes[animeId].orEmpty().sortedBy { it[EpisodeTable.sourceOrder] }
                    val current = list.firstOrNull { it[EpisodeTable.id].value == episodeId }
                    val resume =
                        if (current == null || !current[EpisodeTable.watched]) {
                            episodeId
                        } else {
                            list
                                .firstOrNull {
                                    it[EpisodeTable.sourceOrder] > current[EpisodeTable.sourceOrder] && !it[EpisodeTable.watched]
                                }?.get(EpisodeTable.id)
                                ?.value ?: episodeId
                        }
                    Item(animeId, episodeId, top[AnimeHistoryTable.lastSeen], resume)
                }
            Page(items, animeIds.size > size)
        }
    }

    /** Removes the history of every episode of [animeIds]; returns the number of rows removed. */
    fun delete(animeIds: Collection<Int>): Int {
        if (animeIds.isEmpty()) return 0
        return dbTransaction {
            AnimeHistoryTable.deleteWhere {
                AnimeHistoryTable.episode inSubQuery EpisodeTable.select(EpisodeTable.id).where { EpisodeTable.anime inList animeIds }
            }
        }
    }

    /** Removes all watch history; returns the number of rows removed. */
    fun clear(): Int = dbTransaction { AnimeHistoryTable.deleteAll() }

    /** The library card numbers of an anime: episodes not watched, and when one was last played (0: never). */
    data class Stats(
        val unwatchedCount: Int,
        val lastSeenAt: Long,
    )

    /**
     * [Stats] of [animeIds] in two grouped queries. A Seasons entry counts its seasons' episodes (one level: a season
     * is an entry pointing at its parent), so its card shows what is left of the whole series.
     */
    fun stats(animeIds: Collection<Int>): Map<Int, Stats> {
        val ids = animeIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dbTransaction {
            val owner = HashMap<Int, MutableList<Int>>()
            ids.forEach { owner.getOrPut(it) { mutableListOf() }.add(it) }
            AnimeTable
                .select(AnimeTable.id, AnimeTable.parentId)
                .where { AnimeTable.parentId inList ids }
                .forEach { row ->
                    val parent = row[AnimeTable.parentId]!!.value
                    owner.getOrPut(row[AnimeTable.id].value) { mutableListOf() }.add(parent)
                }
            val all = owner.keys.toList()

            val unwatched =
                Case()
                    .When(EpisodeTable.watched eq false, intLiteral(1))
                    .Else(intLiteral(0))
                    .sum()
            val counts = HashMap<Int, Int>()
            EpisodeTable
                .select(EpisodeTable.anime, unwatched)
                .where { EpisodeTable.anime inList all }
                .groupBy(EpisodeTable.anime)
                .forEach { row ->
                    val n = row[unwatched] ?: 0
                    owner[row[EpisodeTable.anime].value].orEmpty().forEach { counts.merge(it, n, Int::plus) }
                }

            val latest = AnimeHistoryTable.lastSeen.max()
            val seen = HashMap<Int, Long>()
            (AnimeHistoryTable innerJoin EpisodeTable)
                .select(EpisodeTable.anime, latest)
                .where { EpisodeTable.anime inList all }
                .groupBy(EpisodeTable.anime)
                .forEach { row ->
                    val at = row[latest] ?: 0L
                    owner[row[EpisodeTable.anime].value].orEmpty().forEach { seen.merge(it, at, ::maxOf) }
                }
            ids.associateWith { Stats(counts[it] ?: 0, seen[it] ?: 0L) }
        }
    }

    /** All rows, for tests and the backup: episode id to epoch seconds. */
    fun all(): Map<Int, Long> =
        dbTransaction {
            AnimeHistoryTable.selectAll().associate { it[AnimeHistoryTable.episode].value to it[AnimeHistoryTable.lastSeen] }
        }
}
