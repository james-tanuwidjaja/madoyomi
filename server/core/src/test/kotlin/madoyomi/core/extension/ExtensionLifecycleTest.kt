package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.repo.ListingSource
import madoyomi.core.repo.RepoIndexFetcher
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.core.repo.RepoService
import madoyomi.core.repo.StubRepoHttp
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ExtensionLifecycle] with the real APKs from `tools/apks/` on a scratch in-memory H2 database (all migrations
 * applied) and a stubbed network.
 */
@OptIn(ExperimentalPathApi::class)
class ExtensionLifecycleTest {
    companion object {
        const val OTHER_KEY = "1111111111111111111111111111111111111111111111111111111111111111"

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @TempDir
    lateinit var tmp: Path

    private val dbConfig =
        DatabaseConfig {
            useNestedTransactions = true
            @OptIn(ExperimentalKeywordApi::class)
            preserveKeywordCasing = false
        }

    private var previousPrimary: Database? = null
    private lateinit var database: Database
    private val http = StubRepoHttp()
    private var clock = 1_000L
    private val fetcher = RepoIndexFetcher(http)
    private val lifecycle = ExtensionLifecycle(fetcher, http, now = { clock })
    private val repos = RepoService(fetcher, now = { clock }, onTrusted = { lifecycle.reconcileTrust(it) })
    private val progress = mutableListOf<ExtensionInstallProgress>()

    @BeforeEach
    fun setUp() {
        Assumptions.assumeTrue(serverConfig.databaseType.value == DatabaseType.H2)
        previousPrimary = TransactionManager.primaryDatabase
        database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-ext-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig = dbConfig,
            )
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        serverConfig.extensionStores.value = emptyList()
        resetRuntime()
    }

    @AfterEach
    fun tearDown() {
        resetRuntime()
        serverConfig.extensionStores.value = emptyList()
        TransactionManager.closeAndUnregister(database)
        previousPrimary?.let { TransactionManager.defaultDatabase = it }
    }

    /** Nothing loaded, no permits, no installed files. */
    private fun resetRuntime() {
        GetSource.unregisterAllSources()
        PackageTools.jarLoaderMap.values.forEach { it.close() }
        PackageTools.jarLoaderMap.clear()
        ExtensionLoadGate.clear()
        ExtensionKind.entries.forEach { (ExtensionFiles.extensionsRoot / it.dirName).deleteRecursively() }
    }

    // --- helpers ---

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun failure(block: suspend () -> Unit): MadoyomiException = assertFailsWith<MadoyomiException> { run(block) }

    private fun repo(
        kind: ExtensionKind,
        key: String,
        name: String = "${kind.name.lowercase()}-${key.take(4)}",
    ) = TestRepo(http, kind, "https://example.org/$name", key)

    /** Serves [entries] from [repo] and adds it (which trusts its key). */
    private fun addRepo(
        repo: TestRepo,
        vararg entries: TestRepo.Entry,
    ) {
        repo.serve(*entries)
        run { repos.add(repo.listUrl, repo.kind, RepoIndexFormat.JSON) }
    }

