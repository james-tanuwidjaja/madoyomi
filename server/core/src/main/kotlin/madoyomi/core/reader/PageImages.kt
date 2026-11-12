package madoyomi.core.reader

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import okhttp3.Response
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.chapter.refreshChapterPageList
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import suwayomi.tachidesk.manga.impl.Page as UpstreamPage

/** What [PageImages.prefetch] did: the pages it [queued] for fetching and the ones already [cached]. */
data class PrefetchResult(
    val chapterId: Int,
    val pageCount: Int,
    val queued: List<Int>,
    val cached: List<Int>,
)

/**
 * A chapter's page images from its source, through [PageCache] (Phase 5 steps 1–4). Upstream's `Page.getPageImage`
 * resolves the page and its cache folder and calls [get] (a Madoyomi edit, server/UPSTREAM.md "Manga reader"); the
 * request itself is upstream's: `HttpSource.getImage`, with the source's client (its headers, cookie jar and rate
 * limits).
 *
 * The retry: a transient failure is tried once more as it was; an HTTP 401, 403, 404 or 410 first gets the page's URL
 * anew, because a source's image URLs may expire (signed or tokened image hosts), and upstream keeps the page list
 * from the chapter's last open: by the page's own URL when it has one (`getImageUrl`), else by the chapter's page list
 * fetched again, once for all of a chapter's pages that fail together.
 */
object PageImages {
    private val logger = KotlinLogging.logger {}

    const val MAX_PREFETCH = 20

    /** Prefetches use at most this many requests at a time, so the pages the reader shows aren't held up. */
    const val PREFETCH_CONCURRENCY = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchSlots = Semaphore(PREFETCH_CONCURRENCY)
    private val prefetchJobs = ConcurrentHashMap<Int, Job>()
    private val pageListRefreshedAt = ConcurrentHashMap<Int, Long>()

    /** The image of [page] (page [index] of chapter [chapterId]) from the cache folder [saveDir], else from [source]. */
    suspend fun get(
        mangaId: Int,
        chapterId: Int,
        index: Int,
        saveDir: String,
        fileName: String,
        source: HttpSource,
        page: Page,
    ): Pair<InputStream, String> = PageCache.image(saveDir, fileName, SourcePageFetcher(mangaId, chapterId, index, source, page))

    /** An image URL that may have expired: HTTP 401, 403, 404 or 410. */
    fun isExpired(error: Throwable): Boolean = PageCache.httpCode(error) in EXPIRED

    private val EXPIRED = setOf(401, 403, 404, 410)

    private class SourcePageFetcher(
        val mangaId: Int,
        val chapterId: Int,
        val index: Int,
        val source: HttpSource,
        var page: Page,
    ) : PageFetcher {
        private val startedAt = System.currentTimeMillis()

        override suspend fun fetch(
            attempt: Int,
            previous: Throwable?,
        ): Response {
            if (attempt > 0 && previous != null && isExpired(previous)) {
                page = freshPage(mangaId, chapterId, index, source, page, startedAt)
            }
            return source.getImage(page)
        }

        override fun retryable(error: Throwable): Boolean = PageCache.isTransient(error) || isExpired(error)
    }

    private suspend fun freshPage(
        mangaId: Int,
        chapterId: Int,
        index: Int,
        source: HttpSource,
        old: Page,
        since: Long,
    ): Page {
        if (old.url.isNotBlank()) {
            val fresh = Page(old.index, old.url, null)
            val imageUrl = UpstreamPage.getTrueImageUrl(fresh, source)
            transaction {
                PageTable.update({ (PageTable.chapter eq chapterId) and (PageTable.index eq index) }) { it[PageTable.imageUrl] = imageUrl }
            }
            logger.info { "page ${index + 1} of chapter $chapterId: its image URL was got again" }
            return fresh
        }
        if ((pageListRefreshedAt[chapterId] ?: 0L) < since) {
            refreshChapterPageList(mangaId, chapterId)
            pageListRefreshedAt[chapterId] = System.currentTimeMillis()
            logger.info { "chapter $chapterId: its page list was got again for an expired image URL" }
        }
        return pageRow(chapterId, index) ?: old
    }

