package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import io.javalin.Javalin
import io.javalin.json.JavalinJackson3
import madoyomi.core.anime.AnimeThumbnails
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.user.UserType
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `GET /api/v1/anime/{animeId}/thumbnail` on a bare Javalin with [AnimeHttp] and upstream's exception handlers, against
 * a loopback image host that records what it was sent.
 */
class AnimeThumbnailTest : AnimeApiTest() {
    private val png: ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    /** Request paths and their Referer headers, in order. */
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val images: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val imageBase get() = "http://127.0.0.1:${images.address.port}"

    private lateinit var app: Javalin
    private val http = HttpClient.newHttpClient()
    private val json = jacksonObjectMapper()

    private inner class ThumbSource : AnimeHttpSource() {
        override val name = "Fake thumbnails"
        override val lang = "en"
        override val baseUrl get() = imageBase
        override val supportsLatest = false

        /** What a details refresh answers. */
        var thumbnailOnRefresh: String? = null

        override fun headersBuilder() = super.headersBuilder().add("Referer", "$imageBase/")

        override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply { thumbnail_url = thumbnailOnRefresh }
    }

    @BeforeAll
    fun start() {
        images.createContext("/") { exchange ->
            exchange.use {
                requests += it.requestURI.path to it.requestHeaders.getFirst("Referer")
                if (it.requestURI.path.startsWith("/gone")) {
                    it.sendResponseHeaders(404, -1)
                } else {
                    it.responseHeaders.add("Content-Type", "image/png")
                    it.sendResponseHeaders(200, png.size.toLong())
                    it.responseBody.write(png)
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
                    // upstream's beforeMatched sets the user (authMode NONE: the admin)
                    config.routes.beforeMatched { it.attribute(JavalinSetup.Attribute.TachideskUser.name, UserType.Admin(1)) }
                    AnimeHttp.install(config)
                    with(JavalinSetup) { config.routes.defineExceptionHandlers() }
                }.start()
    }

    @AfterAll
    fun stop() {
        app.stop()
        images.stop(0)
    }

    private fun get(animeId: Any): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${app.port()}/api/v1/anime/$animeId/thumbnail")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** An entry of [source] whose thumbnail cache is empty (ids repeat across the per-test databases). */
    private fun entry(
        source: ThumbSource,
        thumbnailUrl: String?,
    ): Int {
        requests.clear()
        return animeRow(source.id, "/show", thumbnailUrl = thumbnailUrl, initialized = true).also(AnimeThumbnails::clear)
    }

    private fun HttpResponse<ByteArray>.errorBody(): Map<String, Any?> = json.readValue(body())

    @Test
    fun `the thumbnail comes from the source with its headers, then from the cache`() {
        val source = ThumbSource().also(::register)
        val id = entry(source, "$imageBase/thumb/1.png")

        val first = get(id)
        assertEquals(200, first.statusCode())
        assertEquals("image/png", first.headers().firstValue("content-type").orElse(null))
        assertEquals("max-age=86400", first.headers().firstValue("cache-control").orElse(null))
        assertContentEquals(png, first.body())
        assertEquals(listOf("/thumb/1.png" to "$imageBase/"), requests.toList(), "the source's Referer was sent")

        val cached = get(id)
        assertEquals(200, cached.statusCode())
        assertEquals("image/png", cached.headers().firstValue("content-type").orElse(null))
        assertContentEquals(png, cached.body())
        assertEquals(1, requests.size, "served from the cache")
        AnimeThumbnails.clear(id)
    }

    @Test
    fun `a URL that is gone refreshes the details once and uses the new URL`() {
        val source = ThumbSource().also(::register)
        source.thumbnailOnRefresh = "$imageBase/moved/1.png"
        val id = entry(source, "$imageBase/gone/1.png")

        val response = get(id)
        assertEquals(200, response.statusCode())
        assertEquals(listOf("/gone/1.png", "/moved/1.png"), requests.map { it.first })
        AnimeThumbnails.clear(id)
    }

    @Test
    fun `no thumbnail or no anime is 404 NOT_FOUND, a bad id 400`() {
        val source = ThumbSource().also(::register)
        val id = entry(source, null)

        val none = get(id)
        assertEquals(404, none.statusCode())
        assertEquals("NOT_FOUND", none.errorBody()["code"])
        assertEquals(404, get(424_242).statusCode())
        assertEquals("NOT_FOUND", get(424_242).errorBody()["code"])
        assertEquals(400, get("abc").statusCode())
        assertEquals(emptyList(), requests.toList())
    }
}
