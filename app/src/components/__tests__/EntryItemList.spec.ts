import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";

import ChapterList from "../ChapterList.vue";
import EpisodeList from "../EpisodeList.vue";
import { ITEM_SORT_KEY, useItemListStore } from "../../stores/itemList";
import { makeChapters, makeEpisodes } from "../../test/fixtures";
import type { Episode } from "../../api/types";

/*
 * The episode and chapter lists (EntryItemList through its two wrappers). jsdom has no layout, so the scroll area's
 * clientHeight and each row's offsetHeight are stubbed (a 560 px viewport, 56 px rows), as in VirtualList.spec.ts.
 */
const VIEWPORT = 560;
const ROW = 56;

let pinia: Pinia;
let wrapper: VueWrapper;
const restore: (() => void)[] = [];

function stub(
  prop: "clientHeight" | "clientWidth" | "offsetHeight",
  get: (el: HTMLElement) => number,
) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
  Object.defineProperty(HTMLElement.prototype, prop, {
    configurable: true,
    get() {
      return get(this as HTMLElement);
    },
  });
  restore.push(() => {
    if (original) Object.defineProperty(HTMLElement.prototype, prop, original);
    else delete (HTMLElement.prototype as unknown as Record<string, unknown>)[prop];
  });
}

async function mountEpisodes(episodes: Episode[], animeId = 12) {
  wrapper = mount(EpisodeList, {
    props: { animeId, episodes },
    global: { plugins: [pinia] },
    attachTo: document.body,
  });
  await flushPromises();
}

const rows = () => wrapper.findAll('[data-testid="item-row"]');
const names = () => rows().map((r) => r.find(".name").text());

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  stub("clientHeight", (el) => (el.classList.contains("virtual-list") ? VIEWPORT : 0));
  stub("clientWidth", (el) => (el.classList.contains("virtual-list") ? 800 : 0));
  stub("offsetHeight", (el) => (el.classList.contains("item") ? ROW : 0));
});

afterEach(() => {
  wrapper.unmount();
  restore.splice(0).forEach((r) => r());
  localStorage.clear();
});

describe("EpisodeList / ChapterList", () => {
  it("virtualizes 1500 episodes: only the rows in view are in the DOM, newest first", async () => {
    await mountEpisodes(makeEpisodes(1500));

    const list = wrapper.find(".virtual-list");
    expect(list.attributes("data-virtual")).toBe("on");
    expect(rows().length).toBeGreaterThan(5);
    expect(rows().length).toBeLessThan(40);
    expect(names()[0]).toBe("Episode 1500");
    expect(wrapper.find('[data-testid="item-count"]').text()).toBe("1,500 episodes");
    // the scroll area is as tall as every row
    expect((wrapper.find(".sizer").element as HTMLElement).style.height).toBe(`${1500 * ROW}px`);
  });

  it("scrolling far down renders the rows there", async () => {
    await mountEpisodes(makeEpisodes(1500));

    const el = wrapper.find(".virtual-list").element as HTMLElement;
    el.scrollTop = 1000 * ROW;
    el.dispatchEvent(new Event("scroll"));
    await flushPromises();

    expect(names()).toContain("Episode 500");
    expect(names()).not.toContain("Episode 1500");
  });

  it("sorts by number or date, either way, and keeps the sort per kind", async () => {
    const episodes = makeEpisodes(3).map((e, i) => ({
      ...e,
      // the source's order (1, 2, 3) differs from the numbers (2, 3, 1) and the dates (3, 1, 2)
      episodeNumber: [2, 3, 1][i],
      uploadDate: String(Date.UTC(2024, 0, [3, 1, 2][i])),
      name: `E${[2, 3, 1][i]}`,
    }));
    await mountEpisodes(episodes);
    expect(names()).toEqual(["E1", "E3", "E2"]);

    await wrapper.find('[data-testid="item-sort"]').setValue("number");
    expect(names()).toEqual(["E3", "E2", "E1"]);
    await wrapper.find('[data-action="sort-direction"]').trigger("click");
    expect(names()).toEqual(["E1", "E2", "E3"]);
    await wrapper.find('[data-testid="item-sort"]').setValue("date");
    expect(names()).toEqual(["E3", "E1", "E2"]);

    expect(JSON.parse(localStorage.getItem(ITEM_SORT_KEY)!).anime).toEqual({
      by: "date",
      desc: false,
    });
    expect(useItemListStore().sortOf("manga")).toEqual({ by: "source", desc: true });
  });

  it("filters bookmarked rows and one scanlator, and clears the filters", async () => {
    const chapters = makeChapters(4).map((c, i) => ({
      ...c,
      isBookmarked: i % 2 === 0,
      scanlator: i < 2 ? "TCB Scans" : "Asura",
    }));
    wrapper = mount(ChapterList, {
      props: { mangaId: 34, chapters },
      global: { plugins: [pinia] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(names()).toEqual(["Chapter 4", "Chapter 3", "Chapter 2", "Chapter 1"]);
    expect(wrapper.text()).toContain("Reading chapters comes in Phase 5");

    await wrapper.find('[data-testid="item-bookmarked"]').setValue(true);
    expect(names()).toEqual(["Chapter 3", "Chapter 1"]);
    expect(wrapper.find('[data-testid="item-count"]').text()).toBe("4 chapters · 2 shown");

    const scanlator = wrapper.find('[data-testid="item-scanlator"]');
    expect(scanlator.findAll("option").map((o) => o.text())).toEqual(["All", "Asura", "TCB Scans"]);
    await scanlator.setValue("TCB Scans");
    expect(names()).toEqual(["Chapter 1"]);

    await wrapper.find('[data-testid="item-progress"]').setValue("done");
    expect(wrapper.find('[data-testid="items-filtered-out"]').exists()).toBe(true);

    await wrapper.find('[data-action="clear-item-filters"]').trigger("click");
    expect(names()).toHaveLength(4);
    // the filters belong to this entry only
    expect(useItemListStore().filtersOf("manga", 99).bookmarked).toBe(false);
  });

  it("says when there are no episodes, and hides the scanlator filter without two of them", async () => {
    await mountEpisodes([]);

    expect(wrapper.find('[data-testid="items-none"]').text()).toBe("No episodes yet.");
    expect(wrapper.find('[data-testid="item-scanlator"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("Playing episodes comes in Phase 4");
  });
});
