package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.extension.ExtensionFiles
import madoyomi.core.extension.ExtensionLifecycle
import madoyomi.core.extension.ExtensionLifecycleTest
import madoyomi.core.extension.ExtensionUnloader
import madoyomi.core.extension.TestApks
import madoyomi.core.extension.TestRepo
import madoyomi.core.repo.ListingSource
import madoyomi.core.repo.RepoService
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Phase 1 API flow end to end: repos, extensions, install/update/uninstall and trust for both kinds, through
 * GraphQL only, with the real APKs from `tools/apks/` (skipped without them) served by a loopback repo.
 */
class ExtensionApiIntegrationTest : ApiGraphQLTest() {
    companion object {
        const val OTHER_KEY = ExtensionLifecycleTest.OTHER_KEY
        const val MANGAPILL_SOURCE = 8448310129093543312L
        const val ANIMEGG_SOURCE = 2247987965040486818L

        const val EXTENSION_FIELDS = "pkgName type versionName isInstalled hasUpdate isTrusted isLoaded isTorrent signatureHash"
    }

    // --- helpers ---

    private fun repo(
        kind: ExtensionKind,
        key: String,
    ) = TestRepo(repoServer.routes, kind, "${repoServer.base}/${kind.name.lowercase()}-${UUID.randomUUID()}", key)

    /** Serves [entries] from [repo] and adds it through `addExtensionStore`; returns the payload's store. */
    private fun addStore(
        repo: TestRepo,
        vararg entries: TestRepo.Entry,
    ): Map<*, *> {
        repo.serve(*entries)
        val response =
            graphql(
                """
                mutation(${'$'}url: String!, ${'$'}type: ExtensionKind!) {
                    addExtensionStore(input: {indexUrl: ${'$'}url, type: ${'$'}type, format: JSON}) {
                        extensionStore { indexUrl name type format signingKey signingKeyTrusted lastRefreshedAt lastError }
                    }
                }
                """.trimIndent(),
                mapOf("url" to repo.listUrl, "type" to repo.kind.name),
            ).assertNoErrors()
        return response.dataPath("addExtensionStore", "extensionStore") as Map<*, *>
    }

    private fun updateExtension(
        pkg: String,
        patch: String,
    ) = graphql("""mutation { updateExtension(input: {id: "$pkg", patch: {$patch: true}}) { extension { $EXTENSION_FIELDS } } }""")

    private fun install(pkg: String): Map<*, *> =
        updateExtension(pkg, "install").assertNoErrors().dataPath("updateExtension", "extension") as Map<*, *>

    private fun extension(pkg: String): Map<*, *> =
        graphql("""{ extension(pkgName: "$pkg") { $EXTENSION_FIELDS extensionStore { type signingKeyTrusted } } }""")
            .assertNoErrors()
            .dataPath("extension") as Map<*, *>

    private fun extensionPkgs(type: ExtensionKind?): List<Any?> {
        val condition = type?.let { "(condition: {type: $it})" }.orEmpty()
        val nodes = graphql("{ extensions$condition { nodes { pkgName type } } }").assertNoErrors().dataPath("extensions", "nodes")
        return (nodes as List<*>).map { it.path("pkgName") }
    }

    /**
     * Runs [block] while the subscription [query] collects (once [subscribers] has gone up, i.e. the flow is being
     * collected) and until [done]; returns [block]'s result and the events' `data.<field>`.
     */
    private fun <T> withSubscription(
        query: String,
        field: String,
        subscribers: () -> Int,
        done: (List<Any?>) -> Boolean,
        block: () -> T,
    ): Pair<T, List<Any?>> =
        runBlocking {
            val events = Collections.synchronizedList(mutableListOf<Any?>())
            val before = subscribers()
            val job =
                launch(Dispatchers.Default) {
                    subscribe(query).collect { response ->
                        response.assertNoErrors()
                        events += response.dataPath(field)
                    }
                }
            withTimeout(10_000) { while (subscribers() <= before) delay(10) }
            val result = withContext(Dispatchers.IO) { block() }
            withTimeout(10_000) { while (!done(events.toList())) delay(10) }
            job.cancel()
            result to events.toList()
        }

    /** Runs [block] while an `extensionInstallProgress(pkgName)` subscription collects; returns the states it saw. */
    private fun <T> withProgress(
        pkg: String,
        block: () -> T,
    ): Pair<T, List<Any?>> {
        val (result, events) =
            withSubscription(
                """subscription { extensionInstallProgress(pkgName: "$pkg") { pkgName type state errorCode } }""",
                "extensionInstallProgress",
                { ExtensionLifecycle.default.progressSubscribers.value },
                { events -> events.lastOrNull().path("state") in setOf("INSTALLED", "FAILED") },
                block,
            )
        return result to events.map { it.path("state") }
    }

