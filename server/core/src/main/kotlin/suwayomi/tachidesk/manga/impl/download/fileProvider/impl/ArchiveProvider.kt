package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileType
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.manga.impl.util.storage.FileDeletionHelper
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException
import java.util.zip.Deflater

private val applicationDirs: ApplicationDirs by injectLazy()

class ArchiveProvider(
    mangaId: Int,
    chapterId: Int,
) : ChaptersFilesProvider<FileType.ZipFile>(mangaId, chapterId) {
    override suspend fun getImageFiles(): List<FileType.ZipFile> {
        val cbzPath = getChapterCbzPath(mangaId, chapterId)
        return try {
            ZipFile.builder().setFile(cbzPath).get().use { zipFile ->
                zipFile.entries
                    .toList()
                    .filter { !it.isDirectory }
                    .map { FileType.ZipFile(it) }
            }
        } catch (e: NoSuchFileException) {
            // "no download present" should throw NoSuchElementException
            throw NoSuchElementException("CBZ archive does not exist", e)
        }
    }

    // Madoyomi: the page's stream owns its ZipFile and closes it. Upstream returned only the entry's stream and dropped
    // the ZipFile, so a page left a file handle open until GC, and ZipFile's finalizer could close the channel while
    // the page was still being served (ClosedChannelException in the reader).
    override suspend fun getImageInputStream(image: FileType.ZipFile): InputStream {
        val zipFile =
            ZipFile
                .builder()
                .setFile(getChapterCbzPath(mangaId, chapterId))
                .get()
        return try {
            ZipEntryStream(zipFile.getInputStream(image.entry), zipFile)
        } catch (e: Throwable) {
            zipFile.close()
            throw e
        }
    }

    // Madoyomi: an entry's stream that closes its archive with it, and keeps the archive reachable until then.
    private class ZipEntryStream(
        entry: InputStream,
        private val zipFile: ZipFile,
    ) : FilterInputStream(entry) {
        override fun close() {
            try {
                super.close()
            } finally {
                zipFile.close()
            }
        }
    }

    override suspend fun extractExistingDownload() {
        val outputFile = File(getChapterCbzPath(mangaId, chapterId))
        val chapterDownloadFolder = File(getChapterDownloadPath(mangaId, chapterId))

        if (!outputFile.exists()) {
            return
        }

        extractCbzFile(outputFile, chapterDownloadFolder)
    }

    override suspend fun handleSuccessfulDownload() {
        val mangaDownloadFolder = File(getMangaDownloadDir(mangaId))
        val outputFile = File(getChapterCbzPath(mangaId, chapterId))
        val chapterCacheFolder = File(getChapterCachePath(mangaId, chapterId))

        withContext(Dispatchers.IO) {
            mangaDownloadFolder.mkdirs()
            outputFile.createNewFile()
        }

        ZipArchiveOutputStream(outputFile.outputStream()).use { zipOut ->
            zipOut.setMethod(ZipArchiveOutputStream.DEFLATED)
            zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)
            if (chapterCacheFolder.isDirectory) {
                chapterCacheFolder.listFiles()?.sortedBy { it.name }?.forEach {
                    val entry = ZipArchiveEntry(it.name)
                    entry.time = 0L
                    try {
                        zipOut.putArchiveEntry(entry)
                        it.inputStream().use { inputStream ->
                            inputStream.copyTo(zipOut)
                        }
                    } finally {
                        zipOut.closeArchiveEntry()
                    }
                }
            }
        }

        if (chapterCacheFolder.exists() && chapterCacheFolder.isDirectory) {
            chapterCacheFolder.deleteRecursively()
        }
    }

    override suspend fun delete(): Boolean {
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        if (!cbzFile.exists()) {
            return true
        }

        val cbzDeleted = cbzFile.delete()
        if (cbzDeleted) {
            transaction {
                ChapterTable.update({ ChapterTable.id eq chapterId }) {
                    it[koreaderHash] = null
                }
            }
        }
        FileDeletionHelper.cleanupParentFoldersFor(cbzFile, applicationDirs.mangaDownloadsRoot)
        return cbzDeleted
    }

    override suspend fun getAsArchiveStream(): Pair<InputStream, Long> {
        val cbzFile =
            File(getChapterCbzPath(mangaId, chapterId))
                .takeIf { it.exists() }
                ?: throw IllegalArgumentException("CBZ file not found for chapter ID: $chapterId (Manga ID: $mangaId)")

        return cbzFile.inputStream() to cbzFile.length()
    }

    override suspend fun getArchiveSize(): Long {
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        return if (cbzFile.exists()) cbzFile.length() else 0L
    }

    // Madoyomi: internal (upstream: private) for ArchiveProviderTest.
    internal fun extractCbzFile(
        cbzFile: File,
        chapterFolder: File,
    ) {
        if (!chapterFolder.exists()) chapterFolder.mkdirs()
        // Madoyomi: every entry must land inside the chapter folder. Upstream wrote File(chapterFolder, entry.name) as
        // it was, so an entry such as "../x" wrote outside it (zip slip). Such a CBZ now fails with an IOException and
        // is kept; the entries before it are already written.
        val root = chapterFolder.canonicalFile.toPath()
        ZipArchiveInputStream(cbzFile.inputStream()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                val file = File(chapterFolder, zipEntry.name).canonicalFile
                val path = file.toPath()
                if (!path.startsWith(root) || path == root) {
                    throw IOException("CBZ entry outside the chapter folder: ${zipEntry.name}")
                }
                if (!file.exists()) {
                    file.parentFile.mkdirs()
                    file.createNewFile()
                }
                file.outputStream().use { outputStream ->
                    zipInputStream.copyTo(outputStream)
                }
                zipEntry = zipInputStream.nextEntry
            }
        }
        cbzFile.delete()
    }
}
