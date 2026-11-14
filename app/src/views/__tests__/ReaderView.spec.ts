import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";
import type { Router } from "vue-router";

import { createQueryClient } from "../../api/realtime";
import { api, ApiError } from "../../api/client";
import type { Chapter, ChapterPages, ChapterPatch } from "../../api/types";
import { useReaderStore } from "../../stores/reader";
import { CLICK_DELAY_MS } from "../../utils/zoom";
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
  fetchMangaReaderMeta: vi.fn(),
  setMangaMeta: vi.fn(),
  deleteMangaMeta: vi.fn(),
}));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

// jsdom can't decode images: the pages' natural sizes come from this map (by page index), so a test can make a page
// wide (a spread). Without an entry a page loads unmeasured, as in jsdom.
const loaderSizes = vi.hoisted(() => new Map<number, { width: number; height: number }>());
vi.mock("../../composables/usePageLoader", async (importOriginal) => {
  const m = await importOriginal<typeof import("../../composables/usePageLoader")>();
  return {
    ...m,
    usePageLoader: (o: Parameters<typeof m.usePageLoader>[0]) =>
      m.usePageLoader({
        decode: async (url: string) => {
          const k = /\/page\/(\d+)#/.exec(url);
          return k ? (loaderSizes.get(Number(k[1])) ?? null) : null;
        },
        ...o,
      }),
  };
});

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
let blobPaths: WeakMap<Blob, string>;

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
/** What the app's router-view does after openChapter: the props follow the route. */
async function follow() {
  await wrapper.setProps({ chapterId: Number(router.currentRoute.value.params.chapterId) });
}
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
  blobPaths = new WeakMap();
  loaderSizes.clear();

  ops.fetchManga.mockResolvedValue(makeManga());
  ops.fetchMangaReaderMeta.mockResolvedValue({ mangaId: 34, meta: [], sourceName: "MangaPill" });
  ops.setMangaMeta.mockResolvedValue(undefined);
  ops.deleteMangaMeta.mockResolvedValue(undefined);
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
          resolve: () => {
            const blob = new Blob([path]);
            blobPaths.set(blob, path);
            resolve(blob);
          },
          reject,
        };
        assetCalls.push(call);
        if (autoResolve) call.resolve();
      }),
  );
  restore.push(() => fetchAsset.mockRestore());
  const created = URL.createObjectURL;
  const revoked = URL.revokeObjectURL;
  URL.createObjectURL = vi.fn(
    (b: Blob) => `blob:${blobPaths.get(b) ?? ""}#${Math.random()}`,
  ) as typeof URL.createObjectURL;
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
    // the middle toggles the toolbar, once it is sure no second click (a double-click zooms) follows
    expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(false);
    vi.useFakeTimers();
    try {
      await clickAt(450);
      expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(false);
      vi.advanceTimersByTime(CLICK_DELAY_MS);
      await flushPromises();
    } finally {
      vi.useRealTimers();
    }
    expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(true);
    expect(position()).toBe("0");
  });

  it("flips to left to right from the toolbar, as this manga's own direction", async () => {
    await mountReader();
    await wrapper.find('[data-action="direction"]').trigger("click");
    await flushPromises();
    expect(ops.setMangaMeta).toHaveBeenCalledWith(34, "madoyomi.reader.direction", "ltr");
    expect(useReaderStore().prefs.direction).toBe("rtl");

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
    expect(wrapper.find('[data-testid="panel-end"]').text()).toContain("Next chapter: Chapter 2");

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
    expect(wrapper.find('[data-testid="panel-previous"]').text()).toContain(
      "Previous chapter: Chapter 1",
    );

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

describe("ReaderView in the webtoon mode (Stage 3)", () => {
  // jsdom has no layout: the strip is 1000 × 900 px (so 800 px wide, pages of 1200 px before their size is known).
  beforeEach(() => {
    for (const [prop, value] of [
      ["clientWidth", 1000],
      ["clientHeight", 900],
    ] as const) {
      const before = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
      Object.defineProperty(HTMLElement.prototype, prop, { configurable: true, get: () => value });
      restore.push(() => {
        if (before) Object.defineProperty(HTMLElement.prototype, prop, before);
      });
    }
  });

  const strip = () => wrapper.find('[data-testid="webtoon-reader"]');
  const stripEl = () => strip().element as HTMLElement;
  async function scrollTo(y: number) {
    stripEl().scrollTop = y;
    stripEl().dispatchEvent(new Event("scroll"));
    await flushPromises();
  }

  it("switches modes from the toolbar for the session, and keeps the page", async () => {
    chapters[0] = { ...chapters[0]!, lastPageRead: 4 };
    await mountReader();
    const modes = () =>
      wrapper
        .findAll('[data-testid="reader-modes"] button')
        .map((b) => b.attributes("aria-pressed"));
    expect(wrapper.findAll('[data-testid="reader-modes"] button').map((b) => b.text())).toEqual([
      "Single",
      "Double",
      "Webtoon",
    ]);
    expect(modes()).toEqual(["true", "false", "false"]);

    await wrapper.find('[data-action="mode-webtoon"]').trigger("click");
    await flushPromises();
    expect(ops.setMangaMeta).toHaveBeenCalledWith(34, "madoyomi.reader.mode", "webtoon");
    expect(useReaderStore().prefs.mode).toBe("paged");
    expect(paged().exists()).toBe(false);
    expect(stripEl().scrollTop).toBe(4 * 1200);
    expect(position()).toBe("4");
    expect(modes()).toEqual(["false", "false", "true"]);
    expect(wrapper.find('[data-action="direction"]').exists()).toBe(false);

    await scrollTo(6 * 1200 + 100);
    expect(position()).toBe("6");
    expect(pageQuery()).toBe("7");
    // scrolling hid the toolbar; M brings it back
    expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(false);
    await key("m");
    await wrapper.find('[data-action="mode-paged"]').trigger("click");
    await flushPromises();
    expect(paged().exists()).toBe(true);
    expect(wrapper.find('[data-testid="page-image"]').attributes("alt")).toBe("Page 7");
  });

  it("saves the page at the top of the strip, marks the chapter read on the last one, and goes on past the end", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    expect(position()).toBe("0");

    await scrollTo(3 * 1200);
    expect(position()).toBe("3");
    await scrollTo(9 * 1200 + 10);
    expect(ops.updateChapter).toHaveBeenCalledWith(5001, { lastPageRead: 9, isRead: true });

    await scrollTo(10 * 1200); // the bottom: the end panel fills the viewport
    expect(position()).toBe("10");
    expect(strip().find('[data-testid="panel-end"]').text()).toContain("Next chapter: Chapter 2");
    await key("PageDown");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5002");
  });

  it("scrolls with the keys and jumps with the slider and Home/End", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();

    await key("PageDown");
    expect(stripEl().scrollTop).toBe(810);
    await key("ArrowDown");
    expect(stripEl().scrollTop).toBe(945);
    await key("ArrowLeft"); // nothing in the strip
    expect(stripEl().scrollTop).toBe(945);

    // scrolling hid the toolbar
    await key("m");
    const slider = wrapper.find('[data-testid="page-slider"]');
    await slider.setValue("8");
    await slider.trigger("input");
    await flushPromises();
    expect(stripEl().scrollTop).toBe(7 * 1200);
    expect(position()).toBe("7");

    await key("End");
    expect(stripEl().scrollTop).toBe(9 * 1200);
    await key("Home");
    expect(stripEl().scrollTop).toBe(0);
    // at the top of the first chapter, a step back goes nowhere
    await key("PageUp");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
  });

  it("keeps the toolbar up while the slider seeks, and hides it on a user scroll", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    const bottom = () => wrapper.find('[data-testid="reader-bottom"]');
    expect(bottom().exists()).toBe(true);

    const slider = wrapper.find('[data-testid="page-slider"]');
    for (const value of ["3", "5", "8"]) {
      await slider.setValue(value);
      // the browser's scroll event for the strip's own scroll
      stripEl().dispatchEvent(new Event("scroll"));
      await flushPromises();
    }
    expect(stripEl().scrollTop).toBe(7 * 1200);
    expect(position()).toBe("7");
    expect(bottom().exists()).toBe(true);

    await scrollTo(7 * 1200 + 300);
    expect(bottom().exists()).toBe(false);
  });

  it("opens the previous chapter at its last page from the top of the strip", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader(5002);
    expect(stripEl().scrollTop).toBe(240); // page 1 below the "Previous" panel

    await scrollTo(0);
    expect(position()).toBe("-1");
    await key("PageUp");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
    expect(pageQuery()).toBe("last");
  });

  it("loads the pages around the viewport and releases the ones it leaves", async () => {
    chapters[0] = { ...chapters[0]!, pageCount: 40 };
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    expect(wrapper.findAll('[data-testid="page-image"]').map((i) => i.attributes("alt"))).toEqual([
      "Page 1",
      "Page 2",
      "Page 3",
    ]);

    await scrollTo(30 * 1200);
    await flushPromises();
    const loaded = wrapper.findAll('[data-testid="page-image"]').map((i) => i.attributes("alt"));
    expect(loaded).toEqual(["Page 30", "Page 31", "Page 32", "Page 33"]);
    expect(assetCalls.some((c) => c.path.endsWith("/page/32"))).toBe(true);
    // every page loaded before was released
    expect(vi.mocked(URL.revokeObjectURL).mock.calls.length).toBeGreaterThanOrEqual(3);
  });

  it("leaves the wheel to the strip", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    const wheel = new WheelEvent("wheel", { deltaY: 120, cancelable: true, bubbles: true });
    stripEl().dispatchEvent(wheel);
    await flushPromises();
    expect(wheel.defaultPrevented).toBe(false);
    expect(position()).toBe("0");
  });
});

