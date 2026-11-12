import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";
import type { Router } from "vue-router";

import { createQueryClient } from "../../api/realtime";
import { api, ApiError } from "../../api/client";
import type { Chapter, ChapterPages, ChapterPatch } from "../../api/types";
import { useReaderStore } from "../../stores/reader";
import { makeChapters, makeManga } from "../../test/fixtures";
import { createTestRouter, mountView, setBackendReady } from "../../test/mountView";
import { backendStatus, healthyStatus } from "../../test/backendStatus";
import { useBackendStore } from "../../stores/backend";

const ops = vi.hoisted(() => ({
  fetchManga: vi.fn(),
  fetchChapters: vi.fn(),
  fetchChapterPages: vi.fn(),
  prefetchChapterPages: vi.fn(),
  updateChapter: vi.fn(),
}));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import ReaderView from "../ReaderView.vue";

/*
 * The reader (Phase 5 Stage 2) against mocked operations and page fetches: where it opens, the click zones, keys
 * and the wheel in both directions, the chapter boundaries, the read mark, a failed page's retry, the prefetch and
 * the concurrency limit. jsdom has no layout, so the reader is 900 px wide by a stubbed getBoundingClientRect.
 */
const WIDTH = 900;
let pinia: Pinia;
let queryClient: QueryClient;
let router: Router;
let wrapper: VueWrapper;
let chapters: Chapter[];
let assetCalls: {
  path: string;
  reload: boolean;
  resolve: () => void;
  reject: (e: unknown) => void;
}[];
let autoResolve: boolean;
const restore: (() => void)[] = [];

const routesOf = (c: Chapter) =>
  Array.from(
    { length: c.pageCount },
    (_, i) => `/api/v1/manga/34/chapter/${c.sourceOrder}/page/${i}`,
  );

async function mountReader(chapterId = 5001, query = "") {
  await router.push(`/manga/34/read/${chapterId}${query}`);
  wrapper = mountView(ReaderView, { pinia, queryClient, router }, { id: 34, chapterId });
  await flushPromises();
}

const position = () => wrapper.find('[data-testid="reader"]').attributes("data-position");
const paged = () => wrapper.find('[data-testid="paged-reader"]');
const pageQuery = () => router.currentRoute.value.query.page;
async function clickAt(x: number) {
  await paged().trigger("click", { clientX: x });
  await flushPromises();
}
async function key(k: string, extra: Partial<KeyboardEventInit> = {}) {
  window.dispatchEvent(new KeyboardEvent("keydown", { key: k, bubbles: true, ...extra }));
  await flushPromises();
}

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  router = createTestRouter();
  setBackendReady();
  chapters = makeChapters(3).map((c) => ({ ...c, pageCount: 10 }));
  assetCalls = [];
  autoResolve = true;

  ops.fetchManga.mockResolvedValue(makeManga());
  ops.fetchChapters.mockImplementation(async () => chapters);
  ops.fetchChapterPages.mockImplementation(async (id: number): Promise<ChapterPages> => {
    const chapter = chapters.find((c) => c.id === id)!;
    return { pages: routesOf(chapter), chapter };
  });
  ops.prefetchChapterPages.mockResolvedValue({
    chapterId: 0,
    pageCount: 0,
    queued: [],
    cached: [],
  });
  ops.updateChapter.mockImplementation(async (id: number, patch: ChapterPatch) => ({
    ...chapters.find((c) => c.id === id)!,
    ...patch,
  }));

  const fetchAsset = vi.spyOn(api, "fetchAsset").mockImplementation(
    (path, o) =>
      new Promise<Blob>((resolve, reject) => {
        const call = {
          path,
          reload: !!o?.reload,
          resolve: () => resolve(new Blob([path])),
          reject,
        };
        assetCalls.push(call);
        if (autoResolve) call.resolve();
      }),
  );
  restore.push(() => fetchAsset.mockRestore());
  const created = URL.createObjectURL;
  const revoked = URL.revokeObjectURL;
  URL.createObjectURL = vi.fn(() => `blob:${Math.random()}`);
  URL.revokeObjectURL = vi.fn();
  restore.push(() => {
    URL.createObjectURL = created;
    URL.revokeObjectURL = revoked;
  });
  const rect = HTMLElement.prototype.getBoundingClientRect;
  HTMLElement.prototype.getBoundingClientRect = () =>
    ({ left: 0, top: 0, width: WIDTH, height: 700, right: WIDTH, bottom: 700 }) as DOMRect;
  restore.push(() => (HTMLElement.prototype.getBoundingClientRect = rect));
});

