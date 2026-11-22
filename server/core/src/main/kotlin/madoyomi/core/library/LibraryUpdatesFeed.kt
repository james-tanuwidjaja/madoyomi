package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * What the Updates view lists: the episodes and chapters of library entries that were fetched after
 * the entry joined the library, newest fetch first, as upstream's `Chapter.getRecentChapters` and Mihon's Updates
 * screen. One rule more: an entry's first fetch (the items whose `fetched_at` is the entry's earliest) is left out, so
 * an entry added to the library before it was ever opened doesn't fill the list with its whole back catalogue on its
 * first check. A re-listed item keeps its first fetch date (upstream's rule for chapters, [AnimeDetails] for episodes).
 *
 * An episode of a season counts for the season's library entry (its parent) when the season itself isn't in the
 * library, as the parent's card counts its seasons' episodes.
 */
object LibraryUpdatesFeed {
    /** An episode or chapter in the list: its id, its entry's, and when it was fetched (epoch seconds). */
    data class Item(
        val itemId: Int,
        val entryId: Int,
        val fetchedAt: Long,
    )

    data class Page(
        val items: List<Item>,
        val hasMore: Boolean,
    )

    fun anime(
        limit: Int,
        offset: Int,
    ): Page {
        val size = limit.coerceIn(1, 500)
        return dbTransaction {
            val firstFetch = EpisodeTable.fetchedAt.min().alias("first_fetch")
            val firsts = EpisodeTable.select(EpisodeTable.anime, firstFetch).groupBy(EpisodeTable.anime).alias("firsts")
            val parent = AnimeTable.alias("parent")
            val rows =
                EpisodeTable
                    .join(AnimeTable, JoinType.INNER, EpisodeTable.anime, AnimeTable.id)
                    .join(parent, JoinType.LEFT, AnimeTable.parentId, parent[AnimeTable.id])
                    .join(firsts, JoinType.INNER, EpisodeTable.anime, firsts[EpisodeTable.anime])
                    .select(EpisodeTable.id, EpisodeTable.anime, EpisodeTable.fetchedAt, AnimeTable.inLibrary, AnimeTable.parentId)
                    .where {
                        (EpisodeTable.fetchedAt greater firsts[firstFetch]) and
                            (
                                ((AnimeTable.inLibrary eq true) and (EpisodeTable.fetchedAt greater AnimeTable.addedAt)) or
                                    ((parent[AnimeTable.inLibrary] eq true) and (EpisodeTable.fetchedAt greater parent[AnimeTable.addedAt]))
                            )
                    }.orderBy(EpisodeTable.fetchedAt to SortOrder.DESC, EpisodeTable.sourceOrder to SortOrder.DESC)
                    .limit(size + 1)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map { Item(it[EpisodeTable.id].value, it[EpisodeTable.anime].value, it[EpisodeTable.fetchedAt]) }
            Page(rows.take(size), rows.size > size)
        }
    }

    fun manga(
        userId: Int,
        limit: Int,
        offset: Int,
    ): Page {
        val size = limit.coerceIn(1, 500)
        return dbTransaction {
            val firstFetch = ChapterTable.fetchedAt.min().alias("first_fetch")
            val firsts = ChapterTable.select(ChapterTable.manga, firstFetch).groupBy(ChapterTable.manga).alias("firsts")
            val rows =
                ChapterTable
                    .join(
                        MangaUserTable,
                        JoinType.INNER,
                        ChapterTable.manga,
                        MangaUserTable.manga,
                        additionalConstraint = { MangaUserTable.user eq userId },
                    ).join(firsts, JoinType.INNER, ChapterTable.manga, firsts[ChapterTable.manga])
                    .select(ChapterTable.id, ChapterTable.manga, ChapterTable.fetchedAt)
                    .where {
                        (MangaUserTable.inLibrary eq true) and
                            (ChapterTable.fetchedAt greater MangaUserTable.inLibraryAt) and
                            (ChapterTable.fetchedAt greater firsts[firstFetch])
                    }.orderBy(ChapterTable.fetchedAt to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                    .limit(size + 1)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map { Item(it[ChapterTable.id].value, it[ChapterTable.manga].value, it[ChapterTable.fetchedAt]) }
            Page(rows.take(size), rows.size > size)
        }
    }
}

/**
 * The "new" marks (M0073): a library update check that finds new items in an entry adds their count to its mark; the
 * mark is cleared when the entry is opened (its details page, or an item from the Updates view), or all at once.
 * Only library entries are listed.
 */
object LibraryUpdateMarks {
    data class Mark(
        val entryId: Int,
        val newCount: Int,
        val markedAt: Long,
    )

    fun addAnime(
        animeId: Int,
        count: Int,
        at: Long,
    ) {
        if (count <= 0) return
        dbTransaction {
            val row = AnimeUpdateMarkTable.selectAll().where { AnimeUpdateMarkTable.anime eq animeId }.singleOrNull()
            if (row == null) {
                AnimeUpdateMarkTable.insert {
                    it[anime] = animeId
                    it[newCount] = count
                    it[markedAt] = at
                }
            } else {
                AnimeUpdateMarkTable.update({ AnimeUpdateMarkTable.id eq row[AnimeUpdateMarkTable.id] }) {
                    it[newCount] = row[AnimeUpdateMarkTable.newCount] + count
                    it[markedAt] = at
                }
            }
        }
    }

    fun addManga(
        userId: Int,
        mangaId: Int,
        count: Int,
        at: Long,
    ) {
        if (count <= 0) return
        dbTransaction {
            val row =
                MangaUpdateMarkTable
                    .selectAll()
                    .where { (MangaUpdateMarkTable.manga eq mangaId) and (MangaUpdateMarkTable.user eq userId) }
                    .singleOrNull()
            if (row == null) {
                MangaUpdateMarkTable.insert {
                    it[manga] = mangaId
                    it[user] = userId
                    it[newCount] = count
                    it[markedAt] = at
                }
            } else {
                MangaUpdateMarkTable.update({ MangaUpdateMarkTable.id eq row[MangaUpdateMarkTable.id] }) {
                    it[newCount] = row[MangaUpdateMarkTable.newCount] + count
                    it[markedAt] = at
                }
            }
        }
    }

    /** The marks of anime library entries, the latest first. */
    fun anime(): List<Mark> =
        dbTransaction {
            AnimeUpdateMarkTable
                .join(AnimeTable, JoinType.INNER, AnimeUpdateMarkTable.anime, AnimeTable.id)
                .select(AnimeUpdateMarkTable.anime, AnimeUpdateMarkTable.newCount, AnimeUpdateMarkTable.markedAt)
                .where { AnimeTable.inLibrary eq true }
                .orderBy(AnimeUpdateMarkTable.markedAt to SortOrder.DESC, AnimeUpdateMarkTable.anime to SortOrder.ASC)
                .map { Mark(it[AnimeUpdateMarkTable.anime].value, it[AnimeUpdateMarkTable.newCount], it[AnimeUpdateMarkTable.markedAt]) }
        }

    /** The marks of [userId]'s manga library entries, the latest first. */
    fun manga(userId: Int): List<Mark> =
        dbTransaction {
            MangaUpdateMarkTable
                .join(
                    MangaUserTable,
                    JoinType.INNER,
                    MangaUpdateMarkTable.manga,
                    MangaUserTable.manga,
                    additionalConstraint = { MangaUserTable.user eq userId },
                ).select(MangaUpdateMarkTable.manga, MangaUpdateMarkTable.newCount, MangaUpdateMarkTable.markedAt)
                .where { (MangaUpdateMarkTable.user eq userId) and (MangaUserTable.inLibrary eq true) }
                .orderBy(MangaUpdateMarkTable.markedAt to SortOrder.DESC, MangaUpdateMarkTable.manga to SortOrder.ASC)
                .map { Mark(it[MangaUpdateMarkTable.manga].value, it[MangaUpdateMarkTable.newCount], it[MangaUpdateMarkTable.markedAt]) }
        }

    /** Clears the marks of [animeIds], or every anime mark when null; returns how many went. */
    fun clearAnime(animeIds: Collection<Int>?): Int =
        dbTransaction {
            if (animeIds == null) {
                AnimeUpdateMarkTable.deleteWhere { AnimeUpdateMarkTable.id greater 0 }
            } else if (animeIds.isEmpty()) {
                0
            } else {
                AnimeUpdateMarkTable.deleteWhere { AnimeUpdateMarkTable.anime inList animeIds }
            }
        }

    /** Clears [userId]'s marks of [mangaIds], or all of them when null; returns how many went. */
    fun clearManga(
        userId: Int,
        mangaIds: Collection<Int>?,
    ): Int =
        dbTransaction {
            if (mangaIds != null && mangaIds.isEmpty()) return@dbTransaction 0
            MangaUpdateMarkTable.deleteWhere {
                (MangaUpdateMarkTable.user eq userId) and
                    (if (mangaIds == null) MangaUpdateMarkTable.id greater 0 else MangaUpdateMarkTable.manga inList mangaIds)
            }
        }
}
