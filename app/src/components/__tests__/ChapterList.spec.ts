import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";
import type { Router } from "vue-router";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Chapter, ChapterPatch } from "../../api/types";
import { queryKeys } from "../../composables/queryKeys";
import type { DownloadQueue } from "../../api/types";
import { makeChapters, makeDownloadItem, makeDownloadQueue } from "../../test/fixtures";
import { createTestRouter, mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  updateChapters: vi.fn(),
  fetchDownloadQueue: vi.fn(),
  enqueueDownloads: vi.fn(),
  changeDownloads: vi.fn(),
  deleteDownloads: vi.fn(),
}));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import ChapterList from "../ChapterList.vue";

/*
 * The chapter list's reading actions (Phase 5 Stage 2): rows open the reader, Read / Continue, a row's page
 * progress, the read filter, and the bulk read marks through `updateChapters`. jsdom has no layout: the list is
 * under VirtualList's threshold, so every row is in the DOM.
 */
let pinia: Pinia;
let queryClient: QueryClient;
let router: Router;
let wrapper: VueWrapper;
let chapters: Chapter[];
let queue: DownloadQueue;

async function mountList() {
  queryClient.setQueryData(queryKeys.chapters(34), chapters);
  wrapper = mountView(ChapterList, { pinia, queryClient, router }, { mangaId: 34, chapters });
  await router.isReady();
  await flushPromises();
}

const row = (name: string) =>
  wrapper.findAll('[data-testid="item-row"]').find((r) => r.find(".name").text() === name)!;
const names = () => wrapper.findAll('[data-testid="item-row"] .name').map((n) => n.text());
const button = (action: string) => wrapper.find(`[data-action="${action}"]`);

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  router = createTestRouter();
  setBackendReady();
  chapters = makeChapters(4).map((c) => ({ ...c, pageCount: 92 }));
  ops.updateChapters.mockImplementation(async (ids: number[], patch: ChapterPatch) =>
    chapters.filter((c) => ids.includes(c.id)).map((c) => ({ ...c, ...patch })),
  );
  queue = makeDownloadQueue();
  ops.fetchDownloadQueue.mockImplementation(async () => queue);
  ops.enqueueDownloads.mockImplementation(async (_kind: string, ids: number[]) => {
    queue = makeDownloadQueue(ids.map((itemId, i) => makeDownloadItem({ id: 100 + i, itemId })));
    return { queued: ids.length, queue };
  });
  ops.changeDownloads.mockImplementation(async (_action: string, t: { ids?: number[] }) => {
    queue = makeDownloadQueue(queue.items.filter((i) => !t.ids?.includes(i.id)));
    return { changed: 1, queue };
  });
  ops.deleteDownloads.mockImplementation(async (_kind: string, ids: number[]) => ({
    deleted: ids.length,
    queue: makeDownloadQueue(),
  }));
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
});

