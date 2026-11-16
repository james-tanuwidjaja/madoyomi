package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import madoyomi.core.downloads.DownloadItem
import madoyomi.core.downloads.DownloadItemState
import madoyomi.core.downloads.DownloadQueue
import madoyomi.core.downloads.DownloadSettings
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.downloads.DownloadTable
import madoyomi.core.downloads.ItemDownloader
import madoyomi.core.downloads.MangaChapterDownloader
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.library.LibraryUpdater
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A downloader that records what it was asked, waits at a gate when one is set, and fails as told. */
private class FakeDownloader : ItemDownloader {
    data class Start(
        val itemId: Int,
        val sourceId: Long,
        val at: Long,
    )

    val starts: MutableList<Start> = Collections.synchronizedList(mutableListOf())
    val gates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /** Item → how many more attempts fail. */
    val failures = ConcurrentHashMap<Int, Int>()
    val discarded: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val active = AtomicInteger()
    private val activeBySource = ConcurrentHashMap<Long, AtomicInteger>()

    @Volatile
    var maxActive = 0

    @Volatile
    var maxPerSource = 0

    fun gate(itemId: Int) = CompletableDeferred<Unit>().also { gates[itemId] = it }

    fun started(): List<Int> = synchronized(starts) { starts.map { it.itemId } }

    override suspend fun download(
        item: DownloadItem,
        progress: suspend (done: Int, total: Int, bytes: Long) -> Unit,
    ): Long {
        starts += Start(item.itemId, item.sourceId, System.currentTimeMillis())
        val n = active.incrementAndGet()
        val s = activeBySource.getOrPut(item.sourceId) { AtomicInteger() }.incrementAndGet()
        synchronized(this) {
            maxActive = maxOf(maxActive, n)
            maxPerSource = maxOf(maxPerSource, s)
        }
        try {
            progress(1, 2, 10)
            gates[item.itemId]?.await()
            val left = failures[item.itemId] ?: 0
            if (left > 0) {
                failures[item.itemId] = left - 1
                throw IOException("boom ${item.itemId}")
            }
            progress(2, 2, 20)
            return 20
        } finally {
            active.decrementAndGet()
            activeBySource.getValue(item.sourceId).decrementAndGet()
        }
    }

    override fun discard(item: DownloadItem) {
        discarded += item.itemId
    }
}

/** A manga source whose chapter list a test sets (for the update check's automatic downloads). */
private class ListSource(
    override val id: Long,
) : Source {
    override val name = "Download list $id"
    override val lang = "en"
    override val supportsLatest = false

    @Volatile
    var chapters: List<SChapter> = emptyList()

    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ) = MangasPage(emptyList(), false)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) = SMangaUpdate(manga, if (fetchChapters) this.chapters else chapters)

    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
}

/**
 * The download queue (Phase 6 Stage 4) with a fake downloader: the order, the limits (global, per source, the stagger),
 * the state machine (retries, FAILED, retry, pause, resume, cancel), reorder, the queue across a restart of the
 * workers, the GraphQL around it (the queue, the completed list, the settings, the subscription), and the automatic
 * downloads after a library update check.
 */
class DownloadQueueApiTest : ApiGraphQLTest() {
    // the class has one instance for all its tests (ApiGraphQLTest's @BeforeAll), so a fresh fake for each
    private lateinit var fake: FakeDownloader
    private val now get() = Instant.now().epochSecond

    @BeforeEach
    fun freshQueue() {
        runBlocking { DownloadQueue.stop() }
        fake = FakeDownloader()
        DownloadSettingsStore.fileOverride =
            kotlin.io.path
                .createTempFile("downloads", ".json")
                .toFile()
                .also { it.delete() }
        DownloadSettingsStore.reload()
        LibraryUpdateSettingsStore.fileOverride =
            kotlin.io.path
                .createTempFile("library-update", ".json")
                .toFile()
                .also { it.delete() }
        LibraryUpdateSettingsStore.reload()
        LibraryUpdater.reset()
        LibraryUpdater.staggerMs = 0
        DownloadQueue.downloaders[ExtensionKind.MANGA] = fake
        DownloadQueue.staggerMs = 0
        DownloadQueue.retryDelaysMs = listOf(10, 10)
    }

