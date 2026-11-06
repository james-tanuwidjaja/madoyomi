package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import okhttp3.Request
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ParsedAnimeHttpSource` (the Jsoup-selector base class) through the anime services, against a loopback site. None
 * of the top 20 extends it (all 20 are plain `AnimeHttpSource`s, `docs/research/compat-scan.md`), so this is what
 * covers it: browse (popular, latest, search), details with episodes, and the videos of an episode.
 */
class ParsedAnimeHttpSourceTest {
    companion object {
        private lateinit var db: ScratchDatabase
        private val site: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val base get() = "http://127.0.0.1:${site.address.port}"

        private val pages =
            mapOf(
                "/popular" to
                    """<div class="item"><a href="/anime/1">First show</a><img src="/t1.jpg"></div>
                       <div class="item"><a href="/anime/2">Second show</a><img src="/t2.jpg"></div>
                       <a class="next" href="/popular?page=2">next</a>""",
                "/latest" to """<ul><li class="new"><a href="/anime/2">Second show</a></li></ul>""",
                "/search" to """<div class="hit"><a href="/anime/1">First show</a></div>""",
                "/anime/1" to
                    """<h1>First show</h1><p class="desc">A show to test selectors.</p>
                       <span class="genre">Action</span><span class="genre">Drama</span>
                       <ol><li class="ep"><a href="/ep/1-1">Episode 1</a></li><li class="ep"><a href="/ep/1-2">Episode 2</a></li></ol>""",
                "/ep/1-2" to
                    """<video><source src="/v/1-2-1080.mp4" data-quality="1080p">
                       <source src="/v/1-2-720.mp4" data-quality="720p"></video>""",
            )

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
            db = ScratchDatabase()
            site.createContext("/") { exchange ->
                val body = pages[exchange.requestURI.path]
                val bytes = (body?.let { "<html><body>$it</body></html>" } ?: "not found").toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(if (body != null) 200 else 404, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            site.start()
        }

        @AfterAll
        @JvmStatic
        fun afterAll() {
            site.stop(0)
            db.close()
        }
    }

    /** A selector-based source as Aniyomi's `ParsedAnimeHttpSource` extensions are written. */
    private class SelectorSource : ParsedAnimeHttpSource() {
        override val name = "Fake parsed"
        override val lang = "en"
        override val baseUrl get() = base
        override val supportsLatest = true

        var lastSearch: String? = null

        override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular?page=$page", headers)

        override fun popularAnimeSelector() = "div.item"

        override fun popularAnimeFromElement(element: Element) =
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst("a")!!.text()
                thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
            }

        override fun popularAnimeNextPageSelector() = "a.next"

        override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest?page=$page", headers)

        override fun latestUpdatesSelector() = "li.new"

        override fun latestUpdatesFromElement(element: Element) =
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.text()
            }

        override fun latestUpdatesNextPageSelector(): String? = null

        override fun searchAnimeRequest(
            page: Int,
            query: String,
            filters: AnimeFilterList,
        ): Request {
            lastSearch = query
            return GET("$baseUrl/search?q=$query&page=$page", headers)
        }

        override fun searchAnimeSelector() = "div.hit"

        override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

        override fun searchAnimeNextPageSelector(): String? = null

        override fun animeDetailsParse(document: Document) =
            SAnime.create().apply {
                title = document.selectFirst("h1")!!.text()
                description = document.selectFirst("p.desc")?.text()
                genre = document.select("span.genre").joinToString { it.text() }
                status = SAnime.ONGOING
            }

        override fun episodeListSelector() = "li.ep"

        override fun episodeFromElement(element: Element) =
            SEpisode.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                name = element.text()
                episode_number = name.substringAfter("Episode ").toFloat()
            }

        override fun seasonListSelector() = throw UnsupportedOperationException()

        override fun seasonFromElement(element: Element) = throw UnsupportedOperationException()

        override fun hosterListSelector() = throw UnsupportedOperationException()

        override fun hosterFromElement(element: Element): Hoster = throw UnsupportedOperationException()

        override fun videoListSelector() = "video source"

        override fun videoFromElement(element: Element) =
            Video(
                videoUrl = element.attr("abs:src"),
                videoTitle = element.attr("data-quality"),
            )

        override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    }

    private val source = SelectorSource()

    @BeforeEach
    fun setUp() {
        FakeAnimeSource.clearRows(source.id)
        AnimeGetSource.registerSource(source)
    }

    @AfterEach
    fun tearDown() {
        AnimeGetSource.unregisterSource(source.id)
        FakeAnimeSource.clearRows(source.id)
    }

    private fun titles(ids: List<Int>): List<String> =
        dbTransaction { ids.map { id -> AnimeTable.selectAll().where { AnimeTable.id eq id }.single()[AnimeTable.title] } }

    @Test
    fun `browse parses popular, latest and search pages with the source's selectors`() {
        val popular = runBlocking { AnimeBrowse.fetch(source.id, AnimeBrowse.Type.POPULAR, 1) }
        assertEquals(listOf("First show", "Second show"), titles(popular.animeIds))
        assertTrue(popular.hasNextPage, "the next-page selector matched")

        val latest = runBlocking { AnimeBrowse.fetch(source.id, AnimeBrowse.Type.LATEST, 1) }
        assertEquals(listOf("Second show"), titles(latest.animeIds))
        assertFalse(latest.hasNextPage)
        assertEquals(popular.animeIds[1], latest.animeIds.single(), "the same url is the same entry")

        val search = runBlocking { AnimeBrowse.fetch(source.id, AnimeBrowse.Type.SEARCH, 1, query = "first") }
        assertEquals("first", source.lastSearch)
        assertEquals(listOf(popular.animeIds[0]), search.animeIds)
    }

    @Test
    fun `details, episodes and videos come from the parsed pages`() {
        val animeId = runBlocking { AnimeBrowse.fetch(source.id, AnimeBrowse.Type.POPULAR, 1) }.animeIds.first()
        val details = runBlocking { AnimeDetails.fetch(animeId) }

        val row = dbTransaction { AnimeTable.selectAll().where { AnimeTable.id eq animeId }.single() }
        assertEquals("A show to test selectors.", row[AnimeTable.description])
        assertEquals("Action, Drama", row[AnimeTable.genre])
        assertEquals(2, details.episodeIds.size)

        val newest =
            dbTransaction {
                EpisodeTable
                    .selectAll()
                    .where { EpisodeTable.anime eq animeId }
                    .maxBy { it[EpisodeTable.episodeNumber] }
            }
        assertEquals("Episode 2", newest[EpisodeTable.name])
        val hosters = runBlocking { AnimeVideos.fetchEpisodeVideos(newest[EpisodeTable.id].value) }
        // no hoster methods of its own: Aniyomi's no_hoster_list around the parsed video list
        assertEquals(Hoster.NO_HOSTER_LIST, hosters.single().name)
        assertEquals(listOf("1080p", "720p"), hosters.single().videos.map { it.videoTitle })
        assertEquals(
            "$base/v/1-2-1080.mp4",
            hosters
                .single()
                .videos
                .first()
                .videoUrl,
        )
    }
}
