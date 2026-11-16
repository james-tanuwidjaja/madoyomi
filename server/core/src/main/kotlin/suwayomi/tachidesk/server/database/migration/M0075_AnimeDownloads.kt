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
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Anime downloads (Phase 6 Stage 5):
 * - `DOWNLOAD` gets `PROGRESS_UNIT` (`PAGES`, `BYTES`, `SECONDS`, `SEGMENTS`; `PAGES` for the rows before) and
 *   `TOTAL_BYTES`;
 * - `EPISODE_DOWNLOAD`, one row per downloaded episode (`EPISODE` unique and `ANIME`, both `CASCADE`): `PATH` and
 *   `SUBTITLES` (JSON) relative to the downloads folder, `VIDEO_TITLE`, `METHOD` (`DIRECT`, `FFMPEG`, `YT_DLP`),
 *   `BYTES`, `DOWNLOADED_AT` (epoch milliseconds).
 *
 * Idempotent: the columns are added only when missing, and `SchemaUtils.create` skips a table that already exists.
 */
@Suppress("ClassName", "unused")
class M0075_AnimeDownloads :
    Migration(),
    MadoyomiMigration {
    private class AnimeTable : IntIdTable("anime")

    private class EpisodeTable : IntIdTable("episode")

    private class EpisodeDownloadTable(
        anime: AnimeTable,
        episode: EpisodeTable,
    ) : IntIdTable("episode_download") {
        val episode = reference("episode", episode, ReferenceOption.CASCADE).uniqueIndex()
        val anime = reference("anime", anime, ReferenceOption.CASCADE).index()
        val path = varchar("path", 2048)
        val subtitles = text("subtitles").default("[]")
        val videoTitle = varchar("video_title", 512).default("")
        val method = varchar("method", 16)
        val bytes = long("bytes").default(0)
        val downloadedAt = long("downloaded_at").default(0)
    }

    override fun run() {
        val transaction = TransactionManager.current()
        // H2: ADD COLUMN IF NOT EXISTS keeps a rerun from failing
        transaction.exec("ALTER TABLE DOWNLOAD ADD COLUMN IF NOT EXISTS PROGRESS_UNIT VARCHAR(16) DEFAULT 'PAGES' NOT NULL")
        transaction.exec("ALTER TABLE DOWNLOAD ADD COLUMN IF NOT EXISTS TOTAL_BYTES BIGINT DEFAULT 0 NOT NULL")
        SchemaUtils.create(EpisodeDownloadTable(AnimeTable(), EpisodeTable()))
    }
}
