package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import madoyomi.core.anime.AnimeCategories
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.model.table.AnimeCategoryAnimeTable
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeHistoryTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.backup.BackupFiles
import madoyomi.core.backup.BackupScheduler
import madoyomi.core.backup.BackupSettingsStore
import madoyomi.core.backup.MadoyomiBackupExport
import madoyomi.core.backup.MadoyomiBackupRestore
import madoyomi.core.backup.RestoreState
import madoyomi.core.downloads.DownloadSettings
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.extension.ExtensionFiles
import madoyomi.core.extension.ExtensionLoadGate
import madoyomi.core.extension.SignatureTrust
import madoyomi.core.extension.SignatureTrustOrigin
import madoyomi.core.extension.TestApks
import madoyomi.core.extension.TestRepo
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.core.library.LibraryUpdateSettings
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.reader.ReaderSettings
import madoyomi.core.reader.ReaderSettingsStore
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `.madoyomi-backup` files (`madoyomi.core.backup`) through the GraphQL API: the round trip from a data
 * dir with both kinds into a fresh database, field by field, the extension reinstalled from its repo; merge against
 * replace; an extension no repo lists; a file that isn't a backup; the schedule's pruning; a `.tachibk`'s manga part.
 */
class BackupRestoreApiTest : AnimeApiTest() {
    private lateinit var dir: File
    private val freshDatabases = mutableListOf<Database>()
    private val anime = FakeSourceIds.ANIME

    private object FakeSourceIds {
        const val ANIME = 7_700L
    }

    @BeforeEach
    fun setUpBackups() {
        dir = createTempDirectory("madoyomi-backups").toFile()
        BackupFiles.dirOverride = File(dir, "backups")
        useFreshSettingsFiles()
    }

    @AfterEach
    fun tearDownBackups() {
        BackupFiles.dirOverride = null
        listOf<() -> Unit>(
            { ReaderSettingsStore.fileOverride = null },
            { LibraryUpdateSettingsStore.fileOverride = null },
            { DownloadSettingsStore.fileOverride = null },
            { BackupSettingsStore.fileOverride = null },
        ).forEach { it() }
        ReaderSettingsStore.reload()
        LibraryUpdateSettingsStore.reload()
        DownloadSettingsStore.reload()
        BackupSettingsStore.reload()
        BackupScheduler.stop()
        sourcePrefs(MANGAPILL_SOURCE).edit().clear().apply()
        dir.deleteRecursively()
        freshDatabases.forEach { TransactionManager.closeAndUnregister(it) }
        freshDatabases.clear()
    }

    private fun useFreshSettingsFiles() {
        val settings = File(dir, "settings-${UUID.randomUUID()}").also { it.mkdirs() }
        ReaderSettingsStore.fileOverride = File(settings, ReaderSettingsStore.FILE_NAME)
        LibraryUpdateSettingsStore.fileOverride = File(settings, LibraryUpdateSettingsStore.FILE_NAME)
        DownloadSettingsStore.fileOverride = File(settings, DownloadSettingsStore.FILE_NAME)
        BackupSettingsStore.fileOverride = File(settings, BackupSettingsStore.FILE_NAME)
        ReaderSettingsStore.reload()
        LibraryUpdateSettingsStore.reload()
        DownloadSettingsStore.reload()
        BackupSettingsStore.reload()
    }

