import { api } from "../client";
import type { HosterChoice, PlaybackSession } from "../types";

/*
 * Playback (Phase 4): the episode's videos (`fetchEpisodeVideos`), and the backend's mpv session (`playEpisode`,
 * `stopPlayback`, `playbackSession`). The backend owns mpv: it resolves the video right before the launch and sends
 * its headers, so the UI never needs a video's headers or tracks, only what to choose between.
 */

export const PLAYBACK_SESSION_FIELDS = /* GraphQL */ `
  fragment PlaybackSessionFields on PlaybackSessionType {
    id
    episodeId
    animeId
    title
    videoTitle
    state
    startedAt
    endedAt
    exitCode
    positionSeconds
    durationSeconds
    paused
  }
`;

export const EPISODE_VIDEOS_MUTATION = /* GraphQL */ `
  mutation EpisodeVideos($episodeId: Int!) {
    fetchEpisodeVideos(input: { episodeId: $episodeId }) {
      hosters {
        index
        name
        lazy
        state
        errorCode
        errorMessage
        videos {
          index
          videoTitle
          resolution
          bitrate
          preferred
          initialized
          subtitleTracks {
            lang
          }
          audioTracks {
            lang
          }
        }
      }
    }
  }
`;

export const PLAY_EPISODE_MUTATION = /* GraphQL */ `
  mutation PlayEpisode(
    $episodeId: Int!
    $hosterIndex: Int!
    $videoIndex: Int!
    $startSeconds: Float
  ) {
    playEpisode(
      input: {
        episodeId: $episodeId
        hosterIndex: $hosterIndex
        videoIndex: $videoIndex
        startSeconds: $startSeconds
      }
    ) {
      session {
        ...PlaybackSessionFields
      }
    }
  }
  ${PLAYBACK_SESSION_FIELDS}
`;

export const STOP_PLAYBACK_MUTATION = /* GraphQL */ `
  mutation StopPlayback {
    stopPlayback(input: {}) {
      session {
        ...PlaybackSessionFields
      }
    }
  }
  ${PLAYBACK_SESSION_FIELDS}
`;

export const PLAYBACK_SESSION_QUERY = /* GraphQL */ `
  query PlaybackSession {
    playbackSession {
      ...PlaybackSessionFields
    }
  }
  ${PLAYBACK_SESSION_FIELDS}
`;

/** The episode's hosters, each with its videos (a lazy hoster is IDLE and has none yet). */
export async function fetchEpisodeVideos(episodeId: number): Promise<HosterChoice[]> {
  return (
    await api.gql<{ fetchEpisodeVideos: { hosters: HosterChoice[] } }>(EPISODE_VIDEOS_MUTATION, {
      episodeId,
    })
  ).fetchEpisodeVideos.hosters;
}

export interface PlayRequest {
  episodeId: number;
  hosterIndex: number;
  videoIndex: number;
  /** Seconds; omitted or 0 starts at the beginning. */
  startSeconds?: number | null;
}

/** Starts the video in mpv (the backend resolves it first) and replaces the session that was playing. */
export async function playEpisode(request: PlayRequest): Promise<PlaybackSession> {
  return (
    await api.gql<{ playEpisode: { session: PlaybackSession } }>(PLAY_EPISODE_MUTATION, {
      ...request,
      startSeconds: request.startSeconds ?? null,
    })
  ).playEpisode.session;
}

/** Closes mpv; returns the session that was stopped, or null if nothing was playing. */
export async function stopPlayback(): Promise<PlaybackSession | null> {
  return (
    await api.gql<{ stopPlayback: { session: PlaybackSession | null } }>(STOP_PLAYBACK_MUTATION)
  ).stopPlayback.session;
}

/** The session that is playing, else the last one, else null. */
export async function fetchPlaybackSession(signal?: AbortSignal): Promise<PlaybackSession | null> {
  return (
    await api.gql<{ playbackSession: PlaybackSession | null }>(PLAYBACK_SESSION_QUERY, undefined, {
      signal,
    })
  ).playbackSession;
}
