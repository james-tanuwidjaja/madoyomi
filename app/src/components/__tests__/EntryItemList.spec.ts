import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { nextTick } from "vue";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";

import ChapterList from "../ChapterList.vue";
import EpisodeList from "../EpisodeList.vue";
import { ITEM_SORT_KEY, useItemListStore } from "../../stores/itemList";
import { usePlaybackStore } from "../../stores/playback";
import { makeChapters, makeEpisodes } from "../../test/fixtures";
import type { Episode, PlaybackSession } from "../../api/types";

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
    props: { animeId, episodes, sourceId: "2196071447214431390", sourceName: "AnimeGG" },
    // EpisodeList's Play is a vue-query mutation
    global: { plugins: [pinia, [VueQueryPlugin, { queryClient: new QueryClient() }]] },
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
    expect(wrapper.text()).toContain("Play lets you choose the video");
  });

  it("shows how far each episode was watched: time left and a bar, nothing for a watched or unstarted one", async () => {
    const [e1, e2, e3, e4] = makeEpisodes(4);
    await mountEpisodes([
      { ...e1!, lastSecondSeen: "752043", totalSeconds: "1500125" },
      { ...e2!, lastSecondSeen: "1499958", totalSeconds: "1500125", isWatched: true },
      { ...e3!, lastSecondSeen: "61000", totalSeconds: "0" },
      e4!,
    ]);
    const row = (name: string) => rows().find((r) => r.find(".name").text() === name)!;

    expect(row("Episode 1").find('[data-testid="item-progress-text"]').text()).toBe("12:28 left");
    const bar = row("Episode 1").find('[data-testid="item-progress-bar"]');
    expect(bar.attributes("aria-valuenow")).toBe("50.1");
    expect(row("Episode 2").find('[data-testid="item-progress-text"]').exists()).toBe(false);
    expect(row("Episode 2").text()).toContain("Watched");
    // a position without a duration: no bar
    expect(row("Episode 3").find('[data-testid="item-progress-text"]').text()).toBe(
      "Stopped at 1:01",
    );
    expect(row("Episode 3").find('[data-testid="item-progress-bar"]').exists()).toBe(false);
    expect(row("Episode 4").find('[data-testid="item-progress-text"]').exists()).toBe(false);
  });

  it("follows the episode mpv plays live, from the playback store", async () => {
    await mountEpisodes(makeEpisodes(2));
    const playback = usePlaybackStore();
    const live: PlaybackSession = {
      id: "s1",
      episodeId: 1001,
      animeId: 12,
      title: "t",
      videoTitle: "v",
      state: "RUNNING",
      startedAt: "1",
      endedAt: null,
      exitCode: null,
      positionSeconds: 60,
      durationSeconds: 1500,
      paused: false,
    };
    const event = (session: PlaybackSession) => ({
      type: "PROGRESS" as const,
      session,
      nextEpisodeId: null,
      reason: null,
      message: null,
      httpStatus: null,
      retried: false,
      failedVideo: null,
      nextVideo: null,
    });
    const first = () => rows().find((r) => r.find(".name").text() === "Episode 1")!;

    playback.apply(event(live));
    await nextTick();
    expect(first().find('[data-testid="item-playing"]').text()).toBe("Playing");
    expect(first().find('[data-testid="item-progress-text"]').text()).toBe("24:00 left");

    playback.apply(event({ ...live, positionSeconds: 750, paused: true }));
    await nextTick();
    expect(first().find('[data-testid="item-playing"]').text()).toBe("Paused");
    expect(first().find('[data-testid="item-progress-bar"]').attributes("aria-valuenow")).toBe(
      "50",
    );

    // another anime's session doesn't touch this list
    playback.apply(event({ ...live, animeId: 99, episodeId: 1002 }));
    await nextTick();
    expect(wrapper.find('[data-testid="item-playing"]').exists()).toBe(false);
  });

  it("selects rows with their checkboxes, a range with Shift, all shown, and clears", async () => {
    await mountEpisodes(makeEpisodes(5));
    const boxes = () => wrapper.findAll('[data-testid="item-select"]');
    const checked = () =>
      rows()
        .filter((r) => (r.find('[data-testid="item-select"]').element as HTMLInputElement).checked)
        .map((r) => r.find(".name").text());
    expect(wrapper.find('[data-testid="bulk-bar"]').exists()).toBe(false);

    // shown newest first: 5, 4, 3, 2, 1
    await boxes()[0]!.trigger("click");
    await boxes()[3]!.trigger("click", { shiftKey: true });
    expect(checked()).toEqual(["Episode 5", "Episode 4", "Episode 3", "Episode 2"]);
    expect(wrapper.find('[data-testid="selected-count"]').text()).toBe("4 selected");
    expect(wrapper.find('[data-action="mark-watched"]').exists()).toBe(true);
    // with several selected there is no "previous"
    expect(wrapper.find('[data-action="mark-previous"]').exists()).toBe(false);

    // a Shift+click on a selected row clears the range from the last one clicked
    await boxes()[2]!.trigger("click", { shiftKey: true });
    expect(checked()).toEqual(["Episode 5", "Episode 4"]);

    await wrapper.find('[data-action="select-all"]').trigger("click");
    expect(checked()).toHaveLength(5);
    await wrapper.find('[data-action="clear-selection"]').trigger("click");
    expect(checked()).toEqual([]);
    expect(wrapper.find('[data-testid="bulk-bar"]').exists()).toBe(false);

    // the selection belongs to the entry shown
    await boxes()[1]!.trigger("click");
    expect(checked()).toEqual(["Episode 4"]);
    await wrapper.setProps({ animeId: 13, episodes: makeEpisodes(5, 13, 1) });
    expect(checked()).toEqual([]);
  });

  it("doesn't make chapters selectable", async () => {
    wrapper = mount(ChapterList, {
      props: { mangaId: 34, chapters: makeChapters(2) },
      global: { plugins: [pinia] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="item-select"]').exists()).toBe(false);
  });
});
