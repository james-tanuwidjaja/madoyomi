package suwayomi.tachidesk.server.database.migration

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Madoyomi migration: see server/UPSTREAM.md "Database migrations" (numbering, idempotency).

import de.neonew.exposed.migrations.Migration
import madoyomi.core.database.MadoyomiMigration
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT

/**
 * What browsing and episode lists need (Phase 2 Stage 3):
 * - `ANIME`: the `SAnime` fields M0070 lacked (`background_url`, `update_strategy`, `fetch_type`, `season_number`,
 *   `memo`) and seasons (`parent_id` → `ANIME`, `ON DELETE SET NULL`; `season_source_order`);
 * - `EPISODE`: the `SEpisode` fields (`fillermark`, `summary`, `preview_url`, `memo`);
 * - unique `(source, url)` on `ANIME` and `(url, anime)` on `EPISODE` (duplicates are dropped first, keeping the
 *   lowest id, as upstream's M0039/M0062 do), so browsed entries keep stable ids.
 *
 * Idempotent: `ADD COLUMN IF NOT EXISTS` (the foreign key is part of its column, so it is only added with it) and
 * `CREATE UNIQUE INDEX IF NOT EXISTS`, both portable to H2 and PostgreSQL.
 */
@Suppress("ClassName", "unused")
class M0071_AnimeBrowse :
    Migration(),
    MadoyomiMigration {
    private val ddl
        get() =
            listOf(
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS BACKGROUND_URL VARCHAR(2048) NULL",
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS UPDATE_STRATEGY VARCHAR(32) DEFAULT 'ALWAYS_UPDATE' NOT NULL",
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS FETCH_TYPE VARCHAR(16) DEFAULT 'Episodes' NOT NULL",
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS SEASON_NUMBER DOUBLE PRECISION DEFAULT -1 NOT NULL",
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS MEMO $UNLIMITED_TEXT DEFAULT '{}' NOT NULL",
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS PARENT_ID INT NULL REFERENCES ANIME (ID) ON DELETE SET NULL",
                "ALTER TABLE ANIME ADD COLUMN IF NOT EXISTS SEASON_SOURCE_ORDER INT DEFAULT 0 NOT NULL",
                "ALTER TABLE EPISODE ADD COLUMN IF NOT EXISTS FILLERMARK BOOLEAN DEFAULT FALSE NOT NULL",
                "ALTER TABLE EPISODE ADD COLUMN IF NOT EXISTS SUMMARY $UNLIMITED_TEXT NULL",
                "ALTER TABLE EPISODE ADD COLUMN IF NOT EXISTS PREVIEW_URL VARCHAR(2048) NULL",
                "ALTER TABLE EPISODE ADD COLUMN IF NOT EXISTS MEMO $UNLIMITED_TEXT DEFAULT '{}' NOT NULL",
                "DELETE FROM ANIME WHERE ID NOT IN (SELECT MIN(ID) FROM ANIME GROUP BY SOURCE, URL)",
                "DELETE FROM EPISODE WHERE ID NOT IN (SELECT MIN(ID) FROM EPISODE GROUP BY URL, ANIME)",
                "CREATE UNIQUE INDEX IF NOT EXISTS UX_ANIME_SOURCE_URL ON ANIME (SOURCE, URL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS UX_EPISODE_URL_ANIME ON EPISODE (URL, ANIME)",
            )

    override fun run() {
        val transaction = TransactionManager.current()
        ddl.forEach { transaction.exec(it) }
    }
}
