import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient, startRealtime } from "../realtime";
import { ApiError } from "../client";
import { useBackendStore } from "../../stores/backend";
import { useEventsStore } from "../../stores/events";
import { usePlaybackStore } from "../../stores/playback";
import { makeEpisodes } from "../../test/fixtures";
import type { Episode, PlaybackSession } from "../types";
import { queryKeys } from "../../composables/queryKeys";
import { backendStatus, healthyStatus } from "../../test/backendStatus";
import { fakeSocketFactory } from "../../test/fakeSocket";

const TOKEN = "test-token-0123";

let sockets: ReturnType<typeof fakeSocketFactory>;
let queryClient: QueryClient;
let stop: () => void;

function ready(seq: number, port: number) {
  const backend = useBackendStore();
  backend.info = { baseUrl: `http://127.0.0.1:${port}`, token: TOKEN };
  backend.status = healthyStatus(seq, port);
}

/** The id the client gave the subscription whose document mentions `field`. */
function opId(field: string): string {
  const op = sockets.last
    .sentOfType("subscribe")
    .find((m) => (m.payload as { query: string }).query.includes(field));
  if (!op) throw new Error(`no subscription for ${field}`);
  return op.id as string;
}

function invalidatedKeys(spy: { mock: { calls: unknown[][] } }) {
  return spy.mock.calls.map((c) => (c[0] as { queryKey: unknown }).queryKey);
}

beforeEach(async () => {
  setActivePinia(createPinia());
  sockets = fakeSocketFactory();
  queryClient = createQueryClient();
  ready(2, 50266);
  ({ stop } = startRealtime({
    queryClient,
    subscription: { createSocket: sockets.create, pingIntervalMs: 0 },
  }));
  await nextTick();
});

afterEach(() => {
  stop();
  queryClient.clear();
});

