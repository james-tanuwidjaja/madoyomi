package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.ArchiveProvider
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * A downloaded chapter's page streamed from its CBZ (upstream's `ArchiveProvider`, the reader's path for a downloaded
 * Chapter): the stream owns its archive (a page cut off by `ClosedChannelException`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CbzPageStreamTest : ApplicationTest() {
    private val createdDirs = mutableListOf<File>()

    @Test
    fun `a page being read survives garbage collection, the archive it came from is no longer referenced elsewhere`() =
        runTest {
            val (provider, cbz) = fixture("CBZ_STREAM_GC", "1")
            // A 4 MB page stored: the reader's buffer holds 8 KB, the rest comes from the file's channel.
            val page = Random(7).nextBytes(4 * 1024 * 1024)
            writeStoredCbz(cbz, mapOf("001.jpg" to page))

            val (stream, _) = provider.getImage().executeGetImage(0)
            stream.use {
                val head = it.readNBytes(1024)
                // The archive object is only reachable through the stream now: collect and finalize what can be.
                repeat(5) {
                    System.gc()
                    @Suppress("DEPRECATION")
                    System.runFinalization()
                    Thread.sleep(20)
                }
                val rest = it.readBytes()
                assertContentEquals(page, head + rest)
            }
        }

    private suspend fun fixture(
        title: String,
        chapterName: String,
    ): Pair<ArchiveProvider, File> {
        val mangaId = createLibraryManga(title)
        val chapterId =
            transaction {
                ChapterTable
                    .insertAndGetId {
                        it[url] = chapterName
                        it[name] = chapterName
                        it[sourceOrder] = 1
                        it[manga] = mangaId
                        it[memo] = JsonObject.EMPTY
                    }.value
            }
        val cbz = File(getChapterCbzPath(mangaId, chapterId))
        cbz.parentFile?.let { createdDirs += it }
        return ArchiveProvider(mangaId, chapterId) to cbz
    }

    /** Entries stored, as `CbzWriter` writes pages, so a page is read straight from the file's channel. */
    private fun writeStoredCbz(
        cbz: File,
        files: Map<String, ByteArray>,
    ) {
        cbz.parentFile.mkdirs()
        ZipArchiveOutputStream(cbz.outputStream()).use { zip ->
            files.forEach { (name, bytes) ->
                val entry = ZipArchiveEntry(name)
                entry.method = ZipArchiveOutputStream.STORED
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().also { it.update(bytes) }.value
                zip.putArchiveEntry(entry)
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
    }

    @AfterEach
    internal fun tearDown() {
        createdDirs.forEach { dir ->
            dir.deleteRecursively()
            dir.parentFile?.delete()
        }
        createdDirs.clear()
        clearTables(ChapterUserTable, ChapterTable, MangaUserTable, MangaTable)
    }
}
