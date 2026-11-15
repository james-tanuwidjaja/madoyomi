import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { LibraryUpdateSettings, MediaKind, UpdatesItem, UpdatesPage } from "../../api/types";
import { DEFAULT_LIBRARY_UPDATE_SETTINGS } from "../../composables/useLibraryUpdates";
import { useMediaKindStore } from "../../stores/mediaKind";
import { makeLibraryCheck } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchUpdates: vi.fn(),
  fetchLibraryCheck: vi.fn(),
  fetchLibraryUpdateSettings: vi.fn(),
  fetchUpdateMarks: vi.fn(),
  clearUpdateMarks: vi.fn(),
  startLibraryUpdate: vi.fn(),
  stopLibraryUpdate: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import UpdatesView from "../UpdatesView.vue";

/* Updates (Phase 6 Stage 3) against mocked calls: the kind's new items by day, opening one, the marks, the check. */
const today = (h: number) => {
  const d = new Date();
  d.setHours(h, 5, 0, 0);
  return d.getTime() / 1000;
};

function item(kind: MediaKind, id: number, entryId: number, at: number, done = false): UpdatesItem {
  return {
    kind,
    entry: { kind, id: entryId, title: `${kind} ${entryId}`, thumbnailUrl: null, inLibrary: true },
    item:
      kind === "anime"
        ? { id, name: `Episode ${id}`, done, progress: { positionMs: 0, durationMs: 0 } }
        : { id, name: `Chapter ${id}`, done, pages: { lastPageRead: 0, pageCount: -1 } },
    at,
  };
}

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let lists: Record<MediaKind, UpdatesItem[]>;
let marks: Record<MediaKind, Record<number, number>>;
let settings: LibraryUpdateSettings;

async function mountUpdates() {
  wrapper = mountView(UpdatesView, { pinia, queryClient });
  await flushPromises();
}

const rows = () =>
  wrapper
    .findAll('[data-testid="updates-row"]')
    .map((r) => [
      r.find('[data-testid="updates-title"]').text(),
      r.find('[data-testid="updates-item"]').text(),
      r.find('[data-action="updates-open"]').text(),
    ]);

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  lists = {
    anime: [
      item("anime", 12, 1, today(20)),
      item("anime", 11, 1, today(20), true),
      item("anime", 30, 3, today(9) - 86400),
    ],
    manga: [item("manga", 829, 7, today(10))],
  };
  marks = { anime: { 1: 1 }, manga: {} };
  settings = { ...DEFAULT_LIBRARY_UPDATE_SETTINGS };
  ops.fetchUpdates.mockImplementation(
    async (kind: MediaKind, offset: number, limit: number): Promise<UpdatesPage> => ({
      items: lists[kind].slice(offset, offset + limit),
      hasMore: lists[kind].length > offset + limit,
    }),
  );
  ops.fetchLibraryCheck.mockResolvedValue(makeLibraryCheck());
  ops.fetchLibraryUpdateSettings.mockImplementation(async () => settings);
  ops.fetchUpdateMarks.mockImplementation(async (kind: MediaKind) => ({ ...marks[kind] }));
  ops.clearUpdateMarks.mockImplementation(async (kind: MediaKind, ids?: number[]) => {
    const before = Object.keys(marks[kind]).length;
    marks[kind] = ids
      ? Object.fromEntries(Object.entries(marks[kind]).filter(([id]) => !ids.includes(Number(id))))
      : {};
    return before - Object.keys(marks[kind]).length;
  });
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
});