    private fun pageRow(
        chapterId: Int,
        index: Int,
    ): Page? =
        transaction {
            PageTable
                .selectAll()
                .where { PageTable.chapter eq chapterId }
                .orderBy(PageTable.index to SortOrder.ASC)
                .limit(1)
                .offset(index.toLong())
                .firstOrNull()
                ?.let { Page(it[PageTable.index], it[PageTable.url], it[PageTable.imageUrl]) }
        }

    /**
     * Warms the cache with pages [from] until [from] + [count] of chapter [chapterId] (step 3), in the background,
     * at most [PREFETCH_CONCURRENCY] requests at a time across chapters. The reader asks for the next pages of the
     * chapter it shows, and for the first pages of the next chapter near its end. A chapter whose page list wasn't
     * fetched yet gets it first (that one request is made before this returns). A new prefetch of a chapter replaces
     * the one still queued for it. Downloaded chapters and the Local source need none.
     */
    suspend fun prefetch(
        chapterId: Int,
        from: Int,
        count: Int,
    ): PrefetchResult {
        if (count !in 1..MAX_PREFETCH) {
            throw MadoyomiException(ErrorCode.BAD_REQUEST, "count: must be 1 to $MAX_PREFETCH", mapOf("field" to "count", "value" to count))
        }
        if (from < 0) throw MadoyomiException(ErrorCode.BAD_REQUEST, "from: must be 0 or more", mapOf("field" to "from", "value" to from))
        val chapter =
            transaction { ChapterTable.selectAll().where { ChapterTable.id eq chapterId }.firstOrNull() }
                ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "There is no chapter $chapterId", mapOf("chapterId" to chapterId))
        val mangaId = chapter[ChapterTable.manga].value
        val sourceId = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()[MangaTable.sourceReference] }
        if (sourceId == LocalSource.ID || chapter[ChapterTable.isDownloaded]) {
            return PrefetchResult(chapterId, chapter[ChapterTable.pageCount], emptyList(), emptyList())
        }
        var pageCount = chapter[ChapterTable.pageCount]
        val rows = transaction { PageTable.selectAll().where { PageTable.chapter eq chapterId }.count() }
        if (pageCount <= 0 || rows == 0L) {
            pageCount = refreshChapterPageList(mangaId, chapterId)
            pageListRefreshedAt[chapterId] = System.currentTimeMillis()
        }
        val saveDir = getChapterCachePath(mangaId, chapterId)
        val (cached, queued) =
            (from until minOf(from + count, pageCount)).partition {
                PageCache.contains(saveDir, UpstreamPage.getPageName(it, pageCount))
            }
        prefetchJobs.remove(chapterId)?.cancel()
        if (queued.isNotEmpty()) {
            val job =
                scope.launch {
                    queued.forEach { index ->
                        launch { prefetchSlots.withPermit { prefetchPage(mangaId, chapterId, index) } }
                    }
                }
            prefetchJobs[chapterId] = job
            job.invokeOnCompletion { prefetchJobs.remove(chapterId, job) }
        }
        return PrefetchResult(chapterId, pageCount, queued, cached)
    }

    private suspend fun prefetchPage(
        mangaId: Int,
        chapterId: Int,
        index: Int,
    ) {
        try {
            UpstreamPage.getPageImage(mangaId = mangaId, chapterId = chapterId, index = index).first.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "prefetch of page ${index + 1} of chapter $chapterId failed: ${e.message}" }
        }
    }

    /** Tests: waits for chapter [chapterId]'s prefetch. */
    suspend fun awaitPrefetch(chapterId: Int) {
        prefetchJobs[chapterId]?.join()
    }
}