    /** A new empty database with every migration, nothing loaded, no extension files, default settings files. */
    @OptIn(ExperimentalKeywordApi::class, ExperimentalPathApi::class)
    private fun freshInstall() {
        val database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-fresh-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        preserveKeywordCasing = false
                    },
            )
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        TransactionManager.defaultDatabase = database
        freshDatabases += database
        GetSource.unregisterAllSources()
        AnimeGetSource.unregisterAllSources()
        PackageTools.jarLoaderMap.values.forEach { it.close() }
        PackageTools.jarLoaderMap.clear()
        ExtensionLoadGate.clear()
        ExtensionKind.entries.forEach { (ExtensionFiles.extensionsRoot / it.dirName).deleteRecursively() }
        serverConfig.extensionStores.value = emptyList()
        useFreshSettingsFiles()
        sourcePrefs(MANGAPILL_SOURCE).edit().clear().apply()
    }

    private fun sourcePrefs(id: Long) = Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)

    // --- seeding ---

    private fun installMangaPill(): TestApks.Apk {
        val apk = TestApks.apk(TestApks.MANGAPILL)
        val repo = TestRepo(repoServer.routes, ExtensionKind.MANGA, "${repoServer.base}/manga-${UUID.randomUUID()}", apk.hash)
        repo.serve(TestRepo.Entry(apk))
        graphql(
            """
            mutation(${'$'}url: String!) {
                addExtensionStore(input: {indexUrl: ${'$'}url, type: MANGA, format: JSON}) { extensionStore { indexUrl } }
            }
            """.trimIndent(),
            mapOf("url" to repo.listUrl),
        ).assertNoErrors()
        graphql("""mutation { updateExtension(input: {id: "${apk.pkg}", patch: {install: true}}) { extension { isInstalled } } }""")
            .assertNoErrors()
        return apk
    }

    private fun mangaRow(
        title: String,
        source: Long = MANGAPILL_SOURCE,
    ): Int =
        dbTransaction {
            MangaTable
                .insertAndGetId {
                    it[url] = "/manga/$title"
                    it[this.title] = title
                    it[initialized] = true
                    it[sourceReference] = source
                    it[description] = "About $title"
                    it[memo] = JsonObject(emptyMap())
                }.value
        }

    private fun inMangaLibrary(
        manga: Int,
        at: Long,
    ) = dbTransaction {
        MangaUserTable.upsert(MangaUserTable.user, MangaUserTable.manga) {
            it[user] = USER
            it[this.manga] = manga
            it[inLibrary] = true
            it[inLibraryAt] = at
        }
    }

    private fun chapterRow(
        manga: Int,
        order: Int,
        read: Boolean = false,
        page: Int = 0,
        lastReadAt: Long = 0,
    ): Int =
        dbTransaction {
            val id =
                ChapterTable
                    .insertAndGetId {
                        it[url] = "/manga/$manga/ch/$order"
                        it[name] = "Chapter $order"
                        it[chapter_number] = order.toFloat()
                        it[sourceOrder] = order
                        it[pageCount] = 20
                        it[fetchedAt] = 1_700_000_000L + order
                        it[this.manga] = manga
                    }.value
            ChapterUserTable.insert {
                it[chapter] = id
                it[user] = USER
                it[isRead] = read
                it[lastPageRead] = page
                it[this.lastReadAt] = lastReadAt
            }
            id
        }

    private fun mangaCategory(
        name: String,
        order: Int,
        includeInUpdate: Int = -1,
    ): Int =
        dbTransaction {
            CategoryTable
                .insertAndGetId {
                    it[CategoryTable.name] = name
                    it[CategoryTable.order] = order
                    it[user] = USER
                    it[CategoryTable.includeInUpdate] = includeInUpdate
                }.value
        }

    private fun animeInLibrary(
        id: Int,
        at: Long,
    ) = dbTransaction {
        AnimeTable.update({ AnimeTable.id eq id }) {
            it[inLibrary] = true
            it[addedAt] = at
            it[initialized] = true
            it[description] = "About $id"
        }
    }

    private fun episode(
        id: Int,
        watched: Boolean = false,
        position: Long = 0,
        total: Long = 0,
        lastSeen: Long = 0,
    ) = dbTransaction {
        EpisodeTable.update({ EpisodeTable.id eq id }) {
            it[EpisodeTable.watched] = watched
            it[lastSecondSeen] = position
            it[totalSeconds] = total
            it[fetchedAt] = 1_700_000_000L + id
        }
        if (lastSeen > 0) {
            AnimeHistoryTable.upsert(AnimeHistoryTable.episode) {
                it[episode] = id
                it[AnimeHistoryTable.lastSeen] = lastSeen
            }
        }
    }

    private fun animeCategory(
        name: String,
        meta: Map<String, String> = emptyMap(),
    ): Int {
        val id = AnimeCategories.create(name)
        dbTransaction {
            AnimeCategoryTable.update({ AnimeCategoryTable.id eq id }) {
                it[AnimeCategoryTable.meta] = JsonObject(meta.mapValues { (_, v) -> JsonPrimitive(v) })
            }
        }
        return id
    }

    private fun assign(
        animeId: Int,
        categoryId: Int,
    ) = dbTransaction {
        AnimeCategoryAnimeTable.insert {
            it[category] = categoryId
            it[anime] = animeId
        }
    }

    /** Both kinds' library with every kind of mark; returns the anime ids (a library entry, its season, another). */
    private fun seedLibrary(): List<Int> {
        // anime: a Seasons entry with one season, and a plain entry
        val parent = animeRow(anime, "/a/parent", "Parent")
        val season = animeRow(anime, "/a/parent/s1", "Parent S1")
        dbTransaction {
            AnimeTable.update({ AnimeTable.id eq season }) {
                it[parentId] = parent
                it[seasonSourceOrder] = 1
                it[seasonNumber] = 1.0
            }
        }
        val other = animeRow(anime, "/a/other", "Other")
        animeInLibrary(parent, 1_700_000_100)
        animeInLibrary(other, 1_700_000_200)
        val s1 = episodeRow(season, "/a/parent/s1/e1", 1)
        val s2 = episodeRow(season, "/a/parent/s1/e2", 2)
        val o1 = episodeRow(other, "/a/other/e1", 1)
        val o2 = episodeRow(other, "/a/other/e2", 2)
        episode(s1, watched = true, position = 1400, total = 1420, lastSeen = 1_700_001_000)
        episode(s2, position = 300, total = 1420, lastSeen = 1_700_002_000)
        episode(o1)
        episode(o2, watched = true)
        dbTransaction { EpisodeTable.update({ EpisodeTable.id eq o1 }) { it[bookmark] = true } }
        val watching = animeCategory("Watching", mapOf("madoyomi.library.sort" to "title:desc"))
        val later = animeCategory("Later")
        dbTransaction { AnimeCategoryTable.update({ AnimeCategoryTable.id eq later }) { it[includeInUpdate] = 0 } }
        assign(parent, watching)
        assign(other, watching)
        assign(other, later)

        // manga: two library entries, one with a reader setting in its meta, two categories
        val kingdom = mangaRow("Kingdom")
        val osora = mangaRow("Osora")
        inMangaLibrary(kingdom, 1_700_000_300)
        inMangaLibrary(osora, 1_700_000_400)
        chapterRow(kingdom, 1, read = true, page = 19, lastReadAt = 1_700_003_000)
        chapterRow(kingdom, 2, page = 7, lastReadAt = 1_700_004_000)
        chapterRow(osora, 1)
        val reading = mangaCategory("Reading", 1)
        mangaCategory("Paused", 2, includeInUpdate = 0)
        dbTransaction {
            CategoryMangaTable.insert {
                it[category] = reading
                it[manga] = kingdom
                it[user] = USER
            }
            CategoryTable.update({ CategoryTable.id eq reading }) { it[isDefault] = true }
        }
        graphql(
            """mutation { setMangaMeta(input: {meta: {mangaId: $kingdom, key: "madoyomi.reader.mode", value: "webtoon"}}) { meta { key } } }""",
        ).assertNoErrors()
        graphql(
            """mutation { setCategoryMeta(input: {meta: {categoryId: $reading, key: "madoyomi.library.sort", value: "unread:desc"}}) { meta { key } } }""",
        ).assertNoErrors()
        return listOf(parent, season, other)
    }

    private fun seedSettings() {
        LibraryUpdateSettingsStore.update(LibraryUpdateSettings.Patch(skipUnseen = true, intervalHours = 12))
        ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = 512))
        DownloadSettingsStore.update(DownloadSettings.Patch(maxParallel = 2))
        BackupSettingsStore.update(
            madoyomi.core.backup.BackupSettings
                .Patch(keep = 3),
        )
        BackupScheduler.stop()
        sourcePrefs(MANGAPILL_SOURCE)
            .edit()
            .putString("pref_quality", "high")
            .putString("__PRIVATE_token", "secret")
            .apply()
    }

    // --- the state compared ---

    private val json = Json

    private fun settingsSnapshot(): Map<String, Any?> =
        MadoyomiBackupExport.settingsFiles().mapValues { (_, file) ->
            if (!file.isFile) null else (json.parseToJsonElement(file.readText()) as JsonObject) - "scheduleAnchorAt"
        }

    private fun snapshot(): Map<String, Any?> =
        dbTransaction {
            val urlById = AnimeTable.selectAll().associate { it[AnimeTable.id].value to it[AnimeTable.url] }
            val history =
                AnimeHistoryTable.selectAll().associate { it[AnimeHistoryTable.episode].value to it[AnimeHistoryTable.lastSeen] }
            val animeCategoryNames =
                AnimeCategoryTable.selectAll().associate {
                    it[AnimeCategoryTable.id].value to
                        it[AnimeCategoryTable.name]
                }
            val animeMembership =
                AnimeCategoryAnimeTable.selectAll().groupBy(
                    { urlById[it[AnimeCategoryAnimeTable.anime].value] },
                    { animeCategoryNames[it[AnimeCategoryAnimeTable.category].value] },
                )
            val anime =
                AnimeTable.selectAll().sortedBy { it[AnimeTable.url] }.map { a ->
                    val episodes =
                        EpisodeTable
                            .selectAll()
                            .where { EpisodeTable.anime eq a[AnimeTable.id].value }
                            .sortedBy { it[EpisodeTable.url] }
                            .map { e ->
                                listOf(
                                    e[EpisodeTable.url],
                                    e[EpisodeTable.name],
                                    e[EpisodeTable.sourceOrder],
                                    e[EpisodeTable.watched],
                                    e[EpisodeTable.bookmark],
                                    e[EpisodeTable.lastSecondSeen],
                                    e[EpisodeTable.totalSeconds],
                                    e[EpisodeTable.fetchedAt],
                                    history[e[EpisodeTable.id].value],
                                )
                            }
                    listOf(
                        a[AnimeTable.sourceReference],
                        a[AnimeTable.url],
                        a[AnimeTable.title],
                        a[AnimeTable.inLibrary],
                        a[AnimeTable.addedAt],
                        a[AnimeTable.description],
                        a[AnimeTable.parentId]?.value?.let(urlById::get),
                        a[AnimeTable.seasonSourceOrder],
                        a[AnimeTable.seasonNumber],
                        animeMembership[a[AnimeTable.url]].orEmpty().sortedBy { it },
                        episodes,
                    )
                }
            val animeCategories =
                AnimeCategoryTable.selectAll().sortedBy { it[AnimeCategoryTable.order] }.map {
                    listOf(
                        it[AnimeCategoryTable.name],
                        it[AnimeCategoryTable.order],
                        it[AnimeCategoryTable.isDefault],
                        it[AnimeCategoryTable.isDefaultCategory],
                        it[AnimeCategoryTable.includeInUpdate],
                        it[AnimeCategoryTable.includeInDownload],
                        it[AnimeCategoryTable.meta],
                    )
                }
            val mangaCategoryNames = CategoryTable.selectAll().associate { it[CategoryTable.id].value to it[CategoryTable.name] }
            val mangaUrl = MangaTable.selectAll().associate { it[MangaTable.id].value to it[MangaTable.url] }
            val manga =
                (MangaTable innerJoinUser USER)
                    .map { m ->
                        val id = m[MangaTable.id].value
                        val chapters =
                            (ChapterTable innerJoinChapterUser USER)
                                .filter { it[ChapterTable.manga].value == id }
                                .sortedBy { it[ChapterTable.url] }
                                .map {
                                    listOf(
                                        it[ChapterTable.url],
                                        it[ChapterTable.name],
                                        it[ChapterUserTable.isRead],
                                        it[ChapterUserTable.isBookmarked],
                                        it[ChapterUserTable.lastPageRead],
                                        it[ChapterUserTable.lastReadAt],
                                    )
                                }
                        val categories =
                            CategoryMangaTable
                                .selectAll()
                                .where { CategoryMangaTable.manga eq id }
                                .map { mangaCategoryNames[it[CategoryMangaTable.category].value] }
                                .sortedBy { it }
                        val meta =
                            suwayomi.tachidesk.manga.model.table.MangaMetaTable
                                .selectAll()
                                .where { suwayomi.tachidesk.manga.model.table.MangaMetaTable.ref eq id }
                                .associate {
                                    it[suwayomi.tachidesk.manga.model.table.MangaMetaTable.key] to
                                        it[suwayomi.tachidesk.manga.model.table.MangaMetaTable.value]
                                }
                        listOf(
                            m[MangaTable.sourceReference],
                            mangaUrl[id],
                            m[MangaTable.title],
                            m[MangaUserTable.inLibrary],
                            m[MangaUserTable.inLibraryAt],
                            categories,
                            meta,
                            chapters,
                        )
                    }.sortedBy { it[1] as String }
            val mangaCategories =
                CategoryTable.selectAll().where { CategoryTable.user eq USER }.sortedBy { it[CategoryTable.order] }.map {
                    val meta =
                        suwayomi.tachidesk.manga.model.table.CategoryMetaTable
                            .selectAll()
                            .where { suwayomi.tachidesk.manga.model.table.CategoryMetaTable.ref eq it[CategoryTable.id].value }
                            .associate { m ->
                                m[suwayomi.tachidesk.manga.model.table.CategoryMetaTable.key] to
                                    m[suwayomi.tachidesk.manga.model.table.CategoryMetaTable.value]
                            }
                    listOf(
                        it[CategoryTable.name],
                        it[CategoryTable.order],
                        it[CategoryTable.isDefault],
                        it[CategoryTable.includeInUpdate],
                        it[CategoryTable.includeInDownload],
                        meta,
                    )
                }
            mapOf(
                "anime" to anime,
                "animeCategories" to animeCategories,
                "manga" to manga,
                "mangaCategories" to mangaCategories,
                "repos" to
                    suwayomi.tachidesk.manga.model.table.ExtensionStoreTable.selectAll().map {
                        listOf(
                            it[suwayomi.tachidesk.manga.model.table.ExtensionStoreTable.indexUrl],
                            it[suwayomi.tachidesk.manga.model.table.ExtensionStoreTable.type],
                            it[suwayomi.tachidesk.manga.model.table.ExtensionStoreTable.signingKeyTrusted],
                        )
                    },
                "certificates" to
                    TrustedSignatureTable
                        .selectAll()
                        .where { TrustedSignatureTable.origin eq SignatureTrustOrigin.USER }
                        .map { it[TrustedSignatureTable.id].value }
                        .sorted(),
                "extensions" to
                    ExtensionTable
                        .selectAll()
                        .where { ExtensionTable.isInstalled eq true }
                        .map { listOf(it[ExtensionTable.pkgName], it[ExtensionTable.type]) }
                        .sortedBy { it[0] as String },
                "mangaSources" to SourceTable.selectAll().map { it[SourceTable.id].value }.sorted(),
            )
        }

    private infix fun MangaTable.innerJoinUser(user: Int) =
        (this innerJoin MangaUserTable).selectAll().where { MangaUserTable.user eq user }.toList()

    private infix fun ChapterTable.innerJoinChapterUser(user: Int) =
        (this innerJoin ChapterUserTable).selectAll().where { ChapterUserTable.user eq user }.toList()

    // --- the API ---

    private fun create(): String =
        graphql("mutation { createMadoyomiBackup(input: {}) { backup { name size automatic format } } }")
            .assertNoErrors()
            .dataPath("createMadoyomiBackup", "backup", "name") as String

    private fun validate(name: String) =
        graphql(
            """query(${'$'}n: String!) { validateMadoyomiBackup(name: ${'$'}n) {
                format anime seasons episodes watchedEpisodes animeHistory animeCategories manga chapters readChapters mangaHistory
                mangaCategories settingsFiles sourcePreferences
                repos { indexUrl kind added } certificates { hash trusted }
                extensions { pkgName kind installed inRepo trustedAfterRestore }
                missingSources { id kind entries extension }
            } }""",
            mapOf("n" to name),
        )

    private fun restore(
        name: String,
        mode: String = "MERGE",
        repos: List<String> = emptyList(),
        certificates: List<String> = emptyList(),
        extensions: List<String> = emptyList(),
    ): madoyomi.core.backup.RestoreStatus {
        graphql(
            """mutation(${'$'}n: String!, ${'$'}r: [String!], ${'$'}c: [String!], ${'$'}e: [String!]) {
                restoreMadoyomiBackup(input: {name: ${'$'}n, mode: $mode, repos: ${'$'}r, certificates: ${'$'}c, extensions: ${'$'}e}) {
                    status { state }
                }
            }""",
            mapOf("n" to name, "r" to repos, "c" to certificates, "e" to extensions),
        ).assertNoErrors()
        return runBlocking { withTimeout(60.seconds) { MadoyomiBackupRestore.awaitEnd() } }
    }

    // --- tests ---

    @Test
    fun `a backup restored into a fresh install gives back the library, categories, progress, history, repos, trust and settings`() {
        val apk = installMangaPill()
        register(FakeAnimeSource(anime))
        seedLibrary()
        seedSettings()
        val certificate = "ab".repeat(32)
        SignatureTrust.trust(certificate, 1_700_000_500)
        val before = snapshot()
        val settingsBefore = settingsSnapshot()

        val name = create()
        assertTrue(name.endsWith(".madoyomi-backup"))
        val file = File(BackupFiles.dir(), name)
        assertFalse(file.readBytes().decodeToString().contains("secret"), "a private preference stays out")

        freshInstall()
        val report = validate(name).assertNoErrors().dataPath("validateMadoyomiBackup") as Map<*, *>
        assertEquals("MADOYOMI", report["format"])
        assertEquals(2, report["anime"])
        assertEquals(1, report["seasons"])
        assertEquals(4, report["episodes"])
        assertEquals(2, report["watchedEpisodes"])
        assertEquals(2, report["animeHistory"])
        assertEquals(2, report["animeCategories"])
        assertEquals(2, report["manga"])
        assertEquals(3, report["chapters"])
        assertEquals(1, report["readChapters"])
        assertEquals(2, report["mangaCategories"])
        assertEquals(1, report["sourcePreferences"])
        assertEquals(false, report["repos"].path("0", "added"))
        assertEquals(listOf(mapOf("hash" to certificate, "trusted" to false)), report["certificates"])
        val ext = report["extensions"].asList.single { (it as Map<*, *>)["pkgName"] == apk.pkg } as Map<*, *>
        assertEquals(false, ext["installed"])
        assertEquals(true, ext["inRepo"])
        assertEquals(true, ext["trustedAfterRestore"])
        val missing = report["missingSources"].asList.map { (it as Map<*, *>)["kind"] to (it["extension"]) }.toSet()
        assertEquals(setOf("MANGA" to apk.pkg, "ANIME" to "test.animeextension.s$anime"), missing)

        register(FakeAnimeSource(anime))
        val status =
            restore(
                name,
                repos = (report["repos"].asList).map { (it as Map<*, *>)["indexUrl"] as String },
                certificates = listOf(certificate),
                extensions = listOf(apk.pkg),
            )
        assertEquals(RestoreState.SUCCESS, status.state, "problems: ${status.problems}")
        assertEquals(emptyList(), status.problems)

        val after = snapshot()
        before.keys.forEach { key -> assertEquals(before[key], after[key], "$key after the restore") }
        assertEquals(settingsBefore, settingsSnapshot())
        assertEquals("high", sourcePrefs(MANGAPILL_SOURCE).getString("pref_quality", null))
        assertEquals(null, sourcePrefs(MANGAPILL_SOURCE).getString("__PRIVATE_token", null))
        assertNotNull(runBlocking { GetSource.getSourceOrNull(MANGAPILL_SOURCE) }, "the reinstalled extension's source is live")
    }

    @Test
    fun `merge keeps what is here and adds the backup's, replace makes the library the backup's`() {
        register(FakeAnimeSource(anime))
        val (parent, season, other) = seedLibrary()
        val before = snapshot()
        val name = create()

        // changes after the backup: a new library entry, a new category, a mark undone, a later position
        val extra = animeRow(anime, "/a/extra", "Extra")
        animeInLibrary(extra, 1_700_009_000)
        animeCategory("Dropped")
        val (s1, s2) =
            dbTransaction {
                EpisodeTable
                    .selectAll()
                    .where { EpisodeTable.anime eq season }
                    .sortedBy { it[EpisodeTable.url] }
                    .map { it[EpisodeTable.id].value }
            }
        episode(s1, watched = false, position = 10, total = 1420, lastSeen = 1_700_000_900)
        episode(s2, position = 900, total = 1420, lastSeen = 1_700_009_999)
        val extraManga = mangaRow("Extra")
        inMangaLibrary(extraManga, 1_700_009_100)

        val merged = restore(name, mode = "MERGE")
        assertEquals(RestoreState.SUCCESS, merged.state, "problems: ${merged.problems}")
        dbTransaction {
            val inLibrary =
                AnimeTable
                    .selectAll()
                    .where { AnimeTable.inLibrary eq true }
                    .map { it[AnimeTable.url] }
                    .toSet()
            assertEquals(setOf("/a/parent", "/a/other", "/a/extra"), inLibrary, "merge keeps the extra entry")
            assertTrue(AnimeCategoryTable.selectAll().any { it[AnimeCategoryTable.name] == "Dropped" }, "merge keeps a category")
            val e1 = EpisodeTable.selectAll().where { EpisodeTable.id eq s1 }.single()
            assertTrue(e1[EpisodeTable.watched], "watched in the backup")
            assertEquals(1400, e1[EpisodeTable.lastSecondSeen], "the backup's position is newer")
            val e2 = EpisodeTable.selectAll().where { EpisodeTable.id eq s2 }.single()
            assertEquals(900, e2[EpisodeTable.lastSecondSeen], "this install's position is newer")
            assertEquals(
                1_700_009_999,
                AnimeHistoryTable.selectAll().where { AnimeHistoryTable.episode eq s2 }.single()[AnimeHistoryTable.lastSeen],
            )
            assertTrue(
                MangaUserTable
                    .selectAll()
                    .where {
                        (MangaUserTable.manga eq extraManga) and (MangaUserTable.user eq USER)
                    }.single()[MangaUserTable.inLibrary],
            )
        }

        val replaced = restore(name, mode = "REPLACE")
        assertEquals(RestoreState.SUCCESS, replaced.state, "problems: ${replaced.problems}")
        val after = snapshot()

        // the extra rows stay in the database, out of the library
        fun withoutExtra(s: Map<String, Any?>): Map<String, Any?> =
            s +
                mapOf(
                    "anime" to (s["anime"] as List<*>).filterNot { (it as List<*>)[1] == "/a/extra" },
                    "manga" to (s["manga"] as List<*>).filterNot { (it as List<*>)[1] == "/manga/Extra" },
                )
        listOf("anime", "animeCategories", "manga", "mangaCategories").forEach { key ->
            assertEquals(withoutExtra(before)[key], withoutExtra(after)[key], "$key after replace")
        }
        dbTransaction {
            assertFalse(AnimeTable.selectAll().where { AnimeTable.id eq extra }.single()[AnimeTable.inLibrary])
            assertFalse(
                MangaUserTable
                    .selectAll()
                    .where {
                        (MangaUserTable.manga eq extraManga) and (MangaUserTable.user eq USER)
                    }.single()[MangaUserTable.inLibrary],
            )
        }
        assertTrue(listOf(parent, other).isNotEmpty())
    }

    @Test
    fun `an extension no repo lists is reported, and the restore goes on without it`() {
        register(FakeAnimeSource(anime), pkg = "test.animeextension.gone")
        seedLibrary()
        val name = create()
        freshInstall()

        val report = validate(name).assertNoErrors().dataPath("validateMadoyomiBackup") as Map<*, *>
        val ext = report["extensions"].asList.single() as Map<*, *>
        assertEquals("test.animeextension.gone", ext["pkgName"])
        assertEquals(false, ext["installed"])
        assertEquals(false, ext["inRepo"])
        val missing = report["missingSources"].asList.single { (it as Map<*, *>)["kind"] == "ANIME" } as Map<*, *>
        assertEquals("$anime", missing["id"].toString())
        assertEquals(3, missing["entries"], "the two entries and the season")
        assertEquals("test.animeextension.gone", missing["extension"])

        val status = restore(name, extensions = listOf("test.animeextension.gone"))
        assertEquals(RestoreState.SUCCESS, status.state)
        assertEquals(1, status.problems.size, "${status.problems}")
        assertTrue(status.problems.single().contains("no added repo lists it"))
        dbTransaction {
            assertEquals(
                2,
                AnimeTable.selectAll().where { AnimeTable.inLibrary eq true }.count(),
                "the entries are back without their source",
            )
        }
    }

    @Test
    fun `a file that isn't a backup is BAD_REQUEST`() {
        val backups = BackupFiles.dir().also { it.mkdirs() }
        File(backups, "notes.madoyomi-backup").writeText("hello")
        File(backups, "zipped.madoyomi-backup").writeBytes(
            java.io
                .ByteArrayOutputStream()
                .also { out ->
                    java.util.zip
                        .GZIPOutputStream(out)
                        .use { it.write(ByteArray(64) { 7 }) }
                }.toByteArray(),
        )
        assertEquals("BAD_REQUEST", validate("notes.madoyomi-backup").errorCode)
        assertEquals("BAD_REQUEST", validate("zipped.madoyomi-backup").errorCode)
        assertEquals("NOT_FOUND", validate("missing.madoyomi-backup").errorCode)
        assertEquals("BAD_REQUEST", validate("../server.conf").errorCode)
        val restore =
            graphql("""mutation { restoreMadoyomiBackup(input: {name: "notes.madoyomi-backup"}) { status { state } } }""")
        assertEquals("BAD_REQUEST", restore.errorCode)
        val bad = runCatching { BackupFiles.import("upload.madoyomi-backup", "hello".byteInputStream()) }.exceptionOrNull()
        assertTrue(bad is madoyomi.core.error.MadoyomiException)
        assertFalse(File(backups, "upload.madoyomi-backup").exists())
    }

    @Test
    fun `the schedule keeps the newest scheduled backups and leaves manual ones`() {
        register(FakeAnimeSource(anime))
        seedLibrary()
        create()
        val settings =
            graphql(
                "mutation { setMadoyomiBackupSettings(input: {intervalHours: 24, keep: 2}) { settings { intervalHours keep nextScheduledAt } } }",
            ).assertNoErrors()
                .dataPath("setMadoyomiBackupSettings", "settings") as Map<*, *>
        assertEquals(24, settings["intervalHours"])
        assertEquals(2, settings["keep"])
        assertEquals(
            "BAD_REQUEST",
            graphql("mutation { setMadoyomiBackupSettings(input: {intervalHours: 5}) { settings { keep } } }").errorCode,
        )
        val start = 1_800_000_000_000L
        (0..3).forEach { BackupScheduler.runOnce(start + it * 1000) }
        val list =
            graphql(
                "{ madoyomiBackups { name automatic size } }",
            ).assertNoErrors().dataPath("madoyomiBackups").asList.map { it as Map<*, *> }
        assertEquals(1, list.count { it["automatic"] == false })
        val automatic = list.filter { it["automatic"] == true }.map { it["name"] as String }.sorted()
        assertEquals(2, automatic.size, "$list")
        assertTrue(automatic.all { it.startsWith("Madoyomi_auto_") })
        val stamps = (2..3).map { java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(java.util.Date(start + it * 1000)) }
        assertEquals(stamps.map { "Madoyomi_auto_$it.madoyomi-backup" }, automatic)

        val deleted =
            graphql("""mutation { deleteMadoyomiBackup(input: {name: "${automatic.first()}"}) { backups { name } } }""")
                .assertNoErrors()
                .dataPath("deleteMadoyomiBackup", "backups")
                .asList
        assertEquals(2, deleted.size)
    }

    @Test
    fun `a Mihon or Suwayomi tachibk restores its manga part`() {
        seedLibrary()
        val before = snapshot()["manga"]
        val backups = BackupFiles.dir().also { it.mkdirs() }
        ProtoBackupExport.createBackup(USER, MadoyomiBackupExport.MANGA_FLAGS).use { input ->
            File(backups, "library.tachibk").outputStream().use { input.copyTo(it) }
        }
        freshInstall()
        val report = validate("library.tachibk").assertNoErrors().dataPath("validateMadoyomiBackup") as Map<*, *>
        assertEquals("TACHIYOMI", report["format"])
        assertEquals(2, report["manga"])
        assertEquals(0, report["anime"])
        val status = restore("library.tachibk")
        assertEquals(RestoreState.SUCCESS, status.state, "problems: ${status.problems}")
        assertEquals(before, snapshot()["manga"])
    }

    companion object {
        const val USER = 1
        const val MANGAPILL_SOURCE = ExtensionApiIntegrationTest.MANGAPILL_SOURCE
    }
}
