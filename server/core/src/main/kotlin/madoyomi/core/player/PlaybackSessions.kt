package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.anime.AnimeEntries
import madoyomi.core.anime.AnimeVideos
import madoyomi.core.anime.AnimeVideos.EpisodeVideo
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The backend owns mpv (Phase 4 §9): one playback session at a time, in mpv's own window.
 *
 * - [play] resolves the video right before the launch (URLs expire), builds the command ([MpvCommand]), stops the
 *   current session and starts mpv. mpv's output goes to `<data dir>/logs/mpv.log` (the last session's).
 * - The process is a child of the JVM, so it is in the Tauri shell's Job Object too: when the app dies, mpv dies.
 *   A normal backend exit stops it from a shutdown hook.
 * - A backend that crashed and was restarted by the shell left its mpv running, with nothing tracking it. Each session
 *   writes `<data dir>/cache/player/<id>.session` (pid, executable, start time), and [reapOrphans] at startup ends a
 *   process that still matches one, then clears the dir (the session config files included).
 * - The session's config file (headers and the rest, [MpvCommand]) lives in the same dir and is deleted when mpv exits.
 * - mpv gets the backend's environment without `MADOYOMI_TOKEN`.
 *
 * Progress over the IPC pipe is Stage 2; until then a session is RUNNING until mpv exits.
 */
object PlaybackSessions {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    enum class State { RUNNING, EXITED, STOPPED }

    data class Session(
        val id: String,
        val episodeId: Int,
        val animeId: Int,
        /** What mpv shows as the title: "<anime> - <episode>". */
        val title: String,
        val videoTitle: String,
        /** The IPC pipe's name (`\\.\pipe\<pipeName>` on Windows). */
        val pipeName: String,
        val pid: Long,
        /** Epoch milliseconds. */
        val startedAt: Long,
        val state: State,
        /** mpv's exit code once it [State.EXITED] (or was [State.STOPPED]), if known. */
        val exitCode: Int?,
        val endedAt: Long?,
    )

    private class Live(
        @Volatile var session: Session,
        val process: Process,
        val files: List<File>,
    )

    private val lock = Any()
    private var live: Live? = null

    @Volatile
    private var last: Session? = null

    private val hookAdded = AtomicBoolean(false)

    /** The command that starts mpv, before its arguments ([MpvLocator]). Tests replace it with a fake mpv. */
    @Volatile
    var executable: () -> List<String> = { listOf(MpvLocator.locate().path) }

    /** The running session, else the last one (ended), else null. */
    fun current(): Session? = last

    /**
     * Plays video [videoIndex] of hoster [hosterIndex] of the episode (indexes of its last `fetchEpisodeVideos`; the
     * video is resolved first), from [startSeconds]. Replaces the current session.
     */
    suspend fun play(
        episodeId: Int,
        hosterIndex: Int,
        videoIndex: Int,
        startSeconds: Double? = null,
    ): Session {
        val video = AnimeVideos.resolveVideo(episodeId, hosterIndex, videoIndex)
        val episode = AnimeEntries.episodeRow(episodeId)
        val anime = AnimeEntries.animeRow(episode[EpisodeTable.anime].value)
        val title = "${anime[AnimeTable.title]} - ${episode[EpisodeTable.name]}"
        return launch(episodeId, anime[AnimeTable.id].value, title, video, startSeconds)
    }

    /** [play] after the video is known. */
    internal fun launch(
        episodeId: Int,
        animeId: Int,
        title: String,
        video: EpisodeVideo,
        startSeconds: Double?,
    ): Session {
        if (video.videoUrl.isBlank()) {
            throw MadoyomiException(ErrorCode.SOURCE_FAILED, "The video has no URL", mapOf("episodeId" to episodeId))
        }
        // before anything is stopped: a missing mpv leaves the current session playing
        val command = executable()
        val id = UUID.randomUUID().toString()
        val dirs = dirs()
        val include = File(dirs.sessions, "$id.conf")
        val record = File(dirs.sessions, "$id.session")
        val pipeName = "madoyomi-mpv-$id"
        val built = MpvCommand.build(video, MpvCommand.Options(title, startSeconds, pipeName, dirs.config, include))
        built.dropped.forEach { logger.warn { "playback $id: left out $it" } }

        synchronized(lock) {
            stopLocked()
            include.writeText(built.include, Charsets.UTF_8)
            val process =
                try {
                    ProcessBuilder(command + built.args)
                        .redirectErrorStream(true)
                        .redirectOutput(dirs.log)
                        .also { scrubEnvironment(it.environment()) }
                        .start()
                } catch (e: IOException) {
                    include.delete()
                    throw MadoyomiException(
                        ErrorCode.PLAYER_FAILED,
                        "mpv could not be started: ${e.message}",
                        mapOf("cause" to e.message, "executable" to command.first()),
                        e,
                    )
                }
            runCatching { process.outputStream.close() }
            writeRecord(record, id, process)
            val session =
                Session(
                    id = id,
                    episodeId = episodeId,
                    animeId = animeId,
                    title = title,
                    videoTitle = video.videoTitle,
                    pipeName = pipeName,
                    pid = process.pid(),
                    startedAt = System.currentTimeMillis(),
                    state = State.RUNNING,
                    exitCode = null,
                    endedAt = null,
                )
            val entry = Live(session, process, listOf(include, record))
            live = entry
            last = session
            process.onExit().thenAccept { onExit(entry) }
            addShutdownHook()
            logger.info { "playback $id: episode $episodeId, ${video.videoTitle} from ${hostOf(video.videoUrl)}, mpv pid ${process.pid()}" }
            return session
        }
    }

