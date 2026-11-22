package madoyomi.core.logs

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Numbered log files: `mpv.log` is the current one, `mpv.1.log` the one before, and so on. The shell
 * keeps `sidecar.log` the same way (app/src-tauri/src/sidecar.rs); the backend's own `madoyomi.log` is rolled by
 * upstream's logback setup (`xyz.nulldev.ts.config.Logging`).
 */
object LogFiles {
    private val logger = KotlinLogging.logger {}

    /** `<name>.<n>.<ext>` beside [file] (`mpv.log`, 2 → `mpv.2.log`); 0 is [file] itself. */
    fun numbered(
        file: File,
        n: Int,
    ): File = if (n == 0) file else File(file.parentFile, "${file.nameWithoutExtension}.$n.${file.extension}")

    /**
     * Makes room for a new [file]: the oldest of [keep] files is deleted, each other one moves up a number, and [file]
     * becomes `.1`. [keep] counts [file] too, so at most [keep] files are left once the new one is written. Best
     * effort: a move or delete that fails is tried again for about a second (Windows refuses one while another
     * program still has the file open, such as a virus scanner reading the log mpv just closed; seen in
     * CI), then logged, and the new output replaces a current log that couldn't move.
     */
    fun rotate(
        file: File,
        keep: Int,
    ) {
        require(keep >= 1) { "keep must be at least 1" }
        if (keep == 1 || !file.exists()) return
        numbered(file, keep - 1).let { oldest ->
            if (oldest.exists() && !retrying { oldest.delete() }) logger.warn { "could not delete ${oldest.path}" }
        }
        for (n in keep - 2 downTo 0) {
            val from = numbered(file, n)
            if (!from.exists()) continue
            val to = numbered(file, n + 1)
            if (!retrying { from.renameTo(to) }) logger.warn { "could not move ${from.path} to ${to.name}" }
        }
        // anything numbered past the limit (a lower limit than before) goes too
        file.parentFile
            ?.listFiles()
            ?.filter { f -> numberOf(file, f)?.let { it >= keep } == true }
            ?.forEach { if (!retrying { it.delete() }) logger.warn { "could not delete ${it.path}" } }
    }

    /** [op] until it succeeds, [ATTEMPTS] times at most, [RETRY_MS] apart. */
    private inline fun retrying(op: () -> Boolean): Boolean {
        repeat(ATTEMPTS - 1) {
            if (op()) return true
            Thread.sleep(RETRY_MS)
        }
        return op()
    }

    private const val ATTEMPTS = 20
    private const val RETRY_MS = 50L

    /** The files of [file]'s series that exist, the current one first. */
    fun series(file: File): List<File> =
        (
            listOf(file) +
                (
                    file.parentFile
                        ?.listFiles()
                        ?.mapNotNull { f -> numberOf(file, f)?.let { it to f } }
                        ?.sortedBy { it.first }
                        ?.map { it.second }
                        ?: emptyList()
                )
        ).filter { it.isFile }

    private fun numberOf(
        file: File,
        candidate: File,
    ): Int? {
        val prefix = "${file.nameWithoutExtension}."
        val suffix = ".${file.extension}"
        val name = candidate.name
        if (!name.startsWith(prefix) || !name.endsWith(suffix) || name.length <= prefix.length + suffix.length) return null
        return name.substring(prefix.length, name.length - suffix.length).toIntOrNull()?.takeIf { it > 0 }
    }
}
