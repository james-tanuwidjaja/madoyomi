package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory

/** What a change of downloads folder does with what is downloaded ([DownloadsFolder.change]). */
enum class DownloadsFolderMode { MOVE, START_EMPTY }

/** How a change of downloads folder goes. */
enum class DownloadsFolderChangeState { MOVING, DONE, FAILED }

/**
 * The downloads folder of one's choice.
 *
 * The folder is [DownloadSettings.folder] (`downloads.json`), else upstream's `server.downloadsPath`, else
 * `<data dir>/downloads`. Upstream's setting falls back to the default when its folder is missing; Madoyomi's never
 * does: a folder that is gone (an unplugged drive) stays the folder, the queue waits ([blockReason]) and the reader and
 * the player answer `DOWNLOADS_UNREACHABLE`, until it is back or another one is chosen.
 *
 * Choosing another folder asks what to do with what is downloaded (the user's decision, asked each time):
 * - [DownloadsFolderMode.MOVE]: everything in the folder (manga's CBZ files, anime's videos with their subtitle and audio files,
 *   upstream's thumbnails, the unfinished downloads' staging) moves to the new folder. On the same drive each top
 *   folder is renamed; across drives every file is copied first, the setting switched, then the old files deleted, so
 *   a failed copy (a full disk) leaves everything where it was. The records stay as they are: anime's paths are relative
 *   to the folder, and a chapter's CBZ path is worked out from it.
 * - [DownloadsFolderMode.START_EMPTY]: the new folder starts empty and the old files stay on disk where they are, forgotten: no
 *   chapter or episode is marked downloaded any more, and the completed list is cleared.
 *
 * Either way the new folder must be empty (or not exist yet), and no download may be queued or running (pause them
 * first): a download writes into the folder. While files move, nothing new starts.
 */

