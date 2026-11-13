import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { shallowReactive } from "vue";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";

import type { PageSize, PageState } from "../../../composables/usePageLoader";
import WebtoonReader from "../WebtoonReader.vue";

/*
 * The webtoon strip (Phase 5 Stage 3) on its own: only the items near the viewport in the DOM, each page sized by its
 * ratio (the estimate before it loads), where it opens, the page reported as the user scrolls, the anchor kept when a
 * page above gets its real size, a position it didn't report, the gap, and the edges. jsdom has no layout: the strip
 * is 1000 × 900 px by stubbed clientWidth/clientHeight (so 800 px wide), and its scrollTop is set by hand.
 */
const VH = 900;
let wrapper: VueWrapper;
let states: Map<number, PageState>;
let sizes: Map<number, PageSize>;
const restore: (() => void)[] = [];

function stub(prop: "clientWidth" | "clientHeight", value: number) {
  const before = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
  Object.defineProperty(HTMLElement.prototype, prop, { configurable: true, get: () => value });
  restore.push(() => {
    if (before) Object.defineProperty(HTMLElement.prototype, prop, before);
  });
}

async function mountStrip(props: Record<string, unknown> = {}) {
  wrapper = mount(WebtoonReader, {
    attachTo: document.body,
    props: {
      position: 0,
      pageCount: 100,
      pageState: (i: number) => states.get(i),
      pageSize: (i: number) => sizes.get(i),
      gap: 0,
      chapterName: "Chapter 126",
      mangaTitle: "Osora",
      previousName: null,
      nextName: "Chapter 127",
      ...props,
    },
  });
  await flushPromises();
}

const el = () => wrapper.find('[data-testid="webtoon-reader"]').element as HTMLElement;
const items = () => wrapper.findAll("[data-position]");
const item = (p: number) => wrapper.find(`[data-position="${p}"]`);
const top = (p: number) => parseFloat((item(p).element as HTMLElement).style.top);
const height = (p: number) => parseFloat((item(p).element as HTMLElement).style.height);
const emitted = <T>(name: string) => (wrapper.emitted(name) ?? []).map((e) => e[0] as T);
const last = <T>(name: string) => {
  const all = emitted<T>(name);
  return all[all.length - 1];
};

async function scrollTo(y: number) {
  el().scrollTop = y;
  el().dispatchEvent(new Event("scroll"));
  await flushPromises();
}

function load(i: number, size?: PageSize) {
  states.set(i, { status: "loaded", url: `blob:${i}` });
  if (size) sizes.set(i, size);
}

beforeEach(() => {
  states = shallowReactive(new Map());
  sizes = shallowReactive(new Map());
  stub("clientWidth", 1000);
  stub("clientHeight", VH);
});

afterEach(() => {
  wrapper?.unmount();
  restore.splice(0).forEach((r) => r());
  vi.unstubAllGlobals();
});

