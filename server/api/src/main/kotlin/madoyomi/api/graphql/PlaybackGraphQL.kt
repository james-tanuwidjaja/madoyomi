package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import madoyomi.core.player.PlaybackSessions
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/** [PlaybackSessions.State] in the schema (a type named `State` would be too vague there). */
enum class PlaybackState { RUNNING, EXITED, STOPPED }

/** A playback session: the backend's mpv process for one episode (Phase 4; one session at a time). */
@GraphQLDescription("The backend's mpv session for one episode; one at a time")
data class PlaybackSessionType(
    val id: String,
    val episodeId: Int,
    val animeId: Int,
    @GraphQLDescription("What mpv shows as its title: \"<anime> - <episode>\"")
    val title: String,
    val videoTitle: String,
    val state: PlaybackState,
    @GraphQLDescription("Epoch milliseconds")
    val startedAt: Long,
    @GraphQLDescription("Epoch milliseconds, once mpv exited or was stopped")
    val endedAt: Long?,
    @GraphQLDescription("mpv's exit code, if known")
    val exitCode: Int?,
    @GraphQLDescription("mpv's position in seconds; null until mpv reports it over IPC")
    val positionSeconds: Double?,
    @GraphQLDescription("The video's duration in seconds; null while unknown")
    val durationSeconds: Double?,
    val paused: Boolean,
) {
    constructor(session: PlaybackSessions.Session) : this(
        id = session.id,
        episodeId = session.episodeId,
        animeId = session.animeId,
        title = session.title,
        videoTitle = session.videoTitle,
        state = PlaybackState.valueOf(session.state.name),
        startedAt = session.startedAt,
        endedAt = session.endedAt,
        exitCode = session.exitCode,
        positionSeconds = session.positionSeconds,
        durationSeconds = session.durationSeconds,
        paused = session.paused,
    )
}

/** [PlaybackSessions.EventType] in the schema. */
enum class PlaybackEventKind { STARTED, PROGRESS, WATCHED, ENDED, ERROR, CLOSED }

@GraphQLDescription("Something that happened in a playback session (playbackEvents)")
data class PlaybackEventType(
    val type: PlaybackEventKind,
    @GraphQLDescription("The session as it is now, with its position")
    val session: PlaybackSessionType,
    @GraphQLDescription("ENDED: the next episode in the source's order, if there is one")
    val nextEpisodeId: Int?,
    @GraphQLDescription("ENDED: mpv's end-file reason (eof, or quit for a quit past the watched threshold)")
    val reason: String?,
    @GraphQLDescription("ERROR: what went wrong")
    val message: String?,
) {
    constructor(event: PlaybackSessions.Event) : this(
        type = PlaybackEventKind.valueOf(event.type.name),
        session = PlaybackSessionType(event.session),
        nextEpisodeId = event.nextEpisodeId,
        reason = event.reason,
        message = event.message,
    )
}

/** Over `graphql-transport-ws` at `/api/graphql`. Nothing is replayed: subscribe before `playEpisode`. */
class PlaybackSubscription {
    @RequireAuth
    @GraphQLDescription(
        "Playback events of every session, or only of episodeId: STARTED (IPC connected), PROGRESS (at most once a " +
            "second, and on pause, resume and seek), WATCHED (once per session), ENDED (with the next episode), ERROR " +
            "(mpv ended early), CLOSED (always last)",
    )
    fun playbackEvents(episodeId: Int? = null): Flow<PlaybackEventType> =
        PlaybackSessions.events
            .filter { episodeId == null || it.session.episodeId == episodeId }
            .map(::PlaybackEventType)
}

class PlaybackQuery {
    @RequireAuth
    @GraphQLDescription("The playback session that is running, else the last one, else null")
    fun playbackSession(): PlaybackSessionType? = PlaybackSessions.current()?.let(::PlaybackSessionType)
}

class PlaybackMutation {
    data class PlayEpisodeInput(
        val clientMutationId: String? = null,
        val episodeId: Int,
        @GraphQLDescription("EpisodeHosterType.index of the episode's last fetchEpisodeVideos")
        val hosterIndex: Int,
        @GraphQLDescription("EpisodeVideoType.index within the hoster")
        val videoIndex: Int,
        @GraphQLDescription("Where to start, in seconds; null or 0 is the beginning")
        val startSeconds: Double? = null,
    )

    data class PlayEpisodePayload(
        val clientMutationId: String?,
        val session: PlaybackSessionType,
    )

    @RequireAuth
    @GraphQLDescription(
        "Plays a video of the episode in mpv (its own window): resolves the video first, then replaces the running " +
            "session. PLAYER_NOT_FOUND without mpv, PLAYER_FAILED if it can't start",
    )
    fun playEpisode(input: PlayEpisodeInput): CompletableFuture<PlayEpisodePayload?> =
        future {
            val session = PlaybackSessions.play(input.episodeId, input.hosterIndex, input.videoIndex, input.startSeconds)
            PlayEpisodePayload(input.clientMutationId, PlaybackSessionType(session))
        }

    data class StopPlaybackInput(
        val clientMutationId: String? = null,
    )

    data class StopPlaybackPayload(
        val clientMutationId: String?,
        @GraphQLDescription("The session that was stopped; null if none was running")
        val session: PlaybackSessionType?,
    )

    @RequireAuth
    @GraphQLDescription("Closes mpv")
    fun stopPlayback(input: StopPlaybackInput): StopPlaybackPayload =
        StopPlaybackPayload(input.clientMutationId, PlaybackSessions.stop()?.let(::PlaybackSessionType))
}
