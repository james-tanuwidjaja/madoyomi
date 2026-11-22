package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.SourceAccess
import madoyomi.core.library.LibraryUpdater
import madoyomi.core.reader.PageCache
import madoyomi.core.reader.PageImages
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReadyById
import suwayomi.tachidesk.manga.impl.util.createComicInfoFile
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.injectLazy
import java.io.File
import suwayomi.tachidesk.manga.impl.Page as UpstreamPage

/**
 * Downloads a chapter as a CBZ, at upstream's place for it (`getChapterCbzPath`:
 * `<downloads>/manga/<source>/<title>/<scanlator_><chapter>.cbz`), so upstream's reader, its delete and its
 * `isDownloaded` work on it as they are:
 * 1. the page list, through upstream's `getChapterDownloadReadyById` (from the CBZ when there is one already);
 * 2. each page, staged in `<downloads>/.partial/manga/<chapterId>/` (outside the reader's page cache, which trims its
 *    folder): a page staged by an earlier attempt is kept, one in the reader's cache is copied, the others are fetched
 *    through the reader's [PageImages] (the source's client and rate limits, the expired-URL retry, `SOURCE_FAILED`),
 *    one at a time;
 * 3. upstream's `ComicInfo.xml`, then the CBZ ([CbzWriter]), then the chapter marked downloaded for the user, as
 *    upstream's downloader does.
 */
object MangaChapterDownloader : ItemDownloader {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    /** Tests: another staging folder than `<downloads>/.partial/manga`. */
    @Volatile
    var stagingRootOverride: File? = null

    /** Names the page count the staged pages were named for (`001` … `999` or `0001` …). */
    private const val PAGE_COUNT_FILE = ".page-count"

    fun stagingRoot(): File = stagingRootOverride ?: File(applicationDirs.downloadsRoot, ".partial/manga")

    fun stagingDir(chapterId: Int): File = File(stagingRoot(), chapterId.toString())

    override suspend fun download(
        item: DownloadItem,
        progress: suspend (DownloadProgress) -> Unit,
    ): Long {
        val mangaId = item.entryId
        val chapterId = item.itemId
        val chapter = getChapterDownloadReadyById(item.userId, chapterId)
        val cbz = File(getChapterCbzPath(mangaId, chapterId))
        if (chapter.downloaded && cbz.isFile) {
            markDownloaded(item.userId, chapterId, chapter.pageCount)
            progress(DownloadProgress(chapter.pageCount, chapter.pageCount, cbz.length()))
            return cbz.length()
        }
        val pageCount = chapter.pageCount
        if (pageCount <= 0) throw MadoyomiException(ErrorCode.SOURCE_FAILED, "The source listed no pages for this chapter")
        val source =
            SourceAccess.require(item.sourceId) as? HttpSource
                ?: throw MadoyomiException(ErrorCode.BAD_REQUEST, "This source's chapters can't be downloaded")

        val staging = stagingDir(chapterId)
        val countFile = File(staging, PAGE_COUNT_FILE)
        if (staging.isDirectory && countFile.takeIf { it.isFile }?.readText()?.trim() != pageCount.toString()) {
            // staged for a page list of another length, whose pages are named differently: start over
            staging.deleteRecursively()
        }
        staging.mkdirs()
        countFile.writeText(pageCount.toString())

        val readerCache = getChapterCachePath(mangaId, chapterId)
        val pages =
            dbTransaction {
                PageTable
                    .selectAll()
                    .where { PageTable.chapter eq chapterId }
                    .orderBy(PageTable.index to SortOrder.ASC)
                    .map { Page(it[PageTable.index], it[PageTable.url], it[PageTable.imageUrl]) }
            }
        if (pages.size < pageCount) throw MadoyomiException(ErrorCode.SOURCE_FAILED, "The chapter's page list is incomplete")

        var bytes = 0L
        for (index in 0 until pageCount) {
            val name = UpstreamPage.getPageName(index, pageCount)
            val staged =
                PageCache.cached(staging.path, name)
                    ?: PageCache.cached(readerCache, name)?.let { cached ->
                        cached.copyTo(File(staging, cached.name), overwrite = true)
                    }
                    ?: fetch(mangaId, chapterId, index, staging, name, source, pages[index])
            bytes += staged.length()
            progress(DownloadProgress(index + 1, pageCount, bytes))
        }

        val (mangaRow, chapterRow) =
            dbTransaction {
                MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() to
                    ChapterTable.selectAll().where { ChapterTable.id eq chapterId }.first()
            }
        createComicInfoFile(staging.toPath(), mangaRow, chapterRow)
        countFile.delete()
        val size = CbzWriter.write(staging, cbz)
        markDownloaded(item.userId, chapterId, pageCount)
        staging.deleteRecursively()
        logger.info { "chapter $chapterId of manga $mangaId saved as ${cbz.path} ($pageCount pages)" }
        return size
    }

    override fun discard(item: DownloadItem) {
        stagingDir(item.itemId).deleteRecursively()
    }

