package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.SignatureTrustOrigin
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.core.repo.RepoFixtures.KEIYOUSHI_KEY
import madoyomi.core.repo.RepoFixtures.KEIYOUSHI_PB
import madoyomi.core.repo.RepoFixtures.MANGA_LIST
import madoyomi.core.repo.RepoFixtures.MANGA_REPO
import madoyomi.core.repo.RepoFixtures.YUZONO_KEY
import madoyomi.core.repo.RepoFixtures.YUZONO_LIST
import madoyomi.core.repo.RepoFixtures.YUZONO_PB
import madoyomi.core.repo.RepoFixtures.YUZONO_PKGS
import madoyomi.core.repo.RepoFixtures.YUZONO_REPO
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RepoService] on a scratch in-memory H2 database (all migrations applied) with a stubbed network. */
class RepoServiceTest {
    companion object {
        private const val OTHER_KEY = "1111111111111111111111111111111111111111111111111111111111111111"
        private const val ANIME2_LIST = "https://example.org/anime2/index.min.json"
        private const val ANIME2_REPO = "https://example.org/anime2/repo.json"
        private const val ONSEN = "eu.kanade.tachiyomi.animeextension.all.animeonsen"
        private const val MISSAV = "eu.kanade.tachiyomi.animeextension.all.missav"
        private const val MANGAPILL = "eu.kanade.tachiyomi.extension.en.mangapill"
        private const val NOSOURCES = "eu.kanade.tachiyomi.extension.all.nosources"

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    private val dbConfig =
        DatabaseConfig {
            useNestedTransactions = true
            @OptIn(ExperimentalKeywordApi::class)
            preserveKeywordCasing = false
        }

    private var previousPrimary: Database? = null
    private lateinit var database: Database
    private val http = StubRepoHttp()
    private val unloaded = mutableListOf<String>()
    private var clock = 1_000L
    private val service =
        RepoService(RepoIndexFetcher(http), unloader = { unloaded += it[ExtensionTable.pkgName] }, now = { clock }, onTrusted = {})

    @BeforeEach
    fun setUp() {
        Assumptions.assumeTrue(serverConfig.databaseType.value == DatabaseType.H2)
        previousPrimary = TransactionManager.primaryDatabase
        database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-repo-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig = dbConfig,
            )
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        serverConfig.extensionStores.value = emptyList()
    }

    @AfterEach
    fun tearDown() {
        serverConfig.extensionStores.value = emptyList()
        TransactionManager.closeAndUnregister(database)
        previousPrimary?.let { TransactionManager.defaultDatabase = it }
    }

    // --- helpers ---

    private fun <T> run(block: suspend RepoService.() -> T): T = runBlocking { service.block() }

    private fun store(url: String): ResultRow? =
        transaction {
            ExtensionStoreTable.selectAll().where { ExtensionStoreTable.indexUrl eq url }.firstOrNull()
        }

    private fun extensions(kind: ExtensionKind? = null): Map<String, ResultRow> =
        transaction {
            ExtensionTable
                .selectAll()
                .toList()
                .filter { kind == null || it[ExtensionTable.type] == kind }
                .associateBy { it[ExtensionTable.pkgName] }
        }

    private fun trust(): Map<String, ResultRow> =
        transaction {
            TrustedSignatureTable.selectAll().associateBy { it[TrustedSignatureTable.id].value }
        }

    private fun setExtension(
        pkg: String,
        installed: Boolean = true,
        hash: String? = null,
        loaded: Boolean = true,
    ) = transaction {
        ExtensionTable.update({ ExtensionTable.pkgName eq pkg }) {
            it[isInstalled] = installed
            it[signatureHash] = hash
            it[isLoaded] = loaded
            it[apkName] = "$pkg.apk"
        }
    }

    private fun failure(block: () -> Unit): MadoyomiException = assertFailsWith<MadoyomiException> { block() }

    private fun mangaLegacyRoutes(
        key: String = KEIYOUSHI_KEY,
        list: String = RepoFixtures.text("manga-index.min.json"),
    ) {
        http.routes[MANGA_LIST] = list.toByteArray()
        http.routes[MANGA_REPO] = RepoFixtures.mangaRepoJson(key)
    }

