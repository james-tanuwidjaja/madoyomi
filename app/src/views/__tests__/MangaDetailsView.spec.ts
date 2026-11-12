import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Chapter, Manga, MangaRefresh } from "../../api/types";
import { queryKeys } from "../../composables/queryKeys";
import { resetAutoRefresh } from "../../composables/useEntries";
import { makeChapters, makeManga, makeMangaSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchManga: vi.fn(),
  fetchChapters: vi.fn(),
  refreshManga: vi.fn(),
  setInLibrary: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import MangaDetailsView from "../MangaDetailsView.vue";

/* A manga's details against mocked operations (refreshManga's own two-step logic is in entries.spec.ts). */
const mangapill = makeMangaSource();

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let stored: Manga;
let chapters: Chapter[];

function refreshResult(over: Partial<MangaRefresh> = {}): MangaRefresh {
  return { manga: stored, chapters, error: null, ...over };
}

async function mountDetails(id = 34) {
  wrapper = mountView(MangaDetailsView, { pinia, queryClient }, { id });
  await flushPromises();
}

const names = () => wrapper.findAll('[data-testid="item-row"] .name').map((n) => n.text());
const button = (action: string) => wrapper.find(`[data-action="${action}"]`);

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  resetAutoRefresh();
  stored = makeManga();
  chapters = makeChapters(3);
  ops.fetchSources.mockResolvedValue([mangapill]);
  ops.fetchManga.mockImplementation(async () => stored);
  ops.fetchChapters.mockImplementation(async () => chapters);
  ops.refreshManga.mockImplementation(async () => refreshResult());
  ops.setInLibrary.mockImplementation(async (_k: string, _id: number, inLibrary: boolean) => {
    stored = { ...stored, inLibrary };
    return stored;
  });
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("MangaDetailsView", () => {
  it("shows the stored details and chapters", async () => {
    await mountDetails();

    expect(wrapper.find('[data-testid="entry-title"]').text()).toBe("One Piece");
    // the author and the artist are the same person: named once
    expect(wrapper.find('[data-testid="entry-people"]').text()).toBe("Eiichiro Oda");
    expect(wrapper.find('[data-testid="entry-source"]').text()).toBe("MangaPill");
    expect(names()).toEqual(["Chapter 3", "Chapter 2", "Chapter 1"]);
    expect(ops.refreshManga).not.toHaveBeenCalled();
  });

  it("refreshes a manga that was never fetched, and shows what came back", async () => {
    stored = makeManga({ initialized: false });
    chapters = [];
    ops.refreshManga.mockImplementation(async () => {
      stored = makeManga({ status: "COMPLETED" });
      chapters = makeChapters(5);
      return refreshResult();
    });
    await mountDetails();

    expect(ops.refreshManga).toHaveBeenCalledWith(34);
    expect(wrapper.find('[data-testid="entry-status"]').text()).toBe("Completed");
    expect(names()).toHaveLength(5);
  });

  it("keeps the stored data with the source's error", async () => {
    await mountDetails();
    ops.refreshManga.mockResolvedValueOnce(
      refreshResult({
        error: new ApiError({
          kind: "graphql",
          code: "SOURCE_FAILED",
          message: "Unable to resolve host",
        }),
      }),
    );

    await button("refresh").trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="refresh-error"]').text()).toContain(
      "Couldn't refresh from MangaPill",
    );
    expect(names()).toHaveLength(3);
  });

  it("adds to and removes from the library", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    await mountDetails();

    await button("library").trigger("click");
    await flushPromises();
    expect(ops.setInLibrary).toHaveBeenCalledWith("manga", 34, true);
    expect(wrapper.find('[data-testid="in-library"]').exists()).toBe(true);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.library("manga") });

    await button("library").trigger("click");
    await flushPromises();
    expect(ops.setInLibrary).toHaveBeenLastCalledWith("manga", 34, false);
    expect(wrapper.find('[data-testid="in-library"]').exists()).toBe(false);
  });

  it("filters the chapters by scanlator", async () => {
    chapters = makeChapters(3).map((c, i) => ({ ...c, scanlator: i === 1 ? "Asura" : "TCB" }));
    await mountDetails();

    await wrapper.find('[data-testid="item-scanlator"]').setValue("Asura");
    expect(names()).toEqual(["Chapter 2"]);
  });

  it("starts reading at the first chapter, and continues at the first unread one (Phase 5)", async () => {
    await mountDetails();
    const resume = () => wrapper.find('[data-action="resume"]');
    expect(resume().text()).toBe("Start reading");
    expect(resume().attributes("href")).toBe("/manga/34/read/5001");
    wrapper.unmount();

    chapters = makeChapters(3).map((c) =>
      c.sourceOrder === 1
        ? { ...c, isRead: true }
        : c.sourceOrder === 2
          ? { ...c, lastPageRead: 6 }
          : c,
    );
    queryClient.clear();
    await mountDetails();
    expect(resume().text()).toBe("Continue: Chapter 2");
    expect(resume().attributes("href")).toBe("/manga/34/read/5002");
    wrapper.unmount();

    chapters = makeChapters(2).map((c) => ({ ...c, isRead: true }));
    queryClient.clear();
    await mountDetails();
    expect(resume().exists()).toBe(false);
  });

  it("says when the source isn't installed, and still shows what is stored", async () => {
    ops.fetchSources.mockResolvedValue([]);
    stored = makeManga({ initialized: false });
    await mountDetails();

    expect(ops.refreshManga).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="entry-source"]').text()).toBe("Source not installed");
    expect(wrapper.find('[data-testid="source-unusable"]').text()).toContain(
      "Its source isn't installed",
    );
    expect(wrapper.find('[data-action="open-extensions"]').exists()).toBe(false);
    expect(names()).toHaveLength(3);
  });
});
