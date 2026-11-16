package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CbzWriterTest {
    @Test
    fun `the pages in name order, stored, ComicInfo deflated, without tmp or dot files, written through a part file`() {
        val dir = Files.createTempDirectory("cbz-test").toFile()
        try {
            val staged = dir.resolve("staged").also { it.mkdirs() }
            staged.resolve("010.jpg").writeBytes(byteArrayOf(10, 10))
            staged.resolve("002.png").writeBytes(byteArrayOf(2))
            staged.resolve("001.jpg").writeBytes(byteArrayOf(1, 1, 1))
            staged.resolve("003.jpg.tmp").writeBytes(byteArrayOf(3))
            staged.resolve(".page-count").writeText("10")
            staged.resolve(CbzWriter.COMIC_INFO).writeText("<ComicInfo><Series>S</Series></ComicInfo>")
            val target = dir.resolve("out/Manga/Chapter 1.cbz")

            val size = CbzWriter.write(staged, target)
            assertEquals(target.length(), size)
            assertFalse(target.resolveSibling("Chapter 1.cbz.part").exists())
            ZipFile(target).use { zip ->
                val entries = zip.entries().toList()
                assertEquals(listOf("001.jpg", "002.png", "010.jpg", "ComicInfo.xml"), entries.map { it.name })
                assertEquals(listOf(ZipEntry.STORED, ZipEntry.STORED, ZipEntry.STORED, ZipEntry.DEFLATED), entries.map { it.method })
                assertContentEquals(byteArrayOf(1, 1, 1), zip.getInputStream(entries[0]).readBytes())
                assertEquals("<ComicInfo><Series>S</Series></ComicInfo>", zip.getInputStream(entries[3]).readBytes().decodeToString())
                assertEquals(1, entries.map { it.time }.distinct().size, "one time for every entry")
            }

            // the same pages give the same bytes
            val again = dir.resolve("again.cbz")
            CbzWriter.write(staged, again)
            assertContentEquals(target.readBytes(), again.readBytes())

            // a CBZ is replaced whole
            staged.resolve("002.png").delete()
            CbzWriter.write(staged, target)
            ZipFile(target).use { zip -> assertEquals(3, zip.size()) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a folder without pages is refused`() {
        val dir = Files.createTempDirectory("cbz-empty").toFile()
        try {
            dir.resolve(CbzWriter.COMIC_INFO).writeText("<ComicInfo/>")
            assertFailsWith<IllegalArgumentException> { CbzWriter.write(dir, dir.resolve("x.cbz")) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
