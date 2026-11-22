package suwayomi.tachidesk.server.database.migration

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Madoyomi migration: see server/UPSTREAM.md "Database migrations" (numbering, idempotency).

import de.neonew.exposed.migrations.Migration
import madoyomi.core.database.MadoyomiMigration
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT

/**
 * The anime library's categories: one list per kind, the user's decision.
 * - M0070's `ANIME_CATEGORY` stub (an anime FK and a FK to upstream's manga `CATEGORY`, never written) is dropped;
 * - `ANIME_CATEGORY` is the anime counterpart of upstream's `CATEGORY` (name, `sort_order`, `is_default`,
 *   `is_default_category`, the include-in-update/download flags, and `meta` as JSON text), with its "Default" row;
 * - `ANIME_CATEGORY_ANIME` is the counterpart of `CATEGORYMANGA` (category and anime FKs, both `CASCADE`, unique pair).
 *
 * Idempotent: the stub is recognised by its columns (an `ANIME` column and no `NAME`), so a second run leaves the new
 * table alone; `SchemaUtils.create` skips existing tables; the Default row is inserted only when there is none.
 */
@Suppress("ClassName", "unused")
class M0072_AnimeCategories :
    Migration(),
    MadoyomiMigration {
    private companion object {
        /** Unlimited text as M0070 creates it (`VARCHAR` on H2, `TEXT` on PostgreSQL); see M0070. */
        fun Table.unlimitedText(name: String): Column<String> =
            registerColumn(
                name,
                object : VarCharColumnType() {
                    override fun sqlType(): String = UNLIMITED_TEXT
                },
            )
    }

    private class AnimeTable : IntIdTable("anime")

    private class AnimeCategoryTable : IntIdTable("anime_category") {
        val name = varchar("name", 64)
        val order = integer("sort_order").default(0)
        val isDefault = bool("is_default").default(false)
        val isDefaultCategory = bool("is_default_category").default(false)
        val includeInUpdate = integer("include_in_update").default(-1)
        val includeInDownload = integer("include_in_download").default(-1)
        val meta = unlimitedText("meta").default("{}")
    }

    private class AnimeCategoryAnimeTable(
        categoryTable: AnimeCategoryTable,
        animeTable: AnimeTable,
    ) : IntIdTable("anime_category_anime") {
        val category = reference("category", categoryTable, ReferenceOption.CASCADE)
        val anime = reference("anime", animeTable, ReferenceOption.CASCADE).index()

        init {
            uniqueIndex(category, anime)
        }
    }

    override fun run() {
        val transaction = TransactionManager.current()

        fun hasColumn(column: String): Boolean =
            transaction.exec(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' " +
                    "AND UPPER(TABLE_NAME) = 'ANIME_CATEGORY' AND UPPER(COLUMN_NAME) = '$column'",
            ) { rs -> rs.next() && rs.getInt(1) > 0 } == true

        if (hasColumn("ANIME") && !hasColumn("NAME")) {
            transaction.exec("DROP TABLE ANIME_CATEGORY")
            // Exposed caches the table names it has seen: without this, SchemaUtils.create would skip the new table
            currentDialectMetadata.resetCaches()
        }

        val categories = AnimeCategoryTable()
        // SchemaUtils.create skips tables that already exist
        SchemaUtils.create(categories, AnimeCategoryAnimeTable(categories, AnimeTable()))

        if (categories.selectAll().where { categories.isDefaultCategory eq true }.empty()) {
            categories.insert {
                it[name] = "Default"
                it[order] = 0
                it[isDefaultCategory] = true
            }
        }
    }
}
