package madoyomi.core.reader

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import okhttp3.Response
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What [PageCache] calls for a page that isn't cached: the source's answer, `attempt` 0, then once more (`attempt` 1,
 * with the first failure as `previous`) when [retryable] accepts that failure.
 */
interface PageFetcher {
    suspend fun fetch(
        attempt: Int,
        previous: Throwable?,
    ): Response

    fun retryable(error: Throwable): Boolean = PageCache.isTransient(error)
}

/** What the page cache holds: [bytes] on disk (temporary files included), [files] cached pages, its cap and folder. */
data class PageCacheUsage(
    val bytes: Long,
    val files: Int,
    val limitBytes: Long,
    val path: String,
)

/**
 * The chapter-page cache (Phase 5 steps 2 and 4): upstream's
 * `<data dir>/cache/manga/<source>/<manga title>/<scanlator_><chapter name>/NNN.<ext>` (`getChapterCachePath`, in the
 * data dir since the Phase 3 exit run), with what upstream's `ImageResponse` lacks:
 * - **one fetch per page at a time**: concurrent requests for a page (the reader and a prefetch, two `<img>`s) wait for
 *   the same download and share its result or its error, instead of each fetching and writing the same `.tmp` file;
 *   the download runs in its own scope, so a caller that goes away doesn't cancel it for the others;
 * - **one retry** for a failure the fetcher accepts ([PageFetcher.retryable]; by default [isTransient]);
 * - **clear errors**: a source's HTTP error or I/O failure is `SOURCE_FAILED` (424) with `httpCode` and `retried`, not
 *   upstream's `Exception("request error! …")`, so the reader can offer "tap to retry";
 * - **a size cap with LRU eviction** ([ReaderSettings.pageCacheLimitMb], 1 GiB by default, the user's decision): a hit
 *   sets the file's modification time, which orders the eviction; once the cache is over its cap, the oldest files go
 *   until it is at [TRIM_TO] of it. Files used in the last [KEEP_RECENT_MS] stay even over the cap: they are the
 *   chapter being read and its prefetch, and upstream's downloader stages a chapter's pages here (Phase 6).
 *
 * The size is counted once by a scan, then kept up to date as pages are added; it may drift between scans (the
 * upstream `clearCachedImages` mutation, files removed by hand), and the next scan corrects it.
 */
object PageCache {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    /** Evict down to this share of the cap, so a cache at its cap doesn't scan after every page. */
    const val TRIM_TO = 0.9

    /** Files used this recently are never evicted. */
    const val KEEP_RECENT_MS = 5 * 60_000L

    private const val MAX_ROUNDS = 3

    /** Tests: another folder than the data dir's. */
    @Volatile
    var rootOverride: File? = null

    /** Tests: the clock of the LRU order and of [KEEP_RECENT_MS]. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** The wait before the retry. */
    @Volatile
    var retryDelayMs: Long = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = ConcurrentHashMap<String, Deferred<Unit>>()

    private val sizeLock = Any()
    private val scanLock = Any()
    private var knownBytes = -1L
    private val trimming = AtomicBoolean(false)

    @Volatile
    private var trimJob: Job? = null

    fun root(): File = (rootOverride ?: File(applicationDirs.tempMangaCacheRoot)).absoluteFile

    /** Tests: a cap in bytes instead of the settings' (whose minimum is 256 MiB). */
    @Volatile
    var limitOverride: Long? = null

    fun limitBytes(): Long = limitOverride ?: ReaderSettingsStore.current().pageCacheLimitBytes