describe("WebtoonReader", () => {
  it("keeps only the items near the viewport in the DOM, a page not loaded as tall as the estimate", async () => {
    await mountStrip();

    // 800 px wide (the cap), 1.5 × 800 before any size is known; the viewport, 2 screens ahead
    expect(height(0)).toBe(1200);
    expect(items().map((i) => i.attributes("data-position"))).toEqual(["0", "1", "2"]);
    expect(wrapper.find('[data-testid="page-loading"]').text()).toBe("Loading page 1…");
    expect(last<number[]>("window")).toEqual([0, 1, 2]);

    await scrollTo(60000); // page 50
    expect(items().map((i) => i.attributes("data-position"))).toEqual(["49", "50", "51", "52"]);
    expect(items()).toHaveLength(4);
  });

  it("sizes a loaded page by its own ratio, and shows an <img> only for the loaded pages in reach", async () => {
    load(0, { width: 800, height: 2000 });
    load(1, { width: 1600, height: 1600 });
    await mountStrip();

    expect(height(0)).toBe(2000);
    expect(height(1)).toBe(800);
    expect(top(1)).toBe(2000);
    expect(height(2)).toBe(1400); // the median of 2.5 and 1, × 800
    const imgs = wrapper.findAll('[data-testid="page-image"]');
    expect(imgs.map((i) => i.attributes("alt"))).toEqual(["Page 1", "Page 2"]);
    expect(imgs[0]!.attributes("src")).toBe("blob:0");

    await scrollTo(30000);
    expect(wrapper.findAll('[data-testid="page-image"]')).toHaveLength(0);
  });

  it("opens with the position's top at the viewport's top, below the previous panel", async () => {
    await mountStrip({ position: 40, previousName: "Chapter 125" });

    // 240 px of panel, then 40 pages of 1200
    expect(el().scrollTop).toBe(240 + 40 * 1200);
    expect(wrapper.attributes("data-shown")).toBe("40");
    expect(last<number[]>("window")!.slice(0, 2)).toEqual([40, 41]);
    expect(wrapper.emitted("position")).toBeUndefined(); // nothing it wasn't told
  });

  it("reports the page at the top as the user scrolls, and the panels", async () => {
    await mountStrip({ previousName: "Chapter 125" });
    await scrollTo(240 + 3 * 1200 + 600);
    expect(last("position")).toBe(3);
    expect(wrapper.emitted("scrolled")).toHaveLength(1);

    await scrollTo(0);
    expect(last("position")).toBe(-1);
    expect(item(-1).text()).toContain("Previous: Chapter 125");

    await scrollTo(240 + 100 * 1200); // the bottom: the end panel fills the viewport
    expect(last("position")).toBe(100);
    expect(item(100).text()).toContain("Next: Chapter 127");
  });

  it("keeps the page it opened at in place when the pages above it get their real size", async () => {
    await mountStrip({ position: 10 });
    expect(el().scrollTop).toBe(12000);

    load(9, { width: 800, height: 2000 }); // 800 px taller than the estimate
    await flushPromises();
    // the estimate is now 2.5 for every unknown page: pages 0–8 are 2000 too
    expect(el().scrollTop).toBe(20000);
    expect(top(10)).toBe(20000);
    expect(wrapper.attributes("data-shown")).toBe("10");
    expect(wrapper.emitted("position")).toBeUndefined();
    expect(wrapper.emitted("scrolled")).toBeUndefined();
  });

  it("keeps what is at the viewport's centre still after a scroll, when a page above loads", async () => {
    await mountStrip();
    for (const p of [0, 1, 2, 3, 4, 5]) sizes.set(p, { width: 800, height: 1200 });
    await flushPromises();
    await scrollTo(5000); // the centre, 5450, is 650 px into page 4

    sizes.set(2, { width: 800, height: 1600 }); // page 2 grows by 400
    await flushPromises();
    expect(el().scrollTop).toBe(5400);
    expect(last("position")).toBe(4);
  });

  it("follows a position it didn't report: the slider puts that page at the top", async () => {
    await mountStrip();
    await wrapper.setProps({ position: 50 });
    await flushPromises();
    expect(el().scrollTop).toBe(60000);
    expect(wrapper.attributes("data-shown")).toBe("50");

    // the position it reported itself comes back without a scroll
    await scrollTo(61500);
    expect(last("position")).toBe(51);
    await wrapper.setProps({ position: 51 });
    await flushPromises();
    expect(el().scrollTop).toBe(61500);
  });

  it("puts the gap between items", async () => {
    await mountStrip({ gap: 16 });
    expect(top(1)).toBe(1216);
    expect(top(2)).toBe(2432);
  });

  it("scrolls a line or a screen with the keys, and says when it is at an end", async () => {
    await mountStrip({ pageCount: 3 });
    const strip = wrapper.vm as unknown as {
      scrollStep: (k: "line" | "screen", d: 1 | -1) => boolean;
    };
    expect(strip.scrollStep("screen", -1)).toBe(false); // at the top
    expect(strip.scrollStep("screen", 1)).toBe(true);
    await flushPromises();
    expect(el().scrollTop).toBe(810);
    expect(strip.scrollStep("line", 1)).toBe(true);
    await flushPromises();
    expect(el().scrollTop).toBe(945);

    await scrollTo(3 * 1200); // the end: 3 pages, then the end panel a viewport tall
    expect(strip.scrollStep("line", 1)).toBe(false);
  });

  it("toggles the toolbar on a click, but not from a failed page's retry", async () => {
    states.set(0, { status: "error", error: new Error("HTTP 503") });
    await mountStrip();
    await wrapper.find('[data-testid="page-failed"]').trigger("click");
    expect(last("retry")).toBe(0);
    expect(wrapper.emitted("menu")).toBeUndefined();

    await wrapper.find('[data-testid="webtoon-reader"]').trigger("click");
    expect(wrapper.emitted("menu")).toHaveLength(1);
  });

  it("counts the visible pages not shown yet (the dev stats)", async () => {
    await mountStrip();
    expect(wrapper.attributes("data-blank")).toBe("1");
    load(0, { width: 800, height: 1200 });
    await flushPromises();
    expect(wrapper.attributes("data-blank")).toBe("0");
  });

  it("keeps the point at the viewport's centre still when the window is resized", async () => {
    let resized: (() => void) | null = null;
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(cb: () => void) {
          resized = cb;
        }
        observe() {}
        disconnect() {}
      },
    );
    await mountStrip();
    await scrollTo(10 * 1200 + 300); // the centre is 62.5 % down page 10
    expect(emitted("scrolled")).toHaveLength(1);

    Object.defineProperty(HTMLElement.prototype, "clientWidth", {
      configurable: true,
      get: () => 500,
    });
    resized!();
    await flushPromises();
    expect(height(10)).toBe(750);
    expect(el().scrollTop).toBe(10 * 750 + 0.625 * 750 - VH / 2);
    // the browser's scroll event for the strip's own move isn't the user's
    el().dispatchEvent(new Event("scroll"));
    await flushPromises();
    expect(emitted("scrolled")).toHaveLength(1);
    expect(last("position")).toBe(10);
  });

  it("shows only the end panel for a chapter with no pages", async () => {
    await mountStrip({ pageCount: 0 });
    const positions = () => items().map((i) => i.attributes("data-position"));
    expect(positions()).toEqual(["0"]);
    expect(wrapper.find('[data-testid="panel-end"]').text()).toContain("Chapter 126 has no pages.");
    expect(wrapper.attributes("data-shown")).toBe("0");
    expect(wrapper.attributes("data-blank")).toBe("0");
    expect(wrapper.emitted("window")).toBeUndefined();

    await wrapper.setProps({ previousName: "Chapter 125" });
    await flushPromises();
    expect(positions()).toEqual(["-1", "0"]);
    expect(el().scrollTop).toBe(240);
    expect(wrapper.attributes("data-shown")).toBe("0");
  });
});
