package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.exthost.ExtensionKind
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adds and refreshes the real yuzono (anime) and Keiyoushi (manga) repos on scratch databases and prints what it got.
 * Needs the network, so it only runs with `MADOYOMI_LIVE_REPO_TESTS=1`:
 *
 *     $env:MADOYOMI_LIVE_REPO_TESTS='1'; .\gradlew.bat :core:test --tests "madoyomi.core.repo.RepoServiceLiveTest"
 */
class RepoServiceLiveTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @BeforeEach
    fun onlyWhenAsked() {
        Assumptions.assumeTrue(
            System.getenv("MADOYOMI_LIVE_REPO_TESTS") == "1",
            "set MADOYOMI_LIVE_REPO_TESTS=1 to run the live repo tests",
        )
    }

    private val client = OkHttpClient.Builder().followRedirects(true).build()

    private val http =
        RepoHttp { url ->
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code} ($url)")
                    it.body.bytes()
                }
            }
        }

    private fun withScratchDb(block: (RepoService) -> Unit) {
        val previous = TransactionManager.primaryDatabase
        val db =
            Database.connect(
                "jdbc:h2:mem:madoyomi-live-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                    },
            )
        try {
            runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), db)
            block(RepoService(RepoIndexFetcher(http), unloader = {}))
        } finally {
            serverConfig.extensionStores.value = emptyList()
            TransactionManager.closeAndUnregister(db)
            previous?.let { TransactionManager.defaultDatabase = it }
        }
    }

    private fun report(
        label: String,
        service: RepoService,
        url: String,
        kind: ExtensionKind,
        format: RepoIndexFormat,
    ) {
        val started = System.currentTimeMillis()
        val store = runBlocking { service.add(url, kind, format) }
        val added = System.currentTimeMillis() - started
        val refresh = runBlocking { service.refreshAll(kind) }.single()
        val (count, torrents) =
            transaction {
                val rows = ExtensionTable.selectAll().where { ExtensionTable.type eq kind }.toList()
                rows.size to rows.count { it[ExtensionTable.isTorrent] }
            }
        val trust =
            transaction {
                TrustedSignatureTable.selectAll().map { "${it[TrustedSignatureTable.id].value}/${it[TrustedSignatureTable.origin]}" }
            }
        println(
            "[live] $label: $url ($format) -> ${store[ExtensionStoreTable.indexUrl]} legacy=${store[ExtensionStoreTable.isLegacy]} " +
                "name=${store[ExtensionStoreTable.name]} key=${store[ExtensionStoreTable.signingKey]} " +
                "trusted=${store[ExtensionStoreTable.signingKeyTrusted]} extensions=$count torrent=$torrents " +
                "addMs=$added refresh=${refresh.ok}/${refresh.extensionCount} trust=$trust",
        )
        assertTrue(refresh.ok, "refresh of $label: ${refresh.error}")
        assertEquals(count, refresh.extensionCount)
        assertTrue(count > 100, "$label: only $count extensions")
    }

    @Test
    fun `yuzono as anime, AUTO (follows index_v2 to index_pb)`() =
        withScratchDb { report("yuzono AUTO", it, RepoFixtures.YUZONO_LIST, ExtensionKind.ANIME, RepoIndexFormat.AUTO) }

    @Test
    fun `yuzono as anime, JSON (index_min_json)`() =
        withScratchDb { report("yuzono JSON", it, RepoFixtures.YUZONO_LIST, ExtensionKind.ANIME, RepoIndexFormat.JSON) }

    @Test
    fun `Keiyoushi as manga, AUTO`() =
        withScratchDb {
            report(
                "keiyoushi AUTO",
                it,
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
                ExtensionKind.MANGA,
                RepoIndexFormat.AUTO,
            )
        }
}