describe("ReaderView in the double-page mode (Stage 4)", () => {
  const spread = () => wrapper.find('[data-testid="spread"]');
  const shown = () => spread().attributes("data-pages");
  const count = () => wrapper.find('[data-testid="page-count"]').text();

  it("shows the first page alone, then spreads of two right to left, step by spread", async () => {
    useReaderStore().prefs.mode = "double";
    await mountReader();
    expect(shown()).toBe("0");
    expect(count()).toBe("1 / 10");

    await key("ArrowLeft");
    expect(shown()).toBe("1,2");
    expect(spread().classes()).toEqual(expect.arrayContaining(["rtl", "two"]));
    // in reading order in the DOM; right to left lays them out reversed (the first page on the right)
    expect(wrapper.findAll('[data-testid="page-image"]').map((i) => i.attributes("alt"))).toEqual([
      "Page 2",
      "Page 3",
    ]);
    await key("m");
    expect(count()).toBe("2–3 / 10");
    expect(pageQuery()).toBe("2");

    await key("ArrowLeft");
    expect(shown()).toBe("3,4");
    await key("ArrowRight");
    expect(shown()).toBe("1,2");
    await key("ArrowRight");
    expect(shown()).toBe("0");
  });

  it("pairs the first two pages with Shift, for the session", async () => {
    useReaderStore().prefs.mode = "double";
    await mountReader();
    const shift = wrapper.find('[data-action="shift"]');
    expect(shift.text()).toBe("Shift: off");

    await shift.trigger("click");
    await flushPromises();
    expect(useReaderStore().shift).toBe(true);
    expect(shown()).toBe("0,1");
    expect(wrapper.find('[data-action="shift"]').text()).toBe("Shift: on");
    await key("ArrowLeft");
    expect(shown()).toBe("2,3");
  });

  it("shows a wide page alone, at an odd and an even index, and keeps the page shown when the pairs change", async () => {
    // 0 | 1-2 | 3 | 4 (wide) | 5-6 | 7 (wide) | 8-9 once the sizes are known
    loaderSizes.set(4, { width: 1600, height: 1200 });
    loaderSizes.set(7, { width: 1600, height: 1200 });
    useReaderStore().prefs.mode = "double";
    await mountReader(5001, "?page=4");
    // page 3 was paired with 4 until 4's size came: it stays on screen, alone
    expect(shown()).toBe("3");

    const seen: string[] = [];
    for (let k = 0; k < 4; k++) {
      await key("ArrowLeft");
      seen.push(shown()!);
    }
    expect(seen).toEqual(["4", "5,6", "7", "8,9"]);
  });

  it("saves the spread's last page, and marks the chapter read on the last spread", async () => {
    chapters[0] = { ...chapters[0]!, pageCount: 9 }; // 0 | 1-2 | 3-4 | 5-6 | 7-8
    useReaderStore().prefs.mode = "double";
    await mountReader(5001, "?page=6");
    expect(shown()).toBe("5,6");
    expect(position()).toBe("5");

    const slider = wrapper.find('[data-testid="page-slider"]');
    await slider.setValue("9");
    await flushPromises();
    // the slider's page 9 is the second of the spread 8-9
    expect(position()).toBe("8");
    expect(shown()).toBe("7,8");
    expect(count()).toBe("8–9 / 9");
    expect(ops.updateChapter).toHaveBeenCalledWith(5001, { lastPageRead: 8, isRead: true });
  });

  it("writes the spread's last page as the place to resume once the reader has moved", async () => {
    useReaderStore().prefs.mode = "double";
    await mountReader(5001, "?page=4");
    expect(shown()).toBe("3,4");
    await key("ArrowLeft");
    await key("ArrowRight");
    expect(shown()).toBe("3,4");
    wrapper.unmount();
    expect(ops.updateChapter).toHaveBeenLastCalledWith(5001, { lastPageRead: 4 });
    wrapper = undefined as unknown as VueWrapper;
  });

  it("saves the page it opened on until the reader moves, so a reopen doesn't move the place (Stage 6)", async () => {
    // left at page 4 (index 3) when page 1 was paired with 2 (Shift): now 3 opens as the first of the spread 3-4
    chapters[0] = { ...chapters[0]!, lastPageRead: 3 };
    useReaderStore().prefs.mode = "double";
    await mountReader();
    expect(shown()).toBe("3,4");
    wrapper.unmount();
    wrapper = undefined as unknown as VueWrapper;
    expect(ops.updateChapter).not.toHaveBeenCalledWith(5001, { lastPageRead: 4 });
    expect(ops.updateChapter).toHaveBeenCalledWith(5001, { lastPageRead: 3 });
  });

  it("keeps the page when switching to and from the single-page mode", async () => {
    useReaderStore().prefs.mode = "double";
    await mountReader(5001, "?page=4");
    await wrapper.find('[data-action="mode-paged"]').trigger("click");
    await flushPromises();
    expect(shown()).toBe("3");
    await wrapper.find('[data-action="mode-double"]').trigger("click");
    await flushPromises();
    expect(shown()).toBe("3,4");
  });

  it("loads the spread, the pages after it and the ones before", async () => {
    autoResolve = false;
    chapters[0] = { ...chapters[0]!, pageCount: 20 };
    useReaderStore().prefs.mode = "double";
    await mountReader(5001, "?page=6");
    // the spread 5-6 first, then 7 and 8 (4 requests at a time)
    expect(assetCalls.map((c) => c.path.split("/").pop())).toEqual(["5", "6", "7", "8"]);
  });
});