    /**
     * The image `<saveDir>/<fileName>.<ext>` and its MIME type: from the cache, else from [fetcher] (saved first).
     * Throws the fetch's error ([MadoyomiException] `SOURCE_FAILED` for the source's failures) to every caller that
     * waited for it.
     */
    suspend fun image(
        saveDir: String,
        fileName: String,
        fetcher: PageFetcher,
    ): Pair<InputStream, String> {
        repeat(MAX_ROUNDS) {
            open(saveDir, fileName)?.let { return it }
            val key = File(saveDir, fileName).absolutePath
            val flight =
                flights.computeIfAbsent(key) { k ->
                    scope
                        .async(start = CoroutineStart.LAZY) { download(saveDir, fileName, fetcher) }
                        .also { d -> d.invokeOnCompletion { flights.remove(k, d) } }
                }
            flight.start()
            flight.await()
        }
        return open(saveDir, fileName)
            ?: throw MadoyomiException(ErrorCode.INTERNAL, "The page $fileName was fetched but isn't in the cache")
    }

    /** Whether `<saveDir>/<fileName>.<ext>` is cached (without using it). */
    fun contains(
        saveDir: String,
        fileName: String,
    ): Boolean = cachedFile(saveDir, fileName) != null

    /** Whether a fetch for that page is running. */
    fun fetching(
        saveDir: String,
        fileName: String,
    ): Boolean = flights.containsKey(File(saveDir, fileName).absolutePath)

    /**
     * A failure worth one more try: a timeout or a dropped connection, HTTP 408, 425, 429 or 5xx. Not a Cloudflare
     * challenge, which another try won't pass, and not another HTTP error.
     */
    fun isTransient(error: Throwable): Boolean {
        val e = ErrorClassifier.unwrap(error)
        if (e is HttpException) return e.code == 408 || e.code == 425 || e.code == 429 || e.code >= 500
        if (e is MadoyomiException) return false
        val cloudflare =
            generateSequence(e) { it.cause }.take(16).any { it is IOException && it.message == ErrorClassifier.CLOUDFLARE_BLOCKED }
        return e is IOException && !cloudflare
    }

    /** The HTTP status of [error], if it is one. */
    fun httpCode(error: Throwable): Int? = (ErrorClassifier.unwrap(error) as? HttpException)?.code

