package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a chapter's staged files as a CBZ: a zip of the page images, in name order (upstream's
 * `001.jpg` …, which its reader sorts by name too), and `ComicInfo.xml`. Images are stored as they are (already
 * compressed; storing keeps the write cheap), the XML deflated. Every entry gets the same time, so the same pages give
 * the same file. The CBZ is written next to its place as `<name>.part`, then moved over it, so a reader never sees half
 * of one and a crash leaves no CBZ that looks done.
 */
object CbzWriter {
    const val COMIC_INFO = "ComicInfo.xml"

    /** 1980-01-01, the earliest time a zip entry can hold. */
    private const val ENTRY_TIME = 315532800000L

    /** Writes the files of [staged] (not its folders, `.tmp` or dot files) into [target]; returns the CBZ's size in bytes. */
    fun write(
        staged: File,
        target: File,
    ): Long {
        val files =
            staged
                .listFiles()
                .orEmpty()
                .filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.startsWith(".") }
                .sortedBy { it.name }
        require(files.any { it.name != COMIC_INFO }) { "no page images in ${staged.path}" }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        ZipOutputStream(part.outputStream().buffered()).use { zip ->
            for (file in files) {
                val entry = ZipEntry(file.name)
                entry.time = ENTRY_TIME
                if (file.name == COMIC_INFO) {
                    entry.method = ZipEntry.DEFLATED
                } else {
                    val bytes = file.readBytes()
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target.length()
    }
}