describe("ReaderView zoom and pan (Stage 4)", () => {
  const zoom = () => Number(wrapper.find('[data-testid="paged-reader"]').attributes("data-zoom"));
  /** A click with its count (`detail`: 2 is the second click of a double-click). */
  async function click(x: number, detail: number) {
    paged().element.dispatchEvent(
      new MouseEvent("click", { bubbles: true, clientX: x, clientY: 300, detail }),
    );
    await flushPromises();
  }
  function wheel(init: WheelEventInit) {
    const e = new WheelEvent("wheel", { cancelable: true, bubbles: true, ...init });
    paged().element.dispatchEvent(e);
    return e;
  }

  it("zooms with Ctrl+wheel and the keys; zoomed in, the wheel and the arrows pan instead of turning", async () => {
    await mountReader();
    const e = wheel({ deltaY: -100, ctrlKey: true, clientX: 450, clientY: 350 });
    await flushPromises();
    expect(e.defaultPrevented).toBe(true);
    expect(zoom()).toBeGreaterThan(1.2);
    expect(paged().classes()).toContain("zoomed");

    wheel({ deltaY: 120 });
    await key("ArrowLeft");
    await key("ArrowDown");
    await flushPromises();
    expect(position()).toBe("0");
    expect(zoom()).toBeGreaterThan(1.2);

    await key("=", { ctrlKey: true });
    expect(zoom()).toBeGreaterThan(1.5);
    await key("0", { ctrlKey: true });
    expect(zoom()).toBe(1);

    // back at fit, the arrows turn again
    await key("ArrowLeft");
    expect(position()).toBe("1");
  });

  it("goes back to fit on a page turn, and Esc goes back to fit before it leaves", async () => {
    await mountReader();
    await key("=", { ctrlKey: true });
    expect(zoom()).toBe(1.25);
    await key(" ");
    expect(position()).toBe("1");
    expect(zoom()).toBe(1);

    await key("=", { ctrlKey: true });
    await key("Escape");
    expect(zoom()).toBe(1);
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
    await key("Escape");
    expect(router.currentRoute.value.path).toBe("/manga/34");
  });

  it("zooms 2× with a double-click in the middle third without toggling the toolbar, and back with another", async () => {
    await mountReader();
    await key("ArrowLeft"); // the toolbar hides
    vi.useFakeTimers();
    try {
      await click(450, 1);
      await click(450, 2);
      vi.advanceTimersByTime(CLICK_DELAY_MS * 2);
      await flushPromises();
      expect(zoom()).toBe(2);
      expect(wrapper.find('[data-testid="reader-bottom"]').exists()).toBe(false);

      // zoomed in, a single click does nothing, even on a side
      await click(50, 1);
      vi.advanceTimersByTime(CLICK_DELAY_MS * 2);
      await flushPromises();
      expect(position()).toBe("1");
      expect(zoom()).toBe(2);

      await click(50, 1);
      await click(50, 2);
      await flushPromises();
      expect(zoom()).toBe(1);
    } finally {
      vi.useRealTimers();
    }
    // the side thirds still turn at once
    await clickAt(50);
    expect(position()).toBe("2");
  });

  it("pans with a drag, which isn't a click", async () => {
    await mountReader();
    await key("=", { ctrlKey: true });
    const el = paged().element;
    const pointer = (type: string, x: number) =>
      el.dispatchEvent(
        new MouseEvent(type, { bubbles: true, clientX: x, clientY: 300, button: 0 }),
      );
    pointer("pointerdown", 450);
    pointer("pointermove", 300);
    await flushPromises();
    expect(paged().classes()).toContain("panning");
    pointer("pointerup", 300);
    await click(300, 1);
    expect(position()).toBe("0");
    expect(zoom()).toBe(1.25);
  });

  it("has no zoom in the webtoon strip", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    const e = new WheelEvent("wheel", {
      deltaY: -100,
      ctrlKey: true,
      cancelable: true,
      bubbles: true,
    });
    wrapper.find('[data-testid="webtoon-reader"]').element.dispatchEvent(e);
    await key("=", { ctrlKey: true });
    expect(e.defaultPrevented).toBe(true);
    expect(wrapper.find('[data-testid="zoom-layer"]').exists()).toBe(false);
  });
});

