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
 * What a row's `progress_done`/`progress_total` count (Stage 5, M0075): a chapter's pages; a file's bytes (the two
 * then thousandths, and [DownloadTable.totalBytes] the size); the seconds of the episode ffmpeg wrote, of its
 * duration (HLS, DASH); yt-dlp's segments (fragments).
 */
enum class DownloadProgressUnit { PAGES, BYTES, SECONDS, SEGMENTS }

/**
 * The download queue (M0074), one row per episode or chapter, for both kinds: a manga row has [manga] and [chapter],
 * an anime row (Stage 5) [anime] and [episode]. The references cascade, so a chapter that a refresh replaced takes
 * its download with it. [position] orders the unfinished rows (lowest first); [progressDone] of [progressTotal] are
 * in [progressUnit] (M0075). Times are epoch milliseconds.
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

    /** Stage 5 (M0075): what [progressDone] and [progressTotal] count. */
    val progressUnit = enumerationByName("progress_unit", 16, DownloadProgressUnit::class).default(DownloadProgressUnit.PAGES)

    /** Stage 5 (M0075): the size the download will have, when known (0: unknown). */
    val totalBytes = long("total_bytes").default(0)
}

/** How an episode was downloaded (Stage 5). */
enum class EpisodeDownloadMethod { DIRECT, FFMPEG, YT_DLP }

/**
 * The downloaded episodes (Stage 5, M0075): one row per episode whose file is on disk, kept apart from the queue's
 * completed list (which Clear list empties while the files stay). Paths are relative to the downloads folder, with
 * `/`; [subtitles] is a JSON list of `{"path": …, "lang": …, "kind": "subtitle" | "audio"}` (the external audio tracks too). The episode reference cascades: a refresh that replaces
 * the episode forgets the download (its files stay, as a re-listed chapter's CBZ does).
 */
object EpisodeDownloadTable : IntIdTable("episode_download") {
    val episode = reference("episode", EpisodeTable, ReferenceOption.CASCADE).uniqueIndex()
    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).index()
    val path = varchar("path", 2048)
    val subtitles = text("subtitles").default("[]")
    val videoTitle = varchar("video_title", 512).default("")
    val method = enumerationByName("method", 16, EpisodeDownloadMethod::class)
    val bytes = long("bytes").default(0)
    val downloadedAt = long("downloaded_at").default(0)
}
