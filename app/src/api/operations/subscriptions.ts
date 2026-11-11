import { PLAYBACK_SESSION_FIELDS } from "./playback";

/** Every install and update step, of every extension (no `pkgName` filter: the app follows all of them). */
export const EXTENSION_INSTALL_PROGRESS_SUBSCRIPTION = /* GraphQL */ `
  subscription ExtensionInstallProgress {
    extensionInstallProgress {
      pkgName
      type
      state
      errorCode
      message
    }
  }
`;

/** One event per repo refresh (`fetchExtensions`, the startup refresh), of both kinds. */
export const EXTENSION_STORE_REFRESHED_SUBSCRIPTION = /* GraphQL */ `
  subscription ExtensionStoreRefreshed {
    extensionStoreRefreshed {
      indexUrl
      type
      ok
      error
      extensionCount
    }
  }
`;

/**
 * Playback events of every session (Phase 4 Stage 2): STARTED, PROGRESS (at most once a second), WATCHED (once per
 * session), ENDED (with the next episode), ERROR, and CLOSED (always last). Nothing is replayed.
 */
export const PLAYBACK_EVENTS_SUBSCRIPTION = /* GraphQL */ `
  subscription PlaybackEvents {
    playbackEvents {
      type
      nextEpisodeId
      reason
      message
      httpStatus
      retried
      failedVideo {
        hosterName
        videoTitle
        resolution
      }
      nextVideo {
        hosterName
        videoTitle
        resolution
      }
      session {
        ...PlaybackSessionFields
      }
    }
  }
  ${PLAYBACK_SESSION_FIELDS}
`;
