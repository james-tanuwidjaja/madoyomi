package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.table.columns.jsonObject

/**
 * The anime library's categories (M0072), the counterpart of upstream's `CATEGORY`: one list per kind (Phase 6 §9,
 * the user's decision). The row with [isDefaultCategory] is the "Default" category: it holds the library entries that
 * are in no other category, and can't be renamed or deleted.
 */
object AnimeCategoryTable : IntIdTable("anime_category") {
    val name = varchar("name", 64)

    /** The position: 0 for Default, then 1, 2, … (as upstream's `sort_order`). */
    val order = integer("sort_order").default(0)

    /** New library entries go into the categories with this flag (upstream's `is_default`). */
    val isDefault = bool("is_default").default(false)
    val isDefaultCategory = bool("is_default_category").default(false)
    val includeInUpdate = integer("include_in_update").default(IncludeOrExclude.UNSET.value)
    val includeInDownload = integer("include_in_download").default(IncludeOrExclude.UNSET.value)

    /** Key/value strings, as upstream's `CATEGORYMETA` (e.g. the Library's sort, `madoyomi.library.sort`). */
    val meta = jsonObject("meta").default(JsonObject(emptyMap()))
}

/** Anime in categories, the counterpart of upstream's `CATEGORYMANGA` (M0072). Unique by `(category, anime)`. */
object AnimeCategoryAnimeTable : IntIdTable("anime_category_anime") {
    val category = reference("category", AnimeCategoryTable, ReferenceOption.CASCADE)
    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).index()

    init {
        uniqueIndex(category, anime)
    }
}
