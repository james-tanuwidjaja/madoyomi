import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { MediaKind } from "../../api/types";
import { useMediaKindStore } from "../../stores/mediaKind";
import { makeDownloadItem, makeDownloadQueue } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({ fetchUpdateMarks: vi.fn(), fetchDownloadQueue: vi.fn() }));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import AppShell from "../AppShell.vue";

/*
 * The sidebar's Updates entry (Phase 6 Stage 3): the kind's new items since its entries were opened; Downloads
 * (Stage 4): the kind's queued and downloading items.
 */
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let marks: Record<MediaKind, Record<number, number>>;

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  marks = { anime: { 1: 2, 3: 1 }, manga: {} };
  ops.fetchUpdateMarks.mockImplementation(async (kind: MediaKind) => marks[kind]);
  ops.fetchDownloadQueue.mockResolvedValue(makeDownloadQueue());
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
});

describe("AppShell", () => {
  it("lists Updates after Library, with the kind's count of new items", async () => {
    wrapper = mountView(AppShell, { pinia, queryClient });
    await flushPromises();

    expect(wrapper.findAll(".nav-list [data-nav]").map((n) => n.attributes("data-nav"))).toEqual([
      "library",
      "updates",
      "history",
      "browse",
      "extensions",
      "downloads",
    ]);
    expect(wrapper.find('[data-testid="nav-badge-updates"]').text()).toBe("3");

    useMediaKindStore().set("manga");
    await flushPromises();
    expect(wrapper.find('[data-testid="nav-badge-updates"]').exists()).toBe(false);

    marks.manga = { 9: 150 };
    await queryClient.invalidateQueries();
    await flushPromises();
    expect(wrapper.find('[data-testid="nav-badge-updates"]').text()).toBe("99+");
  });

  it("shows how many of the kind's downloads are queued or downloading", async () => {
    ops.fetchDownloadQueue.mockResolvedValue(
      makeDownloadQueue([
        makeDownloadItem({ id: 1, state: "DOWNLOADING" }),
        makeDownloadItem({ id: 2 }),
        makeDownloadItem({ id: 3, state: "PAUSED" }),
        makeDownloadItem({ id: 4, kind: "anime" }),
      ]),
    );
    wrapper = mountView(AppShell, { pinia, queryClient });
    await flushPromises();
    // anime first (the store's default)
    expect(wrapper.find('[data-testid="nav-badge-downloads"]').text()).toBe("1");
    useMediaKindStore().set("manga");
    await flushPromises();
    expect(wrapper.find('[data-testid="nav-badge-downloads"]').text()).toBe("2");
  });
});