    /** A second anime repo: AnimeOnsen one version ahead of yuzono, plus one extension of its own. */
    private fun anime2Routes() {
        http.routes[ANIME2_LIST] =
            """[{"name": "Aniyomi: AnimeOnsen", "pkg": "$ONSEN", "apk": "aniyomi-all.animeonsen-v14.11.apk", "lang": "all", "code": 11,
                |"version": "14.11", "nsfw": 0, "torrent": 0},
                |{"name": "Aniyomi: Other", "pkg": "eu.kanade.tachiyomi.animeextension.en.other", "apk": "aniyomi-en.other-v14.1.apk",
                |"lang": "en", "code": 1, "version": "14.1", "nsfw": 0, "torrent": 1}]
            """.trimMargin().toByteArray()
        http.routes[ANIME2_REPO] =
            """{"meta": {"name": "Anime 2", "website": "https://example.org", "signingKeyFingerprint": "$OTHER_KEY"}}""".toByteArray()
    }

    /** yuzono's index.pb fixture without the given pkgs. */
    private fun yuzonoPbWithout(vararg pkgs: String): ByteArray {
        val dto =
            ProtoBuf.decodeFromByteArray(
                AnimeNetworkExtensionStore.serializer(),
                decompressIfGzipped(RepoFixtures.gzipped("yuzono-index.pb.gz.b64")),
            )
        val trimmed =
            dto.copy(
                extensionList =
                    AnimeNetworkExtensionStore.ExtensionList(
                        dto.extensionList!!.extensions.filterNot {
                            it.packageName in
                                pkgs
                        },
                    ),
            )
        return RepoFixtures.gzip(ProtoBuf.encodeToByteArray(AnimeNetworkExtensionStore.serializer(), trimmed))
    }

    // --- add ---

    @Test
    fun `adding yuzono as an anime repo (JSON) stores it, trusts its key and lists ANIME extensions`() {
        http.routes += RepoFixtures.yuzonoRoutes()
        val row = run { add(YUZONO_LIST, ExtensionKind.ANIME, RepoIndexFormat.JSON) }

        assertEquals(YUZONO_REPO, row[ExtensionStoreTable.indexUrl])
        assertEquals(ExtensionKind.ANIME, row[ExtensionStoreTable.type])
        assertEquals(RepoIndexFormat.JSON, row[ExtensionStoreTable.format])
        assertTrue(row[ExtensionStoreTable.isLegacy])
        assertTrue(row[ExtensionStoreTable.signingKeyTrusted])
        assertEquals(clock, row[ExtensionStoreTable.lastRefreshedAt])
        assertNull(row[ExtensionStoreTable.lastError])
        assertFalse(YUZONO_PB in http.requested)

        val signature = assertNotNull(trust()[YUZONO_KEY])
        assertEquals(SignatureTrustOrigin.STORE, signature[TrustedSignatureTable.origin])
        assertEquals(row[ExtensionStoreTable.id], signature[TrustedSignatureTable.store])

        val anime = extensions(ExtensionKind.ANIME)
        assertEquals(YUZONO_PKGS.toSet(), anime.keys)
        assertEquals(
            setOf("eu.kanade.tachiyomi.animeextension.all.hentaitorrent"),
            anime.filterValues { it[ExtensionTable.isTorrent] }.keys,
        )
        assertTrue(anime.values.all { it[ExtensionTable.storeIndexUrl] == YUZONO_REPO && !it[ExtensionTable.isInstalled] })
        assertTrue(extensions(ExtensionKind.MANGA).isEmpty())
        assertTrue(serverConfig.extensionStores.value.isEmpty(), "anime repos are not mirrored to the manga setting")
    }

    @Test
    fun `adding yuzono with AUTO follows index_v2 and reads the torrent flag from the protobuf`() {
        http.routes += RepoFixtures.yuzonoRoutes()
        val row = run { add(YUZONO_LIST, ExtensionKind.ANIME) }
        assertEquals(YUZONO_PB, row[ExtensionStoreTable.indexUrl])
        assertEquals(RepoIndexFormat.AUTO, row[ExtensionStoreTable.format])
        assertFalse(row[ExtensionStoreTable.isLegacy])
        assertEquals(
            setOf("eu.kanade.tachiyomi.animeextension.all.hentaitorrent"),
            extensions(ExtensionKind.ANIME)
                .filterValues {
                    it[ExtensionTable.isTorrent]
                }.keys,
        )
    }

