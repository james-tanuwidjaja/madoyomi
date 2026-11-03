package suwayomi.tachidesk.server.database.migration

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Madoyomi migration: see server/UPSTREAM.md "Database migrations" (numbering, idempotency).

import de.neonew.exposed.migrations.helpers.AddTableMigration
import madoyomi.core.database.MadoyomiMigration
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT

/**
 * The anime domain, in tables of its own next to the manga ones: `ANIME_SOURCE`, `ANIME`, `EPISODE`,
 * plus `ANIME_HISTORY` and `ANIME_CATEGORY` stubs for Phase 6. Idempotent: `SchemaUtils.create` skips
 * tables that already exist.
 */
@Suppress("ClassName", "unused")
class M0070_AnimeTables :
    AddTableMigration(),
    MadoyomiMigration {
    private companion object {
        /**
         * Unlimited text, created the way upstream's migrations create MANGA's text columns
         * (`VARCHAR` on H2, `TEXT` on PostgreSQL). `unlimitedVarchar` can't be used in DDL: on H2 it
         * renders `VARCHAR(2147483647)`, which H2 rejects.
         */
        fun Table.unlimitedText(name: String): Column<String> =
            registerColumn(
                name,
                object : VarCharColumnType() {
                    override fun sqlType(): String = UNLIMITED_TEXT
                },
            )
    }

    private class ExtensionTable : IntIdTable()

    private class CategoryTable : IntIdTable()

    private class AnimeSourceTable(
        extensionTable: ExtensionTable,
    ) : IdTable<Long>("anime_source") {
        override val id = long("id").entityId()
        override val primaryKey = PrimaryKey(id)
        val name = varchar("name", 128)
        val lang = varchar("lang", 32)
        val extension = reference("extension", extensionTable).index()
        val contentWarning = integer("content_warning").default(0)
        val baseUrl = varchar("base_url", 2048).nullable()
    }

    private class AnimeTable : IntIdTable("anime") {
        val url = varchar("url", 2048)
        val title = truncatingVarchar("title", 512)
        val artist = unlimitedText("artist").nullable()
        val author = unlimitedText("author").nullable()
        val description = unlimitedText("description").nullable()
        val genre = unlimitedText("genre").nullable()
        val status = integer("status").default(0)
        val thumbnailUrl = varchar("thumbnail_url", 2048).nullable()
        val inLibrary = bool("in_library").default(false)
        val initialized = bool("initialized").default(false)
        val sourceReference = long("source").index()
        val addedAt = long("added_at").default(0)
        val lastFetchedAt = long("last_fetched_at").default(0)
    }

    private class EpisodeTable(
        animeTable: AnimeTable,
    ) : IntIdTable("episode") {
        val url = varchar("url", 2048)
        val name = truncatingVarchar("name", 512)
        val episodeNumber = float("episode_number").default(-1f)
        val dateUpload = long("date_upload").default(0)
        val scanlator = truncatingVarchar("scanlator", 256).nullable()
        val watched = bool("watched").default(false)
        val bookmark = bool("bookmark").default(false)
        val lastSecondSeen = long("last_second_seen").default(0)
        val totalSeconds = long("total_seconds").default(0)
        val sourceOrder = integer("source_order")
        val fetchedAt = long("fetched_at").default(0)
        val anime = reference("anime", animeTable, ReferenceOption.CASCADE).index()
    }

    private class AnimeHistoryTable(
        episodeTable: EpisodeTable,
    ) : IntIdTable("anime_history") {
        val episode = reference("episode", episodeTable, ReferenceOption.CASCADE).uniqueIndex()
        val lastSeen = long("last_seen").default(0)
    }

    private class AnimeCategoryTable(
        categoryTable: CategoryTable,
        animeTable: AnimeTable,
    ) : IntIdTable("anime_category") {
        val category = reference("category", categoryTable, ReferenceOption.CASCADE)
        val anime = reference("anime", animeTable, ReferenceOption.CASCADE).index()

        init {
            uniqueIndex(category, anime)
        }
    }

    override val tables: Array<Table>
        get() {
            val anime = AnimeTable()
            val episode = EpisodeTable(anime)
            return arrayOf(
                AnimeSourceTable(ExtensionTable()),
                anime,
                episode,
                AnimeHistoryTable(episode),
                AnimeCategoryTable(CategoryTable(), anime),
            )
        }
}
