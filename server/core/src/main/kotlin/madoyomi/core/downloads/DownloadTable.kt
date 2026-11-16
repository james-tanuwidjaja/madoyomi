package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable

/**
 * Where a download is (Phase 6 Stage 4): waiting its turn, being downloaded, paused by the user, failed after its
 * retries, or done (kept for the Downloads screen's completed list until it is cleared or its file deleted).
 */
enum class DownloadItemState { QUEUED, DOWNLOADING, PAUSED, FAILED, DONE }

/**
 * The download queue (M0074), one row per episode or chapter, for both kinds: a manga row has [manga] and [chapter],
 * an anime row (Stage 5) [anime] and [episode]. The references cascade, so a chapter that a refresh replaced takes
 * its download with it. [position] orders the unfinished rows (lowest first); [progressDone] of [progressTotal] are
 * pages for a chapter. Times are epoch milliseconds.
 */
object DownloadTable : IntIdTable("download") {
    val kind = enumerationByName("kind", 16, ExtensionKind::class)
    val manga = reference("manga", MangaTable, ReferenceOption.CASCADE).nullable()
    val chapter = reference("chapter", ChapterTable, ReferenceOption.CASCADE).nullable().uniqueIndex()
    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).nullable()
    val episode = reference("episode", EpisodeTable, ReferenceOption.CASCADE).nullable().uniqueIndex()

    /** The user who asked for it (upstream's library is per user; the app has one). */
    val userId = integer("user_id").default(1)
    val sourceId = long("source_id")
    val state = enumerationByName("state", 16, DownloadItemState::class).default(DownloadItemState.QUEUED)
    val position = integer("position").default(0)
    val progressDone = integer("progress_done").default(0)
    val progressTotal = integer("progress_total").default(0)
    val bytes = long("bytes").default(0)

    /** Failed attempts since it was queued or retried by hand. */
    val tries = integer("tries").default(0)
    val error = text("error").nullable()
    val addedAt = long("added_at").default(0)
    val finishedAt = long("finished_at").default(0)
}