afterEach(() => {
  wrapper?.unmount();
  queryClient.clear();
  restore.splice(0).forEach((r) => r());
  vi.clearAllMocks();
});

describe("ReaderView", () => {
  it("resumes where reading stopped, shows the page, and keeps it in the URL", async () => {
    chapters[0] = { ...chapters[0]!, lastPageRead: 4 };
    await mountReader();

    expect(position()).toBe("4");
    expect(wrapper.find('[data-testid="page-image"]').attributes("alt")).toBe("Page 5");
    expect(wrapper.find('[data-testid="page-count"]').text()).toBe("5 / 10");
    expect(wrapper.find('[data-testid="reader-chapter"]').text()).toBe("Chapter 1");
    expect(pageQuery()).toBe("5");
    expect(document.title).toBe("Chapter 1 · One Piece · Madoyomi");
  });

  it("opens the page in ?page=, and a read chapter at its start", async () => {
    await mountReader(5001, "?page=7");
    expect(position()).toBe("6");
    wrapper.unmount();

    chapters[1] = { ...chapters[1]!, isRead: true, lastPageRead: 9 };
    await mountReader(5002);
    expect(position()).toBe("0");
  });

  it("reads right to left by default: the left side goes forward, the right side back", async () => {
    await mountReader();
    expect(paged().attributes("data-direction")).toBe("rtl");

    await clickAt(50);
    expect(position()).toBe("1");
    await clickAt(850);
    expect(position()).toBe("0");
    // the middle toggles the toolbar
    expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(false);
    await clickAt(450);
    expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(true);
    expect(position()).toBe("0");
  });

  it("flips to left to right from the toolbar, for the session", async () => {
    await mountReader();
    await wrapper.find('[data-action="direction"]').trigger("click");
    expect(useReaderStore().direction).toBe("ltr");

    await clickAt(850);
    expect(position()).toBe("1");
    await clickAt(50);
    expect(position()).toBe("0");
    await key("ArrowRight");
    expect(position()).toBe("1");
  });

  it("turns pages with the keys and the wheel", async () => {
    await mountReader();

    await key("ArrowLeft"); // forward, right to left
    expect(position()).toBe("1");
    await key("ArrowRight");
    expect(position()).toBe("0");
    await key(" ");
    await key("PageDown");
    expect(position()).toBe("2");
    await key("End");
    expect(position()).toBe("9");
    await key("Home");
    expect(position()).toBe("0");

    const surface = wrapper.find('[data-testid="reader"]').element;
    surface.dispatchEvent(new WheelEvent("wheel", { deltaY: 120, cancelable: true }));
    await flushPromises();
    expect(position()).toBe("1");
  });

  it("leaves keys typed into the slider alone", async () => {
    await mountReader();
    const slider = wrapper.find('[data-testid="page-slider"]');
    slider.element.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowLeft", bubbles: true }));
    await flushPromises();
    expect(position()).toBe("0");

    await slider.setValue("8");
    await slider.trigger("input");
    expect(position()).toBe("7");
  });

  it("marks the chapter read on its last page, then offers the next one and opens it", async () => {
    await mountReader(5001, "?page=9");
    expect(ops.updateChapter).not.toHaveBeenCalled();

    await key("ArrowLeft");
    expect(position()).toBe("9");
    expect(ops.updateChapter).toHaveBeenCalledWith(5001, { lastPageRead: 9, isRead: true });

    await key("ArrowLeft");
    expect(wrapper.find('[data-testid="panel-end"]').text()).toContain("Next: Chapter 2");

    await key("ArrowLeft");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5002");
    expect(pageQuery()).toBeUndefined();
  });

  it("says when there is no next chapter, and goes back to the manga", async () => {
    await mountReader(5003, "?page=10");
    await key("ArrowLeft");
    const panel = wrapper.find('[data-testid="panel-end"]');
    expect(panel.text()).toContain("There's no next chapter.");

    await key("ArrowLeft");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5003");

    await panel.find('[data-action="panel-back"]').trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/manga/34");
  });

  it("goes back to the previous chapter's last page through its panel", async () => {
    await mountReader(5002);
    await key("ArrowRight");
    expect(wrapper.find('[data-testid="panel-previous"]').text()).toContain("Previous: Chapter 1");

    await key("ArrowRight");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
    expect(pageQuery()).toBe("last");
  });

  it("follows a chapter change of the route, and opens the new chapter where it stopped", async () => {
    chapters[1] = { ...chapters[1]!, lastPageRead: 3 };
    await mountReader();
    // what openChapter does: the route moves on (without the last chapter's ?page=), and the props follow
    await router.replace("/manga/34/read/5002");
    await wrapper.setProps({ chapterId: 5002 });
    await flushPromises();

    expect(position()).toBe("3");
    expect(wrapper.find('[data-testid="reader-chapter"]').text()).toBe("Chapter 2");
  });

  it("shows a failed page's error with the source's status, and fetches it again on a click", async () => {
    autoResolve = false;
    await mountReader();
    assetCalls[0]!.reject(
      new ApiError({
        kind: "http",
        code: "SOURCE_FAILED",
        message: "The source answered HTTP 403 for this page",
        details: { httpCode: 403, retried: true },
        status: 424,
      }),
    );
    await flushPromises();

    const failed = wrapper.find('[data-testid="page-failed"]');
    expect(failed.text()).toContain("Couldn't load page 1");
    expect(failed.text()).toContain("SOURCE_FAILED");
    expect(failed.text()).toContain("HTTP 403");

    await failed.trigger("click");
    await flushPromises();
    const again = assetCalls.filter((c) => c.path.endsWith("/page/0"));
    expect(again).toHaveLength(2);
    expect(again[1]!.reload).toBe(true);
    // the click retried; it didn't turn the page
    expect(position()).toBe("0");

    again[1]!.resolve();
    await flushPromises();
    expect(wrapper.find('[data-testid="page-image"]').exists()).toBe(true);
  });

  it("fetches the pages that failed during a backend restart again once it is back", async () => {
    autoResolve = false;
    await mountReader();
    const away = new ApiError({
      kind: "unavailable",
      code: "BACKEND_UNAVAILABLE",
      message: "The backend isn't ready",
    });
    assetCalls[0]!.reject(away);
    await flushPromises();
    expect(wrapper.find('[data-testid="page-failed"]').text()).toContain("BACKEND_UNAVAILABLE");

    const backend = useBackendStore();
    backend.status = backendStatus({ seq: 3, state: "restarting" });
    await flushPromises();
    backend.status = healthyStatus(4);
    await flushPromises();

    const again = assetCalls.filter((c) => c.path.endsWith("/page/0"));
    expect(again).toHaveLength(2);
    again[1]!.resolve();
    await flushPromises();
    expect(wrapper.find('[data-testid="page-image"]').exists()).toBe(true);
  });

  it("fetches at most 4 pages at a time", async () => {
    autoResolve = false;
    await mountReader();

    expect(assetCalls.map((c) => c.path.split("/").pop())).toEqual(["0", "1", "2", "3"]);
    expect(wrapper.find('[data-testid="page-loading"]').text()).toBe("Loading page 1…");
  });

  it("asks the backend for the next block, and near the end for the next chapter's first pages", async () => {
    chapters[1] = { ...chapters[1]!, lastPageRead: 2 };
    await mountReader();

    expect(ops.prefetchChapterPages).toHaveBeenCalledWith(5001, 5, 5);
    expect(ops.prefetchChapterPages).toHaveBeenCalledTimes(1);

    await key("End");
    expect(ops.prefetchChapterPages).toHaveBeenCalledWith(5002, 2, 5);
    await key("ArrowRight");
    expect(ops.prefetchChapterPages).toHaveBeenCalledTimes(2);
  });

  it("saves the page when the reader closes", async () => {
    await mountReader();
    await key("ArrowLeft");
    await key("ArrowLeft");
    expect(ops.updateChapter).not.toHaveBeenCalled();

    wrapper.unmount();
    expect(ops.updateChapter).toHaveBeenCalledWith(5001, { lastPageRead: 2 });
    wrapper = undefined as unknown as VueWrapper;
  });

  it("says when the chapter can't be opened, and goes back with Esc", async () => {
    ops.fetchChapterPages.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "SOURCE_FAILED", message: "HTTP 503" }),
    );
    await mountReader();

    expect(wrapper.text()).toContain("Couldn't open Chapter 1");
    await key("Escape");
    expect(router.currentRoute.value.path).toBe("/manga/34");
  });
});