describe("ReaderView, the seamless next chapter (Stage 4)", () => {
  it("fetches the next chapter's page list near the end, and goes on into it at once", async () => {
    let queryOf5002 = 0;
    const base = ops.fetchChapterPages.getMockImplementation()!;
    ops.fetchChapterPages.mockImplementation(async (id: number) => {
      // the next chapter's own query (after the one fetched ahead) never answers: it isn't waited for
      if (id === 5002 && ++queryOf5002 > 1) return new Promise(() => {});
      return base(id);
    });
    await mountReader(5001, "?page=5");
    expect(ops.fetchChapterPages).not.toHaveBeenCalledWith(5002);

    await key("ArrowLeft"); // page 6: 5 from the end
    expect(ops.fetchChapterPages).toHaveBeenCalledWith(5002);

    await key("End");
    await key("ArrowLeft");
    expect(wrapper.find('[data-testid="panel-end"]').text()).toContain("Next chapter: Chapter 2");
    await key("ArrowLeft");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5002");
    await follow();
    expect(wrapper.find('[data-testid="reader-loading"]').exists()).toBe(false);
    expect(position()).toBe("0");
    await flushPromises();
    expect(wrapper.find('[data-testid="page-image"]').attributes("alt")).toBe("Page 1");
    expect(assetCalls.some((c) => c.path === "/api/v1/manga/34/chapter/2/page/0")).toBe(true);
    expect(document.title).toContain("Chapter 2");
    expect(ops.updateChapter).toHaveBeenCalledWith(5001, { lastPageRead: 9, isRead: true });
  });

  it("fetches the previous chapter's page list on its panel, and goes back into it at once", async () => {
    await mountReader(5002);
    const before = ops.fetchChapterPages.mock.calls.filter(([id]) => id === 5001).length;
    await key("ArrowRight");
    expect(ops.fetchChapterPages.mock.calls.filter(([id]) => id === 5001).length).toBe(before + 1);

    ops.fetchChapterPages.mockImplementation(() => new Promise(() => {}));
    await key("ArrowRight");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
    await follow();
    expect(position()).toBe("9");
    expect(wrapper.find('[data-testid="reader-loading"]').exists()).toBe(false);
  });
});