    private fun mangaSourceIds(pkg: String): List<Long> =
        transaction {
            val id =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq pkg }
                    .first()[ExtensionTable.id]
                    .value
            SourceTable.selectAll().where { SourceTable.extension eq id }.map { it[SourceTable.id].value }
        }

    // --- 1. manga: add store → list → install → trusted by the store key → live sources ---

    @Test
    fun `a manga repo is added, listed and installed from, its extension is trusted by the repo key and its sources are live`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        val repo = repo(ExtensionKind.MANGA, apk.hash)

        val store = addStore(repo, TestRepo.Entry(apk))
        assertEquals("MANGA", store["type"])
        assertEquals("JSON", store["format"])
        assertEquals(true, store["signingKeyTrusted"])
        assertEquals(null, store["lastError"])

        val stores = graphql("{ extensionStores(condition: {type: MANGA}) { nodes { indexUrl type signingKeyTrusted } } }")
        assertEquals(1, (stores.assertNoErrors().dataPath("extensionStores", "nodes") as List<*>).size)
        val animeStores = graphql("{ extensionStores(condition: {type: ANIME}) { totalCount } }")
        assertEquals(0, animeStores.assertNoErrors().dataPath("extensionStores", "totalCount"))

        val (fetched, refreshed) =
            withSubscription(
                "subscription { extensionStoreRefreshed(type: MANGA) { indexUrl type ok error extensionCount extensionStore { type } } }",
                "extensionStoreRefreshed",
                { RepoService.default.refreshedSubscribers.value },
                { it.isNotEmpty() },
            ) {
                graphql("mutation { fetchExtensions(input: {type: MANGA}) { extensions { pkgName type } extensionStores { lastError } } }")
                    .assertNoErrors()
            }
        assertEquals(1, refreshed.size)
        assertEquals(true, refreshed.single().path("ok"))
        assertEquals(1, refreshed.single().path("extensionCount"))
        assertEquals("MANGA", refreshed.single().path("extensionStore", "type"))
        assertTrue((fetched.dataPath("fetchExtensions", "extensions") as List<*>).any { it.path("pkgName") == apk.pkg })
        assertEquals(null, fetched.dataPath("fetchExtensions", "extensionStores", "0", "lastError"))
        assertTrue(apk.pkg in extensionPkgs(ExtensionKind.MANGA))
        assertFalse(apk.pkg in extensionPkgs(ExtensionKind.ANIME))

        val (installed, states) = withProgress(apk.pkg) { install(apk.pkg) }
        assertEquals(listOf("DOWNLOADING", "VERIFYING", "CONVERTING", "INSTALLED"), states)
        assertEquals(true, installed["isInstalled"])
        assertEquals("MANGA", installed["type"])
        assertEquals(apk.hash, installed["signatureHash"])
        assertEquals(true, installed["isTrusted"])
        assertEquals(true, installed["isLoaded"])

        val sources = graphql("{ sources { nodes { id name lang extension { pkgName } } } }").assertNoErrors()
        val pill = (sources.dataPath("sources", "nodes") as List<*>).filter { it.path("extension", "pkgName") == apk.pkg }
        assertEquals(listOf(MANGAPILL_SOURCE.toString()), pill.map { it.path("id") })
        assertEquals(listOf(MANGAPILL_SOURCE), mangaSourceIds(apk.pkg))
    }

    // --- 2. key mismatch → untrusted → not loaded → trustSignature → loaded ---

    @Test
    fun `an extension signed with another key than the repo's is not loaded until its signature is trusted`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        val listed = ListingSource(MANGAPILL_SOURCE, "MangaPill", "en", "https://mangapill.com")
        addStore(repo(ExtensionKind.MANGA, OTHER_KEY), TestRepo.Entry(apk, sources = listOf(listed)))

        val installed = install(apk.pkg)
        assertEquals(apk.hash, installed["signatureHash"])
        assertEquals(false, installed["isTrusted"])
        assertEquals(false, installed["isLoaded"])
        assertEquals(listOf(MANGAPILL_SOURCE), mangaSourceIds(apk.pkg), "source rows come from the listing")

        val browse =
            graphql(
                """mutation { fetchSourceManga(input: {source: "$MANGAPILL_SOURCE", type: POPULAR, page: 1}) { hasNextPage } }""",
            )
        assertEquals("UNTRUSTED", browse.errorCode, "errors: ${browse.errors}")
        assertEquals(apk.hash, (browse.errors!!.first().extensions!!["details"] as Map<*, *>)["signatureHash"])

        val trusted =
            graphql(
                """mutation { trustSignature(input: {hash: "${apk.hash}"}) {
                    trustedSignature { hash origin } extensions { pkgName isTrusted isLoaded } } }""",
            ).assertNoErrors()
        assertEquals("USER", trusted.dataPath("trustSignature", "trustedSignature", "origin"))
        assertEquals(true, trusted.dataPath("trustSignature", "extensions", "0", "isLoaded"))
        assertEquals(true, extension(apk.pkg)["isTrusted"])

        val signatures = graphql("{ trustedSignatures { hash origin extensionStore { type } extensions { pkgName } } }").assertNoErrors()
        val byHash = (signatures.dataPath("trustedSignatures") as List<*>).associateBy { it.path("hash") }
        assertEquals("STORE", byHash[OTHER_KEY].path("origin"))
        assertEquals("MANGA", byHash[OTHER_KEY].path("extensionStore", "type"))
        assertEquals("USER", byHash[apk.hash].path("origin"))
        assertEquals(apk.pkg, byHash[apk.hash].path("extensions", "0", "pkgName"))

        val untrusted =
            graphql("""mutation { untrustSignature(input: {hash: "${apk.hash}"}) { hash isTrusted extensions { isLoaded } } }""")
                .assertNoErrors()
        assertEquals(false, untrusted.dataPath("untrustSignature", "isTrusted"))
        assertEquals(false, untrusted.dataPath("untrustSignature", "extensions", "0", "isLoaded"))
    }

    // --- 3. anime: yuzono-shaped repo → install AnimeGG → trusted and loaded → animeSources from the live source ---

    @Test
    fun `an anime repo is added and installed from, and its trusted extension is loaded and its sources listed`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        val listed = ListingSource(ANIMEGG_SOURCE, "AnimeGG", "en", "https://www.animegg.org")
        val store = addStore(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk, sources = listOf(listed)))
        assertEquals("ANIME", store["type"])
        assertEquals(true, store["signingKeyTrusted"])

        assertTrue(apk.pkg in extensionPkgs(ExtensionKind.ANIME))
        assertFalse(apk.pkg in extensionPkgs(ExtensionKind.MANGA))
        assertTrue(apk.pkg in extensionPkgs(null), "no type: both kinds")

        val installed = install(apk.pkg)
        assertEquals("ANIME", installed["type"])
        assertEquals(true, installed["isTrusted"])
        assertEquals(true, installed["isLoaded"])
        assertEquals("ANIME", extension(apk.pkg).path("extensionStore", "type"))

        val sources =
            graphql(
                """{ animeSources(condition: {extensionPkgName: "${apk.pkg}"}) {
                    totalCount nodes { id name lang baseUrl contentWarning extension { pkgName type isLoaded } } } }""",
            ).assertNoErrors()
        assertEquals(1, sources.dataPath("animeSources", "totalCount"))
        val node = sources.dataPath("animeSources", "nodes", "0")
        assertEquals(ANIMEGG_SOURCE.toString(), node.path("id"))
        assertEquals("https://www.animegg.org", node.path("baseUrl"))
        assertEquals(apk.pkg, node.path("extension", "pkgName"))
        assertEquals(true, node.path("extension", "isLoaded"))
        assertEquals(
            1,
            (graphql("""{ animeSources(condition: {id: "$ANIMEGG_SOURCE"}) { totalCount } }""").dataPath("animeSources", "totalCount")),
        )

        val trusted =
            graphql(
                """mutation { trustExtension(input: {pkgName: "${apk.pkg}"}) { extension { isTrusted isLoaded } trustedSignature { origin } } }""",
            ).assertNoErrors()
        assertEquals(true, trusted.dataPath("trustExtension", "extension", "isTrusted"))
        assertEquals(true, trusted.dataPath("trustExtension", "extension", "isLoaded"))
        assertEquals("USER", trusted.dataPath("trustExtension", "trustedSignature", "origin"))
    }

    // --- 4. update: the same certificate goes through, a changed one → SIGNATURE_CHANGED ---

    /** Makes the installed [apk] look like an older version (`0.1`) signed with [oldHash], so the repo lists an update. */
    private fun pretendOlderInstall(
        apk: TestApks.Apk,
        kind: ExtensionKind,
        oldHash: String,
    ): Path {
        val current = ExtensionFiles.jarPath(kind, apk.pkg, apk.inspection.versionName)
        val old = ExtensionFiles.jarPath(kind, apk.pkg, "0.1")
        val row = transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq apk.pkg }.first() }
        ExtensionUnloader.default.unload(row)
        current.moveTo(old)
        transaction {
            ExtensionTable.update({ ExtensionTable.pkgName eq apk.pkg }) {
                it[versionName] = "0.1"
                it[versionCode] = apk.inspection.versionCode - 1
                it[signatureHash] = oldHash
                it[hasUpdate] = true
            }
        }
        return old
    }

    @Test
    fun `an update signed with the same certificate replaces the old version and reloads it`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addStore(repo(ExtensionKind.MANGA, apk.hash), TestRepo.Entry(apk))
        install(apk.pkg)
        val old = pretendOlderInstall(apk, ExtensionKind.MANGA, apk.hash)
        assertEquals(true, extension(apk.pkg)["hasUpdate"])

        val (updated, states) = withProgress(apk.pkg) { updateExtension(apk.pkg, "update").assertNoErrors() }

        assertEquals(listOf("DOWNLOADING", "VERIFYING", "CONVERTING", "INSTALLED"), states)
        val after = updated.dataPath("updateExtension", "extension")
        assertEquals(apk.inspection.versionName, after.path("versionName"))
        assertEquals(false, after.path("hasUpdate"))
        assertEquals(true, after.path("isTrusted"))
        assertEquals(true, after.path("isLoaded"))
        assertFalse(old.exists())
        assertEquals("INSTALL_FAILED", updateExtension(apk.pkg, "update").errorCode, "no update listed any more")
    }

    @Test
    fun `an update signed with a new untrusted certificate fails with SIGNATURE_CHANGED and keeps the installed version`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addStore(repo(ExtensionKind.ANIME, OTHER_KEY), TestRepo.Entry(apk))
        install(apk.pkg)
        // as if the installed version had been signed with the repo's (trusted) key, and the repo now lists a newer
        // version signed with another one (the real APK)
        val old = pretendOlderInstall(apk, ExtensionKind.ANIME, OTHER_KEY)
        assertEquals(true, extension(apk.pkg)["isTrusted"])

        val response = updateExtension(apk.pkg, "update")

        assertEquals("SIGNATURE_CHANGED", response.errorCode, "errors: ${response.errors}")
        val after = extension(apk.pkg)
        assertEquals("0.1", after["versionName"])
        assertEquals(OTHER_KEY, after["signatureHash"])
        assertTrue(old.exists())
    }

    // --- 5. uninstall removes files and rows; the single-id mutation says why it can't act ---

    @Test
    fun `uninstall removes the jar and the source rows, and updateExtension reports what it cannot do`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addStore(repo(ExtensionKind.MANGA, apk.hash), TestRepo.Entry(apk))
        install(apk.pkg)
        val jar = ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)
        assertTrue(jar.exists())

        assertEquals("INSTALL_FAILED", updateExtension(apk.pkg, "install").errorCode, "installing an installed extension")

        val uninstalled = updateExtension(apk.pkg, "uninstall").assertNoErrors().dataPath("updateExtension", "extension")
        assertNotNull(uninstalled)
        assertEquals(false, uninstalled.path("isInstalled"))
        assertEquals(null, uninstalled.path("signatureHash"))
        assertFalse(jar.exists())
        assertFalse(ExtensionFiles.pkgDir(ExtensionKind.MANGA, apk.pkg).exists())
        assertEquals(emptyList(), mangaSourceIds(apk.pkg))

        assertEquals("INSTALL_FAILED", updateExtension(apk.pkg, "uninstall").errorCode, "uninstalling twice")
        assertEquals("NOT_FOUND", updateExtension("com.example.nothing", "install").errorCode)

        // the batch mutation keeps upstream's behaviour: what doesn't apply is skipped
        graphql("""mutation { updateExtensions(input: {ids: ["${apk.pkg}"], patch: {uninstall: true}}) { extensions { pkgName } } }""")
            .assertNoErrors()
    }

    @Test
    fun `uninstalling an anime extension removes its ANIME_SOURCE rows`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        val listed = ListingSource(ANIMEGG_SOURCE, "AnimeGG", "en", "https://www.animegg.org")
        addStore(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk, sources = listOf(listed)))
        install(apk.pkg)

        updateExtension(apk.pkg, "uninstall").assertNoErrors()

        assertEquals(0L, transaction { AnimeSourceTable.selectAll().count() })
        assertEquals(0, graphql("{ animeSources { totalCount } }").dataPath("animeSources", "totalCount"))
        assertFalse(ExtensionFiles.pkgDir(ExtensionKind.ANIME, apk.pkg).exists())
    }
}