describe("UpdatesView", () => {
  it("lists the kind's new episodes by day, newest first, and follows the switch", async () => {
    await mountUpdates();

    expect(wrapper.findAll('[data-testid="updates-day"] h2').map((h) => h.text())).toEqual([
      "Today",
      "Yesterday",
    ]);
    expect(rows()).toEqual([
      ["anime 1", "Episode 12", "Play"],
      ["anime 1", "Episode 11", "Play"],
      ["anime 3", "Episode 30", "Play"],
    ]);
    expect(wrapper.findAll('[data-testid="updates-row"]')[1]!.classes()).toContain("done");
    expect(wrapper.find('[data-action="updates-open"]').attributes("href")).toBe(
      "/anime/1?play=12",
    );
    expect(wrapper.text()).toContain("Scheduled checks are off");

    useMediaKindStore().set("manga");
    await flushPromises();
    expect(rows()).toEqual([["manga 7", "Chapter 829", "Read"]]);
    expect(wrapper.find('[data-action="updates-open"]').attributes("href")).toBe(
      "/manga/7/read/829",
    );
  });

  it("clears an entry's New mark when one of its items is opened, and all with Mark all seen", async () => {
    await mountUpdates();
    expect(wrapper.find('[data-action="mark-all-seen"]').exists()).toBe(true);

    // entry 3 has no mark: nothing to clear
    await wrapper.findAll('[data-action="updates-open"]')[2]!.trigger("click");
    await flushPromises();
    expect(ops.clearUpdateMarks).not.toHaveBeenCalled();

    await wrapper.findAll('[data-action="updates-open"]')[0]!.trigger("click");
    await flushPromises();
    expect(ops.clearUpdateMarks).toHaveBeenCalledWith("anime", [1]);
    expect(wrapper.find('[data-action="mark-all-seen"]').exists()).toBe(false);

    marks.anime = { 3: 2 };
    await queryClient.invalidateQueries();
    await flushPromises();
    await wrapper.find('[data-action="mark-all-seen"]').trigger("click");
    await flushPromises();
    expect(ops.clearUpdateMarks).toHaveBeenLastCalledWith("anime", undefined);
  });

  it("says what the last check found and when the next comes, lists the failures, and starts and stops a check", async () => {
    const now = Date.now();
    settings = { ...settings, intervalHours: 6 };
    ops.fetchLibraryCheck.mockResolvedValue(
      makeLibraryCheck({
        trigger: "SCHEDULED",
        startedAt: String(now - 3 * 60_000),
        finishedAt: String(now - 2 * 60_000),
        total: 5,
        done: 5,
        newEpisodes: 2,
        failures: [{ entry: { kind: "ANIME", id: 3, title: "anime 3" }, error: "HTTP 503" }],
        nextScheduledAt: String(new Date().setHours(23, 0, 0, 0)),
      }),
    );
    ops.startLibraryUpdate.mockResolvedValue(
      makeLibraryCheck({ running: true, startedAt: String(now), total: 5 }),
    );
    ops.stopLibraryUpdate.mockResolvedValue(
      makeLibraryCheck({ cancelled: true, startedAt: String(now), finishedAt: String(now) }),
    );
    await mountUpdates();

    expect(wrapper.text()).toContain(
      "Checked 2 min ago: 2 new episodes · 1 entry couldn't be checked",
    );
    expect(wrapper.text()).toContain("Every 6 h, next at 11:00 PM");
    expect(wrapper.find('[data-testid="update-failures"]').text()).toContain("anime 3: HTTP 503");

    await wrapper.find('[data-action="update-library"]').trigger("click");
    await flushPromises();
    expect(ops.startLibraryUpdate).toHaveBeenCalledWith("anime", undefined);
    expect(wrapper.text()).toContain("Checking 0 of 5…");

    await wrapper.find('[data-action="stop-update"]').trigger("click");
    await flushPromises();
    expect(ops.stopLibraryUpdate).toHaveBeenCalled();
    expect(wrapper.find('[data-action="update-library"]').exists()).toBe(true);
  });

  it("says when there is nothing new yet", async () => {
    lists.anime = [];
    await mountUpdates();
    expect(wrapper.find('[data-testid="updates-empty"]').text()).toContain("No new episodes yet.");
  });
});