describe("ReaderView, the webtoon strip's wheel past its ends (Stage 4)", () => {
  beforeEach(() => {
    for (const [prop, value] of [
      ["clientWidth", 1000],
      ["clientHeight", 900],
    ] as const) {
      const before = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
      Object.defineProperty(HTMLElement.prototype, prop, { configurable: true, get: () => value });
      restore.push(() => {
        if (before) Object.defineProperty(HTMLElement.prototype, prop, before);
      });
    }
    let now = 1000;
    const spy = vi.spyOn(performance, "now").mockImplementation(() => now);
    restore.push(() => spy.mockRestore());
    clock = (t: number) => (now = t);
  });
  let clock: (t: number) => void;

  const stripEl = () => wrapper.find('[data-testid="webtoon-reader"]').element as HTMLElement;
  async function scrollTo(y: number) {
    stripEl().scrollTop = y;
    stripEl().dispatchEvent(new Event("scroll"));
    await flushPromises();
  }
  async function wheelAt(t: number, deltaY: number) {
    clock(t);
    const e = new WheelEvent("wheel", { deltaY, cancelable: true, bubbles: true });
    stripEl().dispatchEvent(e);
    await flushPromises();
    return e;
  }

  it("goes on into the next chapter on a new wheel gesture at the bottom, not on the one that got there", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    await wheelAt(1000, 100);
    // 10 pages of 1200 px and the end panel (900): the bottom is at 12000
    await scrollTo(12000);
    await wheelAt(1050, 100);
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");

    const opening = await wheelAt(1600, 100);
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5002");
    // that notch doesn't also scroll the new strip; the ones before scrolled the strip natively
    expect(opening.defaultPrevented).toBe(true);
    await follow();
    await flushPromises();
    expect(position()).toBe("0");
    // a new strip for the chapter, page 1 at the top below its "Previous chapter" panel
    expect(stripEl().scrollTop).toBe(240);
  });

  it("shows the next chapter's first page held ahead in the new strip, without fetching it again (Stage 6)", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader();
    await wheelAt(1000, 100);
    await scrollTo(12000);
    const first = "/api/v1/manga/34/chapter/2/page/0";
    expect(assetCalls.filter((c) => c.path === first)).toHaveLength(1);

    await wheelAt(1600, 100);
    await follow();
    await flushPromises();
    expect(position()).toBe("0");
    expect(wrapper.find('[data-testid="page-image"]').attributes("alt")).toBe("Page 1");
    expect(assetCalls.filter((c) => c.path === first)).toHaveLength(1);
  });

  it("goes back into the previous chapter on a wheel gesture up at the top", async () => {
    useReaderStore().prefs.mode = "webtoon";
    await mountReader(5002);
    await scrollTo(0);
    await wheelAt(5000, -100);
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
    expect(pageQuery()).toBe("last");
  });
});

