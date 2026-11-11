package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.CompletableFuture
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
 * - Over the IPC pipe (Stage 2, [MpvIpcClient]): the session observes mpv's position ([PlaybackTracker]), saves
 *   progress into `EPISODE` ([EpisodeProgress]), marks the episode watched at [watchedThreshold], adds the external
 *   subtitle and audio tracks with their labels, and stops mpv with `quit` before it would kill it. [events] carries
 *   what happens (nothing is replayed: subscribe first).
 *
 * A session is RUNNING until mpv exits (EXITED) or Madoyomi stops it (STOPPED).
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
        /** mpv's position and duration in seconds (null until mpv reports them over IPC), and whether it is paused. */
        val positionSeconds: Double? = null,
        val durationSeconds: Double? = null,
        val paused: Boolean = false,
    )

    enum class EventType {
        /** The IPC pipe is connected: progress follows. */
        STARTED,

        /** The position moved (at most once a second), or mpv paused, resumed or seeked. */
        PROGRESS,

        /** The episode was marked watched (the threshold was reached; once per session). */
        WATCHED,

        /** The end of the file, or a quit past the threshold; [Event.nextEpisodeId] is the next episode, if any. */
        ENDED,

        /** mpv ended early: the video failed to play, or mpv exited with an error ([Event.message]). */
        ERROR,

        /** The session is over (always the last event of a session; after ENDED or ERROR if there was one). */
        CLOSED,
    }

    data class Event(
        val type: EventType,
        val session: Session,
        val nextEpisodeId: Int? = null,
        /** For ENDED: mpv's end-file reason (`eof`, `quit`). */
        val reason: String? = null,
        val message: String? = null,
    )

    private class Live(
        @Volatile var session: Session,
        val process: Process,
        val files: List<File>,
        val tracks: List<MpvCommand.Track>,
    ) {
        lateinit var tracker: PlaybackTracker
        val client = CompletableFuture<MpvIpcClient>()

        @Volatile
        var finished = false

        fun snapshot(): Session =
            tracker.snapshot().let {
                session.copy(positionSeconds = it.positionSeconds, durationSeconds = it.durationSeconds, paused = it.paused)
            }

        fun clientNow(): MpvIpcClient? = client.getNow(null)
    }

    private val lock = Any()

    @Volatile
    private var live: Live? = null

    @Volatile
    private var last: Session? = null

    private val hookAdded = AtomicBoolean(false)

    /** The command that starts mpv, before its arguments ([MpvLocator]). Tests replace it with a fake mpv. */
    @Volatile
    var executable: () -> List<String> = { listOf(MpvLocator.locate().path) }

    /** The share of the duration at which an episode is marked watched (the player settings, Stage 5). */
    @Volatile
    var watchedThreshold: Double = PlaybackTracker.DEFAULT_WATCHED_THRESHOLD

    /** How long to wait for mpv's IPC pipe after the start (an HLS stream can take a while to open). */
    @Volatile
    var ipcConnectTimeoutMillis: Long = 60_000

    private val eventFlow = MutableSharedFlow<Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What happens in sessions, as it happens. Nothing is replayed. */
    val events: SharedFlow<Event> = eventFlow.asSharedFlow()

    /** How many collect [events] (tests wait for a subscription). */
    val eventSubscribers: StateFlow<Int> get() = eventFlow.subscriptionCount

    private fun emit(event: Event) {
        eventFlow.tryEmit(event)
    }

    /** The running session (with its position), else the last one (ended), else null. */
    fun current(): Session? = live?.snapshot() ?: last

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
            val entry = Live(session, process, listOf(include, record), built.tracks)
            entry.tracker = PlaybackTracker(hooks(entry), watchedThreshold)
            live = entry
            last = session
            process.onExit().thenAccept { onExit(entry) }
            addShutdownHook()
            connectIpc(entry)
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
        entry.finished = true
        // quit over IPC first (mpv exits with 0 and its end-file reaches the tracker); destroy() is TerminateProcess
        // on Windows, the fallback
        val quitSent = entry.clientNow()?.let { client -> runCatching { client.command("quit") }.isSuccess } ?: false
        var exited = quitSent && entry.process.waitFor(3, TimeUnit.SECONDS)
        if (!exited) {
            entry.process.destroy()
            exited = entry.process.waitFor(5, TimeUnit.SECONDS)
            if (!exited) entry.process.destroyForcibly()
        }
        val exitCode = if (exited) runCatching { entry.process.exitValue() }.getOrNull() else null
        val stopped = finish(entry, stopped = true, exitCode = exitCode, state = State.STOPPED)
        logger.info { "playback ${stopped.id}: stopped${if (quitSent) " (quit over IPC)" else ""}" }
        return stopped
    }

    private fun onExit(entry: Live) {
        synchronized(lock) {
            if (entry.finished) {
                entry.files.forEach { it.delete() }
                return
            }
            entry.finished = true
            if (live === entry) live = null
            val exitCode = runCatching { entry.process.exitValue() }.getOrNull()
            val ended = finish(entry, stopped = false, exitCode = exitCode, state = State.EXITED)
            logger.info { "playback ${ended.id}: mpv exited with code ${ended.exitCode}" }
        }
    }

    /** The end of a session: the last save, its outcome as events, its files deleted. */
    private fun finish(
        entry: Live,
        stopped: Boolean,
        exitCode: Int?,
        state: State,
    ): Session {
        entry.clientNow()?.let { client ->
            // mpv closed the pipe when it exited: let the reader hand over what mpv sent last (end-file)
            client.awaitClosed(2_000)
            client.close()
        }
        val outcome = entry.tracker.finish(stopped, exitCode)
        val ended = entry.snapshot().copy(state = state, exitCode = exitCode, endedAt = System.currentTimeMillis())
        entry.session = ended
        if (last?.id == ended.id) last = ended
        entry.files.forEach { it.delete() }
        when (outcome) {
            is PlaybackTracker.Outcome.Ended -> {
                val next = runCatching { EpisodeProgress.nextEpisode(ended.episodeId) }.getOrNull()
                logger.info { "playback ${ended.id}: ended (${outcome.reason}), next episode ${next ?: "none"}" }
                emit(Event(EventType.ENDED, ended, nextEpisodeId = next, reason = outcome.reason))
            }

            is PlaybackTracker.Outcome.Failed -> {
                logger.warn { "playback ${ended.id}: ${outcome.message}" }
                emit(Event(EventType.ERROR, ended, message = outcome.message))
            }

            PlaybackTracker.Outcome.Closed -> {
                Unit
            }
        }
        emit(Event(EventType.CLOSED, ended))
        return ended
    }

    /** What the session's tracker does: saves into `EPISODE`, the watched mark, events, the external tracks. */
    private fun hooks(entry: Live) =
        object : PlaybackTracker.Hooks {
            private val id get() = entry.session.id
            private val episodeId get() = entry.session.episodeId

            override fun save(
                positionMillis: Long,
                durationMillis: Long,
            ) {
                runCatching { EpisodeProgress.save(episodeId, positionMillis, durationMillis) }
                    .onFailure { logger.warn(it) { "playback $id: could not save the progress" } }
            }

            override fun markWatched(): Boolean =
                runCatching { EpisodeProgress.markWatched(episodeId) }
                    .onFailure { logger.warn(it) { "playback $id: could not mark the episode watched" } }
                    .getOrDefault(false)

            override fun progress(snapshot: PlaybackTracker.Snapshot) = emit(Event(EventType.PROGRESS, entry.snapshot()))

            override fun watched(snapshot: PlaybackTracker.Snapshot) {
                logger.info { "playback $id: episode $episodeId marked watched at ${snapshot.positionSeconds} s" }
                emit(Event(EventType.WATCHED, entry.snapshot()))
            }

            override fun fileLoaded() {
                if (entry.tracks.isEmpty()) return
                entry.client.thenAccept { client -> addTracks(entry, client) }
            }
        }

    /**
     * `sub-add`/`audio-add` for the video's external tracks, with their labels as titles (mpv loads them async). The
     * first subtitle track is selected. The first audio track is selected only when the file has no audio of its own
     * (an HLS stream whose audio comes only as external tracks): mpv's own selection has already run by now.
     */
    private fun addTracks(
        entry: Live,
        client: MpvIpcClient,
    ) {
        client.command("get_property", "track-list").whenComplete { list, _ ->
            val hasAudio = (list as? JsonArray)?.any { (it as? JsonObject)?.get("type").asText() == "audio" } == true
            val firstSubtitle = entry.tracks.indexOfFirst { it.kind == MpvCommand.Track.Kind.SUBTITLE }
            val firstAudio = if (hasAudio) -1 else entry.tracks.indexOfFirst { it.kind == MpvCommand.Track.Kind.AUDIO }
            entry.tracks.forEachIndexed { i, track ->
                val select = i == firstSubtitle || i == firstAudio
                client.command(*track.command(select).toTypedArray(), async = true).whenComplete { _, error ->
                    if (error != null && !client.isClosed) {
                        logger.warn {
                            "playback ${entry.session.id}: mpv could not add the ${track.kind.name.lowercase()} track ${track.title}: ${error.message}"
                        }
                    }
                }
            }
        }
    }

    /** Connects to the session's IPC pipe in the background, then observes the [PlaybackTracker.OBSERVED] properties. */
    private fun connectIpc(entry: Live) {
        val id = entry.session.id
        Thread({
            val client =
                MpvIpcClient.connect(
                    pipeName = entry.session.pipeName,
                    timeoutMillis = ipcConnectTimeoutMillis,
                    onEvent = { entry.tracker.onMessage(it) },
                    keepTrying = { entry.process.isAlive && !entry.finished },
                )
            when {
                client == null -> {
                    if (entry.process.isAlive &&
                        !entry.finished
                    ) {
                        logger.warn { "playback $id: mpv's IPC pipe never opened; no progress is saved" }
                    }
                }

                entry.finished || !entry.process.isAlive -> {
                    client.close()
                }

                else -> {
                    entry.client.complete(client)
                    logger.debug { "playback $id: IPC connected" }
                    // before the observations, whose first values are PROGRESS events
                    emit(Event(EventType.STARTED, entry.snapshot()))
                    PlaybackTracker.OBSERVED.forEachIndexed { i, name -> client.observe(i + 1, name) }
                }
            }
        }, "mpv-ipc-connect-$id").apply { isDaemon = true }.start()
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
                // the last progress was saved at most 5 s ago: no database here, it may be shutting down too
                live?.let { entry ->
                    entry.finished = true
                    val quitSent = entry.clientNow()?.let { runCatching { it.command("quit") }.isSuccess } ?: false
                    val exited = quitSent && runCatching { entry.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
                    if (!exited) {
                        entry.process.destroy()
                        runCatching { entry.process.waitFor(2, TimeUnit.SECONDS) }
                    }
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
