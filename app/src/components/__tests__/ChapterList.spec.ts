import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";
import type { Router } from "vue-router";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Chapter, ChapterPatch } from "../../api/types";
import { queryKeys } from "../../composables/queryKeys";
import { makeChapters } from "../../test/fixtures";
import { createTestRouter, mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({ updateChapters: vi.fn() }));
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
});