    private fun row(pkg: String): ResultRow? =
        transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkg }.firstOrNull() }

    private fun mangaSources(pkg: String): List<ResultRow> =
        transaction {
            val id = row(pkg)!![ExtensionTable.id].value
            SourceTable.selectAll().where { SourceTable.extension eq id }.toList()
        }

    private fun animeSources(pkg: String): List<ResultRow> =
        transaction {
            val id = row(pkg)!![ExtensionTable.id].value
            AnimeSourceTable.selectAll().where { AnimeSourceTable.extension eq id }.toList()
        }

    private fun trustRow(hash: String): ResultRow? =
        transaction { TrustedSignatureTable.selectAll().where { TrustedSignatureTable.id eq hash }.firstOrNull() }

    private fun installWithProgress(
        pkg: String,
        kind: ExtensionKind,
    ): InstallResult =
        runBlocking {
            val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { lifecycle.progress.collect { progress += it } }
            try {
                lifecycle.install(pkg, kind)
            } finally {
                job.cancel()
            }
        }

    private fun loaded(jar: Path) = PackageTools.jarLoaderMap.containsKey(jar.absolutePathString())

    private fun setRow(
        pkg: String,
        block: ExtensionTable.(org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit,
    ) = transaction { ExtensionTable.update({ ExtensionTable.pkgName eq pkg }, body = block) }

    private val tempExtensions: Path get() = Path(Injekt.get<ApplicationDirs>().tempRoot) / "extensions"

    private fun tempEntries(): Set<Path> = if (tempExtensions.isDirectory()) tempExtensions.listDirectoryEntries().toSet() else emptySet()

    // --- manga install ---

    @Test
    fun `a manga extension signed with a trusted repo key installs trusted and loaded`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addRepo(
            repo(ExtensionKind.MANGA, apk.hash),
            TestRepo.Entry(apk, sources = listOf(ListingSource(4242, "Listed", "en", "https://x"))),
        )

        val result = installWithProgress(apk.pkg, ExtensionKind.MANGA)

        assertTrue(result.trusted)
        assertTrue(result.loaded)
        assertEquals(apk.hash, result.signatureHash)
        assertEquals(
            listOf(InstallState.DOWNLOADING, InstallState.VERIFYING, InstallState.CONVERTING, InstallState.INSTALLED),
            progress.map { it.state },
        )
        assertTrue(progress.all { it.pkgName == apk.pkg && it.kind == ExtensionKind.MANGA })

        val row = row(apk.pkg)!!
        assertTrue(row[ExtensionTable.isInstalled])
        assertTrue(row[ExtensionTable.isLoaded])
        assertEquals(apk.hash, row[ExtensionTable.signatureHash])
        assertEquals(ExtensionKind.MANGA, row[ExtensionTable.type])
        assertEquals(apk.inspection.versionName, row[ExtensionTable.versionName])
        assertEquals(apk.fileName, row[ExtensionTable.apkName])
        assertEquals("1.6", row[ExtensionTable.extensionLib])

        val jar = ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)
        assertTrue(jar.exists())
        assertEquals(jar, ExtensionFiles.installedJar(row))
        assertTrue(loaded(jar))
        // every class of the repaired jar passes the JVM verifier, not just the main class
        assertEquals(emptyList(), TestApks.verifyErrors(jar))

        // the live sources replace the listing's (upstream setupJar)
        val sources = mangaSources(apk.pkg)
        assertTrue(sources.isNotEmpty())
        assertTrue(sources.none { it[SourceTable.id].value == 4242L })
        val source = run { GetSource.getSourceOrNull(sources.first()[SourceTable.id].value) }
        assertIs<HttpSource>(source)
        assertEquals("MangaPill", source.name)
    }

    @Test
    fun `an untrusted manga extension is installed but never loaded until its signature is trusted`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addRepo(
            repo(ExtensionKind.MANGA, OTHER_KEY),
            TestRepo.Entry(apk, sources = listOf(ListingSource(4242, "Listed", "en", "https://x"))),
        )

        val result = run { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }
        assertFalse(result.trusted)
        assertFalse(result.loaded)

        val jar = ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)
        assertTrue(jar.exists())
        assertFalse(loaded(jar))
        val row = row(apk.pkg)!!
        assertTrue(row[ExtensionTable.isInstalled])
        assertFalse(row[ExtensionTable.isLoaded])
        assertEquals(apk.hash, row[ExtensionTable.signatureHash])
        assertEquals(apk.inspection.metaData[ExtensionKind.MANGA.classKey], row[ExtensionTable.classFQName])
        // source rows from the listing; the gate refuses to load the jar
        assertEquals(listOf(4242L), mangaSources(apk.pkg).map { it[SourceTable.id].value })
        assertNull(run { GetSource.getSourceOrNull(4242) })
        assertFalse(loaded(jar))

        run { lifecycle.trustSignature(apk.hash) }
        assertEquals(SignatureTrustOrigin.USER, trustRow(apk.hash)!![TrustedSignatureTable.origin])
        assertTrue(row(apk.pkg)!![ExtensionTable.isLoaded])
        assertTrue(loaded(jar))
        val liveIds = mangaSources(apk.pkg).map { it[SourceTable.id].value }
        assertFalse(4242L in liveIds)
        assertIs<HttpSource>(run { GetSource.getSourceOrNull(liveIds.first()) })

        assertFalse(run { lifecycle.untrustSignature(apk.hash) })
        assertFalse(row(apk.pkg)!![ExtensionTable.isLoaded])
        assertFalse(loaded(jar))
        assertNull(run { GetSource.getSourceOrNull(liveIds.first()) })
    }

    @Test
    fun `re-adding a repo that vouches for an installed extension loads it`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addRepo(repo(ExtensionKind.MANGA, OTHER_KEY), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }
        assertFalse(row(apk.pkg)!![ExtensionTable.isLoaded])

        addRepo(repo(ExtensionKind.MANGA, apk.hash, name = "vouching"), TestRepo.Entry(apk))

        assertTrue(row(apk.pkg)!![ExtensionTable.isLoaded])
        assertTrue(loaded(ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)))
    }

    // --- anime install ---

    @Test
    fun `an anime extension installs trusted with ANIME_SOURCE rows from the listing and is not loaded`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        val listed = ListingSource(7777, "AnimeGG", "en", "https://www.animegg.org")
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk, sources = listOf(listed)))

        val result = installWithProgress(apk.pkg, ExtensionKind.ANIME)

        assertTrue(result.trusted)
        assertFalse(result.loaded)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(
            listOf(InstallState.DOWNLOADING, InstallState.VERIFYING, InstallState.CONVERTING, InstallState.INSTALLED),
            progress.map { it.state },
        )
        val row = row(apk.pkg)!!
        assertEquals(ExtensionKind.ANIME, row[ExtensionTable.type])
        assertTrue(row[ExtensionTable.isInstalled])
        assertFalse(row[ExtensionTable.isLoaded])
        assertFalse(row[ExtensionTable.isTorrent])
        assertEquals(apk.hash, row[ExtensionTable.signatureHash])
        assertEquals("AnimeGG", row[ExtensionTable.name])
        assertEquals("14", row[ExtensionTable.extensionLib])
        assertEquals("${apk.pkg}.ExtensionGenerated", row[ExtensionTable.classFQName])

        val sources = animeSources(apk.pkg)
        assertEquals(listOf(7777L), sources.map { it[AnimeSourceTable.id].value })
        assertEquals("https://www.animegg.org", sources.single()[AnimeSourceTable.baseUrl])
        assertTrue(mangaSources(apk.pkg).isEmpty())

        val jar = ExtensionFiles.jarPath(ExtensionKind.ANIME, apk.pkg, apk.inspection.versionName)
        assertTrue(jar.exists())
        assertTrue(jar.startsWith(ExtensionFiles.extensionsRoot / "anime" / apk.pkg))
        assertFalse(loaded(jar))
        assertEquals(emptyList(), TestApks.verifyErrors(jar))
    }

    @Test
    fun `a torrent flag that disagrees with the manifest is a warning and the manifest wins`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk, torrent = true))

        val result = run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }

        assertTrue(result.warnings.any { "torrent" in it })
        assertFalse(row(apk.pkg)!![ExtensionTable.isTorrent])
    }

    // --- rejected installs ---

    @Test
    fun `a tampered APK is rejected and nothing is written`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk, apkBytes = TestApks.tampered(apk.bytes, tmp)))
        val tempBefore = tempEntries()

        val e = failure { installWithProgress(apk.pkg, ExtensionKind.ANIME) }

        assertEquals(ErrorCode.SIGNATURE_INVALID, e.code)
        assertEquals(InstallState.FAILED, progress.last().state)
        assertEquals(ErrorCode.SIGNATURE_INVALID, progress.last().errorCode)
        val row = row(apk.pkg)!!
        assertFalse(row[ExtensionTable.isInstalled])
        assertNull(row[ExtensionTable.signatureHash])
        assertFalse((ExtensionFiles.extensionsRoot / "anime" / apk.pkg).exists())
        assertEquals(tempBefore, tempEntries())
    }

    @Test
    fun `an APK whose version code or package differs from the listing is rejected`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        val other = "eu.kanade.tachiyomi.animeextension.en.other"
        addRepo(
            repo(ExtensionKind.ANIME, apk.hash),
            TestRepo.Entry(apk, versionCode = apk.inspection.versionCode + 1),
            TestRepo.Entry(apk, pkg = other),
        )

        val version = failure { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        assertEquals(ErrorCode.PACKAGE_MISMATCH, version.code)
        assertEquals("version", version.details["reason"])

        val pkg = failure { lifecycle.install(other, ExtensionKind.ANIME) }
        assertEquals(ErrorCode.PACKAGE_MISMATCH, pkg.code)
        assertEquals("package", pkg.details["reason"])

        assertFalse(row(apk.pkg)!![ExtensionTable.isInstalled])
        assertFalse(row(other)!![ExtensionTable.isInstalled])
    }

    @Test
    fun `an anime APK listed by a manga repo is rejected by its feature`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        val fake = "eu.kanade.tachiyomi.extension.en.fake"
        addRepo(repo(ExtensionKind.MANGA, apk.hash), TestRepo.Entry(apk, pkg = fake))

        val e = failure { lifecycle.install(fake, ExtensionKind.MANGA) }

        assertEquals(ErrorCode.PACKAGE_MISMATCH, e.code)
        assertEquals("feature", e.details["reason"])
    }

    @Test
    fun `installing an installed extension, or one no repo lists, fails`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }

        assertEquals(ErrorCode.INSTALL_FAILED, failure { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }.code)
        assertEquals(ErrorCode.NOT_FOUND, failure { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }.code)
        assertEquals(
            ErrorCode.NOT_FOUND,
            failure { lifecycle.install("eu.kanade.tachiyomi.animeextension.en.none", ExtensionKind.ANIME) }.code,
        )
    }

    // --- sideloads ---

    @Test
    fun `a sideloaded jar is refused and a sideloaded APK goes through verification`() {
        val jar = failure { lifecycle.installExternal("PK".byteInputStream(), "tachiyomi-en.something-v1.4.1.jar") }
        assertEquals(ErrorCode.SIGNATURE_INVALID, jar.code)

        val apk = TestApks.apk(TestApks.ANIMEGG)
        val result = run { lifecycle.installExternal(apk.bytes.inputStream(), apk.fileName) }
        assertEquals(apk.pkg, result.pkgName)
        assertEquals(ExtensionKind.ANIME, result.kind)
        assertFalse(result.trusted)
        val row = row(apk.pkg)!!
        assertEquals(ExtensionKind.ANIME, row[ExtensionTable.type])
        assertTrue(row[ExtensionTable.isInstalled])
        assertTrue(animeSources(apk.pkg).isEmpty())

        val tampered = failure { lifecycle.installExternal(TestApks.tampered(apk.bytes, tmp).inputStream(), "x.apk") }
        assertEquals(ErrorCode.SIGNATURE_INVALID, tampered.code)
    }

    // --- update ---

    /**
     * Makes the installed [apk] look like an older version signed with [oldHash]: its jar moves to `-v0.1.jar` and
     * its row gets version code − 1, so the repo's listing is an update.
     */
    private fun pretendOlderInstall(
        apk: TestApks.Apk,
        kind: ExtensionKind,
        oldHash: String,
    ): Path {
        val current = ExtensionFiles.jarPath(kind, apk.pkg, apk.inspection.versionName)
        row(apk.pkg)?.let { ExtensionUnloader.default.unload(it) }
        val old = ExtensionFiles.jarPath(kind, apk.pkg, "0.1")
        current.moveTo(old)
        setRow(apk.pkg) {
            it[versionName] = "0.1"
            it[versionCode] = apk.inspection.versionCode - 1
            it[signatureHash] = oldHash
            it[hasUpdate] = true
        }
        return old
    }

    @Test
    fun `an update signed with the same certificate keeps the trust and replaces the old jar`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        val oldJar = pretendOlderInstall(apk, ExtensionKind.ANIME, apk.hash)

        val result = run { lifecycle.update(apk.pkg, ExtensionKind.ANIME) }

        assertTrue(result.trusted)
        val row = row(apk.pkg)!!
        assertEquals(apk.inspection.versionCode, row[ExtensionTable.versionCode])
        assertEquals(apk.inspection.versionName, row[ExtensionTable.versionName])
        assertFalse(row[ExtensionTable.hasUpdate])
        assertFalse(oldJar.exists())
        assertTrue(ExtensionFiles.jarPath(ExtensionKind.ANIME, apk.pkg, apk.inspection.versionName).exists())
    }

    @Test
    fun `an update of a loaded manga extension reloads it from the new jar`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addRepo(repo(ExtensionKind.MANGA, apk.hash), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }
        val oldJar = pretendOlderInstall(apk, ExtensionKind.MANGA, apk.hash)

        val result = run { lifecycle.update(apk.pkg, ExtensionKind.MANGA) }

        assertTrue(result.loaded)
        assertFalse(oldJar.exists())
        assertFalse(loaded(oldJar))
        assertTrue(loaded(ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)))
        assertTrue(row(apk.pkg)!![ExtensionTable.isLoaded])
    }

    @Test
    fun `an update signed with another trusted certificate goes through`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        pretendOlderInstall(apk, ExtensionKind.ANIME, OTHER_KEY)

        val result = run { lifecycle.update(apk.pkg, ExtensionKind.ANIME) }

        assertTrue(result.trusted)
        assertEquals(apk.hash, row(apk.pkg)!![ExtensionTable.signatureHash])
    }

    @Test
    fun `an update signed with a new untrusted certificate is refused with SIGNATURE_CHANGED and keeps the old version`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, OTHER_KEY), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        // the installed version was signed with the repo's (trusted) key; the new APK is signed with another one
        val oldJar = pretendOlderInstall(apk, ExtensionKind.ANIME, OTHER_KEY)
        val before = row(apk.pkg)!!

        val e = failure { lifecycle.update(apk.pkg, ExtensionKind.ANIME) }

        assertEquals(ErrorCode.SIGNATURE_CHANGED, e.code)
        assertEquals(OTHER_KEY, e.details["oldHash"])
        assertEquals(apk.hash, e.details["newHash"])
        val after = row(apk.pkg)!!
        assertEquals(before[ExtensionTable.versionCode], after[ExtensionTable.versionCode])
        assertEquals(OTHER_KEY, after[ExtensionTable.signatureHash])
        assertTrue(after[ExtensionTable.isInstalled])
        assertTrue(oldJar.exists())
        assertFalse(ExtensionFiles.jarPath(ExtensionKind.ANIME, apk.pkg, apk.inspection.versionName).exists())
        assertNull(trustRow(apk.hash))

        // trusting the new certificate lets the update through
        run { lifecycle.trustSignature(apk.hash) }
        assertTrue(run { lifecycle.update(apk.pkg, ExtensionKind.ANIME) }.trusted)
    }

    @Test
    fun `a refresh listing a higher version marks installed extensions of both kinds as updatable`() {
        val anime = TestApks.apk(TestApks.ANIMEGG)
        val manga = TestApks.apk(TestApks.MANGAPILL)
        val animeRepo = repo(ExtensionKind.ANIME, anime.hash)
        val mangaRepo = repo(ExtensionKind.MANGA, OTHER_KEY)
        addRepo(animeRepo, TestRepo.Entry(anime))
        addRepo(mangaRepo, TestRepo.Entry(manga))
        run { lifecycle.install(anime.pkg, ExtensionKind.ANIME) }
        run { lifecycle.install(manga.pkg, ExtensionKind.MANGA) }
        assertFalse(row(anime.pkg)!![ExtensionTable.hasUpdate])

        animeRepo.serve(TestRepo.Entry(anime, versionCode = anime.inspection.versionCode + 1))
        mangaRepo.serve(TestRepo.Entry(manga, versionCode = manga.inspection.versionCode + 1))
        run { repos.refreshAll() }

        assertTrue(row(anime.pkg)!![ExtensionTable.hasUpdate])
        assertTrue(row(manga.pkg)!![ExtensionTable.hasUpdate])
        // the installed version is unchanged until the update runs
        assertEquals(anime.inspection.versionCode, row(anime.pkg)!![ExtensionTable.versionCode])
        // and the "update" that the repo now lists is checked against the APK it serves
        assertEquals(ErrorCode.PACKAGE_MISMATCH, failure { lifecycle.update(anime.pkg, ExtensionKind.ANIME) }.code)
    }

    @Test
    fun `updating without a newer listed version fails`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, apk.hash), TestRepo.Entry(apk))
        assertEquals(ErrorCode.INSTALL_FAILED, failure { lifecycle.update(apk.pkg, ExtensionKind.ANIME) }.code)
        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        assertEquals(ErrorCode.INSTALL_FAILED, failure { lifecycle.update(apk.pkg, ExtensionKind.ANIME) }.code)
    }

    // --- uninstall ---

    @Test
    fun `uninstalling a loaded manga extension unloads it and removes its files and source rows`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        addRepo(repo(ExtensionKind.MANGA, apk.hash), TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }
        val jar = ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)
        val sourceId = mangaSources(apk.pkg).first()[SourceTable.id].value
        assertNotNull(run { GetSource.getSourceOrNull(sourceId) })

        run { lifecycle.uninstall(apk.pkg, ExtensionKind.MANGA) }

        assertFalse(loaded(jar))
        assertFalse(jar.exists())
        assertFalse(ExtensionFiles.pkgDir(ExtensionKind.MANGA, apk.pkg).exists())
        assertTrue(mangaSources(apk.pkg).isEmpty())
        val row = row(apk.pkg)!!
        assertFalse(row[ExtensionTable.isInstalled])
        assertFalse(row[ExtensionTable.isLoaded])
        assertNull(row[ExtensionTable.signatureHash])
        assertNull(row[ExtensionTable.apkName])
        assertNull(run { GetSource.getSourceOrNull(sourceId) })

        // and it installs again
        assertTrue(run { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }.loaded)
    }

    @Test
    fun `uninstalling an anime extension removes its files and ANIME_SOURCE rows`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(
            repo(ExtensionKind.ANIME, apk.hash),
            TestRepo.Entry(apk, sources = listOf(ListingSource(7777, "AnimeGG", "en", "https://x"))),
        )
        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        assertEquals(1, animeSources(apk.pkg).size)

        run { lifecycle.uninstall(apk.pkg, ExtensionKind.ANIME) }

        assertTrue(animeSources(apk.pkg).isEmpty())
        assertFalse(ExtensionFiles.pkgDir(ExtensionKind.ANIME, apk.pkg).exists())
        assertFalse(row(apk.pkg)!![ExtensionTable.isInstalled])
        assertEquals(ErrorCode.INSTALL_FAILED, failure { lifecycle.uninstall(apk.pkg, ExtensionKind.ANIME) }.code)
    }

    @Test
    fun `uninstalling a sideloaded extension deletes its row`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        run { lifecycle.installExternal(apk.bytes.inputStream(), apk.fileName) }
        run { lifecycle.uninstall(apk.pkg, ExtensionKind.ANIME) }
        assertNull(row(apk.pkg))
    }

    // --- the gate ---

    @Test
    fun `the loader refuses a jar without a permit or with an untrusted one`() {
        val jar = tmp / "some.jar"
        val noPermit = failure { PackageTools.loadExtensionSources(jar, "a.B") }
        assertEquals(ErrorCode.UNTRUSTED, noPermit.code)

        ExtensionLoadGate.permit(jar, OTHER_KEY)
        val untrusted = failure { PackageTools.loadExtensionSources(jar, "a.B") }
        assertEquals(ErrorCode.UNTRUSTED, untrusted.code)
        assertEquals(OTHER_KEY, untrusted.details["signatureHash"])
        assertFalse(loaded(jar))
    }

    @Test
    fun `an upstream install without a signature hash is never loaded`() {
        // what an upstream (or pre-Stage 6) install leaves behind: the flat layout, no signature_hash
        val legacyJar = ExtensionFiles.legacyJarPath("tachiyomi-en.legacy-v1.4.1.apk")
        legacyJar.parent.createDirectories()
        legacyJar.writeText("not really a jar")
        try {
            transaction {
                val id =
                    ExtensionTable
                        .insertAndGetId {
                            it[apkName] = "tachiyomi-en.legacy-v1.4.1.apk"
                            it[name] = "Legacy"
                            it[pkgName] = "eu.kanade.tachiyomi.extension.en.legacy"
                            it[versionName] = "1.4.1"
                            it[versionCode] = 1
                            it[extensionLib] = "1.4"
                            it[lang] = "en"
                            it[contentWarning] = 0
                            it[isInstalled] = true
                            it[classFQName] = "eu.kanade.tachiyomi.extension.en.legacy.Legacy"
                        }.value
                SourceTable.insert {
                    it[SourceTable.id] = 5151
                    it[name] = "Legacy"
                    it[lang] = "en"
                    it[extension] = id
                }
            }
            assertNull(run { GetSource.getSourceOrNull(5151) })
            assertFalse(loaded(legacyJar))

            // uninstall still cleans up the flat-layout jar
            run { lifecycle.uninstall("eu.kanade.tachiyomi.extension.en.legacy", ExtensionKind.MANGA) }
            assertFalse(legacyJar.exists())
        } finally {
            legacyJar.toFile().delete()
        }
    }

    @Test
    fun `removing the repo that trusted a loaded extension stops it from being reloaded`() {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        val repo = repo(ExtensionKind.MANGA, apk.hash)
        addRepo(repo, TestRepo.Entry(apk))
        run { lifecycle.install(apk.pkg, ExtensionKind.MANGA) }
        val sourceId = mangaSources(apk.pkg).first()[SourceTable.id].value
        assertNotNull(run { GetSource.getSourceOrNull(sourceId) })

        run { repos.remove(repo.repoUrl) }

        assertFalse(row(apk.pkg)!![ExtensionTable.isLoaded])
        // Stage 5 caveat closed: GetSource no longer reloads it lazily
        assertNull(run { GetSource.getSourceOrNull(sourceId) })
        assertFalse(loaded(ExtensionFiles.jarPath(ExtensionKind.MANGA, apk.pkg, apk.inspection.versionName)))
    }

    @Test
    fun `trusting an extension without a verified signature fails`() {
        val apk = TestApks.apk(TestApks.ANIMEGG)
        addRepo(repo(ExtensionKind.ANIME, OTHER_KEY), TestRepo.Entry(apk))
        assertEquals(ErrorCode.UNTRUSTED, failure { lifecycle.trustExtension(apk.pkg, ExtensionKind.ANIME) }.code)

        run { lifecycle.install(apk.pkg, ExtensionKind.ANIME) }
        run { lifecycle.trustExtension(apk.pkg, ExtensionKind.ANIME) }
        assertNotNull(trustRow(apk.hash))
        // anime: trusted, still not loaded (Phase 2)
        assertFalse(row(apk.pkg)!![ExtensionTable.isLoaded])
    }
}