    @Test
    fun `adding a manga protobuf repo lists MANGA extensions and mirrors it to the extensionStores setting`() {
        http.routes[KEIYOUSHI_PB] = RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64")
        val row = run { add(KEIYOUSHI_PB, ExtensionKind.MANGA) }
        assertEquals(ExtensionKind.MANGA, row[ExtensionStoreTable.type])
        assertEquals("KEI", row[ExtensionStoreTable.badgeLabel])
        assertEquals(3, extensions(ExtensionKind.MANGA).size)
        assertTrue(extensions(ExtensionKind.MANGA).values.all { it[ExtensionTable.jarUrl] != null })
        assertEquals(KEIYOUSHI_KEY, trust().keys.single())
        assertEquals(listOf(KEIYOUSHI_PB), serverConfig.extensionStores.value)

        run { remove(KEIYOUSHI_PB) }
        assertTrue(serverConfig.extensionStores.value.isEmpty())
    }

    @Test
    fun `a repo whose packages are of the other type is rejected and nothing is written`() {
        mangaLegacyRoutes()
        val e = failure { run { add(MANGA_LIST, ExtensionKind.ANIME) } }
        assertEquals(ErrorCode.REPO_TYPE_MISMATCH, e.code)
        assertTrue(e.message!!.contains(MANGAPILL))

        http.routes += RepoFixtures.yuzonoRoutes()
        assertEquals(ErrorCode.REPO_TYPE_MISMATCH, failure { run { add(YUZONO_LIST, ExtensionKind.MANGA) } }.code)

        transaction { assertEquals(0, ExtensionStoreTable.selectAll().count()) }
        assertTrue(extensions().isEmpty())
        assertTrue(trust().isEmpty())
    }

    @Test
    fun `re-adding a repo URL as the other type is rejected`() {
        mangaLegacyRoutes(list = "[]")
        run { add(MANGA_LIST, ExtensionKind.MANGA) }
        val e = failure { run { add(MANGA_LIST, ExtensionKind.ANIME) } }
        assertEquals(ErrorCode.REPO_TYPE_MISMATCH, e.code)
        assertTrue(e.message!!.contains("already added as a manga repo"))
        assertEquals(ExtensionKind.MANGA, store(MANGA_REPO)!![ExtensionStoreTable.type])
    }

    @Test
    fun `adding an unreachable repo fails without writing`() {
        val e = failure { run { add("https://down.example/index.min.json", ExtensionKind.ANIME) } }
        assertEquals(ErrorCode.REPO_FETCH_FAILED, e.code)
        transaction { assertEquals(0, ExtensionStoreTable.selectAll().count()) }
    }

    // --- refresh ---

    @Test
    fun `a failed refresh keeps the repo's rows and records last_error, and is never destructive`() {
        http.routes += RepoFixtures.yuzonoRoutes()
        anime2Routes()
        run { add(YUZONO_LIST, ExtensionKind.ANIME) }
        run { add(ANIME2_LIST, ExtensionKind.ANIME) }
        val before = extensions(ExtensionKind.ANIME)
        assertEquals(5, before.size)
        assertEquals(ANIME2_REPO, before.getValue("eu.kanade.tachiyomi.animeextension.en.other")[ExtensionTable.storeIndexUrl])

        clock = 2_000
        http.failing += ANIME2_REPO
        val results = run { refreshAll(ExtensionKind.ANIME) }
        assertEquals(listOf(true, false), results.map { it.ok })
        assertTrue(results[1].error!!.contains("connection reset"))

        val failed = store(ANIME2_REPO)!!
        assertTrue(failed[ExtensionStoreTable.lastError]!!.contains("connection reset"))
        assertEquals(1_000, failed[ExtensionStoreTable.lastRefreshedAt], "the last good refresh time stays")
        assertTrue(failed[ExtensionStoreTable.signingKeyTrusted])
        assertNotNull(trust()[OTHER_KEY])
        assertEquals(before.keys, extensions(ExtensionKind.ANIME).keys, "the other repo succeeded, but nothing is pruned")
        val onsen = extensions().getValue(ONSEN)
        assertEquals(
            ANIME2_REPO to 11L,
            onsen[ExtensionTable.storeIndexUrl] to onsen[ExtensionTable.versionCode],
            "not downgraded to yuzono's older listing",
        )
        assertNull(store(YUZONO_PB)!![ExtensionStoreTable.lastError])
        assertEquals(2_000, store(YUZONO_PB)!![ExtensionStoreTable.lastRefreshedAt])

        http.failing.clear()
        run { refreshAll(ExtensionKind.ANIME) }
        assertNull(store(ANIME2_REPO)!![ExtensionStoreTable.lastError])
    }

