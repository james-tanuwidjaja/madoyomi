package suwayomi.tachidesk.server.database.migration

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Madoyomi migration: see server/UPSTREAM.md "Database migrations" (numbering, idempotency).

import de.neonew.exposed.migrations.Migration
import madoyomi.core.database.MadoyomiMigration
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

/**
 * The library update check's "new" marks: a check that finds new items marks the library entry with
 * their count until the entry is opened.
 * - `ANIME_UPDATE_MARK`: `ANIME` (FK, `CASCADE`, unique), `NEW_COUNT`, `MARKED_AT` (epoch seconds);
 * - `MANGA_UPDATE_MARK`: `MANGA` and `USER_ID` (FKs, `CASCADE`, unique pair: upstream's library is per user), `NEW_COUNT`,
 *   `MARKED_AT`.
 *
 * Idempotent: `SchemaUtils.create` skips tables that already exist.
 */
@Suppress("ClassName", "unused")
class M0073_LibraryUpdateMarks :
    Migration(),
    MadoyomiMigration {
    private class AnimeTable : IntIdTable("anime")

    // the names Exposed gives upstream's `object MangaTable : IntIdTable()` and `object UserAccountTable : IntIdTable()`
    private class MangaTable : IntIdTable("Manga")

    private class UserAccountTable : IntIdTable("UserAccount")

    private class AnimeUpdateMarkTable(
        animeTable: AnimeTable,
    ) : IntIdTable("anime_update_mark") {
        val anime = reference("anime", animeTable, ReferenceOption.CASCADE).uniqueIndex()
        val newCount = integer("new_count").default(0)
        val markedAt = long("marked_at").default(0)
    }

    private class MangaUpdateMarkTable(
        mangaTable: MangaTable,
        userTable: UserAccountTable,
    ) : IntIdTable("manga_update_mark") {
        val manga = reference("manga", mangaTable, ReferenceOption.CASCADE)
        val user = reference("user_id", userTable, ReferenceOption.CASCADE)
        val newCount = integer("new_count").default(0)
        val markedAt = long("marked_at").default(0)

        init {
            uniqueIndex(manga, user)
        }
    }

    override fun run() {
        SchemaUtils.create(AnimeUpdateMarkTable(AnimeTable()), MangaUpdateMarkTable(MangaTable(), UserAccountTable()))
    }
}