describe("ReaderToolbar's focus (Stage 4)", () => {
  it("leaves the focus on the reader after a mouse click on a button, and the slider gives it back", async () => {
    await mountReader();
    for (const action of ["mode-double", "direction", "fullscreen"]) {
      const e = new MouseEvent("mousedown", { bubbles: true, cancelable: true });
      wrapper.find(`[data-action="${action}"]`).element.dispatchEvent(e);
      expect(e.defaultPrevented).toBe(true);
    }
    const slider = wrapper.find('[data-testid="page-slider"]');
    const down = new MouseEvent("mousedown", { bubbles: true, cancelable: true });
    slider.element.dispatchEvent(down);
    expect(down.defaultPrevented).toBe(false);

    (slider.element as HTMLElement).focus();
    expect(document.activeElement).toBe(slider.element);
    await slider.trigger("pointerup");
    expect(document.activeElement).not.toBe(slider.element);
  });
});

describe("ReaderView settings (Stage 5)", () => {
  const reader = () => wrapper.find('[data-testid="reader"]');
  const strip = () => wrapper.find('[data-testid="webtoon-reader"]');
  const sheet = () => wrapper.find('[data-testid="reader-settings-sheet"]');

  it("waits for the manga's own settings, then opens in its own mode and direction", async () => {
    let answer!: (m: unknown) => void;
    ops.fetchMangaReaderMeta.mockReturnValue(new Promise((r) => (answer = r)));
    await mountReader();
    expect(wrapper.find('[data-testid="reader-loading"]').exists()).toBe(true);
    expect(paged().exists()).toBe(false);

    answer({
      mangaId: 34,
      sourceName: "MangaPill",
      meta: [
        { key: "madoyomi.reader.mode", value: "double" },
        { key: "madoyomi.reader.direction", value: "ltr" },
      ],
    });
    await flushPromises();
    expect(reader().attributes("data-mode")).toBe("double");
    expect(paged().attributes("data-direction")).toBe("ltr");
    expect(wrapper.find('[data-testid="spread"]').attributes("data-pages")).toBe("0");
  });

  it("opens a Long Strip title, and one from a webtoon site, in the webtoon mode; others in the default", async () => {
    ops.fetchManga.mockResolvedValue(makeManga({ genre: ["Action", "Long Strip"] }));
    await mountReader();
    expect(reader().attributes("data-mode")).toBe("webtoon");
    expect(strip().exists()).toBe(true);
    wrapper.unmount();
    queryClient.clear();

    ops.fetchManga.mockResolvedValue(makeManga({ genre: ["Fantasy"] }));
    ops.fetchMangaReaderMeta.mockResolvedValue({
      mangaId: 34,
      meta: [],
      sourceName: "Webtoons.com",
    });
    await mountReader();
    expect(reader().attributes("data-mode")).toBe("webtoon");
    wrapper.unmount();
    queryClient.clear();

    ops.fetchMangaReaderMeta.mockResolvedValue({ mangaId: 34, meta: [], sourceName: "MangaPill" });
    useReaderStore().set({ mode: "double", direction: "ltr" });
    await mountReader();
    expect(reader().attributes("data-mode")).toBe("double");
    expect(paged().attributes("data-direction")).toBe("ltr");
  });

  it("opens the settings sheet from the toolbar; Esc closes it, and the reader's keys wait meanwhile", async () => {
    ops.fetchManga.mockResolvedValue(makeManga({ genre: ["Manhwa"] }));
    ops.fetchMangaReaderMeta.mockResolvedValue({
      mangaId: 34,
      sourceName: "MangaPill",
      meta: [{ key: "madoyomi.reader.mode", value: "paged" }],
    });
    await mountReader();
    await wrapper.find('[data-action="settings"]').trigger("click");
    expect(sheet().exists()).toBe(true);
    expect(sheet().find('[data-testid="mode-origin"]').text()).toBe("Single · This manga's");
    expect(sheet().find('[data-manga-pref="mode"]').text()).toContain("Default (Webtoon)");

    await key("ArrowLeft");
    expect(position()).toBe("0");

    // "Use default": the manga's own mode is removed, and the genre gives webtoon
    await sheet().find('[data-manga-pref="mode"]').setValue("");
    await flushPromises();
    expect(ops.deleteMangaMeta).toHaveBeenCalledWith(34, "madoyomi.reader.mode");
    expect(reader().attributes("data-mode")).toBe("webtoon");
    expect(sheet().find('[data-testid="mode-origin"]').text()).toBe(
      "Webtoon · From the genre “Manhwa”",
    );

    await sheet().find('[data-manga-pref="direction"]').setValue("ltr");
    await flushPromises();
    expect(ops.setMangaMeta).toHaveBeenCalledWith(34, "madoyomi.reader.direction", "ltr");

    await key("Escape");
    expect(sheet().exists()).toBe(false);
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
  });

  it("changes the global settings from the sheet, and resets the manga's own", async () => {
    ops.fetchMangaReaderMeta.mockResolvedValue({
      mangaId: 34,
      sourceName: "MangaPill",
      meta: [{ key: "madoyomi.reader.direction", value: "ltr" }],
    });
    await mountReader();
    await wrapper.find('[data-action="settings"]').trigger("click");

    await sheet().find('[data-pref="background"]').setValue("white");
    expect(useReaderStore().prefs.background).toBe("white");
    expect(reader().attributes("data-background")).toBe("white");
    expect(reader().attributes("style")).toContain("--reader-bg: #ffffff");
    await sheet().find('[data-pref="mode"]').setValue("double");
    expect(reader().attributes("data-mode")).toBe("double");

    await sheet().find('[data-action="reset-manga-prefs"]').trigger("click");
    await flushPromises();
    expect(ops.deleteMangaMeta.mock.calls).toEqual([
      [34, "madoyomi.reader.mode"],
      [34, "madoyomi.reader.direction"],
    ]);
    expect(paged().attributes("data-direction")).toBe("rtl");
    expect(sheet().find('[data-action="reset-manga-prefs"]').attributes("disabled")).toBeDefined();

    await sheet().find('[data-action="close-settings"]').trigger("click");
    expect(sheet().exists()).toBe(false);
  });

  it("loads as many pages ahead as the preload count says, and the backend's block after them", async () => {
    useReaderStore().set({ preload: 8 });
    chapters = makeChapters(3).map((c) => ({ ...c, pageCount: 30 }));
    await mountReader();
    const loaded = new Set(assetCalls.map((c) => Number(c.path.split("/").pop())));
    expect([...loaded].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8]);
    expect(ops.prefetchChapterPages).toHaveBeenCalledWith(5001, 9, 10);
  });

  it("says when the manga's setting couldn't be saved", async () => {
    ops.setMangaMeta.mockRejectedValueOnce(
      new ApiError({ kind: "graphql", code: "INTERNAL", message: "no" }),
    );
    await mountReader();
    await wrapper.find('[data-action="mode-double"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="manga-prefs-save-error"]').exists()).toBe(true);
    expect(reader().attributes("data-mode")).toBe("paged");
  });
});