    private suspend fun fetch(
        mangaId: Int,
        chapterId: Int,
        index: Int,
        staging: File,
        name: String,
        source: HttpSource,
        page: Page,
    ): File {
        if (page.imageUrl == null) {
            // upstream's getPageImage: a page with only a page URL gets its image URL first
            val imageUrl = UpstreamPage.getTrueImageUrl(page, source)
            dbTransaction {
                PageTable.update({ (PageTable.chapter eq chapterId) and (PageTable.index eq index) }) { it[PageTable.imageUrl] = imageUrl }
            }
        }
        PageImages.get(mangaId, chapterId, index, staging.path, name, source, page, counted = false).first.close()
        return PageCache.cached(staging.path, name)
            ?: throw MadoyomiException(ErrorCode.INTERNAL, "Page ${index + 1} was fetched but isn't in ${staging.path}")
    }

    /** Upstream's downloader's marks: the chapter is downloaded (its page count that of the CBZ) and so it is for the user. */
    private fun markDownloaded(
        userId: Int,
        chapterId: Int,
        pageCount: Int,
    ) {
        dbTransaction {
            ChapterTable.update({ ChapterTable.id eq chapterId }) {
                it[isDownloaded] = true
                it[ChapterTable.pageCount] = pageCount
            }
            ChapterUserTable.upsert(ChapterUserTable.chapter, ChapterUserTable.user) {
                it[user] = userId
                it[chapter] = chapterId
                it[isDownloaded] = true
                it[isDownloadRequested] = true
            }
        }
    }
}

/** The manga side of the queue: which chapters to queue, deleting downloaded ones, and the automatic downloads. */
object MangaDownloads {
    private val logger = KotlinLogging.logger {}

    /**
     * Queues [chapterIds] for [userId] in reading order (by manga, then the source's order). Chapters already
     * downloaded are only marked so for the user (upstream's rule); unknown ids and the Local source's chapters (local
     * files already) are left out. Returns how many were queued.
     */
    fun enqueue(
        userId: Int,
        chapterIds: List<Int>,
    ): Int {
        if (chapterIds.isEmpty()) return 0
        val rows =
            dbTransaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where { ChapterTable.id inList chapterIds.distinct() }
                    .orderBy(ChapterTable.manga to SortOrder.ASC, ChapterTable.sourceOrder to SortOrder.ASC)
                    .toList()
            }
        val (downloaded, rest) = rows.partition { it[ChapterTable.isDownloaded] }
        if (downloaded.isNotEmpty()) {
            dbTransaction {
                downloaded.forEach { row ->
                    ChapterUserTable.upsert(ChapterUserTable.chapter, ChapterUserTable.user) {
                        it[user] = userId
                        it[chapter] = row[ChapterTable.id]
                        it[isDownloaded] = true
                        it[isDownloadRequested] = true
                    }
                }
            }
        }
        val items =
            rest
                .filter { it[MangaTable.sourceReference] != LocalSource.ID }
                .map {
                    DownloadQueue.NewItem(
                        kind = ExtensionKind.MANGA,
                        entryId = it[ChapterTable.manga].value,
                        itemId = it[ChapterTable.id].value,
                        sourceId = it[MangaTable.sourceReference],
                        userId = userId,
                    )
                }
        return DownloadQueue.enqueue(items)
    }

    /**
     * Deletes the downloads of [chapterIds] for [userId]: their unfinished queue items are cancelled, the CBZs deleted
     * through upstream's `Chapter.deleteDownloadedChapters` (which clears the marks), and their rows left the completed
     * list. Returns how many chapters were downloaded or queued.
     */
    suspend fun delete(
        userId: Int,
        chapterIds: List<Int>,
    ): Int {
        if (chapterIds.isEmpty()) return 0
        val ids = chapterIds.distinct()
        val cancelled = DownloadQueue.cancelItems(ExtensionKind.MANGA, ids)
        val downloaded =
            dbTransaction {
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.id inList ids) and (ChapterTable.isDownloaded eq true) }
                    .map { it[ChapterTable.id].value }
            }
        Chapter.deleteDownloadedChapters(userId, ids)
        DownloadQueue.forgetDone(ExtensionKind.MANGA, ids)
        if (downloaded.isNotEmpty()) logger.info { "deleted ${downloaded.size} downloaded chapters" }
        return cancelled + downloaded.size
    }

    /**
     * After a library update check: queues the new chapters of [entry] when the setting is on and the entry is in a
     * category with Downloads on (upstream's `includeInDownload` rule, `Manga.isInIncludedDownloadCategory`).
     */
    fun autoDownload(
        entry: LibraryUpdater.Entry,
        newChapterIds: List<Int>,
    ) {
        if (entry.kind != ExtensionKind.MANGA || newChapterIds.isEmpty()) return
        if (!DownloadSettingsStore.current().autoDownload) return
        if (!Manga.isInIncludedDownloadCategory(entry.userId, mangaId = entry.id)) return
        val queued = enqueue(entry.userId, newChapterIds)
        if (queued > 0) logger.info { "auto-download: $queued new chapters of '${entry.title}' queued" }
    }
}
