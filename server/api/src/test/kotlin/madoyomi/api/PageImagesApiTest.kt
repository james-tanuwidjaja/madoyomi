package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.javalin.Javalin
import io.javalin.json.JavalinJackson3
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import madoyomi.core.reader.PageCache
import madoyomi.core.reader.PageImages
import madoyomi.core.reader.ReaderSettingsStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.controller.MangaController
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.UserType
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chapter-page images (Phase 5 Stage 1): upstream's page route
 * (`GET /api/v1/manga/{mangaId}/chapter/{chapterIndex}/page/{index}`, on a bare Javalin with upstream's exception
 * handlers) through `PageImages`/`PageCache`, against a loopback image host that records what it was sent; the page
 * lists from upstream's `fetchChapterPages`; and the reader's GraphQL (`prefetchChapterPages`, `readerSettings`,
 * `setReaderSettings`, `pageCache`, `clearPageCache`).
 */
class PageImagesApiTest : ApiGraphQLTest() {
    private val png: ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private data class Seen(
        val path: String,
        val referer: String?,
        val cookie: String?,
    )

    private val requests = Collections.synchronizedList(mutableListOf<Seen>())

    /** Paths answered with this status, a number of times (the count goes down with each answer). */
    private val failures = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** Path prefixes answered 403 for as long as they are here: image URLs that expired. */
    private val expired: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val images: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val imageBase get() = "http://127.0.0.1:${images.address.port}"

    private lateinit var app: Javalin
    private val http = HttpClient.newHttpClient()
    private val json = jacksonObjectMapper()
    private lateinit var cacheRoot: File

    private inner class PagesSource(
        val pages: Int,
        /** Pages with a page URL whose image URL `getImageUrl` resolves, instead of image URLs in the list. */
        val byPageUrl: Boolean = false,
    ) : HttpSource() {
        override val name = "Fake pages ${if (byPageUrl) "by page URL" else "with image URLs"}"
        override val lang = "en"
        override val baseUrl get() = imageBase
        override val supportsLatest = false

        var listCalls = 0
        var resolves = 0

        /** The image URLs' version: each page list gives that version's URLs. */
        var version = 1

        override fun headersBuilder() = super.headersBuilder().add("Referer", "$imageBase/")

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            listCalls++
            return List(pages) { i ->
                if (byPageUrl) Page(i, url = "/p$i") else Page(i, imageUrl = "$imageBase/img/v$version/$i.png")
            }
        }

