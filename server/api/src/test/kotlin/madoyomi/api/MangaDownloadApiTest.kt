package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import madoyomi.core.downloads.DownloadQueue
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.downloads.DownloadsFolder
import madoyomi.core.downloads.MangaChapterDownloader
import madoyomi.core.reader.PageCache
import madoyomi.core.reader.ReaderSettingsStore
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.injectLazy
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import suwayomi.tachidesk.manga.impl.Page as UpstreamPage

/**
 * Manga downloads end to end (Phase 6 Stage 4): a chapter's pages from a loopback image host through the real
 * downloader, staged outside the page cache, written as a CBZ with `ComicInfo.xml` at upstream's place for it, marked
 * downloaded, then read by upstream's reader path with the host refusing every request (offline); a page in the
 * reader's cache isn't fetched again; a retry goes on from the pages staged before; deleting the download.
 */
class MangaDownloadApiTest : ApiGraphQLTest() {
    private val applicationDirs: ApplicationDirs by injectLazy()

    /** Each page its own image, so the CBZ's entries can be told apart. */
    private fun png(index: Int): ByteArray =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(index + 1, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Paths answered with HTTP 500 this many more times. */
    private val failures = ConcurrentHashMap<String, Int>()

    @Volatile
    private var offline = false

    private val images: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val imageBase get() = "http://127.0.0.1:${images.address.port}"
    private lateinit var staging: File

    private inner class PagesSource(
        val pages: Int,
    ) : HttpSource() {
        override val name = "Download pages"
        override val lang = "en"
        override val baseUrl get() = imageBase
        override val supportsLatest = false
        var listCalls = 0

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            listCalls++
            return List(pages) { i -> Page(i, imageUrl = "$imageBase/img${chapter.url}/$i.png") }
        }
    }