    @Test
    fun `when every repo refreshed, missing listings are pruned and installed ones become obsolete`() {
        http.routes += RepoFixtures.yuzonoRoutes()
        run { add(YUZONO_LIST, ExtensionKind.ANIME) }
        setExtension(ONSEN)

        http.routes[YUZONO_PB] = yuzonoPbWithout(ONSEN, MISSAV)
        run { refreshAll() }
        val anime = extensions(ExtensionKind.ANIME)
        assertFalse(MISSAV in anime, "not installed: deleted")
        assertTrue(anime.getValue(ONSEN)[ExtensionTable.isObsolete], "installed: kept, marked obsolete (upstream behaviour)")
        assertEquals(3, anime.size)
    }

    @Test
    fun `a changed signing key is not applied by a refresh`() {
        mangaLegacyRoutes()
        run { add(MANGA_LIST, ExtensionKind.MANGA) }
        http.routes[MANGA_REPO] = RepoFixtures.mangaRepoJson(OTHER_KEY)

        val result = run { refreshAll(ExtensionKind.MANGA) }.single()
        assertFalse(result.ok)
        val row = store(MANGA_REPO)!!
        assertTrue(row[ExtensionStoreTable.lastError]!!.startsWith("Signing key changed"))
        assertEquals(KEIYOUSHI_KEY, row[ExtensionStoreTable.signingKey])
        assertEquals(setOf(KEIYOUSHI_KEY), trust().keys)

        // re-adding is the user accepting the new key: the old one is revoked
        run { add(MANGA_LIST, ExtensionKind.MANGA) }
        assertEquals(setOf(OTHER_KEY), trust().keys)
        assertNull(store(MANGA_REPO)!![ExtensionStoreTable.lastError])
    }

    @Test
    fun `a repo that moves on refresh keeps its id, trust, type and format`() {
        mangaLegacyRoutes()
        val added = run { add(MANGA_LIST, ExtensionKind.MANGA) }
        assertEquals(MANGA_REPO, added[ExtensionStoreTable.indexUrl])
        assertEquals(listOf(MANGA_REPO), serverConfig.extensionStores.value)

        // the repo starts publishing a v2 index
        val pbUrl = "https://example.org/manga-repo/index.pb"
        http.routes[MANGA_REPO] = RepoFixtures.mangaRepoJson(indexV2 = pbUrl)
        http.routes[pbUrl] = RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64")
        run { refreshAll() }

        assertNull(store(MANGA_REPO))
        val moved = assertNotNull(store(pbUrl))
        assertEquals(added[ExtensionStoreTable.id], moved[ExtensionStoreTable.id])
        assertEquals(ExtensionKind.MANGA, moved[ExtensionStoreTable.type])
        assertEquals(RepoIndexFormat.AUTO, moved[ExtensionStoreTable.format])
        assertFalse(moved[ExtensionStoreTable.isLegacy])
        assertEquals(moved[ExtensionStoreTable.id], trust().getValue(KEIYOUSHI_KEY)[TrustedSignatureTable.store])
        assertTrue(extensions(ExtensionKind.MANGA).values.all { it[ExtensionTable.storeIndexUrl] == pbUrl })
        assertEquals(listOf(pbUrl), serverConfig.extensionStores.value)
    }

