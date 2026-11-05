package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.repo.RepoIndexFetcher
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.core.repo.RepoService
import madoyomi.core.repo.normalizeSigningKey
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Installs real extensions from the real repos (network). Skipped unless `MADOYOMI_LIVE_REPO_TESTS=1`:
 * AnimeGG from yuzono and MangaPill from Keiyoushi, both trusted by their repo's signing key.
 */
@OptIn(ExperimentalPathApi::class)
class ExtensionLifecycleLiveTest {
    companion object {
        private const val YUZONO = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
        private const val KEIYOUSHI = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        private const val ANIMEGG = "eu.kanade.tachiyomi.animeextension.en.animegg"
        private const val MANGAPILL = "eu.kanade.tachiyomi.extension.en.mangapill"

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            assumeTrue(System.getenv("MADOYOMI_LIVE_REPO_TESTS") == "1", "live repo tests: set MADOYOMI_LIVE_REPO_TESTS=1")
            ApplicationTest.testingSetup()
        }
    }

    // the explicit default before the test (not `primaryDatabase`: restoring an implicit primary would make it explicit,
    // and an explicit default wins over every later `Database.connect`)
    private var previousDefault: Database? = null
    private lateinit var database: Database
    private val lifecycle = ExtensionLifecycle(RepoIndexFetcher.network, madoyomi.core.repo.NetworkRepoHttp)
    private val repos = RepoService(RepoIndexFetcher.network, onTrusted = { lifecycle.reconcileTrust(it) })

    @BeforeEach
    fun setUp() {
        previousDefault = TransactionManager.defaultDatabase
        database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-live-ext-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                    },
            )
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        TransactionManager.defaultDatabase = database
        serverConfig.extensionStores.value = emptyList()
    }

    @AfterEach
    fun tearDown() {
        GetSource.unregisterAllSources()
        PackageTools.jarLoaderMap.values.forEach { it.close() }
        PackageTools.jarLoaderMap.clear()
        ExtensionKind.entries.forEach { (ExtensionFiles.extensionsRoot / it.dirName).deleteRecursively() }
        serverConfig.extensionStores.value = emptyList()
        TransactionManager.closeAndUnregister(database)
        TransactionManager.defaultDatabase = previousDefault
    }

    private fun row(pkg: String) = transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkg }.first() }

    @Test
    fun `AnimeGG from yuzono installs trusted with its listed sources`() {
        val store = runBlocking { repos.add(YUZONO, ExtensionKind.ANIME, RepoIndexFormat.AUTO) }
        val key = normalizeSigningKey(store[ExtensionStoreTable.signingKey])

        val result = runBlocking { lifecycle.install(ANIMEGG, ExtensionKind.ANIME) }
        println("live AnimeGG: $result (repo key $key, store ${store[ExtensionStoreTable.indexUrl]})")

        assertEquals(key, result.signatureHash)
        assertTrue(result.trusted)
        assertFalse(result.loaded)
        val row = row(ANIMEGG)
        assertFalse(row[ExtensionTable.isLoaded])
        val sources =
            transaction { AnimeSourceTable.selectAll().where { AnimeSourceTable.extension eq row[ExtensionTable.id].value }.toList() }
        println(
            "live AnimeGG sources: ${sources.map {
                "${it[AnimeSourceTable.id].value} ${it[AnimeSourceTable.name]} ${it[AnimeSourceTable.baseUrl]}"
            }}",
        )
        assertTrue(sources.isNotEmpty())
    }

    @Test
    fun `MangaPill from Keiyoushi installs trusted and loads`() {
        val store = runBlocking { repos.add(KEIYOUSHI, ExtensionKind.MANGA, RepoIndexFormat.AUTO) }
        val key = normalizeSigningKey(store[ExtensionStoreTable.signingKey])

        val result = runBlocking { lifecycle.install(MANGAPILL, ExtensionKind.MANGA) }
        println("live MangaPill: $result (repo key $key, store ${store[ExtensionStoreTable.indexUrl]})")

        assertEquals(key, result.signatureHash)
        assertTrue(result.trusted)
        assertTrue(result.loaded)
        val row = row(MANGAPILL)
        val sourceId =
            transaction {
                SourceTable
                    .selectAll()
                    .where { SourceTable.extension eq row[ExtensionTable.id].value }
                    .first()[SourceTable.id]
                    .value
            }
        val source = runBlocking { GetSource.getSourceOrNull(sourceId) }
        assertIs<HttpSource>(source)
        println("live MangaPill source: ${source.id} ${source.name} ${source.baseUrl}")
    }
}