    private fun cachedFile(
        saveDir: String,
        fileName: String,
    ): File? =
        File(saveDir).listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("$fileName.") && !it.name.endsWith(".tmp")
        }

    private fun open(
        saveDir: String,
        fileName: String,
    ): Pair<InputStream, String>? {
        val file = cachedFile(saveDir, fileName) ?: return null
        file.setLastModified(clock())
        return try {
            ImageResponse.getCachedImageResponse(file.path, file.path.substringBeforeLast('.'))
        } catch (_: FileNotFoundException) {
            null // evicted or cleared since it was listed
        }
    }

    private suspend fun download(
        saveDir: String,
        fileName: String,
        fetcher: PageFetcher,
    ) {
        if (cachedFile(saveDir, fileName) != null) return
        File(saveDir).mkdirs()
        val base = File(saveDir, fileName).path
        var previous: Throwable? = null
        for (attempt in 0..1) {
            try {
                fetcher.fetch(attempt, previous).use { response ->
                    if (response.code != 200) throw HttpException(response.code)
                    val (saved, _) = ImageResponse.saveImage(base, response.body.byteStream(), response.header("Content-Type"))
                    File(saved).setLastModified(clock())
                    added(File(saved).length())
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                File("$base.tmp").delete()
                previous = e
                if (attempt == 0 && fetcher.retryable(e)) {
                    logger.info { "page ${File(base).name} of ${File(saveDir).name} failed (${describe(e)}); trying once more" }
                    delay(retryDelayMs)
                } else {
                    throw failure(e, retried = attempt > 0)
                }
            }
        }
    }

    /** A source's HTTP error or I/O failure as `SOURCE_FAILED` with `httpCode` and `retried`; anything else as it is. */
    private fun failure(
        e: Throwable,
        retried: Boolean,
    ): Throwable {
        val classified = ErrorClassifier.classify(e)
        val unwrapped = ErrorClassifier.unwrap(e)
        if (classified.code != ErrorCode.SOURCE_FAILED && unwrapped !is IOException) return e
        val code = httpCode(e)
        val message = if (code != null) "The source answered HTTP $code for this page" else classified.message
        val details = classified.details + mapOf("retried" to retried) + (code?.let { mapOf("httpCode" to it) } ?: emptyMap())
        return MadoyomiException(ErrorCode.SOURCE_FAILED, message, details, e)
    }

    private fun describe(e: Throwable): String =
        httpCode(e)?.let { "HTTP $it" } ?: ErrorClassifier.unwrap(e).let { "${it.javaClass.simpleName}: ${it.message}" }

    private fun added(bytes: Long) {
        val scan =
            synchronized(sizeLock) {
                if (knownBytes < 0) {
                    true
                } else {
                    knownBytes += bytes
                    knownBytes > limitBytes()
                }
            }
        if (scan) trimSoon()
    }

    /** Starts a [trim] in the background, unless one is running. */
    fun trimSoon(): Job? {
        if (!trimming.compareAndSet(false, true)) return null
        return scope
            .launch {
                try {
                    trim()
                } catch (e: Exception) {
                    logger.warn(e) { "could not trim the page cache" }
                } finally {
                    trimming.set(false)
                }
            }.also { trimJob = it }
    }

    /** Tests: waits for the last [trimSoon]. */
    suspend fun awaitTrim() {
        trimJob?.join()
    }

    /** Scans the cache and, when it is over its cap, removes the least recently used files down to [TRIM_TO] of it. */
    fun trim(): PageCacheUsage =
        synchronized(scanLock) {
            val root = root()
            val limit = limitBytes()
            val entries = scan(root)
            var total = entries.sumOf { it.size }
            var pages = entries.count { !it.file.name.endsWith(".tmp") }
            if (total > limit) {
                val target = (limit * TRIM_TO).toLong()
                val keepAfter = clock() - KEEP_RECENT_MS
                var removed = 0
                var freed = 0L
                for (e in entries.sortedBy { it.modified }) {
                    if (total <= target || e.modified >= keepAfter) break
                    if (e.file.delete()) {
                        total -= e.size
                        freed += e.size
                        removed++
                        if (!e.file.name.endsWith(".tmp")) pages--
                    }
                }
                removeEmptyDirs(root, keepAfter)
                logger.info {
                    "page cache over its cap of ${mib(limit)}: removed $removed files (${mib(freed)}), ${mib(total)} left" +
                        if (total > limit) " (the rest was used in the last ${KEEP_RECENT_MS / 60_000} minutes)" else ""
                }
            }
            synchronized(sizeLock) { knownBytes = total }
            PageCacheUsage(total, pages, limit, root.path)
        }

    /** What the cache holds now (a scan). */
    fun usage(): PageCacheUsage =
        synchronized(scanLock) {
            val root = root()
            val entries = scan(root)
            val total = entries.sumOf { it.size }
            synchronized(sizeLock) { knownBytes = total }
            PageCacheUsage(total, entries.count { !it.file.name.endsWith(".tmp") }, limitBytes(), root.path)
        }

    /** Removes every cached page (a file that is being read stays until the next trim) and returns what is left. */
    fun clear(): PageCacheUsage {
        synchronized(scanLock) {
            root().listFiles()?.forEach { it.deleteRecursively() }
            logger.info { "page cache cleared" }
        }
        return usage()
    }

    /** Tests: forget the counted size, so the next page added scans again. */
    fun forgetSize() {
        synchronized(sizeLock) { knownBytes = -1 }
    }

    private class Entry(
        val file: File,
        val size: Long,
        val modified: Long,
    )

    private fun scan(root: File): List<Entry> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root
                .walkTopDown()
                .filter { it.isFile }
                .map { Entry(it, it.length(), it.lastModified()) }
                .toList()
        }

    private fun removeEmptyDirs(
        root: File,
        keepAfter: Long,
    ) {
        root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { dir ->
            if (dir.list()?.isEmpty() == true && dir.lastModified() < keepAfter) dir.delete()
        }
    }

    private fun mib(bytes: Long): String = "%.1f MiB".format(bytes / ReaderSettings.MIB.toDouble())
}