    @Test
    fun `a pkg listed by two repos of the same type keeps upstream's highest-version rule`() {
        http.routes += RepoFixtures.yuzonoRoutes()
        anime2Routes()
        run { add(YUZONO_LIST, ExtensionKind.ANIME) }
        run { add(ANIME2_LIST, ExtensionKind.ANIME) }
        run { refreshAll() }
        val onsen = extensions().getValue(ONSEN)
        assertEquals(11L, onsen[ExtensionTable.versionCode])
        assertEquals(ANIME2_REPO, onsen[ExtensionTable.storeIndexUrl])
        assertEquals(1, extensions().count { it.key == ONSEN })
    }

    @Test
    fun `refreshing a kind with no repos is a no-op`() {
        assertTrue(run { refreshAll() }.isEmpty())
        assertTrue(http.requested.isEmpty())
    }

    // --- remove ---

    @Test
    fun `removing a repo that shares its key with another keeps the key trusted and only unloads untrusted extensions`() {
        mangaLegacyRoutes()
        http.routes[KEIYOUSHI_PB] = RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64")
        val first = run { add(MANGA_LIST, ExtensionKind.MANGA) }
        val second = run { add(KEIYOUSHI_PB, ExtensionKind.MANGA) }
        assertEquals(
            first[ExtensionStoreTable.id],
            trust().getValue(KEIYOUSHI_KEY)[TrustedSignatureTable.store],
            "one row per hash, owned by the first repo",
        )

        setExtension(MANGAPILL, hash = KEIYOUSHI_KEY)
        setExtension(NOSOURCES, hash = null)

        run { remove(MANGA_REPO) }
        assertNull(store(MANGA_REPO))
        assertEquals(
            second[ExtensionStoreTable.id],
            trust().getValue(KEIYOUSHI_KEY)[TrustedSignatureTable.store],
            "re-pointed at the other repo",
        )

        val manga = extensions(ExtensionKind.MANGA)
        assertTrue(manga.getValue(MANGAPILL)[ExtensionTable.isLoaded], "its signature is still trusted")
        assertFalse(manga.getValue(NOSOURCES)[ExtensionTable.isLoaded], "no signature: untrusted")
        assertEquals(listOf(NOSOURCES), unloaded)
        assertEquals(listOf(KEIYOUSHI_PB), serverConfig.extensionStores.value)

        run { remove(KEIYOUSHI_PB) }
        assertTrue(trust().isEmpty(), "the last repo with the key is gone, so is the trust")
        assertFalse(extensions(ExtensionKind.MANGA).getValue(MANGAPILL)[ExtensionTable.isLoaded])
        assertEquals(listOf(NOSOURCES, MANGAPILL), unloaded)
        assertEquals(setOf(MANGAPILL, NOSOURCES), extensions().keys, "installed extensions stay; the uninstalled listings are gone")
    }

    @Test
    fun `user trust of a repo key survives removing the repo`() {
        transaction {
            TrustedSignatureTable.insert {
                it[id] = KEIYOUSHI_KEY
                it[origin] = SignatureTrustOrigin.USER
                it[addedAt] = 1
            }
        }
        mangaLegacyRoutes()
        run { add(MANGA_LIST, ExtensionKind.MANGA) }
        val signature = trust().getValue(KEIYOUSHI_KEY)
        assertEquals(SignatureTrustOrigin.USER, signature[TrustedSignatureTable.origin])
        assertNull(signature[TrustedSignatureTable.store])
        assertTrue(store(MANGA_REPO)!![ExtensionStoreTable.signingKeyTrusted])

        setExtension(MANGAPILL, hash = KEIYOUSHI_KEY)
        run { remove(MANGA_REPO) }
        assertEquals(SignatureTrustOrigin.USER, trust().getValue(KEIYOUSHI_KEY)[TrustedSignatureTable.origin])
        assertTrue(extensions().getValue(MANGAPILL)[ExtensionTable.isLoaded])
        assertTrue(unloaded.isEmpty())
    }

    @Test
    fun `removing an unknown repo is NOT_FOUND`() {
        assertEquals(ErrorCode.NOT_FOUND, failure { run { remove("https://nope/index.pb") } }.code)
    }
}
