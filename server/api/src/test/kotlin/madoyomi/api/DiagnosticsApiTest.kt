package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import madoyomi.core.MadoyomiBuildInfo
import madoyomi.core.downloads.DownloadSettingsStore
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.AfterEach
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the backend adds to Export logs: versions, repos, installed extensions and the settings
 * without secrets; the library's titles only when asked.
 */
class DiagnosticsApiTest : ApiGraphQLTest() {
    private val secret = "a-secret-session-name-1234"

    /** Set in a test and put back after it (read only once the server is set up: the config exists then). */
    private var savedSessionName: String? = null

    @AfterEach
    fun restore() {
        savedSessionName?.let { serverConfig.flareSolverrSessionName.value = it }
        savedSessionName = null
    }

    private fun report(includeLibrary: Boolean?): Pair<String, JsonObject> {
        val arg = includeLibrary?.let { "(includeLibrary: $it)" } ?: ""
        val text = graphql("{ diagnostics$arg }").assertNoErrors().dataPath("diagnostics") as String
        return text to Json.parseToJsonElement(text).jsonObject
    }

    private fun libraryManga(title: String) =
        dbTransaction {
            val id =
                MangaTable
                    .insertAndGetId {
                        it[url] = "/$title"
                        it[this.title] = title
                        it[sourceReference] = 424242L
                        it[memo] = JsonObject(emptyMap())
                    }.value
            MangaUserTable.upsert(MangaUserTable.user, MangaUserTable.manga) {
                it[user] = 1
                it[manga] = id
                it[inLibrary] = true
            }
        }

    @Test
    fun `the report has the versions, repos, extensions and settings, without secrets or the library`() {
        savedSessionName = serverConfig.flareSolverrSessionName.value
        serverConfig.flareSolverrSessionName.value = secret
        libraryManga("A Private Title")
        DownloadSettingsStore.reload()

        val (text, report) = report(null)
        val backend = report["backend"]!!.jsonObject
        assertEquals(MadoyomiBuildInfo.version, backend["version"]!!.jsonPrimitive.content)
        assertTrue(backend["java"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(report["repos"] is JsonArray)
        assertTrue(report["extensions"] is JsonArray)
        val folder = report["downloadsFolder"]!!.jsonObject
        assertEquals("true", folder["reachable"]!!.jsonPrimitive.content)

        val settings = report["settings"]!!.jsonObject
        val conf = settings["server.conf"]!!.jsonObject
        assertEquals("[REDACTED]", conf["flareSolverrSessionName"]!!.jsonPrimitive.content)
        assertEquals("[REDACTED]", conf["socksProxyPassword"]!!.jsonPrimitive.content)
        assertEquals("[REDACTED]", conf["authPassword"]!!.jsonPrimitive.content)
        assertTrue(conf["maxLogFiles"]!!.jsonPrimitive.content.isNotBlank(), "privacy-safe values are kept")
        assertTrue(settings.containsKey("downloads.json"))
        assertTrue(settings.containsKey("player-settings.json"))
        assertFalse(text.contains(secret), "no secret in the report")
        assertFalse(text.contains("A Private Title"), "no library without the opt-in")
        assertTrue(report["library"] is JsonPrimitive)
        assertEquals(report, report(false).second.let { r -> JsonObject(r + ("generatedAt" to report["generatedAt"]!!)) })
    }

    @Test
    fun `with the opt-in the library's titles and sources are in it`() {
        libraryManga("An Opted In Title")
        val (_, report) = report(true)
        val manga = report["library"]!!.jsonObject["manga"]!!.jsonArray
        val entry = manga.map { it.jsonObject }.first { it["title"]!!.jsonPrimitive.content == "An Opted In Title" }
        assertEquals("424242", entry["source"]!!.jsonPrimitive.content)
        assertTrue(report["library"]!!.jsonObject["anime"] is JsonArray)
    }
}