describe("ReaderView page fit (Stage 5)", () => {
  // The reader is 900 × 700; a 1:2 page fitted to the width is 900 × 1400, laid out 350 px above and below the
  // window; fitted to the screen it is 350 × 700 in the middle.
  beforeEach(() => {
    const stub = (proto: object, prop: string, get: (el: HTMLElement) => number) => {
      const before = Object.getOwnPropertyDescriptor(proto, prop);
      Object.defineProperty(proto, prop, {
        configurable: true,
        get(this: HTMLElement) {
          return get(this);
        },
      });
      restore.push(() => {
        if (before) Object.defineProperty(proto, prop, before);
        else delete (proto as Record<string, unknown>)[prop];
      });
    };
    const wide = (el: HTMLElement) => !!el.closest(".fit-width");
    stub(HTMLElement.prototype, "clientWidth", () => 900);
    stub(HTMLElement.prototype, "clientHeight", () => 700);
    stub(HTMLImageElement.prototype, "offsetLeft", (el) => (wide(el) ? 0 : 275));
    stub(HTMLImageElement.prototype, "offsetTop", (el) => (wide(el) ? -350 : 0));
    stub(HTMLImageElement.prototype, "offsetWidth", (el) => (wide(el) ? 900 : 350));
    stub(HTMLImageElement.prototype, "offsetHeight", (el) => (wide(el) ? 1400 : 700));
  });

  const layer = () => wrapper.find('[data-testid="zoom-layer"]').attributes("style") ?? "";
  function wheel(deltaY: number) {
    const e = new WheelEvent("wheel", { cancelable: true, bubbles: true, deltaY });
    paged().element.dispatchEvent(e);
    return e;
  }

  it("starts a page bigger than the window at its top, scrolls it with the keys, then turns the page", async () => {
    useReaderStore().set({ fit: "width" });
    await mountReader();
    expect(paged().attributes("data-fit")).toBe("width");
    expect(paged().classes()).toContain("scrollable");
    expect(layer()).toContain("translate(0px, 350px) scale(1)");

    await key(" ");
    expect(position()).toBe("0");
    expect(layer()).toContain("translate(0px, -280px)");
    await key("ArrowDown");
    expect(layer()).toContain("translate(0px, -350px)");
    // at the bottom: the next step turns the page, which starts at its top
    await key("PageDown");
    expect(position()).toBe("1");
    await flushPromises();
    expect(layer()).toContain("translate(0px, 350px)");

    await key("ArrowUp");
    expect(position()).toBe("0");
  });

  it("scrolls with the wheel, and a new gesture at the page's bottom turns it", async () => {
    useReaderStore().set({ fit: "width" });
    await mountReader();
    wheel(100);
    await flushPromises();
    expect(position()).toBe("0");
    expect(layer()).toContain("translate(0px, 250px)");
    await key(" ");
    expect(layer()).toContain("translate(0px, -350px)");
    // a new gesture at the bottom turns the page
    await new Promise((r) => setTimeout(r, 220));
    wheel(100);
    await flushPromises();
    expect(position()).toBe("1");
  });

  it("goes back to the start view when the fit changes after a pan (Stage 6)", async () => {
    useReaderStore().set({ fit: "width" });
    await mountReader();
    await key(" ");
    expect(layer()).toContain("translate(0px, -280px)");

    useReaderStore().set({ fit: "screen" });
    await flushPromises();
    expect(paged().attributes("data-fit")).toBe("screen");
    // the whole page again: no transform (the start view of a page that fits is the fit itself)
    expect(layer()).not.toContain("translate");
  });

  it("keeps Stage 2's whole page with fit screen", async () => {
    await mountReader();
    expect(paged().attributes("data-fit")).toBe("screen");
    expect(paged().classes()).not.toContain("scrollable");
    await key(" ");
    expect(position()).toBe("1");
  });
});

