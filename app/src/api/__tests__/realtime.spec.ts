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
import { makeLibraryCheck } from "../../test/fixtures";
import type { DownloadQueue, LibraryCheck } from "../types";

const toast = vi.hoisted(() => ({ showToast: vi.fn(), windowFocused: vi.fn() }));
vi.mock("../../utils/toast", () => toast);
const updatesOps = vi.hoisted(() => ({ fetchLibraryUpdateSettings: vi.fn() }));
vi.mock("../operations/updates", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...updatesOps,
}));

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
  it("connects to the backend's WebSocket with the token, and subscribes to the five event streams", () => {
    expect(sockets.last.url).toBe(`ws://127.0.0.1:50266/api/graphql?token=${TOKEN}`);
    sockets.last.open();
    sockets.last.ack();

    const queries = sockets.last
      .sentOfType("subscribe")
      .map((m) => (m.payload as { query: string }).query);
    expect(queries).toHaveLength(5);
    expect(queries.some((q) => q.includes("libraryUpdates"))).toBe(true);
    expect(queries.some((q) => q.includes("downloadsChanged"))).toBe(true);
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

  describe("download queue events (Phase 6 Stage 4)", () => {
    const item = (id: number, itemId: number, entryId: number, state = "DOWNLOADING") => ({
      id,
      kind: "MANGA",
      state,
      entryId,
      itemId,
      entryTitle: "Kingdom",
      itemName: `Chapter ${itemId}`,
      progressDone: 1,
      progressTotal: 20,
      bytes: "1000",
      tries: 0,
      error: null,
      addedAt: "1700000000000",
      finishedAt: "0",
    });
    const send = (items: unknown[], mangaDone = 0, lastFinishedAt = "0") =>
      sockets.last.receive({
        id: opId("downloadsChanged"),
        type: "next",
        payload: { data: { downloadsChanged: { items, mangaDone, animeDone: 0, lastFinishedAt } } },
      });

    beforeEach(() => {
      sockets.last.open();
      sockets.last.ack();
    });

    it("keeps the cached queue current, and refetches the entries whose downloads finished or went", () => {
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send([item(1, 900, 51), item(2, 901, 51, "QUEUED"), item(3, 700, 7, "QUEUED")]);
      const queue = queryClient.getQueryData<DownloadQueue>(queryKeys.downloadQueue());
      expect(queue?.items.map((i) => [i.id, i.kind, i.bytes])).toEqual([
        [1, "manga", 1000],
        [2, "manga", 1000],
        [3, "manga", 1000],
      ]);
      expect(spy).not.toHaveBeenCalled();

      // a page more: nothing to refetch
      send([item(1, 900, 51), item(2, 901, 51, "QUEUED"), item(3, 700, 7, "QUEUED")]);
      expect(spy).not.toHaveBeenCalled();

      // chapter 900 done, chapter 700 cancelled
      send([item(2, 901, 51)], 1, "1700000009000");
      expect(invalidatedKeys(spy)).toEqual([
        queryKeys.chapters(51),
        queryKeys.chapters(7),
        queryKeys.library("manga"),
        queryKeys.completedDownloads("manga"),
      ]);
    });
  });

  describe("library update events (Phase 6 Stage 3)", () => {
    const send = (check: LibraryCheck) =>
      sockets.last.receive({
        id: opId("libraryUpdates"),
        type: "next",
        payload: { data: { libraryUpdates: check } },
      });
    const found = (kind: "ANIME" | "MANGA", id: number, newCount: number) => ({
      entry: { kind, id, title: `${kind} ${id}` },
      newCount,
    });

    beforeEach(() => {
      sockets.last.open();
      sockets.last.ack();
      toast.windowFocused.mockReturnValue(false);
      toast.showToast.mockResolvedValue(true);
      updatesOps.fetchLibraryUpdateSettings.mockResolvedValue({ notify: true });
    });

    it("keeps the cached check current, and refetches a kind's lists when its entries get new items", () => {
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send(makeLibraryCheck({ running: true, startedAt: "1", total: 3 }));
      expect(queryClient.getQueryData<LibraryCheck>(queryKeys.libraryCheck())?.total).toBe(3);
      expect(spy).not.toHaveBeenCalled();

      send(
        makeLibraryCheck({
          running: true,
          startedAt: "1",
          total: 3,
          done: 1,
          found: [found("ANIME", 4, 2)],
          newEpisodes: 2,
        }),
      );
      expect(invalidatedKeys(spy)).toEqual([
        queryKeys.library("anime"),
        queryKeys.updates("anime"),
        queryKeys.updateMarks("anime"),
        queryKeys.episodes(4),
      ]);

      // the same finds again: nothing more
      spy.mockClear();
      send(
        makeLibraryCheck({
          running: true,
          startedAt: "1",
          total: 3,
          done: 2,
          found: [found("ANIME", 4, 2)],
          newEpisodes: 2,
        }),
      );
      expect(spy).not.toHaveBeenCalled();
    });

    it("after a check that found something, refetches both kinds and shows the toast when it is on and the window isn't focused", async () => {
      queryClient.setQueryData(
        queryKeys.libraryCheck(),
        makeLibraryCheck({ running: true, startedAt: "1", total: 2 }),
      );
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      const done = makeLibraryCheck({
        startedAt: "1",
        finishedAt: "2",
        total: 2,
        done: 2,
        found: [found("MANGA", 7, 3)],
        newChapters: 3,
      });
      send(done);
      await vi.waitFor(() => expect(toast.showToast).toHaveBeenCalledOnce());
      expect(toast.showToast).toHaveBeenCalledWith({ title: "3 new chapters", body: "MANGA 7" });
      expect(invalidatedKeys(spy)).toEqual(
        expect.arrayContaining([
          queryKeys.library("anime"),
          queryKeys.updates("manga"),
          queryKeys.chapters(7),
        ]),
      );
    });

    it("shows no toast when it is off, the window is focused, the check found nothing or was stopped", async () => {
      const run = async (check: LibraryCheck) => {
        queryClient.setQueryData(
          queryKeys.libraryCheck(),
          makeLibraryCheck({ running: true, startedAt: "1" }),
        );
        send(check);
        await flushMicrotasks();
      };
      const finds = {
        startedAt: "1",
        finishedAt: "2",
        found: [found("ANIME", 1, 1)],
        newEpisodes: 1,
      };

      updatesOps.fetchLibraryUpdateSettings.mockResolvedValue({ notify: false });
      await run(makeLibraryCheck(finds));
      updatesOps.fetchLibraryUpdateSettings.mockResolvedValue({ notify: true });
      queryClient.removeQueries({ queryKey: queryKeys.libraryUpdateSettings() });
      toast.windowFocused.mockReturnValue(true);
      await run(makeLibraryCheck(finds));
      toast.windowFocused.mockReturnValue(false);
      await run(makeLibraryCheck({ startedAt: "1", finishedAt: "2" }));
      await run(makeLibraryCheck({ ...finds, cancelled: true }));
      expect(toast.showToast).not.toHaveBeenCalled();
    });
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
              httpStatus: null,
              retried: false,
              failedVideo: null,
              nextVideo: null,
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

    const progressKeys = [
      queryKeys.library("anime"),
      queryKeys.history("anime"),
      queryKeys.updates("anime"),
    ];

    it("keeps the live session in the playback store, without refetching on PROGRESS", () => {
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send("STARTED", { positionSeconds: null });
      // a session writes the watch history (Phase 6 Stage 2): the library's counts and History follow
      expect(invalidatedKeys(spy)).toEqual(progressKeys);
      spy.mockClear();
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
      expect(invalidatedKeys(spy)).toEqual([...progressKeys, queryKeys.episodes(12)]);
    });

    it("writes the last position into the cached list on CLOSED, clears the store and refetches", () => {
      send("PROGRESS");
      const spy = vi.spyOn(queryClient, "invalidateQueries");
      send("CLOSED", { state: "EXITED", positionSeconds: 752.0437, durationSeconds: 1500.125 });

      expect(cached()[0]).toMatchObject({ lastSecondSeen: "752044", totalSeconds: "1500125" });
      expect(cached()[1]!.lastSecondSeen).toBe("0");
      expect(usePlaybackStore().session).toBeNull();
      expect(invalidatedKeys(spy)).toEqual([...progressKeys, queryKeys.episodes(12)]);
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
              httpStatus: null,
              retried: false,
              failedVideo: null,
              nextVideo: null,
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
    expect(sockets.last.sentOfType("subscribe")).toHaveLength(5);
    // a reconnect may have missed events: the lists are fetched again
    expect(invalidatedKeys(invalidate)).toEqual([
      queryKeys.extensionStores(),
      queryKeys.extensions(),
      queryKeys.sources(),
      queryKeys.libraryCheck(),
      queryKeys.updateMarks(),
      queryKeys.downloads(),
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

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) await Promise.resolve();
  await new Promise((r) => setTimeout(r, 0));
}
