package madoyomi.core.logs

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Numbered log files: mpv's per-session logs. */
class LogFilesTest {
    private val dir: File = Files.createTempDirectory("log-files").toFile()
    private val log = File(dir, "mpv.log")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun names() = dir.listFiles()!!.map { it.name }.sorted()

    /** One session: rotate, then write the new current log. */
    private fun session(text: String) {
        LogFiles.rotate(log, 3)
        log.writeText(text)
    }

    @Test
    fun `each rotation moves the logs up a number and keeps only the newest`() {
        session("a")
        assertEquals(listOf("mpv.log"), names())
        session("b")
        session("c")
        assertEquals(listOf("mpv.1.log", "mpv.2.log", "mpv.log"), names())
        session("d")
        session("e")
        assertEquals(listOf("mpv.1.log", "mpv.2.log", "mpv.log"), names())
        assertEquals(listOf("e", "d", "c"), LogFiles.series(log).map { it.readText() })
    }

    @Test
    fun `nothing to rotate without a current log, and gaps are fine`() {
        LogFiles.rotate(log, 3)
        assertEquals(emptyList(), names())
        File(dir, "mpv.2.log").writeText("old")
        log.writeText("now")
        LogFiles.rotate(log, 3)
        // .2 was the oldest kept: it goes, and the current log becomes .1
        assertEquals(listOf("mpv.1.log"), names())
        assertEquals("now", File(dir, "mpv.1.log").readText())
    }

    @Test
    fun `logs numbered past a lower limit are deleted, and other files are left alone`() {
        listOf("mpv.log", "mpv.1.log", "mpv.7.log", "mpv.x.log", "mpv.0.log", "sidecar.1.log", "mpv.log.gz").forEach {
            File(dir, it).writeText(it)
        }
        LogFiles.rotate(log, 3)
        assertEquals(listOf("mpv.0.log", "mpv.1.log", "mpv.2.log", "mpv.log.gz", "mpv.x.log", "sidecar.1.log"), names())
        assertEquals("mpv.log", File(dir, "mpv.1.log").readText())
        assertEquals("mpv.1.log", File(dir, "mpv.2.log").readText())
    }

    @Test
    fun `keeping one file rotates nothing, and fewer is refused`() {
        log.writeText("only")
        LogFiles.rotate(log, 1)
        assertEquals(listOf("mpv.log"), names())
        assertFailsWith<IllegalArgumentException> { LogFiles.rotate(log, 0) }
    }

    @Test
    fun `a log another program still holds is moved once it lets go`() {
        session("a")
        session("b")
        // on Windows a file open without FILE_SHARE_DELETE (as java.io streams open it) can't be renamed: the way a
        // virus scanner reading the log mpv just closed blocked the move in CI
        val holder = log.inputStream()
        val release =
            Thread {
                Thread.sleep(300)
                holder.close()
            }.apply { start() }
        try {
            session("c")
        } finally {
            release.join()
            holder.close()
        }
        assertEquals(listOf("mpv.1.log", "mpv.2.log", "mpv.log"), names())
        assertEquals("c", log.readText())
        assertEquals("b", File(dir, "mpv.1.log").readText())
        assertEquals("a", File(dir, "mpv.2.log").readText())
    }
}
