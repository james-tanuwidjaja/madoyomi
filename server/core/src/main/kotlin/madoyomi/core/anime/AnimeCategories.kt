package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import madoyomi.core.anime.model.table.AnimeCategoryAnimeTable
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * The anime library's categories (Phase 6 Stage 1), with upstream's rules for manga categories (`Category`,
 * `CategoryManga`, `CategoryMutation`, `Library.addMangaToLibrary`):
 * - one "Default" category ([AnimeCategoryTable.isDefaultCategory]) holds the library entries that are in no other
 *   category; it can't be renamed, flagged for new entries or deleted, and its name is reserved;
 * - the others are ordered 1, 2, … after it (Default is 0), and moved by position;
 * - names are trimmed, 1 to 64 characters and unique ignoring case. Upstream only checks a new manga category's name,
 *   case-sensitively; anime ones are checked on a rename too;
 * - an entry added to the library goes into the categories flagged [AnimeCategoryTable.isDefault], unless it is in
 *   a category already (a removed entry keeps its categories, as upstream's manga do);
 * - deleting a category sends its entries back to Default when they are in no other.
 *
 * A wrong argument is an [IllegalArgumentException] (`BAD_REQUEST`), an unknown id `NOT_FOUND`.
 */
object AnimeCategories {
    const val DEFAULT_NAME = "Default"
    const val MAX_NAME_LENGTH = 64

    /** The Default category's id (M0072 inserts it; inserted again if it was removed by hand). */
    fun defaultId(): Int =
        dbTransaction {
            AnimeCategoryTable
                .select(AnimeCategoryTable.id)
                .where { AnimeCategoryTable.isDefaultCategory eq true }
                .orderBy(AnimeCategoryTable.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(AnimeCategoryTable.id)
                ?.value
                ?: AnimeCategoryTable
                    .insertAndGetId {
                        it[name] = DEFAULT_NAME
                        it[order] = 0
                        it[isDefaultCategory] = true
                    }.value
        }

    /** Every category, Default first, then by position. */
    fun list(): List<ResultRow> =
        dbTransaction {
            defaultId()
            AnimeCategoryTable
                .selectAll()
                .orderBy(AnimeCategoryTable.order to SortOrder.ASC, AnimeCategoryTable.id to SortOrder.ASC)
                .toList()
        }

    /** The categories [ids], in that order (unknown ids left out). */
    fun rows(ids: List<Int>): List<ResultRow> {
        if (ids.isEmpty()) return emptyList()
        val byId =
            dbTransaction {
                AnimeCategoryTable.selectAll().where { AnimeCategoryTable.id inList ids }.associateBy { it[AnimeCategoryTable.id].value }
            }
        return ids.mapNotNull(byId::get)
    }

    /** The row [categoryId], or `NOT_FOUND`. */
    fun row(categoryId: Int): ResultRow =
        dbTransaction { AnimeCategoryTable.selectAll().where { AnimeCategoryTable.id eq categoryId }.singleOrNull() }
            ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No anime category with id $categoryId", mapOf("categoryId" to categoryId))

    private fun checkedName(
        name: String,
        except: Int? = null,
    ): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "'name' must not be empty" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "'name' must be at most $MAX_NAME_LENGTH characters" }
        require(!trimmed.equals(DEFAULT_NAME, ignoreCase = true)) { "'name' must not be $DEFAULT_NAME" }
        val taken =
            dbTransaction {
                AnimeCategoryTable
                    .selectAll()
                    .any { it[AnimeCategoryTable.id].value != except && it[AnimeCategoryTable.name].equals(trimmed, ignoreCase = true) }
            }
        require(!taken) { "'name' must be unique: there is a category named $trimmed" }
        return trimmed
    }

    /** Creates a category at 1-based [position] among the others (the end when null or past it); its id. */
    fun create(
        name: String,
        position: Int? = null,
        isDefault: Boolean? = null,
        includeInUpdate: IncludeOrExclude? = null,
        includeInDownload: IncludeOrExclude? = null,
    ): Int {
        require(position == null || position > 0) { "'order' must be > 0" }
        return dbTransaction {
            val checked = checkedName(name)
            defaultId()
            val id =
                AnimeCategoryTable
                    .insertAndGetId {
                        it[AnimeCategoryTable.name] = checked
                        it[order] = Int.MAX_VALUE
                        isDefault?.let { flag -> it[AnimeCategoryTable.isDefault] = flag }
                        includeInUpdate?.let { value -> it[AnimeCategoryTable.includeInUpdate] = value.value }
                        includeInDownload?.let { value -> it[AnimeCategoryTable.includeInDownload] = value.value }
                    }.value
            if (position != null) move(id, position) else normalize()
            id
        }
    }

    /** Changes a category; null leaves a field as it is. Default takes only the update and download flags. */
    fun update(
        categoryId: Int,
        name: String? = null,
        isDefault: Boolean? = null,
        includeInUpdate: IncludeOrExclude? = null,
        includeInDownload: IncludeOrExclude? = null,
    ) {
        dbTransaction {
            val row = row(categoryId)
            val protected = row[AnimeCategoryTable.isDefaultCategory]
            require(!(protected && name != null)) { "The $DEFAULT_NAME category can't be renamed" }
            require(!(protected && isDefault == true)) { "New entries can't go into the $DEFAULT_NAME category by a flag" }
            val checked = name?.let { checkedName(it, except = categoryId) }
            if (checked == null && isDefault == null && includeInUpdate == null && includeInDownload == null) return@dbTransaction
            AnimeCategoryTable.update({ AnimeCategoryTable.id eq categoryId }) {
                checked?.let { value -> it[AnimeCategoryTable.name] = value }
                isDefault?.let { flag -> it[AnimeCategoryTable.isDefault] = flag }
                includeInUpdate?.let { value -> it[AnimeCategoryTable.includeInUpdate] = value.value }
                includeInDownload?.let { value -> it[AnimeCategoryTable.includeInDownload] = value.value }
            }
        }
    }

    /** Moves a category to 1-based [position] among the others (clamped to the last); Default doesn't move. */
    fun move(
        categoryId: Int,
        position: Int,
    ) {
        require(position > 0) { "'position' must be > 0" }
        dbTransaction {
            val row = row(categoryId)
            require(!row[AnimeCategoryTable.isDefaultCategory]) { "The $DEFAULT_NAME category can't be moved" }
            val others = ordered().filterNot { it[AnimeCategoryTable.isDefaultCategory] }.map { it[AnimeCategoryTable.id].value }
            val reordered = others.toMutableList()
            reordered.remove(categoryId)
            reordered.add((position - 1).coerceAtMost(reordered.size), categoryId)
            write(reordered)
        }
    }

    /** Deletes a category; its entries are in Default again unless they are in another. The ids that were in it. */
    fun delete(categoryId: Int): List<Int> =
        dbTransaction {
            val row = row(categoryId)
            require(!row[AnimeCategoryTable.isDefaultCategory]) { "The $DEFAULT_NAME category can't be deleted" }
            val animeIds =
                AnimeCategoryAnimeTable
                    .select(AnimeCategoryAnimeTable.anime)
                    .where { AnimeCategoryAnimeTable.category eq categoryId }
                    .map { it[AnimeCategoryAnimeTable.anime].value }
            AnimeCategoryTable.deleteWhere { AnimeCategoryTable.id eq categoryId }
            normalize()
            animeIds
        }

    /**
     * Changes the categories of [animeIds], as upstream's `updateMangasCategories`: [clear] removes them from every
     * category (back to Default), else [remove] from those; then [add] puts them into those. Default in [add] or
     * [remove] is ignored (an entry is in Default by being in no other). Every id must exist, before anything is written.
     */
    fun assign(
        animeIds: List<Int>,
        add: List<Int> = emptyList(),
        remove: List<Int> = emptyList(),
        clear: Boolean = false,
    ) {
        val ids = animeIds.distinct()
        if (ids.isEmpty()) return
        dbTransaction {
            ids.forEach(AnimeEntries::animeRow)
            val defaultId = defaultId()
            val toAdd = add.distinct().onEach(::row).filter { it != defaultId }
            val toRemove = remove.distinct().onEach(::row).filter { it != defaultId }
            if (clear) {
                AnimeCategoryAnimeTable.deleteWhere { AnimeCategoryAnimeTable.anime inList ids }
            } else if (toRemove.isNotEmpty()) {
                AnimeCategoryAnimeTable.deleteWhere {
                    (AnimeCategoryAnimeTable.anime inList ids) and (AnimeCategoryAnimeTable.category inList toRemove)
                }
            }
            if (toAdd.isEmpty()) return@dbTransaction
            val existing =
                AnimeCategoryAnimeTable
                    .selectAll()
                    .where { (AnimeCategoryAnimeTable.anime inList ids) and (AnimeCategoryAnimeTable.category inList toAdd) }
                    .map { it[AnimeCategoryAnimeTable.anime].value to it[AnimeCategoryAnimeTable.category].value }
                    .toSet()
            val pairs = ids.flatMap { anime -> toAdd.map { anime to it } }.filterNot { it in existing }
            AnimeCategoryAnimeTable.batchInsert(pairs) { (anime, category) ->
                this[AnimeCategoryAnimeTable.anime] = anime
                this[AnimeCategoryAnimeTable.category] = category
            }
        }
    }

    /** Puts entries just added to the library into the categories flagged for new entries, unless they are in one. */
    fun addedToLibrary(animeIds: List<Int>) {
        val ids = animeIds.distinct()
        if (ids.isEmpty()) return
        dbTransaction {
            val flagged =
                AnimeCategoryTable
                    .select(AnimeCategoryTable.id)
                    .where { (AnimeCategoryTable.isDefault eq true) and (AnimeCategoryTable.isDefaultCategory eq false) }
                    .map { it[AnimeCategoryTable.id].value }
            if (flagged.isEmpty()) return@dbTransaction
            val categorized =
                AnimeCategoryAnimeTable
                    .select(AnimeCategoryAnimeTable.anime)
                    .where { AnimeCategoryAnimeTable.anime inList ids }
                    .map { it[AnimeCategoryAnimeTable.anime].value }
                    .toSet()
            val pairs = ids.filterNot { it in categorized }.flatMap { anime -> flagged.map { anime to it } }
            AnimeCategoryAnimeTable.batchInsert(pairs) { (anime, category) ->
                this[AnimeCategoryAnimeTable.anime] = anime
                this[AnimeCategoryAnimeTable.category] = category
            }
        }
    }

    /** The categories of each of [animeIds] (Default never listed), by position; an id in none maps to an empty list. */
    fun categoriesOf(animeIds: List<Int>): Map<Int, List<ResultRow>> {
        if (animeIds.isEmpty()) return emptyMap()
        val byAnime =
            dbTransaction {
                AnimeCategoryAnimeTable
                    .innerJoin(AnimeCategoryTable)
                    .selectAll()
                    .where { AnimeCategoryAnimeTable.anime inList animeIds }
                    .orderBy(AnimeCategoryTable.order to SortOrder.ASC, AnimeCategoryTable.id to SortOrder.ASC)
                    .groupBy({ it[AnimeCategoryAnimeTable.anime].value }, { it })
            }
        return animeIds.associateWith { byAnime[it].orEmpty() }
    }

    /** The library entries in a category (for Default: those in no category), by title. */
    fun libraryAnimeIn(categoryId: Int): List<ResultRow> =
        dbTransaction {
            val row = row(categoryId)
            val query =
                if (row[AnimeCategoryTable.isDefaultCategory]) {
                    AnimeTable.selectAll().where {
                        (AnimeTable.inLibrary eq true) and
                            (AnimeTable.id notInSubQuery AnimeCategoryAnimeTable.select(AnimeCategoryAnimeTable.anime))
                    }
                } else {
                    AnimeTable
                        .innerJoin(AnimeCategoryAnimeTable)
                        .select(AnimeTable.columns)
                        .where { (AnimeTable.inLibrary eq true) and (AnimeCategoryAnimeTable.category eq categoryId) }
                }
            query.orderBy(AnimeTable.title to SortOrder.ASC, AnimeTable.id to SortOrder.ASC).toList()
        }

    /** Sets one meta key of a category (as upstream's `setCategoryMeta`). */
    fun setMeta(
        categoryId: Int,
        key: String,
        value: String,
    ) {
        require(key.isNotEmpty()) { "'key' must not be empty" }
        dbTransaction {
            val meta = row(categoryId)[AnimeCategoryTable.meta]
            AnimeCategoryTable.update({ AnimeCategoryTable.id eq categoryId }) {
                it[AnimeCategoryTable.meta] = JsonObject(meta + (key to JsonPrimitive(value)))
            }
        }
    }

    /** Removes one meta key of a category; its old value, null if it had none. */
    fun deleteMeta(
        categoryId: Int,
        key: String,
    ): String? =
        dbTransaction {
            val meta = row(categoryId)[AnimeCategoryTable.meta]
            val old = meta[key]
            if (old != null) {
                AnimeCategoryTable.update({ AnimeCategoryTable.id eq categoryId }) {
                    it[AnimeCategoryTable.meta] = JsonObject(meta - key)
                }
            }
            (old as? JsonPrimitive)?.content
        }

    /** A category's meta as strings. */
    fun metaOf(row: ResultRow): Map<String, String> =
        row[AnimeCategoryTable.meta].mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }

    private fun ordered(): List<ResultRow> =
        AnimeCategoryTable
            .selectAll()
            .orderBy(AnimeCategoryTable.order to SortOrder.ASC, AnimeCategoryTable.id to SortOrder.ASC)
            .toList()

    /** Default at 0, the others 1, 2, … in their current order (as upstream's `normalizeCategories`). */
    private fun normalize() {
        write(ordered().filterNot { it[AnimeCategoryTable.isDefaultCategory] }.map { it[AnimeCategoryTable.id].value })
    }

    private fun write(others: List<Int>) {
        AnimeCategoryTable.update({ AnimeCategoryTable.isDefaultCategory eq true }) { it[order] = 0 }
        val current = ordered().associate { it[AnimeCategoryTable.id].value to it[AnimeCategoryTable.order] }
        others.forEachIndexed { index, id ->
            if (current[id] != index + 1) AnimeCategoryTable.update({ AnimeCategoryTable.id eq id }) { it[order] = index + 1 }
        }
    }
}