describe("ChapterList", () => {
  it("opens the reader from a row's button or a click on the row", async () => {
    await mountList();

    await row("Chapter 2").find('[data-action="read"]').trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5002");

    await row("Chapter 3").find(".name").trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5003");

    // the checkbox selects, it doesn't open
    await row("Chapter 1").find('[data-testid="item-select"]').trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5003");
  });

  it("offers Continue with the page for a chapter read partway", async () => {
    chapters[1] = { ...chapters[1]!, lastPageRead: 12 };
    chapters[2] = { ...chapters[2]!, isRead: true, lastPageRead: 91 };
    await mountList();

    expect(row("Chapter 2").find('[data-action="read"]').text()).toBe("Continue");
    expect(row("Chapter 2").find('[data-testid="item-progress-text"]').text()).toBe(
      "Page 13 of 92",
    );
    expect(
      row("Chapter 2").find('[data-testid="item-progress-bar"]').attributes("aria-valuenow"),
    ).toBe("14.1");
    // read: Read again from the start, no progress
    expect(row("Chapter 3").find('[data-action="read"]').text()).toBe("Read");
    expect(row("Chapter 3").find('[data-testid="item-progress-text"]').exists()).toBe(false);
    expect(row("Chapter 3").text()).toContain("Read");
    expect(row("Chapter 1").find('[data-action="read"]').text()).toBe("Read");
  });

  it("filters read and unread chapters", async () => {
    chapters[0] = { ...chapters[0]!, isRead: true };
    await mountList();

    await wrapper.find('[data-testid="item-progress"]').setValue("notDone");
    expect(names()).toEqual(["Chapter 4", "Chapter 3", "Chapter 2"]);
    await wrapper.find('[data-testid="item-progress"]').setValue("done");
    expect(names()).toEqual(["Chapter 1"]);
  });

  it("marks chapters read, and unread back to their first page", async () => {
    chapters[3] = { ...chapters[3]!, lastPageRead: 40 };
    await mountList();

    await row("Chapter 4").find('[data-testid="item-select"]').trigger("click");
    await row("Chapter 3").find('[data-testid="item-select"]').trigger("click");
    await button("mark-read").trigger("click");
    await flushPromises();

    expect(ops.updateChapters).toHaveBeenCalledWith([5004, 5003], { isRead: true });
    expect(wrapper.find('[data-testid="bulk-bar"]').exists()).toBe(false);
    // the details page passes the cached list, which has the marks (MangaDetailsView draws them from it)
    const cached = queryClient.getQueryData<Chapter[]>(queryKeys.chapters(34))!;
    expect(cached.find((c) => c.id === 5004)!.isRead).toBe(true);

    await row("Chapter 4").find('[data-testid="item-select"]').trigger("click");
    await button("mark-unread").trigger("click");
    await flushPromises();
    expect(ops.updateChapters).toHaveBeenLastCalledWith([5004], { isRead: false, lastPageRead: 0 });
  });

  it("marks every earlier unread chapter in the source's order", async () => {
    chapters[1] = { ...chapters[1]!, isRead: true };
    await mountList();

    await row("Chapter 4").find('[data-testid="item-select"]').trigger("click");
    await button("mark-previous").trigger("click");
    await flushPromises();

    expect(ops.updateChapters).toHaveBeenCalledWith([5001, 5003], { isRead: true });
  });

  it("keeps the selection and shows the error when a mark fails", async () => {
    ops.updateChapters.mockRejectedValue(
      new ApiError({ kind: "network", code: "NETWORK", message: "The backend didn't answer" }),
    );
    await mountList();

    await row("Chapter 1").find('[data-testid="item-select"]').trigger("click");
    await button("mark-read").trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="mark-error"]').text()).toContain(
      "Couldn't change the chapters",
    );
    expect(wrapper.find('[data-testid="selected-count"]').text()).toBe("1 selected");
  });

  it("downloads a chapter from its row, shows how far it got, and cancels it", async () => {
    await mountList();

    await row("Chapter 2").find('[data-action="download"]').trigger("click");
    await flushPromises();
    expect(ops.enqueueDownloads).toHaveBeenCalledWith("manga", [5002]);
    expect(row("Chapter 2").find('[data-testid="download-state"]').text()).toBe("Queued");
    expect(row("Chapter 2").find('[data-action="download"]').exists()).toBe(false);

    // the subscription's queue: downloading, page 5 of 20
    queryClient.setQueryData(
      queryKeys.downloadQueue(),
      makeDownloadQueue([
        makeDownloadItem({
          id: 100,
          itemId: 5002,
          state: "DOWNLOADING",
          progressDone: 5,
          progressTotal: 20,
        }),
      ]),
    );
    await flushPromises();
    expect(row("Chapter 2").find('[data-testid="download-state"]').text()).toBe(
      "Downloading 5/20 · 25%",
    );

    await row("Chapter 2").find('[data-action="cancel-download"]').trigger("click");
    await flushPromises();
    expect(ops.changeDownloads).toHaveBeenCalledWith("cancel", { kind: undefined, ids: [100] });
  });

  it("offers Retry for a failed download, and marks downloaded chapters", async () => {
    chapters[0] = { ...chapters[0]!, isDownloaded: true };
    queue = makeDownloadQueue([
      makeDownloadItem({ id: 9, itemId: 5003, state: "FAILED", tries: 3, error: "HTTP 403" }),
    ]);
    await mountList();

    expect(row("Chapter 1").find('[data-testid="downloaded"]').text()).toBe("Downloaded");
    expect(row("Chapter 1").find('[data-action="download"]').exists()).toBe(false);
    expect(row("Chapter 3").find('[data-testid="download-state"]').text()).toBe("Failed");
    expect(row("Chapter 3").find('[data-testid="download-state"]').attributes("title")).toBe(
      "HTTP 403",
    );
    await row("Chapter 3").find('[data-action="retry-download"]').trigger("click");
    await flushPromises();
    expect(ops.changeDownloads).toHaveBeenCalledWith("retry", { kind: undefined, ids: [9] });
  });

  it("downloads and deletes the selected chapters", async () => {
    chapters[0] = { ...chapters[0]!, isDownloaded: true };
    await mountList();

    for (const name of ["Chapter 1", "Chapter 2", "Chapter 3"]) {
      await row(name).find('[data-testid="item-select"]').trigger("click");
    }
    await button("download-selected").trigger("click");
    await flushPromises();
    expect(ops.enqueueDownloads).toHaveBeenCalledWith("manga", [5002, 5003]);
    expect(wrapper.find('[data-testid="bulk-bar"]').exists()).toBe(false);

    await row("Chapter 1").find('[data-testid="item-select"]').trigger("click");
    await row("Chapter 2").find('[data-testid="item-select"]').trigger("click");
    await row("Chapter 4").find('[data-testid="item-select"]').trigger("click");
    expect(button("delete-downloads").attributes("disabled")).toBeUndefined();
    await button("delete-downloads").trigger("click");
    await flushPromises();
    expect(ops.deleteDownloads).toHaveBeenCalledWith("manga", [5001, 5002]);
  });

  it("downloads the next unread chapters in reading order", async () => {
    chapters[0] = { ...chapters[0]!, isRead: true };
    chapters[1] = { ...chapters[1]!, isDownloaded: true };
    await mountList();

    const select = wrapper.find('[data-testid="download-next"]');
    expect(select.findAll("option").map((o) => o.text())).toEqual([
      "Download…",
      "Next unread chapter",
      "Next 5 unread",
      "Next 10 unread",
      "Next 25 unread",
      "All unread",
    ]);
    await select.setValue("5");
    await flushPromises();
    expect(ops.enqueueDownloads).toHaveBeenCalledWith("manga", [5003, 5004]);
    expect((select.element as HTMLSelectElement).value).toBe("");
  });
});