        override suspend fun getImageUrl(page: Page): String = "$imageBase/img/r${++resolves}${page.url}.png"
    }

    @BeforeAll
    fun start() {
        images.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                requests += Seen(path, it.requestHeaders.getFirst("Referer"), it.requestHeaders.getFirst("Cookie"))
                val failure = failures[path]
                when {
                    failure != null && failure.second > 0 -> {
                        failures[path] = failure.first to failure.second - 1
                        it.sendResponseHeaders(failure.first, -1)
                    }

                    expired.any(path::startsWith) -> {
                        it.sendResponseHeaders(403, -1)
                    }

                    else -> {
                        it.responseHeaders.add("Content-Type", "image/png")
                        it.sendResponseHeaders(200, png.size.toLong())
                        it.responseBody.write(png)
                    }
                }
            }
        }
        images.start()
        app =
            Javalin
                .create { config ->
                    config.jetty.host = "127.0.0.1"
                    config.jetty.port = 0
                    config.jsonMapper(JavalinJackson3())
                    config.routes.beforeMatched { it.attribute(JavalinSetup.Attribute.TachideskUser.name, UserType.Admin(1)) }
                    config.routes.get("/api/v1/manga/{mangaId}/chapter/{chapterIndex}/page/{index}", MangaController.pageRetrieve)
                    with(JavalinSetup) { config.routes.defineExceptionHandlers() }
                }.start()
    }

    @AfterAll
    fun stop() {
        app.stop()
        images.stop(0)
    }

    @BeforeEach
    fun scratchCache() {
        // the page cache is upstream's folder (the test data dir's), named by the source, title and chapter names,
        // which repeat across tests and runs: each test starts from an empty one
        cacheRoot = PageCache.root()
        PageCache.clear()
        PageCache.retryDelayMs = 1
        PageCache.forgetSize()
        ReaderSettingsStore.fileOverride = Files.createTempFile("reader-settings", ".json").toFile().also { it.delete() }
        ReaderSettingsStore.reload()
        requests.clear()
        failures.clear()
        expired.clear()
    }

    @AfterEach
    fun dropCache() {
        runBlocking { PageCache.awaitTrim() }
        PageCache.clear()
        PageCache.retryDelayMs = 500
        PageCache.forgetSize()
        ReaderSettingsStore.fileOverride?.delete()
        ReaderSettingsStore.fileOverride = null
        ReaderSettingsStore.reload()
    }

    /** A manga of [source] with chapters 1 to [chapters] (sourceOrder 1 to n), their page lists not fetched. */
    private fun manga(
        source: HttpSource,
        chapters: Int = 1,
    ): Pair<Int, List<Int>> {
        GetSource.registerSource(source.id to source)
        return dbTransaction {
            val mangaId =
                MangaTable
                    .insertAndGetId {
                        it[url] = "/manga"
                        it[title] = "Fake manga"
                        it[sourceReference] = source.id
                        it[memo] = JsonObject(emptyMap())
                    }.value
            mangaId to
                (1..chapters).map { order ->
                    ChapterTable
                        .insertAndGetId {
                            it[url] = "/chapter/$order"
                            it[name] = "Chapter $order"
                            it[sourceOrder] = order
                            it[manga] = mangaId
                            it[memo] = JsonObject(emptyMap())
                        }.value
                }
        }
    }

    /** Upstream's `fetchChapterPages`: the page routes of a chapter (its page list fetched from the source). */
    private fun pages(chapterId: Int): List<String> =
        graphql("mutation { fetchChapterPages(input: {chapterId: $chapterId}) { pages chapter { pageCount } } }")
            .assertNoErrors()
            .dataPath("fetchChapterPages", "pages")
            .let { (it as List<*>).map { p -> p as String } }

    private fun get(route: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${app.port()}$route")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun HttpResponse<ByteArray>.errorBody(): Map<String, Any?> = json.readValue(body())

    private fun prefetch(
        chapterId: Int,
        from: Int,
        count: Int,
    ) = graphql(
        "mutation { prefetchChapterPages(input: {chapterId: $chapterId, from: $from, count: $count}) " +
            "{ chapterId pageCount queued cached } }",
    )

    private fun cacheFiles(): Int = graphql("query { pageCache { files } }").assertNoErrors().dataPath("pageCache", "files") as Int

    @Test
    fun `a page comes from the source with its headers and its cookies, then from the cache`() {
        val source = PagesSource(3)
        val (_, chapters) = manga(source)
        source.client.cookieJar.saveFromResponse(
            imageBase.toHttpUrl(),
            listOf(Cookie.parse(imageBase.toHttpUrl(), "madoyomi_page=1; Path=/")!!),
        )
        val routes = pages(chapters[0])
        assertEquals(3, routes.size)

        val first = get(routes[0])
        assertEquals(200, first.statusCode())
        assertEquals("image/png", first.headers().firstValue("content-type").orElse(null))
        assertEquals("max-age=86400", first.headers().firstValue("cache-control").orElse(null))
        assertContentEquals(png, first.body())
        val seen = requests.single()
        assertEquals("/img/v1/0.png", seen.path)
        assertEquals("$imageBase/", seen.referer, "the source's Referer was sent")
        assertTrue(seen.cookie.orEmpty().contains("madoyomi_page=1"), "the cookie jar's cookie was sent: ${seen.cookie}")

        val cached = get(routes[0])
        assertEquals(200, cached.statusCode())
        assertContentEquals(png, cached.body())
        assertEquals(1, requests.size, "served from the cache")
        assertEquals(1, cacheFiles())
    }

    @Test
    fun `a source's HTTP error is SOURCE_FAILED with its status, a 5xx after one more try`() {
        val source = PagesSource(3)
        val (_, chapters) = manga(source)
        val routes = pages(chapters[0])

        failures["/img/v1/0.png"] = 503 to 2
        val failed = get(routes[0])
        assertEquals(424, failed.statusCode())
        val body = failed.errorBody()
        assertEquals("SOURCE_FAILED", body["code"])
        assertEquals("The source answered HTTP 503 for this page", body["message"])
        assertEquals(503, body.path("details", "httpCode"))
        assertEquals(true, body.path("details", "retried"))
        assertEquals(listOf("/img/v1/0.png", "/img/v1/0.png"), requests.map { it.path })

        requests.clear()
        failures["/img/v1/1.png"] = 502 to 1
        assertEquals(200, get(routes[1]).statusCode(), "the second try worked")
        assertEquals(2, requests.size)

        // a page the site doesn't have: 404 isn't transient, but it may be an expired URL, so the list is got again once
        requests.clear()
        failures["/img/v1/2.png"] = 404 to 5
        val missing = get(routes[2])
        assertEquals(424, missing.statusCode())
        assertEquals(404, missing.errorBody().path("details", "httpCode"))
        assertEquals(2, source.listCalls, "one new page list for the retry")
        assertEquals(1, cacheFiles(), "only page 1 of 3 is cached")
    }

    @Test
    fun `an expired image URL from the page list gets a new page list once for the chapter`() {
        val source = PagesSource(3)
        val (mangaId, chapters) = manga(source)
        val routes = pages(chapters[0])
        assertEquals(1, source.listCalls)

        // the list's URLs expire, and the site now gives version 2
        expired += "/img/v1/"
        source.version = 2
        val page0 = get(routes[0])
        assertEquals(200, page0.statusCode())
        assertEquals(2, source.listCalls)
        // the next page uses the new list at once
        assertEquals(200, get(routes[1]).statusCode())
        assertEquals(listOf("/img/v1/0.png", "/img/v2/0.png", "/img/v2/1.png"), requests.map { it.path })
        assertEquals(2, source.listCalls)
        val stored =
            dbTransaction {
                PageTable.selectAll().where { (PageTable.chapter eq chapters[0]) and (PageTable.index eq 2) }.single()[PageTable.imageUrl]
            }
        assertEquals("$imageBase/img/v2/2.png", stored)
        assertTrue(mangaId > 0)
    }

    @Test
    fun `an expired image URL got from the page's own URL is resolved again`() {
        val source = PagesSource(2, byPageUrl = true)
        val (_, chapters) = manga(source)
        val routes = pages(chapters[0])

        expired += "/img/r1/"
        assertEquals(200, get(routes[0]).statusCode())
        assertEquals(listOf("/img/r1/p0.png", "/img/r2/p0.png"), requests.map { it.path })
        assertEquals(1, source.listCalls, "no new page list")
        val stored =
            dbTransaction {
                PageTable.selectAll().where { (PageTable.chapter eq chapters[0]) and (PageTable.index eq 0) }.single()[PageTable.imageUrl]
            }
        assertEquals("$imageBase/img/r2/p0.png", stored)
    }

    @Test
    fun `a prefetch warms the next pages in the background, so reading them makes no request`() {
        val source = PagesSource(10)
        val (_, chapters) = manga(source, chapters = 2)
        val routes = pages(chapters[0])

        val r = prefetch(chapters[0], from = 2, count = 5).assertNoErrors().dataPath("prefetchChapterPages")
        assertEquals(listOf(2, 3, 4, 5, 6), r.path("queued"))
        assertEquals(emptyList<Int>(), r.path("cached"))
        assertEquals(10, r.path("pageCount"))
        runBlocking { PageImages.awaitPrefetch(chapters[0]) }
        assertEquals((2..6).map { "/img/v1/$it.png" }.toSet(), requests.map { it.path }.toSet())
        assertEquals(5, cacheFiles())

        requests.clear()
        assertEquals(200, get(routes[4]).statusCode())
        assertTrue(requests.isEmpty(), "a prefetched page is a hit")

        // again: nothing to fetch; past the end: clamped to the chapter
        val again = prefetch(chapters[0], from = 2, count = 5).assertNoErrors().dataPath("prefetchChapterPages")
        assertEquals(emptyList<Int>(), again.path("queued"))
        assertEquals(listOf(2, 3, 4, 5, 6), again.path("cached"))
        val end = prefetch(chapters[0], from = 8, count = 5).assertNoErrors()
        assertEquals(listOf(8, 9), end.dataPath("prefetchChapterPages", "queued"))
        runBlocking { PageImages.awaitPrefetch(chapters[0]) }

        // the next chapter, whose page list was never fetched: it is fetched first
        val calls = source.listCalls
        val next = prefetch(chapters[1], from = 0, count = 3).assertNoErrors().dataPath("prefetchChapterPages")
        assertEquals(listOf(0, 1, 2), next.path("queued"))
        assertEquals(10, next.path("pageCount"))
        assertEquals(calls + 1, source.listCalls)
        runBlocking { PageImages.awaitPrefetch(chapters[1]) }
        assertEquals(10, cacheFiles())
    }

    @Test
    fun `a prefetch refuses a count out of 1 to 20, a negative start and an unknown chapter`() {
        val (_, chapters) = manga(PagesSource(3))
        assertEquals("BAD_REQUEST", prefetch(chapters[0], 0, 0).errorCode)
        assertEquals("BAD_REQUEST", prefetch(chapters[0], 0, 21).errorCode)
        assertEquals("BAD_REQUEST", prefetch(chapters[0], -1, 3).errorCode)
        assertEquals("NOT_FOUND", prefetch(99_999, 0, 3).errorCode)
    }

    @Test
    fun `the reader settings and the page cache through GraphQL`() {
        val settings = "query { readerSettings { pageCacheLimitMb } }"
        assertEquals(1024, graphql(settings).assertNoErrors().dataPath("readerSettings", "pageCacheLimitMb"))
        val changed =
            graphql("mutation { setReaderSettings(input: {patch: {pageCacheLimitMb: 512}}) { settings { pageCacheLimitMb } } }")
                .assertNoErrors()
                .dataPath("setReaderSettings", "settings", "pageCacheLimitMb")
        assertEquals(512, changed)
        assertTrue(ReaderSettingsStore.file().readText().contains("\"pageCacheLimitMb\": 512"))
        val refused =
            graphql("mutation { setReaderSettings(input: {patch: {pageCacheLimitMb: 100}}) { settings { pageCacheLimitMb } } }")
        assertEquals("BAD_REQUEST", refused.errorCode)
        assertEquals(512, graphql(settings).assertNoErrors().dataPath("readerSettings", "pageCacheLimitMb"))

        val (_, chapters) = manga(PagesSource(2))
        pages(chapters[0]).forEach { assertEquals(200, get(it).statusCode()) }
        val usage = graphql("query { pageCache { bytes files limitBytes path } }").assertNoErrors().dataPath("pageCache")
        assertEquals(2, usage.path("files"))
        assertEquals((2L * png.size).toString(), usage.path("bytes").toString())
        assertEquals((512L * 1024 * 1024).toString(), usage.path("limitBytes").toString())
        assertEquals(cacheRoot.absolutePath, usage.path("path"))

        val cleared = graphql("mutation { clearPageCache(input: {}) { cache { bytes files } } }").assertNoErrors()
        assertEquals(0, cleared.dataPath("clearPageCache", "cache", "files"))
        assertEquals("0", cleared.dataPath("clearPageCache", "cache", "bytes").toString())
        requests.clear()
        assertEquals(200, get(pages(chapters[0])[0]).statusCode())
        assertEquals(1, requests.size, "fetched again after the clear")
    }
}
