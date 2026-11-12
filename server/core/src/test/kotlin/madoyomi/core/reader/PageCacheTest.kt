package madoyomi.core.reader

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chapter-page cache (Phase 5 Stage 1): hits and misses, one fetch per page at a time, the retry, the errors, and
 * the size cap with LRU eviction. A scratch folder, a fake clock and scripted fetchers; no source, no database.
 */
class PageCacheTest {
    private val root: File = Files.createTempDirectory("page-cache").toFile()
    private val settings: File = File(root.parentFile, "${root.name}-reader-settings.json")
    private var now = 1_800_000_000_000L
    private val minute = 60_000L

    /** A PNG-looking body (the type is found from the bytes' signature, and from Content-Type). */
    private fun image(
        size: Int = 100,
        fill: Byte = 1,
    ): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(size - 8) { fill }

    private fun response(
        code: Int,
        body: ByteArray = image(),
        type: String = "image/png",
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("https://images.example/page").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("status $code")
            .header("Content-Type", type)
            .body(body.toResponseBody(type.toMediaType()))
            .build()

    /** Answers with [answers] in turn (the last one again after that), recording each call's attempt and previous error. */
    private inner class Scripted(
        vararg val answers: () -> Response,
    ) : PageFetcher {
        val calls = Collections.synchronizedList(mutableListOf<Pair<Int, Throwable?>>())
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(
            attempt: Int,
            previous: Throwable?,
        ): Response {
            calls += attempt to previous
            gate?.await()
            return answers[minOf(calls.size - 1, answers.size - 1)]()
        }
    }

    private fun dir(chapter: Int = 1) = File(root, "1/$chapter").path

    private fun read(
        saveDir: String,
        name: String,
        fetcher: PageFetcher,
    ): Pair<ByteArray, String> =
        runBlocking {
            val (stream, mime) = PageCache.image(saveDir, name, fetcher)
            stream.use { it.readBytes() } to mime
        }

    @BeforeTest
    fun setUp() {
        PageCache.rootOverride = root
        PageCache.clock = { now }
        PageCache.retryDelayMs = 1
        PageCache.limitOverride = null
        PageCache.forgetSize()
        ReaderSettingsStore.fileOverride = settings
        ReaderSettingsStore.reload()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { PageCache.awaitTrim() }
        PageCache.rootOverride = null
        PageCache.clock = System::currentTimeMillis
        PageCache.retryDelayMs = 500
        PageCache.limitOverride = null
        PageCache.forgetSize()
        ReaderSettingsStore.fileOverride = null
        ReaderSettingsStore.reload()
        root.deleteRecursively()
        settings.delete()
    }

    @Test
    fun `a miss is fetched and saved, then served from the cache`() {
        val body = image(500)
        val fetcher = Scripted({ response(200, body) })

        val (first, mime) = read(dir(), "001", fetcher)
        assertContentEquals(body, first)
        assertEquals("image/png", mime)
        assertTrue(File(dir(), "001.png").isFile)
        assertTrue(PageCache.contains(dir(), "001"))

        val (again, mime2) = read(dir(), "001", fetcher)
        assertContentEquals(body, again)
        assertEquals("image/png", mime2)
        assertEquals(1, fetcher.calls.size, "the second read is a hit")
    }

    @Test
    fun `concurrent requests for a page share one fetch`() {
        val fetcher = Scripted({ response(200, image(300)) }).apply { gate = CompletableDeferred() }
        val results =
            runBlocking {
                val readers = List(8) { async { PageCache.image(dir(), "002", fetcher).first.use { it.readBytes() } } }
                withTimeout(5_000) { while (fetcher.calls.isEmpty() || !PageCache.fetching(dir(), "002")) delay(5) }
                delay(50)
                fetcher.gate!!.complete(Unit)
                readers.awaitAll()
            }
        assertEquals(1, fetcher.calls.size)
        results.forEach { assertContentEquals(image(300), it) }
        assertFalse(PageCache.fetching(dir(), "002"))
        assertEquals(listOf("002.png"), File(dir()).list()!!.toList(), "no .tmp left")
    }

    @Test
    fun `the requests that waited get the fetch's error, and a 404 isn't retried`() {
        val fetcher = Scripted({ response(404) }).apply { gate = CompletableDeferred() }
        val errors =
            runBlocking {
                val readers = List(4) { async { runCatching { PageCache.image(dir(), "003", fetcher) }.exceptionOrNull() } }
                withTimeout(5_000) { while (fetcher.calls.isEmpty()) delay(5) }
                delay(50)
                fetcher.gate!!.complete(Unit)
                readers.awaitAll()
            }
        assertEquals(1, fetcher.calls.size)
        errors.forEach { e ->
            val m = assertIs<MadoyomiException>(e)
            assertEquals(ErrorCode.SOURCE_FAILED, m.code)
            assertEquals(404, m.details["httpCode"])
            assertEquals(false, m.details["retried"])
            assertEquals("The source answered HTTP 404 for this page", m.message)
        }
        assertFalse(PageCache.contains(dir(), "003"))
        assertTrue(File(dir()).list()!!.isEmpty(), "nothing saved")
    }

    @Test
    fun `a 503 is tried once more with the first failure, and the second answer counts`() {
        val fetcher = Scripted({ response(503) }, { response(200, image(200)) })
        val (bytes, _) = read(dir(), "004", fetcher)
        assertContentEquals(image(200), bytes)
        assertEquals(listOf(0, 1), fetcher.calls.map { it.first })
        assertNull(fetcher.calls[0].second)
        assertEquals(503, (fetcher.calls[1].second as HttpException).code)
    }

    @Test
    fun `two failures are SOURCE_FAILED with retried, for an HTTP error and for a timeout`() {
        val http = Scripted({ response(502) })
        val e1 = assertFailsWith<MadoyomiException> { read(dir(), "005", http) }
        assertEquals(ErrorCode.SOURCE_FAILED, e1.code)
        assertEquals(502, e1.details["httpCode"])
        assertEquals(true, e1.details["retried"])
        assertEquals(2, http.calls.size)

        val timeout = Scripted({ throw SocketTimeoutException("read timed out") })
        val e2 = assertFailsWith<MadoyomiException> { read(dir(), "006", timeout) }
        assertEquals(ErrorCode.SOURCE_FAILED, e2.code)
        assertEquals("read timed out", e2.message)
        assertEquals(true, e2.details["retried"])
        assertNull(e2.details["httpCode"])
        assertEquals(2, timeout.calls.size)
    }

    @Test
    fun `a Cloudflare challenge isn't retried, and keeps its reason`() {
        val fetcher = Scripted({ throw IOException(ErrorClassifier.CLOUDFLARE_BLOCKED) })
        val e = assertFailsWith<MadoyomiException> { read(dir(), "007", fetcher) }
        assertEquals(ErrorCode.SOURCE_FAILED, e.code)
        assertEquals(ErrorClassifier.REASON_CLOUDFLARE, e.details["reason"])
        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun `a fetcher can make more failures retryable`() {
        val fetcher =
            object : PageFetcher {
                var calls = 0

                override suspend fun fetch(
                    attempt: Int,
                    previous: Throwable?,
                ): Response = if (calls++ == 0) response(403) else response(200)

                override fun retryable(error: Throwable) = PageCache.httpCode(error) == 403
            }
        read(dir(), "008", fetcher)
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `a caller that goes away doesn't stop the fetch for the others`() {
        val fetcher = Scripted({ response(200, image(150)) }).apply { gate = CompletableDeferred() }
        runBlocking {
            val leaving = async { PageCache.image(dir(), "009", fetcher) }
            withTimeout(5_000) { while (fetcher.calls.isEmpty()) delay(5) }
            leaving.cancel()
            val staying = async { PageCache.image(dir(), "009", fetcher).first.use { it.readBytes() } }
            delay(50)
            fetcher.gate!!.complete(Unit)
            assertContentEquals(image(150), staying.await())
        }
        assertEquals(1, fetcher.calls.size)
        assertTrue(PageCache.contains(dir(), "009"))
    }

    @Test
    fun `a temporary file left by a crash is not a hit, and is replaced`() {
        File(dir()).mkdirs()
        File(dir(), "010.tmp").writeBytes(image(40))
        val fetcher = Scripted({ response(200, image(120)) })
        assertFalse(PageCache.contains(dir(), "010"))
        assertContentEquals(image(120), read(dir(), "010", fetcher).first)
        assertEquals(listOf("010.png"), File(dir()).list()!!.toList())
    }

    /** A cached page [ageMinutes] old (its modification time is the LRU order). */
    private fun cached(
        chapter: Int,
        name: String,
        size: Int,
        ageMinutes: Long,
    ): File =
        File(dir(chapter), "$name.png").apply {
            parentFile.mkdirs()
            writeBytes(image(size))
            setLastModified(now - ageMinutes * minute)
        }

    @Test
    fun `over the cap the least recently used pages go, down to 90 percent of it, and empty folders with them`() {
        PageCache.limitOverride = 1000
        val old1 = cached(1, "001", 200, ageMinutes = 60)
        val old2 = cached(1, "002", 200, ageMinutes = 50)
        val mid = cached(2, "001", 200, ageMinutes = 40)
        val new = cached(2, "002", 200, ageMinutes = 30)
        File(dir(1)).setLastModified(now - 60 * minute)

        // a page added past the cap (1200 bytes with this one) starts a trim, down to 900
        read(dir(3), "001", Scripted({ response(200, image(400)) }))
        runBlocking { PageCache.awaitTrim() }

        assertFalse(old1.exists())
        assertFalse(old2.exists())
        assertFalse(File(dir(1)).exists(), "the emptied chapter folder is gone")
        assertTrue(mid.exists() && new.exists())
        assertTrue(File(dir(3), "001.png").exists())
        val usage = PageCache.usage()
        assertEquals(800, usage.bytes)
        assertEquals(3, usage.files)
        assertEquals(1000, usage.limitBytes)
    }

    @Test
    fun `a hit makes a page recent, so it outlives older ones`() {
        PageCache.limitOverride = 500
        val a = cached(1, "001", 200, ageMinutes = 60)
        val b = cached(1, "002", 200, ageMinutes = 50)
        val c = cached(1, "003", 200, ageMinutes = 40)
        // reading the oldest one now: it becomes the newest
        read(dir(1), "001", Scripted({ error("not fetched") }))
        assertEquals(now, a.lastModified())

        assertEquals(400, PageCache.trim().bytes)
        assertTrue(a.exists())
        assertFalse(b.exists())
        assertTrue(c.exists())
    }

    @Test
    fun `pages used in the last 5 minutes stay even over the cap`() {
        PageCache.limitOverride = 300
        val old = cached(1, "001", 200, ageMinutes = 10)
        val recent1 = cached(1, "002", 200, ageMinutes = 4)
        val recent2 = cached(1, "003", 200, ageMinutes = 0)
        val usage = PageCache.trim()
        assertFalse(old.exists())
        assertTrue(recent1.exists() && recent2.exists())
        assertEquals(400, usage.bytes, "over the cap, but nothing older is left")
    }

    @Test
    fun `a lower cap applies at the next trim, and the settings' cap is used without an override`() {
        ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = 256))
        assertEquals(256L * 1024 * 1024, PageCache.limitBytes())
        assertEquals(256L * 1024 * 1024, PageCache.usage().limitBytes)
        cached(1, "001", 200, ageMinutes = 60)
        assertEquals(200, PageCache.trim().bytes, "under 256 MiB: nothing goes")
        PageCache.limitOverride = 100
        assertEquals(0, PageCache.trim().bytes)
    }

    @Test
    fun `usage counts pages and bytes, temporary files in the bytes only, and clear removes everything`() {
        cached(1, "001", 200, ageMinutes = 1)
        cached(2, "001", 300, ageMinutes = 1)
        File(dir(2), "002.tmp").writeBytes(ByteArray(50))
        val usage = PageCache.usage()
        assertEquals(550, usage.bytes)
        assertEquals(2, usage.files)
        assertEquals(root.absolutePath, usage.path)

        val after = PageCache.clear()
        assertEquals(0, after.bytes)
        assertEquals(0, after.files)
        assertTrue(root.list()!!.isEmpty())
        assertFalse(PageCache.contains(dir(1), "001"))
    }

    @Test
    fun `the counted size follows the pages added, so the page that passes the cap trims`() {
        PageCache.limitOverride = 10_000
        repeat(3) { i -> read(dir(), "1%02d".format(i), Scripted({ response(200, image(1000)) })) }
        runBlocking { PageCache.awaitTrim() }
        assertEquals(3000, PageCache.usage().bytes)

        // ten minutes later, with a cap of 2500: the fourth page makes 4000 counted bytes, so the oldest go
        now += 10 * minute
        PageCache.limitOverride = 2500
        read(dir(), "103", Scripted({ response(200, image(1000)) }))
        runBlocking { PageCache.awaitTrim() }
        val usage = PageCache.usage()
        assertEquals(2000, usage.bytes)
        assertTrue(PageCache.contains(dir(), "103"), "the new page stays")
    }
}
