import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { DownloadItem, DownloadQueue } from "../../api/types";
import { queryKeys } from "../../composables/queryKeys";
import { useMediaKindStore } from "../../stores/mediaKind";
import { makeDownloadItem, makeDownloadQueue } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchDownloadQueue: vi.fn(),
  fetchCompletedDownloads: vi.fn(),
  changeDownloads: vi.fn(),
  reorderDownload: vi.fn(),
  deleteDownloads: vi.fn(),
  clearCompletedDownloads: vi.fn(),
  playDownloadedEpisode: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import DownloadsView from "../DownloadsView.vue";

/*
 * Downloads (Phase 6 Stage 4) against mocked calls: the kind's queue with each row's state and actions, the actions
 * for the whole kind, moving rows, the completed list with Read and Delete. Stage 5: the anime view, its rows in
 * bytes, time or segments, and Play for a downloaded episode.
 */
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let queue: DownloadQueue;
let completed: DownloadItem[];

async function mountDownloads() {
  wrapper = mountView(DownloadsView, { pinia, queryClient });
  await flushPromises();
}

const rows = () =>
  wrapper
    .findAll('[data-testid="download-row"]')
    .map((r) => [r.find(".item").text(), r.find('[data-testid="download-row-state"]').text()]);
const button = (action: string) => wrapper.find(`[data-action="${action}"]`);

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  queue = makeDownloadQueue(
    [
      makeDownloadItem({
        id: 1,
        itemId: 5001,
        itemName: "Chapter 1",
        state: "DOWNLOADING",
        progressDone: 5,
        progressTotal: 20,
        bytes: 2_097_152,
      }),
      makeDownloadItem({ id: 2, itemId: 5002, itemName: "Chapter 2" }),
      makeDownloadItem({ id: 3, itemId: 5003, itemName: "Chapter 3", state: "PAUSED" }),
      makeDownloadItem({
        id: 4,
        itemId: 5004,
        itemName: "Chapter 4",
        state: "FAILED",
        tries: 3,
        error: "The source answered HTTP 403 for this page",
      }),
      makeDownloadItem({ id: 9, kind: "anime", entryId: 2, itemId: 77, itemName: "Episode 1" }),
    ],
    { mangaDone: 1 },
  );
  completed = [
    makeDownloadItem({
      id: 8,
      itemId: 4990,
      itemName: "Chapter 0",
      state: "DONE",
      bytes: 3_145_728,
      finishedAt: 1_700_000_000_000,
    }),
  ];
  ops.fetchDownloadQueue.mockImplementation(async () => queue);
  ops.fetchCompletedDownloads.mockImplementation(async () => ({
    items: completed,
    hasMore: false,
  }));
  ops.changeDownloads.mockImplementation(async () => ({ changed: 1, queue }));
  ops.reorderDownload.mockImplementation(async () => queue);
  ops.deleteDownloads.mockImplementation(async () => ({ deleted: 1, queue }));
  ops.clearCompletedDownloads.mockResolvedValue(1);
  useMediaKindStore().set("manga");
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
});

