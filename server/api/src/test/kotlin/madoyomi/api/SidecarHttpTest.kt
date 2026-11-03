package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.Javalin
import io.javalin.json.JavalinJackson3
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.server.JavalinSetup
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sidecar HTTP layer on a bare Javalin: the token gate (`SidecarHttp`) in front of upstream's exception handlers
 * (`JavalinSetup.defineExceptionHandlers`, which write Madoyomi's `{code, message, details}` error bodies).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SidecarHttpTest {
    private val tokenValue = "test-token-${System.nanoTime()}"
    private val token = SidecarToken.of(tokenValue)
    private lateinit var app: Javalin
    private val http = HttpClient.newHttpClient()
    private val json = jacksonObjectMapper()
    private val base get() = "http://127.0.0.1:${app.port()}"

    @BeforeAll
    fun start() {
        app =
            Javalin
                .create { config ->
                    config.jetty.host = "127.0.0.1"
                    config.jetty.port = 0
                    config.jsonMapper(JavalinJackson3())
                    SidecarHttp.install(config, token, System.currentTimeMillis())
                    val routes = config.routes
                    routes.post("/api/graphql") { it.result("ok") }
                    routes.ws("/api/graphql") { }
                    routes.get("/api/v1/untrusted") {
                        throw MadoyomiException(ErrorCode.UNTRUSTED, "not trusted", mapOf("signatureHash" to "abc"))
                    }
                    routes.get("/api/v1/npe") { throw NullPointerException("no such manga") }
                    routes.get("/api/v1/boom") { throw IllegalStateException("boom") }
                    with(JavalinSetup) { routes.defineExceptionHandlers() }
                }.start()
    }

    @AfterAll
    fun stop() {
        app.stop()
    }

    private fun send(
        method: String,
        path: String,
        bearer: String? = tokenValue,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$base$path"))
                .method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString("{}"))
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<String>.json(): Map<String, Any?> = json.readValue(body())

    @Test
    fun `health is open without a token`() {
        val response = send("GET", SidecarHttp.HEALTH_PATH, bearer = null)
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.json()["status"])
    }

    @Test
    fun `graphql needs the token`() {
        val rejected = send("POST", "/api/graphql", bearer = null)
        assertEquals(401, rejected.statusCode())
        assertEquals("UNAUTHORIZED", rejected.json()["code"])
        assertTrue(rejected.json().containsKey("details"))

        assertEquals(401, send("POST", "/api/graphql", bearer = "wrong").statusCode())
        assertEquals(200, send("POST", "/api/graphql").statusCode())
    }

    @Test
    fun `a websocket upgrade needs the token in the query`() {
        val failure =
            assertFailsWith<CompletionException> {
                http
                    .newWebSocketBuilder()
                    .buildAsync(
                        URI("ws://127.0.0.1:${app.port()}/api/graphql"),
                        object : WebSocket.Listener {},
                    ).join()
            }
        assertTrue(failure.cause.toString().contains("401") || failure.cause is java.net.http.WebSocketHandshakeException, "$failure")

        val socket =
            http
                .newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:${app.port()}/api/graphql?token=$tokenValue"), object : WebSocket.Listener {})
                .get(10, TimeUnit.SECONDS)
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `REST errors are code, message and details`() {
        val untrusted = send("GET", "/api/v1/untrusted")
        assertEquals(403, untrusted.statusCode())
        assertEquals(
            mapOf("code" to "UNTRUSTED", "message" to "not trusted", "details" to mapOf("signatureHash" to "abc")),
            untrusted.json(),
        )

        val npe = send("GET", "/api/v1/npe")
        assertEquals(404, npe.statusCode(), "upstream's status is kept")
        assertEquals("NOT_FOUND", npe.json()["code"])

        val boom = send("GET", "/api/v1/boom")
        assertEquals(500, boom.statusCode())
        assertEquals(mapOf("code" to "INTERNAL", "message" to "boom", "details" to null), boom.json())
    }
}
