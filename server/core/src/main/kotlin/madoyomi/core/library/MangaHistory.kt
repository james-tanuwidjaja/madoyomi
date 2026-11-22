package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * Reading history: upstream's `CHAPTER_USER.last_read_at` (epoch seconds), which upstream's
 * `updateChapter(s)` stamps whenever `lastPageRead` is saved (the reader does on opening a chapter and on every page)
 * and its backup already carries as `BackupHistory`. No table of Madoyomi's: deleting history sets `last_read_at` back
 * to 0 and keeps the read marks and the pages.
 */
object MangaHistory {
    /** As [AnimeHistory.Item]: one per manga, its chapter read last, when, and the chapter "resume" opens. */
    data class Item(
        val mangaId: Int,
        val chapterId: Int,
        val lastReadAt: Long,
        val resumeChapterId: Int,
    )

    data class Page(
        val items: List<Item>,
        val hasMore: Boolean,
    )

    /** The manga [userId] read, the one read last first, [limit] from [offset]. */
    fun page(
        userId: Int,
        limit: Int,
        offset: Int,
    ): Page {
        val size = limit.coerceIn(1, 500)
        return dbTransaction {
            val latest = ChapterUserTable.lastReadAt.max()
            val read = (ChapterUserTable.user eq userId) and (ChapterUserTable.lastReadAt greater 0L)
            val mangaIds =
                (ChapterUserTable innerJoin ChapterTable)
                    .select(ChapterTable.manga, latest)
                    .where { read }
                    .groupBy(ChapterTable.manga)
                    .orderBy(latest to SortOrder.DESC, ChapterTable.manga to SortOrder.DESC)
                    .limit(size + 1)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map { it[ChapterTable.manga].value }
            val shown = mangaIds.take(size)
            if (shown.isEmpty()) return@dbTransaction Page(emptyList(), false)

            val readIds =
                (ChapterUserTable innerJoin ChapterTable)
                    .select(ChapterTable.id)
                    .where { (ChapterTable.manga inList shown) and (ChapterUserTable.user eq userId) and (ChapterUserTable.isRead eq true) }
                    .map { it[ChapterTable.id].value }
                    .toSet()
            val chapters =
                ChapterTable
                    .select(ChapterTable.id, ChapterTable.manga, ChapterTable.sourceOrder)
                    .where { ChapterTable.manga inList shown }
                    .groupBy { it[ChapterTable.manga].value }
            val last =
                (ChapterUserTable innerJoin ChapterTable)
                    .select(ChapterTable.id, ChapterTable.manga, ChapterUserTable.lastReadAt)
                    .where { (ChapterTable.manga inList shown) and read }
                    .orderBy(ChapterUserTable.lastReadAt to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                    .groupBy { it[ChapterTable.manga].value }
            val items =
                shown.mapNotNull { mangaId ->
                    val top = last[mangaId]?.firstOrNull() ?: return@mapNotNull null
                    val chapterId = top[ChapterTable.id].value
                    val list = chapters[mangaId].orEmpty().sortedBy { it[ChapterTable.sourceOrder] }
                    val current = list.firstOrNull { it[ChapterTable.id].value == chapterId }
                    val resume =
                        if (current == null || chapterId !in readIds) {
                            chapterId
                        } else {
                            list
                                .firstOrNull {
                                    it[ChapterTable.sourceOrder] > current[ChapterTable.sourceOrder] &&
                                        it[ChapterTable.id].value !in readIds
                                }?.get(ChapterTable.id)
                                ?.value ?: chapterId
                        }
                    Item(mangaId, chapterId, top[ChapterUserTable.lastReadAt], resume)
                }
            Page(items, mangaIds.size > size)
        }
    }

    /** Sets `last_read_at` back to 0 for every chapter of [mangaIds]; returns the number of chapters changed. */
    fun delete(
        userId: Int,
        mangaIds: Collection<Int>,
    ): Int {
        if (mangaIds.isEmpty()) return 0
        return dbTransaction {
            ChapterUserTable.update({
                (ChapterUserTable.user eq userId) and (ChapterUserTable.lastReadAt greater 0L) and
                    (ChapterUserTable.chapter inSubQuery ChapterTable.select(ChapterTable.id).where { ChapterTable.manga inList mangaIds })
            }) { it[lastReadAt] = 0 }
        }
    }

    /** Sets `last_read_at` back to 0 for every chapter [userId] read; returns the number of chapters changed. */
    fun clear(userId: Int): Int =
        dbTransaction {
            ChapterUserTable.update({ (ChapterUserTable.user eq userId) and (ChapterUserTable.lastReadAt greater 0L) }) {
                it[lastReadAt] = 0
            }
        }
}