describe("DownloadsView", () => {
  it("lists the kind's queue with each row's state, size and error", async () => {
    await mountDownloads();

    expect(rows()).toEqual([
      ["Chapter 1", "Downloading 5/20"],
      ["Chapter 2", "Queued"],
      ["Chapter 3", "Paused"],
      ["Chapter 4", "Failed"],
    ]);
    const first = wrapper.findAll('[data-testid="download-row"]')[0]!;
    expect(first.text()).toContain("2.0 MB");
    expect(first.find('[role="progressbar"]').attributes("aria-valuenow")).toBe("25");
    expect(wrapper.find('[data-testid="download-row-error"]').text()).toBe(
      "The source answered HTTP 403 for this page",
    );
    expect(wrapper.text()).toContain(
      "1 downloading · 1 queued · 1 paused · 1 failed · 1 downloaded",
    );
  });

  it("pauses, resumes, retries, moves and cancels rows", async () => {
    await mountDownloads();
    const row = (i: number) => wrapper.findAll('[data-testid="download-row"]')[i]!;

    await row(1).find('[data-action="pause"]').trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("pause", { kind: undefined, ids: [2] });
    await row(2).find('[data-action="resume"]').trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("resume", { kind: undefined, ids: [3] });
    await row(3).find('[data-action="retry"]').trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("retry", { kind: undefined, ids: [4] });
    await row(0).find('[data-action="cancel"]').trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("cancel", { kind: undefined, ids: [1] });

    expect(row(0).find('[data-action="move-up"]').exists()).toBe(false);
    expect(row(3).find('[data-action="move-down"]').exists()).toBe(false);
    await row(2).find('[data-action="move-up"]').trigger("click");
    expect(ops.reorderDownload).toHaveBeenLastCalledWith(3, 1);
    await row(2).find('[data-action="move-top"]').trigger("click");
    expect(ops.reorderDownload).toHaveBeenLastCalledWith(3, 0);
    await row(1).find('[data-action="move-down"]').trigger("click");
    expect(ops.reorderDownload).toHaveBeenLastCalledWith(2, 2);
    await flushPromises();
  });

  it("acts on the whole kind, Cancel all after a second click", async () => {
    await mountDownloads();

    await button("pause-all").trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("pause", {
      kind: "manga",
      ids: undefined,
    });
    await flushPromises();
    await button("resume-all").trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("resume", {
      kind: "manga",
      ids: undefined,
    });
    await flushPromises();
    await button("retry-all").trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("retry", {
      kind: "manga",
      ids: undefined,
    });
    await flushPromises();
    ops.changeDownloads.mockClear();

    await button("cancel-all").trigger("click");
    expect(button("cancel-all").text()).toBe("Cancel 4 downloads?");
    expect(ops.changeDownloads).not.toHaveBeenCalled();
    await button("cancel-all").trigger("click");
    expect(ops.changeDownloads).toHaveBeenLastCalledWith("cancel", {
      kind: "manga",
      ids: undefined,
    });
    await flushPromises();
  });

  it("lists the completed downloads with Read and Delete, and clears the list", async () => {
    await mountDownloads();

    const done = wrapper.find('[data-testid="completed-row"]');
    expect(done.text()).toContain("Chapter 0");
    expect(done.text()).toContain("3.0 MB");
    expect(done.find('[data-action="read-download"]').attributes("href")).toBe(
      "/manga/34/read/4990",
    );
    await done.find('[data-action="delete-download"]').trigger("click");
    await flushPromises();
    expect(ops.deleteDownloads).toHaveBeenCalledWith("manga", [4990]);

    ops.fetchCompletedDownloads.mockResolvedValue({ items: [], hasMore: false });
    await button("clear-completed").trigger("click");
    await flushPromises();
    expect(ops.clearCompletedDownloads).toHaveBeenCalledWith("manga");
    // An empty list doesn't claim that nothing is downloaded: Clear list keeps the files.
    expect(wrapper.find('[data-testid="completed-row"]').exists()).toBe(false);
    const empty = wrapper.find('[data-testid="completed-empty"]').text();
    expect(empty).toContain("keeps the files");
    expect(empty).not.toContain("Nothing downloaded");
  });

  it("follows the anime/manga switch, and the queue as the subscription changes it", async () => {
    await mountDownloads();
    useMediaKindStore().set("anime");
    await flushPromises();
    expect(rows()).toEqual([["Episode 1", "Queued"]]);
    expect(wrapper.find('[data-testid="anime-note"]').exists()).toBe(false);
    expect(ops.fetchCompletedDownloads).toHaveBeenLastCalledWith("anime", 0, 50, expect.anything());

    useMediaKindStore().set("manga");
    queryClient.setQueryData(queryKeys.downloadQueue(), makeDownloadQueue());
    await flushPromises();
    expect(rows()).toEqual([]);
    expect(wrapper.find('[data-testid="queue-empty"]').exists()).toBe(true);
  });

  it("shows the anime queue in bytes, time or segments, and plays a downloaded episode", async () => {
    queue = makeDownloadQueue(
      [
        makeDownloadItem({
          id: 21,
          kind: "anime",
          itemId: 81,
          itemName: "Episode 1",
          state: "DOWNLOADING",
          progressUnit: "BYTES",
          progressDone: 250,
          progressTotal: 1000,
          bytes: 52_428_800,
          totalBytes: 209_715_200,
        }),
        makeDownloadItem({
          id: 22,
          kind: "anime",
          itemId: 82,
          itemName: "Episode 2",
          state: "DOWNLOADING",
          progressUnit: "SECONDS",
          progressDone: 600,
          progressTotal: 1440,
          bytes: 10_485_760,
        }),
      ],
      { animeDone: 1 },
    );
    completed = [
      makeDownloadItem({
        id: 23,
        kind: "anime",
        entryId: 2,
        itemId: 80,
        itemName: "Episode 0",
        state: "DONE",
        bytes: 3_145_728,
      }),
    ];
    ops.playDownloadedEpisode.mockResolvedValue({ id: "s1" });
    useMediaKindStore().set("anime");
    await mountDownloads();

    expect(rows()).toEqual([
      ["Episode 1", "Downloading · 25%"],
      ["Episode 2", "Downloading 10:00/24:00"],
    ]);
    const [first, second] = wrapper.findAll('[data-testid="download-row-size"]');
    expect(first!.text()).toBe("50.0 MB of 200 MB");
    expect(second!.text()).toBe("10.0 MB");
    expect(
      wrapper.findAll('[role="progressbar"]').map((b) => b.attributes("aria-valuenow")),
    ).toEqual(["25", "42"]);

    const done = wrapper.find('[data-testid="completed-row"]');
    expect(done.find('[data-action="read-download"]').exists()).toBe(false);
    await done.find('[data-action="play-download"]').trigger("click");
    await flushPromises();
    expect(ops.playDownloadedEpisode).toHaveBeenCalledWith(80, undefined, true);
  });
});