    @AfterEach
    fun resetQueue() {
        fake.gates.values.forEach { it.complete(Unit) }
        runBlocking { DownloadQueue.stop() }
        DownloadQueue.downloaders[ExtensionKind.MANGA] = MangaChapterDownloader
        DownloadQueue.staggerMs = 1_000
        DownloadQueue.retryDelaysMs = listOf(5_000, 30_000)
        DownloadQueue.maxTries = 3
        DownloadSettingsStore.fileOverride?.delete()
        DownloadSettingsStore.fileOverride = null
        DownloadSettingsStore.reload()
        LibraryUpdater.reset()
        LibraryUpdater.staggerMs = 1_000
        LibraryUpdater.onNewItems = null
        LibraryUpdateSettingsStore.fileOverride?.delete()
        LibraryUpdateSettingsStore.fileOverride = null
        LibraryUpdateSettingsStore.reload()
        GetSource.unregisterAllSources()
    }

    /** A manga of [sourceId] with chapters 1 to [count] (source order 1 to n); returns the chapter ids. */
    private fun chapters(
        sourceId: Long,
        count: Int,
        title: String = "Manga $sourceId",
        inLibrary: Boolean = false,
    ): List<Int> =
        dbTransaction {
            val mangaId =
                MangaTable
                    .insertAndGetId {
                        it[url] = "/manga/$title"
                        it[this.title] = title
                        it[initialized] = true
                        it[sourceReference] = sourceId
                        it[memo] = JsonObject(emptyMap())
                    }.value
            if (inLibrary) {
                MangaUserTable.insert {
                    it[manga] = mangaId
                    it[user] = 1
                    it[MangaUserTable.inLibrary] = true
                    it[inLibraryAt] = now - 3_600
                }
            }
            (1..count).map { order ->
                ChapterTable
                    .insertAndGetId {
                        it[url] = "/manga/$title/$order"
                        it[name] = "Chapter $order"
                        it[chapter_number] = order.toFloat()
                        it[sourceOrder] = order
                        it[manga] = mangaId
                        it[fetchedAt] = now - 7_200
                        it[memo] = JsonObject(emptyMap())
                    }.value
            }
        }

    private fun enqueue(ids: List<Int>): Int =
        graphql("mutation { enqueueDownloads(input: {kind: MANGA, itemIds: $ids}) { queued } }")
            .assertNoErrors()
            .dataPath("enqueueDownloads", "queued") as Int

    private fun queue(): List<Map<*, *>> =
        (
            graphql("{ downloadQueue { items { id itemId state progressDone progressTotal tries error } } }")
                .assertNoErrors()
                .dataPath("downloadQueue", "items") as List<*>
        ).map { it as Map<*, *> }

    private fun stateOf(itemId: Int): String? = queue().firstOrNull { it["itemId"] == itemId }?.get("state") as String?

    private fun idOf(itemId: Int): Int = queue().first { it["itemId"] == itemId }["id"] as Int

    private fun completed(): List<Int> =
        (
            graphql("{ completedDownloads(kind: MANGA) { hasMore items { itemId state bytes entryTitle itemName } } }")
                .assertNoErrors()
                .dataPath("completedDownloads", "items") as List<*>
        ).map { it.path("itemId") as Int }