    @BeforeAll
    fun startHost() {
        images.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                requests += path
                val left = failures[path] ?: 0
                when {
                    offline -> {
                        it.sendResponseHeaders(503, -1)
                    }

                    left > 0 -> {
                        failures[path] = left - 1
                        it.sendResponseHeaders(500, -1)
                    }

                    else -> {
                        val body = png(path.substringAfterLast('/').substringBefore('.').toInt())
                        it.responseHeaders.add("Content-Type", "image/png")
                        it.sendResponseHeaders(200, body.size.toLong())
                        it.responseBody.write(body)
                    }
                }
            }
        }
        images.start()
    }

    @AfterAll
    fun stopHost() = images.stop(0)

    @BeforeEach
    fun freshDownloads() {
        runBlocking { DownloadQueue.stop() }
        File(applicationDirs.mangaDownloadsRoot).deleteRecursively()
        staging = Files.createTempDirectory("madoyomi-staging").toFile()
        MangaChapterDownloader.stagingRootOverride = staging
        DownloadSettingsStore.fileOverride = Files.createTempFile("downloads", ".json").toFile().also { it.delete() }
        DownloadSettingsStore.reload()
        ReaderSettingsStore.fileOverride = Files.createTempFile("reader-settings", ".json").toFile().also { it.delete() }
        ReaderSettingsStore.reload()
        PageCache.clear()
        PageCache.retryDelayMs = 1
        DownloadQueue.staggerMs = 0
        DownloadQueue.retryDelaysMs = listOf(10, 10)
        requests.clear()
        failures.clear()
        offline = false
    }

    @AfterEach
    fun dropDownloads() {
        runBlocking { DownloadQueue.stop() }
        runBlocking { DownloadsFolder.awaitChange() }
        DownloadsFolder.reset()
        DownloadsFolder.forceCopy = false
        File(applicationDirs.mangaDownloadsRoot).deleteRecursively()
        staging.deleteRecursively()
        MangaChapterDownloader.stagingRootOverride = null
        DownloadSettingsStore.fileOverride?.delete()
        DownloadSettingsStore.fileOverride = null
        DownloadSettingsStore.reload()
        ReaderSettingsStore.fileOverride?.delete()
        ReaderSettingsStore.fileOverride = null
        ReaderSettingsStore.reload()
        runBlocking { PageCache.awaitTrim() }
        PageCache.clear()
        PageCache.retryDelayMs = 500
        DownloadQueue.staggerMs = 1_000
        DownloadQueue.retryDelaysMs = listOf(5_000, 30_000)
        GetSource.unregisterAllSources()
    }

    /** A manga of [source] titled [title] with chapters 1 to [chapters]; returns its id and the chapter ids. */
    private fun manga(
        source: HttpSource,
        title: String,
        chapters: Int,
    ): Pair<Int, List<Int>> {
        GetSource.registerSource(source.id to source)
        return dbTransaction {
            val mangaId =
                MangaTable
                    .insertAndGetId {
                        it[url] = "/$title"
                        it[this.title] = title
                        it[author] = "An Author"
                        it[initialized] = true
                        it[sourceReference] = source.id
                        it[memo] = JsonObject(emptyMap())
                    }.value
            mangaId to
                (1..chapters).map { order ->
                    ChapterTable
                        .insertAndGetId {
                            it[url] = "/$title/$order"
                            it[name] = "Chapter $order"
                            it[chapter_number] = order.toFloat()
                            it[sourceOrder] = order
                            it[manga] = mangaId
                            it[memo] = JsonObject(emptyMap())
                        }.value
                }
        }
    }

    private fun download(vararg chapterIds: Int) {
        graphql("mutation { enqueueDownloads(input: {kind: MANGA, itemIds: ${chapterIds.toList()}}) { queued } }").assertNoErrors()
        DownloadQueue.start()
        runBlocking { DownloadQueue.awaitIdle() }
    }

    private fun cbzOf(
        mangaId: Int,
        chapterId: Int,
    ): File = File(runBlocking { getChapterCbzPath(mangaId, chapterId) })

    @Test
    fun `a chapter is written as a CBZ with ComicInfo xml, staged outside the page cache, and read offline`() {
        val source = PagesSource(3)
        val (mangaId, chapters) = manga(source, "Download test", 2)
        download(chapters[0])

        val cbz = cbzOf(mangaId, chapters[0])
        assertTrue(cbz.isFile, "the CBZ at upstream's place: $cbz")
        val root = File(applicationDirs.mangaDownloadsRoot).absoluteFile
        assertTrue(cbz.absoluteFile.startsWith(root), "under the manga downloads: $cbz")
        assertTrue(root.path.replace('\\', '/').endsWith("/downloads/manga"), "downloads/manga, not upstream's mangas: $root")
        assertEquals("Download test", cbz.parentFile.name)
        assertEquals("Chapter 1.cbz", cbz.name)
        assertFalse(File(cbz.parentFile, "${cbz.name}.part").exists())
        assertFalse(staging.absoluteFile.startsWith(PageCache.root()), "staged outside the page cache's folder")
        assertFalse(File(staging, chapters[0].toString()).exists(), "the staged pages are gone")
        assertEquals(0, PageCache.usage().files, "the download didn't go through the page cache")

        ZipFile(cbz).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("001.png", "002.png", "003.png", "ComicInfo.xml"), entries.map { it.name })
            entries.filter { it.name.endsWith(".png") }.forEachIndexed { i, entry ->
                assertEquals(ZipEntry.STORED, entry.method)
                assertContentEquals(png(i), zip.getInputStream(entry).readBytes())
            }
            val info = zip.getInputStream(zip.getEntry("ComicInfo.xml")).readBytes().decodeToString()
            assertTrue(info.contains("<Series>Download test</Series>"), info)
            assertTrue(info.contains("<Title>Chapter 1</Title>"), info)
            assertTrue(info.contains("<Number>1</Number>"), info)
            assertTrue(info.contains("<Writer>An Author</Writer>"), info)
        }

        val chapter =
            graphql("{ chapter(id: ${chapters[0]}) { isDownloaded pageCount } manga(id: $mangaId) { downloadCount } }").assertNoErrors()
        assertEquals(true, chapter.dataPath("chapter", "isDownloaded"))
        assertEquals(3, chapter.dataPath("chapter", "pageCount"))
        assertEquals(1, chapter.dataPath("manga", "downloadCount"))
        val done =
            graphql("{ completedDownloads(kind: MANGA) { items { itemId entryTitle itemName bytes progressDone progressTotal } } }")
                .assertNoErrors()
                .dataPath("completedDownloads", "items", "0") as Map<*, *>
        assertEquals(chapters[0], done["itemId"])
        assertEquals("Download test", done["entryTitle"])
        assertEquals("Chapter 1", done["itemName"])
        assertEquals(cbz.length(), done["bytes"].toString().toLong())
        assertEquals(3, done["progressDone"])

        // offline: the reader's page list and pages come from the CBZ, the source isn't asked
        offline = true
        requests.clear()
        val listCalls = source.listCalls
        val pages =
            graphql("mutation { fetchChapterPages(input: {chapterId: ${chapters[0]}}) { pages } }")
                .assertNoErrors()
                .dataPath("fetchChapterPages", "pages") as List<*>
        assertEquals(3, pages.size)
        for (i in 0 until 3) {
            val (stream, mime) = runBlocking { UpstreamPage.getPageImage(mangaId = mangaId, chapterId = chapters[0], index = i) }
            assertEquals("image/png", mime)
            assertContentEquals(png(i), stream.use { it.readBytes() })
        }
        assertEquals(listCalls, source.listCalls)
        assertEquals(emptyList(), requests.toList(), "nothing went to the source")
    }

    @Test
    fun `a page in the reader's cache isn't fetched again, and a retry goes on from the pages staged`() {
        val (mangaId, chapters) = manga(PagesSource(3), "Download resume", 2)
        // the reader read page 1 of chapter 1: it is in the page cache
        graphql("mutation { fetchChapterPages(input: {chapterId: ${chapters[0]}}) { pages } }").assertNoErrors()
        runBlocking { UpstreamPage.getPageImage(mangaId = mangaId, chapterId = chapters[0], index = 0) }.first.close()
        assertEquals(listOf("/img/Download resume/1/0.png"), requests.toList())
        requests.clear()
        download(chapters[0])
        assertEquals(listOf("/img/Download resume/1/1.png", "/img/Download resume/1/2.png"), requests.toList())
        assertTrue(cbzOf(mangaId, chapters[0]).isFile)

        // page 3 of chapter 2 fails twice (an attempt and its quick retry inside the page fetch), then works
        requests.clear()
        failures["/img/Download resume/2/2.png"] = 2
        download(chapters[1])
        assertTrue(cbzOf(mangaId, chapters[1]).isFile)
        assertEquals(1, requests.count { it.endsWith("/2/0.png") }, "pages staged by the failed attempt were kept: $requests")
        assertEquals(1, requests.count { it.endsWith("/2/1.png") })
        assertEquals(3, requests.count { it.endsWith("/2/2.png") })
        val done =
            graphql("{ completedDownloads(kind: MANGA, limit: 1) { hasMore items { itemId tries } } }")
                .assertNoErrors()
                .dataPath("completedDownloads") as Map<*, *>
        assertEquals(true, done["hasMore"])
        assertEquals(chapters[1], done.path("items", "0", "itemId"))
        assertEquals(1, done.path("items", "0", "tries"))
    }

    @Test
    fun `deleting a download removes the CBZ, the marks and the completed row, and cancels a queued one`() {
        val (mangaId, chapters) = manga(PagesSource(2), "Download delete", 2)
        download(chapters[0])
        val cbz = cbzOf(mangaId, chapters[0])
        assertTrue(cbz.isFile)

        // the second is queued with the workers stopped, then deleted with the first
        runBlocking { DownloadQueue.stop() }
        graphql("mutation { enqueueDownloads(input: {kind: MANGA, itemIds: [${chapters[1]}]}) { queued } }").assertNoErrors()
        val deleted =
            graphql(
                "mutation { deleteDownloads(input: {kind: MANGA, itemIds: $chapters}) { deleted downloadQueue { items { id } mangaDone } } }",
            ).assertNoErrors().dataPath("deleteDownloads") as Map<*, *>
        assertEquals(2, deleted["deleted"])
        assertEquals(emptyList<Any>(), deleted.path("downloadQueue", "items"))
        assertEquals(0, deleted.path("downloadQueue", "mangaDone"))
        assertFalse(cbz.exists())
        assertEquals(
            false,
            graphql("{ chapter(id: ${chapters[0]}) { isDownloaded } }").assertNoErrors().dataPath("chapter", "isDownloaded"),
        )

        // the completed list can be cleared too, keeping the file
        download(chapters[0])
        assertEquals(
            1,
            graphql("mutation { clearCompletedDownloads(input: {kind: MANGA}) { cleared } }")
                .assertNoErrors()
                .dataPath("clearCompletedDownloads", "cleared"),
        )
        assertTrue(cbzOf(mangaId, chapters[0]).isFile)
    }

    // --- Phase 7 Stage 3: the downloads folder of one's choice ---

    private val folderFields =
        "path defaultPath isDefault reachable change { mode state from to filesDone filesTotal bytesDone bytesTotal message }"

    private fun folder() = graphql("{ downloadsFolder { $folderFields } }").assertNoErrors().dataPath("downloadsFolder") as Map<*, *>

    private fun setFolder(
        path: String?,
        mode: String,
    ) = graphql(
        "mutation(\$path: String) { setDownloadsFolder(input: {path: \$path, mode: $mode}) { downloadsFolder { $folderFields } } }",
        mapOf("path" to path),
    )

    /** Changes the folder and waits for the change to end; returns the change. */
    private fun changeFolder(
        path: String?,
        mode: String,
    ): Map<*, *> {
        setFolder(path, mode).assertNoErrors()
        runBlocking { DownloadsFolder.awaitChange() }
        return folder()["change"] as Map<*, *>
    }

    private fun readOffline(
        mangaId: Int,
        chapterId: Int,
        pages: Int,
    ) {
        offline = true
        requests.clear()
        val list =
            graphql("mutation { fetchChapterPages(input: {chapterId: $chapterId}) { pages } }")
                .assertNoErrors()
                .dataPath("fetchChapterPages", "pages") as List<*>
        assertEquals(pages, list.size)
        for (i in 0 until pages) {
            val (stream, _) = runBlocking { UpstreamPage.getPageImage(mangaId = mangaId, chapterId = chapterId, index = i) }
            assertContentEquals(png(i), stream.use { it.readBytes() })
        }
        assertEquals(emptyList(), requests.toList(), "read from the CBZ, nothing went to the source")
        offline = false
    }

    private fun tempFolder(name: String): File = Files.createTempDirectory("madoyomi-$name").toFile().also { it.deleteOnExit() }

    @Test
    fun `moving the downloads folder moves the files, and the reader and new downloads follow it`() {
        File(applicationDirs.downloadsRoot).deleteRecursively()
        val (mangaId, chapters) = manga(PagesSource(2), "Folder move", 3)
        download(chapters[0])
        val oldCbz = cbzOf(mangaId, chapters[0])
        val defaultRoot = File(applicationDirs.downloadsRoot).absoluteFile
        // something else in the folder: an anime episode with its subtitle, and an empty folder
        val anime = File(defaultRoot, "anime/Src (EN)/Show").apply { mkdirs() }
        File(anime, "Ep 1.mkv").writeBytes(ByteArray(4096) { it.toByte() })
        File(anime, "Ep 1.en.vtt").writeText("WEBVTT")
        File(defaultRoot, "anime/Empty").mkdirs()
        val before = folder()
        assertEquals(true, before["isDefault"])
        assertEquals(true, before["reachable"])
        assertEquals(defaultRoot.path, before["path"])

        val target = File(tempFolder("move"), "Madoyomi downloads")
        val change = changeFolder(target.path, "MOVE")
        assertEquals("DONE", change["state"], "$change")
        assertEquals("MOVE", change["mode"])
        assertTrue((change["filesTotal"] as Int) >= 3, "$change")
        assertEquals(change["filesTotal"], change["filesDone"])
        val after = folder()
        assertEquals(target.path, after["path"])
        assertEquals(false, after["isDefault"])
        assertEquals(defaultRoot.path, after["defaultPath"])
        assertEquals(target.path, File(applicationDirs.downloadsRoot).absolutePath)

        val newCbz = cbzOf(mangaId, chapters[0])
        assertTrue(newCbz.absoluteFile.startsWith(target), "the CBZ is in the new folder: $newCbz")
        assertTrue(newCbz.isFile)
        assertFalse(oldCbz.exists(), "and gone from the old one")
        assertTrue(File(target, "anime/Src (EN)/Show/Ep 1.mkv").length() == 4096L)
        assertTrue(File(target, "anime/Src (EN)/Show/Ep 1.en.vtt").isFile)
        assertTrue(File(target, "anime/Empty").isDirectory)
        assertEquals(emptyList(), defaultRoot.list()!!.toList(), "the old folder is empty")
        assertEquals(true, graphql("{ chapter(id: ${chapters[0]}) { isDownloaded } }").assertNoErrors().dataPath("chapter", "isDownloaded"))
        readOffline(mangaId, chapters[0], 2)

        // a new download goes to the new folder
        download(chapters[1])
        assertTrue(cbzOf(mangaId, chapters[1]).absoluteFile.startsWith(target))

        // back to the default, copying as across drives this time
        DownloadsFolder.forceCopy = true
        val back = changeFolder(null, "MOVE")
        assertEquals("DONE", back["state"], "$back")
        assertEquals(true, folder()["isDefault"])
        assertTrue(cbzOf(mangaId, chapters[1]).absoluteFile.startsWith(defaultRoot))
        assertTrue(File(defaultRoot, "anime/Empty").isDirectory, "empty folders are copied too")
        assertEquals(emptyList(), target.list()!!.toList(), "the copies' originals are deleted")
        readOffline(mangaId, chapters[1], 2)
    }

    @Test
    fun `starting an empty folder leaves the old files and forgets them`() {
        val (mangaId, chapters) = manga(PagesSource(2), "Folder empty", 2)
        download(chapters[0])
        val oldCbz = cbzOf(mangaId, chapters[0])
        val target = tempFolder("empty")

        val change = changeFolder(target.path, "START_EMPTY")
        assertEquals("DONE", change["state"], "$change")
        assertEquals(target.path, folder()["path"])
        assertTrue(oldCbz.isFile, "the old file stays where it was")
        assertEquals(
            false,
            graphql("{ chapter(id: ${chapters[0]}) { isDownloaded } }").assertNoErrors().dataPath("chapter", "isDownloaded"),
        )
        assertEquals(0, graphql("{ downloadQueue { mangaDone } }").assertNoErrors().dataPath("downloadQueue", "mangaDone"))

        // downloading it again writes into the new folder
        download(chapters[0])
        assertTrue(cbzOf(mangaId, chapters[0]).absoluteFile.startsWith(target))
        oldCbz.parentFile.parentFile.deleteRecursively()
    }

    @Test
    fun `a downloads folder that is gone is an error for the reader, the queue waits, and nothing is unmarked`() {
        val (mangaId, chapters) = manga(PagesSource(2), "Folder gone", 2)
        download(chapters[0])
        val target = File(tempFolder("gone"), "drive")
        changeFolder(target.path, "MOVE")
        val unplugged = File(target.parentFile, "unplugged")
        assertTrue(target.renameTo(unplugged), "the drive is unplugged")

        val gone = folder()
        assertEquals(target.path, gone["path"], "still the chosen folder, not the default")
        assertEquals(false, gone["reachable"])
        val read = graphql("mutation { fetchChapterPages(input: {chapterId: ${chapters[0]}}) { pages } }")
        assertEquals("DOWNLOADS_UNREACHABLE", read.errorCode, "${read.errors}")
        assertEquals(true, graphql("{ chapter(id: ${chapters[0]}) { isDownloaded } }").assertNoErrors().dataPath("chapter", "isDownloaded"))

        // a new download waits instead of failing
        graphql("mutation { enqueueDownloads(input: {kind: MANGA, itemIds: [${chapters[1]}]}) { queued } }").assertNoErrors()
        DownloadQueue.start()
        Thread.sleep(500)
        val queue = graphql("{ downloadQueue { heldBecause items { state } } }").assertNoErrors().dataPath("downloadQueue") as Map<*, *>
        assertEquals("QUEUED", queue.path("items", "0", "state"))
        assertTrue((queue["heldBecause"] as String).contains("isn't reachable"), "$queue")
        assertFalse(File(applicationDirs.dataRoot, "downloads").walkTopDown().any { it.name == "Folder gone" }, "nothing in the default")

        // moving is refused (nothing to move from), and so is anything while a download is queued
        val noMove = setFolder(tempFolder("other").path, "MOVE")
        assertEquals("BAD_REQUEST", noMove.errorCode)
        assertTrue(noMove.errors!![0].message.contains("start an empty folder instead"), noMove.errors!![0].message)
        val refused = setFolder(tempFolder("other").path, "START_EMPTY")
        assertEquals("BAD_REQUEST", refused.errorCode)
        assertTrue(refused.errors!![0].message.contains("Pause the downloads first"), refused.errors!![0].message)

        // plugged back in: the queue goes on and the reader reads
        assertTrue(unplugged.renameTo(target))
        runBlocking { DownloadQueue.awaitIdle(20_000) }
        assertTrue(cbzOf(mangaId, chapters[1]).absoluteFile.startsWith(target))
        assertEquals(true, folder()["reachable"])
        readOffline(mangaId, chapters[0], 2)
        changeFolder(null, "MOVE")
    }

    @Test
    fun `a new folder must be empty, absolute and apart from the old one, with nothing downloading`() {
        val (_, chapters) = manga(PagesSource(2), "Folder checks", 1)
        val full = tempFolder("full").also { File(it, "something.txt").writeText("x") }
        val withIgnored = tempFolder("ignored").also { File(it, "desktop.ini").writeText("x") }
        val root = File(applicationDirs.downloadsRoot).absoluteFile.also { it.mkdirs() }

        fun refusal(
            path: String?,
            mode: String = "MOVE",
        ): String {
            val r = setFolder(path, mode)
            assertEquals("BAD_REQUEST", r.errorCode, "$path: ${r.errors}")
            return r.errors!![0].message
        }
        assertTrue(refusal(full.path).contains("Choose an empty folder"))
        assertTrue(refusal("relative\\folder").contains("full path"))
        assertTrue(refusal(File(root, "manga/inside").path).contains("inside"))
        assertTrue(refusal(root.path).contains("already"))
        assertTrue(refusal(null).contains("already"))
        assertTrue(refusal(File(applicationDirs.dataRoot, "elsewhere").path).contains("data folder"))
        File(full, "file.txt").writeText("x")
        assertTrue(refusal(File(full, "file.txt").path).contains("not a folder"))
        assertEquals(true, folder()["isDefault"])

        runBlocking { DownloadQueue.stop() }
        graphql("mutation { enqueueDownloads(input: {kind: MANGA, itemIds: [${chapters[0]}]}) { queued } }").assertNoErrors()
        assertTrue(refusal(withIgnored.path).contains("Pause the downloads first"))
        graphql("mutation { pauseDownloads(input: {kind: MANGA}) { changed } }").assertNoErrors()
        // paused is fine, and a folder with only Explorer's files counts as empty
        assertEquals("DONE", changeFolder(withIgnored.path, "MOVE")["state"])
        assertEquals(withIgnored.path, folder()["path"])
        changeFolder(null, "MOVE")
    }
}
