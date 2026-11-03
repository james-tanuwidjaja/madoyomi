package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable

/**
 * Anime sources, kept apart from the manga `SOURCE` table: sources are typed by table, so upstream's
 * manga-only `SOURCE` queries stay untouched. Rows come from the repo listing at install time.
 */
object AnimeSourceTable : IdTable<Long>("anime_source") {
    override val id = long("id").entityId()
    override val primaryKey = PrimaryKey(id)

    val name = varchar("name", 128)
    val lang = varchar("lang", 32)
    val extension = reference("extension", ExtensionTable).index()
    val contentWarning = integer("content_warning").default(0)
    val baseUrl = varchar("base_url", 2048).nullable()
}
