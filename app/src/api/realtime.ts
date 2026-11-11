import { effectScope, watch } from "vue";
import { QueryClient } from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import { useEventsStore } from "../stores/events";
import { usePlaybackStore } from "../stores/playback";
import { queryKeys } from "../composables/queryKeys";
import { api, isApiError } from "./client";
import {
  createSubscriptionClient,
  type SubscriptionClient,
  type SubscriptionClientOptions,
} from "./ws";
import {
  EXTENSION_INSTALL_PROGRESS_SUBSCRIPTION,
  EXTENSION_STORE_REFRESHED_SUBSCRIPTION,
  PLAYBACK_EVENTS_SUBSCRIPTION,
} from "./operations/subscriptions";
import type {
  Episode,
  ExtensionInstallProgress,
  ExtensionStoreRefreshed,
  PlaybackEvent,
  PlaybackSession,
} from "./types";
import { mediaKindOf } from "./types";

/**
 * The app's vue-query defaults (Phase 3 §9 "Stage 2"):
 * - a query retries only while the backend is down or restarting (`NETWORK`, `BACKEND_UNAVAILABLE`), 3 times; a
 *   server error code is an answer, not a glitch;
 * - mutations never retry (they call sources and change state);
 * - no refetch on window focus: it's a desktop app, and a browse refetch hits the source's site.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => isApiError(error) && error.isTransient && failureCount < 3,
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
      },
      mutations: { retry: false },
    },
  });
}

const TERMINAL_INSTALL_STATES = new Set(["INSTALLED", "FAILED"]);

export interface RealtimeOptions {
  queryClient: QueryClient;
  /** For tests: the socket factory and timings of the subscription client. */
  subscription?: Omit<SubscriptionClientOptions, "onStateChange" | "onConnected">;
}

/**
 * Connects the app to the backend's events (Phase 3 step 7) and keeps queries in step with backend restarts:
 * - points the subscription client at `ws://<baseUrl>/api/graphql?token=` while the backend is ready, and at the new
 *   port after a restart;
 * - subscribes to `extensionInstallProgress` and `extensionStoreRefreshed` for the app's lifetime, records them in
 *   the events store and invalidates the queries they change;
 * - subscribes to `playbackEvents` (Phase 4): every event of a session goes into the playback store, from which the
 *   episode list draws the playing row's progress live (Stage 3: PROGRESS refetches nothing). WATCHED marks the
 *   episode in the cached list at once, and CLOSED writes its last position there; both then fetch the anime's
 *   episodes again, as stored;
 * - after a reconnect (events may have been missed: nothing is replayed) invalidates the extension and repo queries,
 *   and forgets the session it was following (its CLOSED may be lost; a backend restart ends mpv anyway);
 * - when the backend comes back, refetches the queries that failed while it was away.
 *
 * Call after Pinia is installed. Returns a function that stops all of it.
 */
export function startRealtime(options: RealtimeOptions): {
  client: SubscriptionClient;
  stop: () => void;
} {
  const { queryClient } = options;
  const backend = useBackendStore();
  const events = useEventsStore();
  const playback = usePlaybackStore();

  /** Patches one episode in its anime's cached list (if that list is cached). */
  const patchEpisode = (session: PlaybackSession, patch: (e: Episode) => Episode) =>
    queryClient.setQueryData<Episode[]>(
      queryKeys.episodes(session.animeId),
      (list) => list && list.map((e) => (e.id === session.episodeId ? patch(e) : e)),
    );
  const invalidateEpisodes = (session: PlaybackSession) =>
    void queryClient.invalidateQueries({ queryKey: queryKeys.episodes(session.animeId) });

  const invalidateExtensions = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.extensionStores() });
    void queryClient.invalidateQueries({ queryKey: queryKeys.extensions() });
    void queryClient.invalidateQueries({ queryKey: queryKeys.sources() });
  };

  const client = createSubscriptionClient({
    ...options.subscription,
    onStateChange: (state) => {
      events.wsState = state;
    },
    onConnected: ({ first }) => {
      events.connections++;
      if (!first) {
        invalidateExtensions();
        const stale = playback.session;
        if (stale) {
          playback.clear();
          invalidateEpisodes(stale);
        }
      }
    },
  });

  client.subscribe<{ extensionInstallProgress: ExtensionInstallProgress }>(
    EXTENSION_INSTALL_PROGRESS_SUBSCRIPTION,
    {},
    {
      next: ({ extensionInstallProgress: e }) => {
        events.recordInstall(e);
        if (TERMINAL_INSTALL_STATES.has(e.state)) {
          const kind = e.type ? mediaKindOf(e.type) : undefined;
          void queryClient.invalidateQueries({ queryKey: queryKeys.extensions(kind) });
          void queryClient.invalidateQueries({ queryKey: queryKeys.sources(kind) });
        }
      },
      error: (e) =>
        console.warn("extensionInstallProgress subscription failed:", e.code, e.message),
    },
  );

  client.subscribe<{ extensionStoreRefreshed: ExtensionStoreRefreshed }>(
    EXTENSION_STORE_REFRESHED_SUBSCRIPTION,
    {},
    {
      next: ({ extensionStoreRefreshed: e }) => {
        events.recordStoreRefresh(e);
        void queryClient.invalidateQueries({ queryKey: queryKeys.extensionStores() });
        void queryClient.invalidateQueries({ queryKey: queryKeys.extensions(mediaKindOf(e.type)) });
      },
      error: (e) => console.warn("extensionStoreRefreshed subscription failed:", e.code, e.message),
    },
  );

  client.subscribe<{ playbackEvents: PlaybackEvent }>(
    PLAYBACK_EVENTS_SUBSCRIPTION,
    {},
    {
      next: ({ playbackEvents: e }) => {
        playback.apply(e);
        if (e.type === "WATCHED") {
          patchEpisode(e.session, (ep) => ({ ...ep, isWatched: true }));
          invalidateEpisodes(e.session);
        } else if (e.type === "CLOSED") {
          const { positionSeconds, durationSeconds } = e.session;
          if (positionSeconds != null) {
            patchEpisode(e.session, (ep) => ({
              ...ep,
              lastSecondSeen: String(Math.round(positionSeconds * 1000)),
              totalSeconds: durationSeconds
                ? String(Math.round(durationSeconds * 1000))
                : ep.totalSeconds,
            }));
          }
          invalidateEpisodes(e.session);
        }
      },
      error: (e) => console.warn("playbackEvents subscription failed:", e.code, e.message),
    },
  );

  const scope = effectScope(true);
  scope.run(() => {
    // The URL carries the token; it changes with the port after every restart.
    watch(
      () => (backend.ready ? api.subscriptionUrl() : null),
      (url) => client.setEndpoint(url),
      { immediate: true },
    );

    // Back after a restart (or a first connect): what failed meanwhile is fetched again.
    watch(
      () => (backend.ready ? backend.info?.baseUrl : null),
      (url, previous) => {
        if (url && url !== previous) {
          void queryClient.refetchQueries({ predicate: (q) => q.state.status === "error" });
        }
      },
    );
  });

  return {
    client,
    stop: () => {
      scope.stop();
      client.dispose();
    },
  };
}
