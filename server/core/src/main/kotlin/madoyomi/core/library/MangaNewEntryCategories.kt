package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable

/**
 * New manga library entries into the categories flagged for new entries, for upstream's GraphQL
 * `updateManga(s)`. Upstream does this only in its REST `Library.addMangaToLibrary`, so a manga added through GraphQL
 * stayed in Default whatever the flags said; anime's `AnimeCategories.addedToLibrary` follows the same rule.
 *
 * Both run inside the caller's transaction: [notInLibrary] before the entries are added, [addedToLibrary] after.
 */
object MangaNewEntryCategories {
    /** The ids of [mangaIds] that [userId] doesn't have in the library yet, each once. */
    fun notInLibrary(
        userId: Int,
        mangaIds: List<Int>,
    ): List<Int> {
        val ids = mangaIds.distinct()
        if (ids.isEmpty()) return emptyList()
        val inLibrary =
            MangaUserTable
                .select(MangaUserTable.manga)
                .where {
                    (MangaUserTable.user eq userId) and (MangaUserTable.manga inList ids) and (MangaUserTable.inLibrary eq true)
                }.map { it[MangaUserTable.manga].value }
                .toSet()
        return ids.filterNot { it in inLibrary }
    }

    /**
     * Puts [mangaIds], just added to [userId]'s library, into the categories flagged `is_default` (not Default itself),
     * unless they are in a category already: a removed entry keeps its categories, as upstream's REST path does.
     */
    fun addedToLibrary(
        userId: Int,
        mangaIds: List<Int>,
    ) {
        if (mangaIds.isEmpty()) return
        val flagged =
            CategoryTable
                .select(CategoryTable.id)
                .where {
                    (CategoryTable.user eq userId) and (CategoryTable.isDefault eq true) and (CategoryTable.isDefaultCategory eq false)
                }.map { it[CategoryTable.id].value }
        if (flagged.isEmpty()) return
        val categorized =
            CategoryMangaTable
                .select(CategoryMangaTable.manga)
                .where { (CategoryMangaTable.user eq userId) and (CategoryMangaTable.manga inList mangaIds) }
                .map { it[CategoryMangaTable.manga].value }
                .toSet()
        val pairs = mangaIds.distinct().filterNot { it in categorized }.flatMap { manga -> flagged.map { manga to it } }
        CategoryMangaTable.batchInsert(pairs) { (manga, category) ->
            this[CategoryMangaTable.manga] = manga
            this[CategoryMangaTable.category] = category
            this[CategoryMangaTable.user] = userId
        }
    }
}