    /** What mpv must not inherit from the backend's environment: the API token. */
    internal fun scrubEnvironment(env: MutableMap<String, String>) {
        env.keys.removeIf { it.equals("MADOYOMI_TOKEN", ignoreCase = true) }
    }

    /** Stops the current session; returns it, or null if none was running. */
    fun stop(): Session? = synchronized(lock) { stopLocked() }

    private fun stopLocked(): Session? {
        val entry = live ?: return null
        live = null
        // Stage 2 asks mpv to quit over IPC first; on Windows destroy() is TerminateProcess either way
        entry.process.destroy()
        val exited = entry.process.waitFor(5, TimeUnit.SECONDS)
        if (!exited) entry.process.destroyForcibly()
        val stopped =
            entry.session.copy(
                state = State.STOPPED,
                exitCode = if (exited) runCatching { entry.process.exitValue() }.getOrNull() else null,
                endedAt = System.currentTimeMillis(),
            )
        entry.session = stopped
        if (last?.id == stopped.id) last = stopped
        entry.files.forEach { it.delete() }
        logger.info { "playback ${stopped.id}: stopped" }
        return stopped
    }

    private fun onExit(entry: Live) {
        synchronized(lock) {
            entry.files.forEach { it.delete() }
            if (entry.session.state != State.RUNNING) return
            val ended =
                entry.session.copy(
                    state = State.EXITED,
                    exitCode = runCatching { entry.process.exitValue() }.getOrNull(),
                    endedAt = System.currentTimeMillis(),
                )
            entry.session = ended
            if (live === entry) live = null
            if (last?.id == ended.id) last = ended
            logger.info { "playback ${ended.id}: mpv exited with code ${ended.exitCode}" }
        }
    }

    /**
     * Ends the mpv processes that sessions of an earlier backend left running (that backend crashed or was killed),
     * matched by pid, executable and start time so a reused pid is never hit, and clears the sessions dir. Returns how
     * many were ended. Called once at startup.
     */
    fun reapOrphans(): Int {
        val dir = dirs().sessions
        var ended = 0
        dir.listFiles { f -> f.name.endsWith(".session") }.orEmpty().forEach { file ->
            val props = Properties()
            runCatching { file.reader(Charsets.UTF_8).use(props::load) }
            val pid = props.getProperty("pid")?.toLongOrNull()
            val handle = pid?.let { ProcessHandle.of(it).orElse(null) }
            if (handle != null && handle.isAlive && matches(handle, props)) {
                handle.descendants().forEach { it.destroy() }
                handle.destroy()
                ended++
                logger.info { "playback ${props.getProperty("id")}: ended the mpv (pid $pid) an earlier backend left running" }
            }
        }
        dir.listFiles { f -> f.name.endsWith(".session") || f.name.endsWith(".conf") }.orEmpty().forEach { it.delete() }
        return ended
    }

    private fun matches(
        handle: ProcessHandle,
        props: Properties,
    ): Boolean {
        val info = handle.info()
        val command = info.command().orElse(null) ?: return false
        if (!command.equals(props.getProperty("command"), ignoreCase = true)) return false
        val started = props.getProperty("startedAt")?.toLongOrNull()
        val actual = info.startInstant().orElse(null)?.toEpochMilli()
        return started == null || actual == null || started == actual
    }

    private fun writeRecord(
        file: File,
        id: String,
        process: Process,
    ) {
        val info = process.toHandle().info()
        val props = Properties()
        props.setProperty("id", id)
        props.setProperty("pid", process.pid().toString())
        info.command().ifPresent { props.setProperty("command", it) }
        info.startInstant().ifPresent { props.setProperty("startedAt", it.toEpochMilli().toString()) }
        runCatching { file.writer(Charsets.UTF_8).use { props.store(it, "Madoyomi playback session") } }
            .onFailure { logger.warn(it) { "playback $id: could not write ${file.path}" } }
    }

    private fun addShutdownHook() {
        if (!hookAdded.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(
            Thread({
                // no lock: a thread holding it may never let go during shutdown. onExit may not run any more, so the
                // session's files go here too
                live?.let { entry ->
                    entry.process.destroy()
                    runCatching { entry.process.waitFor(2, TimeUnit.SECONDS) }
                    entry.files.forEach { it.delete() }
                }
            }, "playback-shutdown"),
        )
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "(no host)"

    /** mpv's config dir, the sessions' files (`<data dir>/cache/player`) and mpv's log. */
    data class Dirs(
        val config: File,
        val sessions: File,
        val log: File,
    )

    fun dirs(): Dirs {
        val root = File(applicationDirs.dataRoot)
        val sessions = File(root, "cache/player").apply { mkdirs() }
        val logs = File(root, "logs").apply { mkdirs() }
        return Dirs(PlayerDirs.configDir(root), sessions, File(logs, "mpv.log"))
    }
}