object DownloadsFolder {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Files a folder may hold and still count as empty (Explorer's own). */
    private val IGNORED = setOf("desktop.ini", "thumbs.db")

    /** The last change of folder since the backend started. */
    data class Change(
        val mode: DownloadsFolderMode,
        val state: DownloadsFolderChangeState,
        val from: String,
        val to: String,
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        /** FAILED: why. DONE: what was left behind (old files that couldn't be deleted), if anything. */
        val message: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long? = null,
    )

    data class Status(
        /** The folder, absolute. */
        val path: String,
        /** `<data dir>/downloads`. */
        val defaultPath: String,
        val isDefault: Boolean,
        /** The folder is there (a folder on an unplugged drive isn't). */
        val reachable: Boolean,
        val change: Change?,
    )

    private val _change = MutableStateFlow<Change?>(null)

    /** The last change, as it goes. */
    val change: StateFlow<Change?> = _change.asStateFlow()

    private var job: Job? = null

    /** Tests: copy and delete as across drives, even on one drive. */
    @Volatile
    var forceCopy = false

    /**
     * The downloads folder of the data dir [dataRoot] (`ApplicationDirs.downloadsRoot`): Madoyomi's setting, then
     * upstream's [upstreamPath], then `<data dir>/downloads`.
     */
    fun root(
        dataRoot: String,
        upstreamPath: String,
    ): String =
        DownloadSettingsStore
            .currentAt(DownloadSettingsStore.fileFor(dataRoot))
            .folder
            .ifBlank { upstreamPath }
            .ifBlank { defaultRoot(dataRoot) }

    fun defaultRoot(dataRoot: String): String = "$dataRoot/downloads"

    private fun current(): File = File(applicationDirs.downloadsRoot).absoluteFile

    private fun default(): File = File(defaultRoot(applicationDirs.dataRoot)).absoluteFile

    fun status(): Status {
        val folder = current()
        val default = default()
        return Status(
            path = folder.path,
            defaultPath = default.path,
            isDefault = same(folder, default),
            reachable = folder.isDirectory,
            change = _change.value,
        )
    }

    /** Why nothing new may be downloaded now (the queue waits), or null. */
    fun blockReason(): String? {
        val change = _change.value
        if (change?.state == DownloadsFolderChangeState.MOVING) return "The downloads are moving to ${change.to}"
        val folder = current()
        // the default folder is made at startup and by the downloaders; a chosen one is never made again
        if (!folder.isDirectory && !same(folder, default())) return "The downloads folder isn't reachable: ${folder.path}"
        return null
    }

    /** Throws `DOWNLOADS_UNREACHABLE` when the folder isn't there (the reader and the player, before "the file is gone"). */
    fun requireReachable() {
        val folder = current()
        if (!folder.isDirectory) {
            throw MadoyomiException(
                ErrorCode.DOWNLOADS_UNREACHABLE,
                "The downloads folder isn't reachable: ${folder.path}",
                mapOf("path" to folder.path),
            )
        }
    }

    /**
     * Makes [path] the downloads folder ("" or null: the default one), doing [mode] with what is downloaded. Checks
     * everything first (`BAD_REQUEST` naming what is wrong), then moves in the background: [change] and [status] follow
     * it. Returns the change as it starts.
     */
    fun change(
        path: String?,
        mode: DownloadsFolderMode,
    ): Change {
        val target = resolve(path)
        synchronized(this) {
            if (_change.value?.state == DownloadsFolderChangeState.MOVING) bad("The downloads are already moving to ${_change.value?.to}")
            val from = current()
            check(from, target, mode)
            val change = Change(mode, DownloadsFolderChangeState.MOVING, from.path, target.path)
            _change.value = change
            logger.info { "downloads folder: ${mode.name.lowercase()} from ${from.path} to ${target.path}" }
            job =
                scope.launch {
                    val result =
                        runCatching { if (mode == DownloadsFolderMode.MOVE) move(from, target) else startEmpty(target) }
                            .getOrElse { e ->
                                logger.warn(e) { "downloads folder: the change to ${target.path} failed" }
                                _change.value!!.copy(
                                    state = DownloadsFolderChangeState.FAILED,
                                    message = e.message ?: e.toString(),
                                    finishedAt = System.currentTimeMillis(),
                                )
                            }
                    _change.value = result
                    if (result.state == DownloadsFolderChangeState.DONE) {
                        logger.info {
                            "downloads folder: now ${target.path} (${result.filesDone} files, ${result.bytesDone} bytes moved)" +
                                (result.message?.let { "; $it" } ?: "")
                        }
                    }
                    DownloadQueue.refresh()
                }
            return change
        }
    }

    /** Tests: waits for the change in progress. */
    suspend fun awaitChange() {
        job?.join()
    }

    /** Tests: forgets the last change. */
    fun reset() {
        _change.value = null
    }

    private fun resolve(path: String?): File {
        val text =
            path
                ?.trim()
                ?.removeSurrounding("\"")
                ?.trim()
                .orEmpty()
        if (text.isEmpty()) return default()
        if (text.any { it == '\n' || it == '\r' || it == '\u0000' }) bad("Not a folder path")
        val file = File(text)
        if (!file.isAbsolute) bad("Choose a full path, with its drive (${file.path} isn't one)")
        return runCatching {
            file
                .toPath()
                .toAbsolutePath()
                .normalize()
                .toFile()
        }.getOrElse { bad("Not a folder path: $text") }
    }

    private fun check(
        from: File,
        target: File,
        mode: DownloadsFolderMode,
    ) {
        if (same(from, target)) bad("${target.path} is the downloads folder already")
        if (inside(target, from) || inside(from, target)) bad("The new folder can't be inside the downloads folder, or hold it")
        if (inside(target, File(applicationDirs.dataRoot).absoluteFile) && !same(target, default())) {
            bad("Choose a folder outside Madoyomi's data folder (or the default one)")
        }
        if (target.exists()) {
            if (!target.isDirectory) bad("${target.path} is a file, not a folder")
            val names = target.list()?.filterNot { it.lowercase() in IGNORED } ?: bad("${target.path} can't be read")
            if (names.isNotEmpty()) bad("Choose an empty folder: ${target.path} already holds ${names.size} files or folders")
        }
        if (mode == DownloadsFolderMode.MOVE && !from.isDirectory) {
            bad("The downloads folder isn't reachable (${from.path}), so nothing can be moved from it: start an empty folder instead")
        }
        val busy = DownloadQueue.count(DownloadItemState.QUEUED) + DownloadQueue.count(DownloadItemState.DOWNLOADING)
        if (busy > 0) bad("Pause the downloads first: $busy are queued or downloading")
        // the folder can be made and written to
        if (!target.isDirectory && !target.mkdirs()) bad("${target.path} can't be created")
        val probe = File(target, ".madoyomi-write-test")
        val writable = runCatching { probe.writeText("") }.isSuccess && probe.delete()
        if (!writable) bad("Madoyomi can't write to ${target.path}")
    }

    private fun move(
        from: File,
        target: File,
    ): Change {
        val children = from.listFiles()?.toList() ?: throw IOException("${from.path} can't be read")
        val files = children.flatMap { c -> c.walkTopDown().filter { it.isFile }.toList() }
        val change = _change.value!!.copy(filesTotal = files.size, bytesTotal = files.sumOf { it.length() })
        _change.value = change
        if (!forceCopy && sameStore(from, target)) {
            // one drive: rename each top folder (and put back what was renamed if one fails)
            val moved = mutableListOf<Pair<Path, Path>>()
            try {
                for (child in children) {
                    val dest = File(target, child.name).toPath()
                    Files.move(child.toPath(), dest, StandardCopyOption.ATOMIC_MOVE)
                    moved += child.toPath() to dest
                }
            } catch (e: Exception) {
                moved.asReversed().forEach { (src, dest) -> runCatching { Files.move(dest, src, StandardCopyOption.ATOMIC_MOVE) } }
                if (e is AtomicMoveNotSupportedException) return copyThenDelete(from, target, children, files)
                throw e
            }
            DownloadSettingsStore.setFolder(folderSetting(target))
            return change.copy(
                state = DownloadsFolderChangeState.DONE,
                filesDone = change.filesTotal,
                bytesDone = change.bytesTotal,
                finishedAt = System.currentTimeMillis(),
            )
        }
        return copyThenDelete(from, target, children, files)
    }

    private fun copyThenDelete(
        from: File,
        target: File,
        children: List<File>,
        files: List<File>,
    ): Change {
        var change = _change.value!!
        try {
            for (file in files) {
                val dest = target.toPath().resolve(from.toPath().relativize(file.toPath()))
                Files.createDirectories(dest.parent)
                Files.copy(file.toPath(), dest, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                change = change.copy(filesDone = change.filesDone + 1, bytesDone = change.bytesDone + file.length())
                _change.value = change
            }
            // empty folders too (an anime's folder whose episodes were deleted)
            children.filter { it.isDirectory }.forEach { c ->
                c.walkTopDown().filter { it.isDirectory }.forEach { d ->
                    Files.createDirectories(target.toPath().resolve(from.toPath().relativize(d.toPath())))
                }
            }
        } catch (e: Exception) {
            // nothing changed: the copies go, the originals stay
            children.forEach { File(target, it.name).deleteRecursively() }
            throw IOException("Copying to ${target.path} failed: ${e.message ?: e}", e)
        }
        DownloadSettingsStore.setFolder(folderSetting(target))
        val left = children.count { !it.deleteRecursively() }
        val leftover = if (left > 0) "some old files couldn't be deleted from ${from.path} (in use?)" else null
        return change.copy(state = DownloadsFolderChangeState.DONE, message = leftover, finishedAt = System.currentTimeMillis())
    }

    private fun startEmpty(target: File): Change {
        DownloadSettingsStore.setFolder(folderSetting(target))
        val (chapters, episodes, completed) =
            dbTransaction {
                val chapters = ChapterTable.update({ ChapterTable.isDownloaded eq true }) { it[isDownloaded] = false }
                ChapterUserTable.update({ ChapterUserTable.isDownloaded eq true }) { it[isDownloaded] = false }
                val episodes = EpisodeDownloadTable.deleteAll()
                val completed = DownloadTable.deleteWhere { DownloadTable.state eq DownloadItemState.DONE }
                Triple(chapters, episodes, completed)
            }
        logger.info {
            "downloads folder: started empty; $chapters chapters and $episodes episodes no longer marked downloaded, " +
                "$completed completed downloads cleared"
        }
        DownloadQueue.refresh()
        return _change.value!!.copy(state = DownloadsFolderChangeState.DONE, finishedAt = System.currentTimeMillis())
    }

    /** What `downloads.json` keeps: "" for the default folder. */
    private fun folderSetting(target: File): String = if (same(target, default())) "" else target.path

    private fun same(
        a: File,
        b: File,
    ): Boolean =
        a.absoluteFile
            .normalize()
            .path
            .equals(b.absoluteFile.normalize().path, ignoreCase = isWindows)

    /** [child] is strictly inside [parent]. */
    private fun inside(
        child: File,
        parent: File,
    ): Boolean {
        var p = child.absoluteFile.normalize().parentFile
        while (p != null) {
            if (same(p, parent)) return true
            p = p.parentFile
        }
        return false
    }

    private fun sameStore(
        from: File,
        target: File,
    ): Boolean =
        runCatching {
            val existing = generateSequence(target.toPath()) { it.parent }.first { it.isDirectory() }
            Files.getFileStore(from.toPath()) == Files.getFileStore(existing)
        }.getOrDefault(false)

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    private fun bad(message: String): Nothing = throw MadoyomiException(ErrorCode.BAD_REQUEST, message)
}