    private fun until(
        what: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; queue: ${queue()}" }
            Thread.sleep(10)
        }
    }

    private fun settings(input: String) =
        graphql("mutation { setDownloadSettings(input: {$input}) { settings { maxParallel perSource autoDownload path } } }")

    @Test
    fun `queued chapters download in order within the global and per-source limits, staggered per source`() {
        settings("maxParallel: 2, perSource: 1").assertNoErrors()
        DownloadQueue.staggerMs = 150
        val a = chapters(9_001L, 3)
        val b = chapters(9_002L, 2)
        (a + b).forEach { fake.gate(it) }
        assertEquals(5, enqueue(a + b))
        DownloadQueue.start()

        until("a chapter of each source") { fake.started().size == 2 }
        assertEquals(listOf(a[0], b[0]), fake.started(), "one per source, in queue order, two at once")
        assertEquals("DOWNLOADING", stateOf(a[0]))
        assertEquals(listOf(1, 2), queue().first { it["itemId"] == a[0] }.let { listOf(it["progressDone"], it["progressTotal"]) })
        Thread.sleep(200)
        assertEquals(2, fake.started().size, "nothing else starts while the limits are full")

        fake.gates.getValue(a[0]).complete(Unit)
        until("the next of source A") { fake.started().size == 3 }
        assertEquals(a[1], fake.started()[2], "source A's next, not source B's, since B has its one")
        (a + b).forEach { fake.gates.getValue(it).complete(Unit) }
        runBlocking { DownloadQueue.awaitIdle() }

        assertEquals(listOf(a[0], b[0], a[1], b[1], a[2]), fake.started())
        assertEquals(2, fake.maxActive)
        assertEquals(1, fake.maxPerSource)
        val aStarts = fake.starts.filter { it.sourceId == 9_001L }.map { it.at }
        assertTrue(aStarts.zipWithNext().all { (x, y) -> y - x >= 140 }, "a source's downloads start the stagger apart: $aStarts")
        assertEquals(emptyList(), queue())
        assertEquals((a + b).toSet(), completed().toSet())
        val done =
            graphql("{ downloadQueue { mangaDone animeDone lastFinishedAt } }").assertNoErrors().dataPath("downloadQueue") as Map<*, *>
        assertEquals(5, done["mangaDone"])
        assertEquals(0, done["animeDone"])
        assertTrue((done["lastFinishedAt"].toString().toLong()) > 0)
    }

    @Test
    fun `a failure is retried, then FAILED with its error until Retry, and a done item is in the completed list`() {
        val (once, always) = chapters(9_011L, 2)
        fake.failures[once] = 1
        fake.failures[always] = 10
        enqueue(listOf(once, always))
        DownloadQueue.start()
        until("the failing one to fail") { stateOf(always) == "FAILED" }
        runBlocking { DownloadQueue.awaitIdle() }

        val failed = queue().single()
        assertEquals(always, failed["itemId"])
        assertEquals(3, failed["tries"], "the first attempt and two retries")
        assertEquals("boom $always", failed["error"])
        assertEquals(listOf(once), completed(), "one failure, then done on the retry")
        assertEquals(5, fake.started().size, "two attempts of one, three of the other")

        fake.failures[always] = 0
        val retried =
            graphql("mutation { retryDownloads(input: {kind: MANGA}) { changed downloadQueue { items { state tries error } } } }")
                .assertNoErrors()
                .dataPath("retryDownloads") as Map<*, *>
        assertEquals(1, retried["changed"])
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(emptyList(), queue())
        assertEquals(setOf(once, always), completed().toSet())
    }

    @Test
    fun `pause stops a running download until resumed, and cancel removes one with what it staged`() {
        settings("maxParallel: 1").assertNoErrors()
        val (first, second, third) = chapters(9_021L, 3)
        fake.gate(first)
        fake.gate(second)
        enqueue(listOf(first, second, third))
        DownloadQueue.start()
        until("the first to start") { stateOf(first) == "DOWNLOADING" }

        graphql("mutation { pauseDownloads(input: {ids: [${idOf(first)}]}) { changed } }").assertNoErrors()
        until("the second to start") { stateOf(second) == "DOWNLOADING" }
        assertEquals("PAUSED", stateOf(first))

        // pause all, then resume all: the running one stops, every one keeps its place
        assertEquals(
            2,
            graphql("mutation { pauseDownloads(input: {kind: MANGA}) { changed } }").assertNoErrors().dataPath("pauseDownloads", "changed"),
        )
        Thread.sleep(100)
        assertEquals(listOf("PAUSED", "PAUSED", "PAUSED"), queue().map { it["state"] })
        assertEquals(listOf(first, second), fake.started())

        // cancel the third: it goes, with what it staged
        graphql("mutation { cancelDownloads(input: {ids: [${idOf(third)}]}) { changed } }").assertNoErrors()
        assertEquals(listOf(first, second), queue().map { it["itemId"] })
        assertEquals(listOf(third), fake.discarded.toList())

        fake.gates.values.forEach { it.complete(Unit) }
        assertEquals(
            2,
            graphql("mutation { resumeDownloads(input: {kind: MANGA}) { changed } }")
                .assertNoErrors()
                .dataPath("resumeDownloads", "changed"),
        )
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(listOf(first, second, first, second), fake.started(), "resumed in their places")
        assertEquals(setOf(first, second), completed().toSet())
    }

    @Test
    fun `reorder moves an item among its kind's unfinished items`() {
        settings("maxParallel: 1").assertNoErrors()
        val ids = chapters(9_031L, 4)
        fake.gate(ids[0])
        enqueue(ids)
        DownloadQueue.start()
        until("the first to start") { stateOf(ids[0]) == "DOWNLOADING" }

        graphql("mutation { reorderDownload(input: {id: ${idOf(ids[3])}, to: 1}) { downloadQueue { items { itemId } } } }")
            .assertNoErrors()
        assertEquals(listOf(ids[0], ids[3], ids[1], ids[2]), queue().map { it["itemId"] })
        graphql("mutation { reorderDownload(input: {id: ${idOf(ids[1])}, to: 99}) { downloadQueue { items { itemId } } } }")
            .assertNoErrors()
        assertEquals(listOf(ids[0], ids[3], ids[2], ids[1]), queue().map { it["itemId"] })
        assertEquals(
            "NOT_FOUND",
            graphql("mutation { reorderDownload(input: {id: 9999, to: 0}) { downloadQueue { items { id } } } }")
                .errors
                ?.single()
                ?.extensions
                ?.get("code"),
        )

        fake.gates.getValue(ids[0]).complete(Unit)
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(listOf(ids[0], ids[3], ids[2], ids[1]), fake.started())
    }

    @Test
    fun `the queue survives a restart of the workers, with its order, paused items and the one cut off`() {
        settings("maxParallel: 1").assertNoErrors()
        val ids = chapters(9_041L, 4)
        fake.gate(ids[0])
        enqueue(ids)
        DownloadQueue.start()
        until("the first to start") { stateOf(ids[0]) == "DOWNLOADING" }
        graphql("mutation { pauseDownloads(input: {ids: [${idOf(ids[2])}]}) { changed } }").assertNoErrors()

        // a stop as at an exit or a crash: the running one stays DOWNLOADING in the table
        runBlocking { DownloadQueue.stop() }
        val rows =
            dbTransaction {
                DownloadTable
                    .selectAll()
                    .orderBy(DownloadTable.position)
                    .map { it[DownloadTable.chapter]?.value to it[DownloadTable.state] }
            }
        assertEquals(
            listOf(
                ids[0] to DownloadItemState.DOWNLOADING,
                ids[1] to DownloadItemState.QUEUED,
                ids[2] to DownloadItemState.PAUSED,
                ids[3] to DownloadItemState.QUEUED,
            ),
            rows,
        )

        fake.gates.clear()
        DownloadQueue.start()
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(listOf(ids[0], ids[0], ids[1], ids[3]), fake.started(), "the one cut off first, then in order")
        assertEquals(listOf(ids[2]), queue().map { it["itemId"] })
        assertEquals("PAUSED", stateOf(ids[2]))
    }

    @Test
    fun `enqueue keeps queued items, queues paused, failed and gone ones again, and skips downloaded chapters`() {
        val ids = chapters(9_051L, 4)
        dbTransaction { ChapterTable.update({ ChapterTable.id eq ids[3] }) { it[isDownloaded] = true } }
        assertEquals(3, enqueue(ids), "the downloaded one isn't queued")
        assertEquals(0, enqueue(ids), "nothing new")
        assertEquals(
            true,
            graphql("{ chapter(id: ${ids[3]}) { isDownloaded } }").assertNoErrors().dataPath("chapter", "isDownloaded"),
            "a downloaded chapter counts as the user's download at once (upstream's rule)",
        )
        graphql("mutation { pauseDownloads(input: {ids: [${idOf(ids[0])}]}) { changed } }").assertNoErrors()
        assertEquals(1, enqueue(listOf(ids[0])), "a paused one is queued again")
        assertEquals("QUEUED", stateOf(ids[0]))
        assertEquals(listOf(ids[0], ids[1], ids[2]), queue().map { it["itemId"] }, "where it was")

        val anime =
            graphql("mutation { enqueueDownloads(input: {kind: ANIME, itemIds: [1]}) { queued } }").errors?.single()?.extensions
        assertEquals("BAD_REQUEST", anime?.get("code"))
        assertEquals(0, enqueue(listOf(424242)), "unknown ids are left out")

        // a chapter's download goes with it when a refresh deletes the chapter (the reference cascades)
        dbTransaction { ChapterTable.deleteWhere { ChapterTable.id eq ids[2] } }
        assertEquals(listOf(ids[0], ids[1]), queue().map { it["itemId"] })
    }

    @Test
    fun `the settings are validated, kept in their file, and new limits apply at once`() {
        val defaults =
            graphql("{ downloadSettings { maxParallel perSource autoDownload path } }").assertNoErrors().dataPath("downloadSettings")
                as Map<*, *>
        assertEquals(3, defaults["maxParallel"])
        assertEquals(1, defaults["perSource"])
        assertEquals(false, defaults["autoDownload"], "off on a fresh install (the user's decision)")
        assertTrue((defaults["path"] as String).replace('\\', '/').endsWith("/downloads"), "the data dir's downloads folder")

        assertEquals(
            "BAD_REQUEST",
            settings("maxParallel: 0")
                .errors
                ?.single()
                ?.extensions
                ?.get("code"),
        )
        assertEquals(
            "BAD_REQUEST",
            settings("perSource: 4")
                .errors
                ?.single()
                ?.extensions
                ?.get("code"),
        )
        settings("maxParallel: 5, perSource: 2, autoDownload: true").assertNoErrors()
        DownloadSettingsStore.reload()
        assertEquals(DownloadSettings(maxParallel = 5, perSource = 2, autoDownload = true), DownloadSettingsStore.current())

        // one at a time, then two at once from the same source once the limit is raised
        settings("maxParallel: 1, perSource: 1").assertNoErrors()
        val ids = chapters(9_061L, 3)
        ids.forEach { fake.gate(it) }
        enqueue(ids)
        DownloadQueue.start()
        until("the first to start") { fake.started().size == 1 }
        Thread.sleep(100)
        assertEquals(1, fake.started().size)
        settings("maxParallel: 3, perSource: 3").assertNoErrors()
        until("all three") { fake.started().size == 3 }
        ids.forEach { fake.gates.getValue(it).complete(Unit) }
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(3, fake.maxPerSource)
    }

    @Test
    fun `downloadsChanged gives the queue at once, then its changes`() {
        val ids = chapters(9_071L, 2)
        val query = "subscription { downloadsChanged { items { itemId state } mangaDone } }"
        runBlocking {
            val first = withTimeout(10_000) { subscribe(query).first() }
            assertEquals(emptyList<Any>(), first.data.path("downloadsChanged", "items"))
            assertEquals(0, first.data.path("downloadsChanged", "mangaDone"))

            val seen = Collections.synchronizedList(mutableListOf<Any?>())
            val done =
                async {
                    withTimeout(10_000) {
                        subscribe(query).first { response ->
                            seen += response.data.path("downloadsChanged", "items")
                            response.data.path("downloadsChanged", "mangaDone") == 2
                        }
                    }
                }
            delay(300)
            enqueue(ids)
            DownloadQueue.start()
            done.await()
            assertTrue(seen.any { (it as List<*>).isNotEmpty() }, "the queued items were sent before they were done: $seen")
        }
    }

    @Test
    fun `a library update queues the new chapters of entries in categories with Downloads on, when the setting is on`() {
        val source = ListSource(9_081L)
        GetSource.registerSource(source.id to source)
        val old = chapters(source.id, 1, title = "Auto", inLibrary = true)
        val mangaId =
            dbTransaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq old[0] }
                    .single()[ChapterTable.manga]
                    .value
            }

        fun chapter(n: Int) =
            SChapter.create().apply {
                url = "/manga/Auto/$n"
                name = "Chapter $n"
                chapter_number = n.toFloat()
            }
        DownloadQueue.start()

        fun check(): Map<*, *> {
            graphql("mutation { startLibraryUpdate(input: {kind: MANGA}) { libraryCheck { total } } }").assertNoErrors()
            runBlocking { LibraryUpdater.awaitIdle() }
            return graphql("{ libraryCheck { newChapters failures { error } } }").assertNoErrors().dataPath("libraryCheck") as Map<*, *>
        }

        // the setting off (the default): nothing is queued
        source.chapters = listOf(chapter(2), chapter(1))
        assertEquals(1, check()["newChapters"])
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(emptyList(), fake.started())

        // on, and Default (the entry's category) with Downloads on (UNSET): the new chapter is queued and downloaded
        settings("autoDownload: true").assertNoErrors()
        source.chapters = listOf(chapter(3), chapter(2), chapter(1))
        assertEquals(1, check()["newChapters"])
        runBlocking { DownloadQueue.awaitIdle() }
        val third =
            dbTransaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.url eq "/manga/Auto/3" }
                    .single()[ChapterTable.id]
                    .value
            }
        assertEquals(listOf(third), fake.started())

        // Downloads off for Default (EXCLUDE): not queued
        dbTransaction {
            CategoryTable.update({ CategoryTable.isDefaultCategory eq true }) {
                it[includeInDownload] =
                    IncludeOrExclude.EXCLUDE.value
            }
        }
        source.chapters = listOf(chapter(4), chapter(3), chapter(2), chapter(1))
        assertEquals(1, check()["newChapters"])
        runBlocking { DownloadQueue.awaitIdle() }
        assertEquals(listOf(third), fake.started())
        assertTrue(mangaId > 0)
    }
}
