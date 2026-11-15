package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.model.table.AnimeTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.global.model.table.UserAccountTable
import suwayomi.tachidesk.manga.model.table.MangaTable

/**
 * The "new" mark of an anime library entry (M0073): a library update check found [newCount] new episodes since the
 * entry was last opened. One row per entry, deleted when it is opened ([LibraryUpdateMarks.clear]).
 */
object AnimeUpdateMarkTable : IntIdTable("anime_update_mark") {
    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).uniqueIndex()
    val newCount = integer("new_count").default(0)

    /** Epoch seconds of the last check that added to [newCount]. */
    val markedAt = long("marked_at").default(0)
}

/** The same for a manga entry of a user (upstream's library is per user, `MANGA_USER`). Unique by `(manga, user)`. */
object MangaUpdateMarkTable : IntIdTable("manga_update_mark") {
    val manga = reference("manga", MangaTable, ReferenceOption.CASCADE)
    val user = reference("user_id", UserAccountTable, ReferenceOption.CASCADE)
    val newCount = integer("new_count").default(0)
    val markedAt = long("marked_at").default(0)

    init {
        uniqueIndex(manga, user)
    }
}