describe("realtime", () => {
  it("connects to the backend's WebSocket with the token, and subscribes to the three event streams", () => {
    expect(sockets.last.url).toBe(`ws://127.0.0.1:50266/api/graphql?token=${TOKEN}`);
    sockets.last.open();
    sockets.last.ack();

    const queries = sockets.last
      .sentOfType("subscribe")
      .map((m) => (m.payload as { query: string }).query);
    expect(queries).toHaveLength(3);
    expect(queries.some((q) => q.includes("extensionInstallProgress"))).toBe(true);
    expect(queries.some((q) => q.includes("extensionStoreRefreshed"))).toBe(true);
    expect(queries.some((q) => q.includes("playbackEvents"))).toBe(true);
    expect(useEventsStore().wsState).toBe("connected");
    expect(useEventsStore().connections).toBe(1);
  });

  it("records a repo refresh and invalidates the repo and extension lists", () => {
    sockets.last.open();
    sockets.last.ack();
    const spy = vi.spyOn(queryClient, "invalidateQueries");

    sockets.last.receive({
      id: opId("extensionStoreRefreshed"),
      type: "next",
      payload: {
        data: {
          extensionStoreRefreshed: {
            indexUrl: "https://example.org/index.min.json",
            type: "ANIME",
            ok: true,
            error: null,
            extensionCount: 12,
          },
        },
      },
    });

    const events = useEventsStore();
    expect(events.recent).toHaveLength(1);
    expect(events.recent[0]).toMatchObject({
      kind: "storeRefreshed",
      event: { extensionCount: 12 },
    });
    expect(invalidatedKeys(spy)).toEqual([
      queryKeys.extensionStores(),
      queryKeys.extensions("anime"),
    ]);
  });

  describe("playback events", () => {
    const session: PlaybackSession = {
      id: "s1",
      episodeId: 1001,
      animeId: 12,
      title: "One Piece - Episode 1",
      videoTitle: "1080p",
      state: "RUNNING",
      startedAt: "1",
      endedAt: null,
      exitCode: null,
      positionSeconds: 1300,
      durationSeconds: 1500,
      paused: false,
    };
    const send = (type: string, over: Partial<PlaybackSession> = {}) =>
      sockets.last.receive({
        id: opId("playbackEvents"),
        type: "next",
        payload: {
          data: {
            playbackEvents: {
              type,
              session: { ...session, ...over },
              nextEpisodeId: null,
              reason: null,
              message: null,
            },
          },
        },
      });
    const cached = () => queryClient.getQueryData<Episode[]>(queryKeys.episodes(12))!;

    beforeEach(() => {
      sockets.last.open();
      sockets.last.ack();
      queryClient.setQueryData(queryKeys.episodes(12), makeEpisodes(2));
    });

    it("keeps the live session in the playback store, without refetching on PROGRESS", () => {
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send("STARTED", { positionSeconds: null });
      send("PROGRESS", { positionSeconds: 61.5 });

      expect(usePlaybackStore().session).toMatchObject({ episodeId: 1001, positionSeconds: 61.5 });
      expect(spy).not.toHaveBeenCalled();
      // the cached rows stay as stored
      expect(cached()[0]!.lastSecondSeen).toBe("0");
    });

    it("marks the episode watched in the cached list at once on WATCHED, then refetches it", () => {
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send("WATCHED");

      expect(cached().map((e) => e.isWatched)).toEqual([true, false]);
      expect(invalidatedKeys(spy)).toEqual([queryKeys.episodes(12)]);
    });

    it("writes the last position into the cached list on CLOSED, clears the store and refetches", () => {
      send("PROGRESS");
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send("CLOSED", { state: "EXITED", positionSeconds: 752.0437, durationSeconds: 1500.125 });

      expect(cached()[0]).toMatchObject({ lastSecondSeen: "752044", totalSeconds: "1500125" });
      expect(cached()[1]!.lastSecondSeen).toBe("0");
      expect(usePlaybackStore().session).toBeNull();
      expect(invalidatedKeys(spy)).toEqual([queryKeys.episodes(12)]);
    });

    it("forgets a session it was following after a reconnect (its CLOSED may be lost)", async () => {
      send("PROGRESS");
      const backend = useBackendStore();
      backend.status = backendStatus({ seq: 3, state: "restarting" });
      await nextTick();
      const invalidate = vi.spyOn(queryClient, "invalidateQueries");
      ready(4, 61000);
      await nextTick();
      sockets.last.open();
      sockets.last.ack();

      expect(usePlaybackStore().session).toBeNull();
      expect(invalidatedKeys(invalidate)).toContainEqual(queryKeys.episodes(12));
    });
  });

  it("keeps the latest install step per package, and invalidates only when it ends", () => {
    sockets.last.open();
    sockets.last.ack();
    const spy = vi.spyOn(queryClient, "invalidateQueries");
    const step = (state: string) =>
      sockets.last.receive({
        id: opId("extensionInstallProgress"),
        type: "next",
        payload: {
          data: {
            extensionInstallProgress: {
              pkgName: "eu.kanade.tachiyomi.animeextension.en.example",
              type: "ANIME",
              state,
              errorCode: null,
              message: null,
            },
          },
        },
      });

    step("DOWNLOADING");
    step("CONVERTING");
    expect(spy).not.toHaveBeenCalled();
    step("INSTALLED");

    const events = useEventsStore();
    expect(events.installProgress["eu.kanade.tachiyomi.animeextension.en.example"].state).toBe(
      "INSTALLED",
    );
    expect(events.recent).toHaveLength(3);
    expect(invalidatedKeys(spy)).toEqual([
      queryKeys.extensions("anime"),
      queryKeys.sources("anime"),
    ]);
  });

  it("follows a backend restart: closes while it's down, reconnects to the new port, catches up", async () => {
    sockets.last.open();
    sockets.last.ack();
    const backend = useBackendStore();

    backend.status = backendStatus({ seq: 3, state: "restarting" });
    await nextTick();
    expect(sockets.last.closedWith).not.toBeNull();
    expect(useEventsStore().wsState).toBe("idle");

    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const refetch = vi.spyOn(queryClient, "refetchQueries");
    ready(4, 61000);
    await nextTick();

    expect(sockets.sockets).toHaveLength(2);
    expect(sockets.last.url).toBe(`ws://127.0.0.1:61000/api/graphql?token=${TOKEN}`);
    expect(refetch).toHaveBeenCalledOnce();

    sockets.last.open();
    sockets.last.ack();
    expect(sockets.last.sentOfType("subscribe")).toHaveLength(3);
    // a reconnect may have missed events: the lists are fetched again
    expect(invalidatedKeys(invalidate)).toEqual([
      queryKeys.extensionStores(),
      queryKeys.extensions(),
      queryKeys.sources(),
    ]);
  });

  it("refetches, after a restart, the queries that failed while the backend was away", async () => {
    const fn = vi
      .fn()
      .mockRejectedValueOnce(new ApiError({ kind: "http", code: "INTERNAL", message: "x" }))
      .mockResolvedValue(["ok"]);
    await queryClient.fetchQuery({ queryKey: ["api", "probe"], queryFn: fn }).catch(() => {});
    expect(queryClient.getQueryState(["api", "probe"])?.status).toBe("error");

    const backend = useBackendStore();
    backend.status = backendStatus({ seq: 3, state: "restarting" });
    await nextTick();
    ready(4, 61000);
    await nextTick();
    await vi.waitFor(() => expect(queryClient.getQueryData(["api", "probe"])).toEqual(["ok"]));
  });
});

describe("query defaults", () => {
  it("retries only while the backend is down or restarting", () => {
    const retry = createQueryClient().getDefaultOptions().queries?.retry as (
      n: number,
      e: unknown,
    ) => boolean;
    const err = (code: string) => new ApiError({ kind: "http", code, message: code });

    expect(retry(0, err("NETWORK"))).toBe(true);
    expect(retry(2, err("BACKEND_UNAVAILABLE"))).toBe(true);
    expect(retry(3, err("NETWORK"))).toBe(false);
    expect(retry(0, err("NOT_FOUND"))).toBe(false);
    expect(retry(0, err("SOURCE_FAILED"))).toBe(false);
    expect(retry(0, new Error("bug"))).toBe(false);
  });
});
