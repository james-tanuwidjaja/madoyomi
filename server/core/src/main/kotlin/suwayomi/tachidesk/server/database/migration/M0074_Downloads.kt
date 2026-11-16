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
 * The download queue (Phase 6 Stage 4), for both kinds: `DOWNLOAD`, one row per chapter (`MANGA`, `CHAPTER`) or
 * episode (`ANIME`, `EPISODE`), the four references nullable and `CASCADE`, `CHAPTER` and `EPISODE` unique; `KIND`,
 * `USER_ID`, `SOURCE_ID`, `STATE` (`QUEUED`, `DOWNLOADING`, `PAUSED`, `FAILED`, `DONE`), `POSITION`, `PROGRESS_DONE`,
 * `PROGRESS_TOTAL`, `BYTES`, `TRIES`, `ERROR`, `ADDED_AT` and `FINISHED_AT` (epoch milliseconds).
 *
 * Idempotent: `SchemaUtils.create` skips a table that already exists.
 */
@Suppress("ClassName", "unused")
class M0074_Downloads :
    Migration(),
    MadoyomiMigration {
    // the names Exposed gives upstream's `object MangaTable : IntIdTable()` and `object ChapterTable : IntIdTable()`
    private class MangaTable : IntIdTable("Manga")

    private class ChapterTable : IntIdTable("Chapter")

    private class AnimeTable : IntIdTable("anime")

    private class EpisodeTable : IntIdTable("episode")

    private class DownloadTable(
        manga: MangaTable,
        chapter: ChapterTable,
        anime: AnimeTable,
        episode: EpisodeTable,
    ) : IntIdTable("download") {
        val kind = varchar("kind", 16)
        val manga = reference("manga", manga, ReferenceOption.CASCADE).nullable()
        val chapter = reference("chapter", chapter, ReferenceOption.CASCADE).nullable().uniqueIndex()
        val anime = reference("anime", anime, ReferenceOption.CASCADE).nullable()
        val episode = reference("episode", episode, ReferenceOption.CASCADE).nullable().uniqueIndex()
        val userId = integer("user_id").default(1)
        val sourceId = long("source_id")
        val state = varchar("state", 16).default("QUEUED")
        val position = integer("position").default(0)
        val progressDone = integer("progress_done").default(0)
        val progressTotal = integer("progress_total").default(0)
        val bytes = long("bytes").default(0)
        val tries = integer("tries").default(0)
        val error = text("error").nullable()
        val addedAt = long("added_at").default(0)
        val finishedAt = long("finished_at").default(0)
    }

    override fun run() {
        SchemaUtils.create(DownloadTable(MangaTable(), ChapterTable(), AnimeTable(), EpisodeTable()))
    }
}
