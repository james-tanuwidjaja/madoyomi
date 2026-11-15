import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { HistoryItem, HistoryPage, MediaKind } from "../../api/types";
import { queryKeys } from "../../composables/queryKeys";
import { HISTORY_PAGE } from "../../composables/useHistory";
import { useMediaKindStore } from "../../stores/mediaKind";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchHistory: vi.fn(),
  deleteHistory: vi.fn(),
  clearHistory: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import HistoryView from "../HistoryView.vue";

/* History (Phase 6 Stage 2) against mocked history calls: the kind's list by day, resume, remove, clear, paging. */
const today = (h: number) => {
  const d = new Date();
  d.setHours(h, 5, 0, 0);
  return d.getTime() / 1000;
};

function item(
  kind: MediaKind,
  id: number,
  at: number,
  over: Partial<HistoryItem> = {},
): HistoryItem {
  return {
    kind,
    entry: { kind, id, title: `${kind} ${id}`, thumbnailUrl: null, inLibrary: true },
    item:
      kind === "anime"
        ? {
            id: id * 10,
            name: "Episode 3",
            done: false,
            progress: { positionMs: 600_000, durationMs: 1_440_000 },
          }
        : {
            id: id * 10,
            name: "Chapter 5",
            done: false,
            pages: { lastPageRead: 12, pageCount: 40 },
          },
    resume:
      kind === "anime"
        ? {
            id: id * 10,
            name: "Episode 3",
            done: false,
            progress: { positionMs: 600_000, durationMs: 1_440_000 },
          }
        : {
            id: id * 10,
            name: "Chapter 5",
            done: false,
            pages: { lastPageRead: 12, pageCount: 40 },
          },
    at,
    ...over,
  };
}

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let lists: Record<MediaKind, HistoryItem[]>;

async function mountHistory() {
  wrapper = mountView(HistoryView, { pinia, queryClient });
  await flushPromises();
}

const rows = () =>
  wrapper
    .findAll('[data-testid="history-row"]')
    .map((r) => [
      r.find('[data-testid="history-title"]').text(),
      r.find('[data-testid="history-item"]').text(),
      r.find('[data-testid="history-progress"]').exists()
        ? r.find('[data-testid="history-progress"]').text()
        : "",
    ]);

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  lists = {
    anime: [
      item("anime", 1, today(20)),
      item("anime", 2, today(9)),
      item("anime", 3, today(9) - 86400),
    ],
    manga: [item("manga", 7, today(10))],
  };
  ops.fetchHistory.mockImplementation(
    async (kind: MediaKind, offset: number, limit: number): Promise<HistoryPage> => ({
      items: lists[kind].slice(offset, offset + limit),
      hasMore: lists[kind].length > offset + limit,
    }),
  );
  ops.deleteHistory.mockImplementation(async (kind: MediaKind, ids: number[]) => {
    lists[kind] = lists[kind].filter((i) => !ids.includes(i.entry.id));
    return ids.length;
  });
  ops.clearHistory.mockImplementation(async (kind: MediaKind) => {
    const n = lists[kind].length;
    lists[kind] = [];
    return n;
  });
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("HistoryView", () => {
  it("shows the kind's history by day, each row with its episode, progress and resume", async () => {
    await mountHistory();

    expect(ops.fetchHistory).toHaveBeenCalledWith("anime", 0, HISTORY_PAGE, expect.anything());
    expect(wrapper.findAll('[data-testid="history-day"] h2').map((h) => h.text())).toEqual([
      "Today",
      "Yesterday",
    ]);
    expect(rows()).toEqual([
      ["anime 1", "Episode 3", "14:00 left"],
      ["anime 2", "Episode 3", "14:00 left"],
      ["anime 3", "Episode 3", "14:00 left"],
    ]);
    const resume = wrapper.find('[data-action="history-resume"]');
    expect(resume.text()).toBe("Resume");
    expect(resume.attributes("href")).toBe("/anime/1?play=10");
    expect(wrapper.find('[data-testid="history-time"]').text()).toMatch(/^8:05\sPM$/);
  });

  it("follows the anime/manga switch, and resumes a chapter in the reader", async () => {
    await mountHistory();
    useMediaKindStore().set("manga");
    await flushPromises();

    expect(rows()).toEqual([["manga 7", "Chapter 5", "Page 13 of 40"]]);
    expect(wrapper.find('[data-action="history-resume"]').attributes("href")).toBe(
      "/manga/7/read/70",
    );
    expect(wrapper.text()).toContain("The manga you read last");
  });

  it("names the next episode when the last one is watched", async () => {
    lists.anime = [
      item("anime", 1, today(20), {
        item: { id: 10, name: "Episode 3", done: true },
        resume: { id: 11, name: "Episode 4", done: false },
      }),
    ];
    await mountHistory();

    expect(rows()).toEqual([["anime 1", "Episode 3", "Watched"]]);
    const resume = wrapper.find('[data-action="history-resume"]');
    expect(resume.text()).toBe("Next: Episode 4");
    expect(resume.attributes("href")).toBe("/anime/1?play=11");
  });

  it("removes one entry, and clears all after a second click", async () => {
    await mountHistory();
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");

    await wrapper.findAll('[data-action="remove-history"]')[1]!.trigger("click");
    await flushPromises();
    expect(ops.deleteHistory).toHaveBeenCalledWith("anime", [2]);
    expect(rows().map((r) => r[0])).toEqual(["anime 1", "anime 3"]);
    expect(invalidate.mock.calls.map((c) => (c[0] as { queryKey: unknown }).queryKey)).toEqual([
      queryKeys.library("anime"),
      queryKeys.history("anime"),
      queryKeys.updates("anime"),
    ]);

    const clear = wrapper.find('[data-action="clear-history"]');
    await clear.trigger("click");
    expect(clear.text()).toBe("Clear all anime history?");
    expect(ops.clearHistory).not.toHaveBeenCalled();
    await clear.trigger("click");
    await flushPromises();
    expect(ops.clearHistory).toHaveBeenCalledWith("anime");
    expect(wrapper.find('[data-testid="history-empty"]').text()).toContain("Nothing watched yet.");
    expect(wrapper.find('[data-action="clear-history"]').exists()).toBe(false);
  });

  it("loads the next page with Show more", async () => {
    lists.anime = Array.from({ length: HISTORY_PAGE + 2 }, (_, i) =>
      item("anime", i + 1, today(20) - i),
    );
    await mountHistory();
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(HISTORY_PAGE);

    await wrapper.find('[data-action="history-more"]').trigger("click");
    await flushPromises();
    expect(ops.fetchHistory).toHaveBeenLastCalledWith(
      "anime",
      HISTORY_PAGE,
      HISTORY_PAGE,
      expect.anything(),
    );
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(HISTORY_PAGE + 2);
    expect(wrapper.find('[data-action="history-more"]').exists()).toBe(false);
  });

  it("says when there is nothing yet", async () => {
    lists.anime = [];
    await mountHistory();

    expect(wrapper.find('[data-testid="history-empty"]').text()).toContain(
      "Episodes you play show up here",
    );
  });
});