describe("ReaderView strip width (Stage 5)", () => {
  beforeEach(() => {
    for (const [prop, value] of [
      ["clientWidth", 1000],
      ["clientHeight", 900],
    ] as const) {
      const before = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
      Object.defineProperty(HTMLElement.prototype, prop, { configurable: true, get: () => value });
      restore.push(() => {
        if (before) Object.defineProperty(HTMLElement.prototype, prop, before);
      });
    }
  });

  it("makes the strip as wide as the setting, or the window, and keeps the page shown", async () => {
    useReaderStore().set({ mode: "webtoon", stripWidth: 600 });
    chapters[0] = { ...chapters[0]!, lastPageRead: 4 };
    await mountReader();
    const sizer = () => wrapper.find('[data-testid="webtoon-reader"] .sizer');
    expect(sizer().attributes("style")).toContain("width: 600px");
    // 600 px wide pages of the default ratio 1.5: 900 px each
    expect((wrapper.find('[data-testid="webtoon-reader"]').element as HTMLElement).scrollTop).toBe(
      4 * 900,
    );

    useReaderStore().set({ stripWidth: 0 });
    await flushPromises();
    expect(sizer().attributes("style")).toContain("width: 1000px");
    expect(position()).toBe("4");
    expect((wrapper.find('[data-testid="webtoon-reader"]').element as HTMLElement).scrollTop).toBe(
      4 * 1500,
    );
  });
});

describe("ReaderView, the neighbours' opening pages held ahead (Stage 6)", () => {
  const callsFor = (path: string) => assetCalls.filter((c) => c.path === path).length;

  it("loads the next chapter's first pages once its list is in, and shows its first page in the first render", async () => {
    await mountReader(5001, "?page=5");
    await key("ArrowLeft"); // page 6: near the end, the next chapter's list is fetched ahead
    await flushPromises();
    expect(callsFor("/api/v1/manga/34/chapter/2/page/0")).toBe(1);
    expect(callsFor("/api/v1/manga/34/chapter/2/page/1")).toBe(1);

    await key("End");
    await key("ArrowLeft");
    await key("ArrowLeft");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5002");
    await follow();
    // no flush: the page held ahead is the new chapter's loaded page at once
    expect(wrapper.find('[data-testid="page-image"]').attributes("alt")).toBe("Page 1");
    await flushPromises();
    expect(callsFor("/api/v1/manga/34/chapter/2/page/0")).toBe(1);
  });

  it("loads the previous chapter's last pages on its panel, and shows its last page in the first render", async () => {
    await mountReader(5002);
    await key("ArrowRight"); // the "Previous chapter" panel
    await flushPromises();
    expect(callsFor("/api/v1/manga/34/chapter/1/page/9")).toBe(1);
    expect(callsFor("/api/v1/manga/34/chapter/1/page/8")).toBe(1);

    await key("ArrowRight");
    expect(router.currentRoute.value.path).toBe("/manga/34/read/5001");
    await follow();
    expect(position()).toBe("9");
    expect(wrapper.find('[data-testid="page-image"]').attributes("alt")).toBe("Page 10");
    await flushPromises();
    expect(callsFor("/api/v1/manga/34/chapter/1/page/9")).toBe(1);
  });

  it("holds nothing before the end, and the next chapter's opening page where reading stopped in it", async () => {
    chapters = chapters.map((c) => (c.id === 5002 ? { ...c, isRead: false, lastPageRead: 4 } : c));
    await mountReader(5001);
    await flushPromises();
    expect(assetCalls.some((c) => c.path.includes("/chapter/2/"))).toBe(false);

    await key("End");
    await flushPromises();
    expect(callsFor("/api/v1/manga/34/chapter/2/page/4")).toBe(1);
    expect(callsFor("/api/v1/manga/34/chapter/2/page/5")).toBe(1);
    expect(callsFor("/api/v1/manga/34/chapter/2/page/0")).toBe(0);
  });
});
