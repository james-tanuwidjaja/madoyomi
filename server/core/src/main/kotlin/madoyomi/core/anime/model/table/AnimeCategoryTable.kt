package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.CategoryTable

/** Anime in categories (upstream's `CATEGORY` table). A stub for Phase 6 (library features). */
object AnimeCategoryTable : IntIdTable("anime_category") {
    val category = reference("category", CategoryTable, ReferenceOption.CASCADE)
    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).index()

    init {
        uniqueIndex(category, anime)
    }
}
