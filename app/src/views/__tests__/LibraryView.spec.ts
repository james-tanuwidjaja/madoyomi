import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { LibraryCategory, LibraryEntry, MediaKind } from "../../api/types";
import { LIBRARY_CATEGORY_KEY } from "../../stores/libraryCategory";
import { LIBRARY_SORT_KEY } from "../../stores/librarySort";
import { makeCategory, makeDefaultCategory } from "../../test/fixtures";
import { useMediaKindStore } from "../../stores/mediaKind";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchLibrary: vi.fn(),
  fetchCategories: vi.fn(),
  setCategoryMeta: vi.fn(),
  setEntriesCategories: vi.fn(),
  setManyInLibrary: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import LibraryView from "../LibraryView.vue";

/* The Library against a mocked `fetchLibrary` and, since Phase 6, `fetchCategories` and the category calls. */
const entry = (
  kind: MediaKind,
  id: number,
  title: string,
  inLibraryAt: number,
  categoryIds: number[] = [],
  unreadCount = 0,
  lastReadAt = 0,
): LibraryEntry => ({
  kind,
  id,
  title,
  thumbnailUrl: `/api/v1/${kind}/${id}/thumbnail`,
  inLibrary: true,
  inLibraryAt,
  categoryIds,
  unreadCount,
  lastReadAt,
});

const libraries: Record<MediaKind, LibraryEntry[]> = { anime: [], manga: [] };
const categoryLists: Record<MediaKind, LibraryCategory[]> = { anime: [], manga: [] };

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;

async function mountLibrary() {
  wrapper = mountView(LibraryView, { pinia, queryClient });
  await flushPromises();
}

const titles = () =>
  wrapper.findAll('[data-testid="media-card"]').map((c) => c.attributes("title"));

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  libraries.anime = [
    entry("anime", 1, "Bocchi the Rock!", 300),
    entry("anime", 2, "akira", 100),
    entry("anime", 3, "Cowboy Bebop", 200),
  ];
  libraries.manga = [entry("manga", 9, "One Piece", 50)];
  ops.fetchLibrary.mockImplementation(async (kind: MediaKind) => libraries[kind]);
  categoryLists.anime = [makeDefaultCategory()];
  categoryLists.manga = [makeDefaultCategory()];
  ops.fetchCategories.mockImplementation(async (kind: MediaKind) => categoryLists[kind]);
  ops.setCategoryMeta.mockResolvedValue(undefined);
  ops.setEntriesCategories.mockResolvedValue(undefined);
  ops.setManyInLibrary.mockResolvedValue(undefined);
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("LibraryView", () => {
  it("shows the saved entries, newest additions first, without the in-library badge", async () => {
    await mountLibrary();

    expect(ops.fetchLibrary).toHaveBeenCalledWith("anime", expect.anything());
    expect(titles()).toEqual(["Bocchi the Rock!", "Cowboy Bebop", "akira"]);
    expect(wrapper.text()).toContain("3 saved anime");
    expect(wrapper.find('[data-testid="in-library"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="media-card"]').attributes("href")).toBe("/anime/1");
  });

  it("sorts by title or date added, either way, and keeps the sort", async () => {
    await mountLibrary();

    await wrapper.find('[data-testid="library-sort"]').setValue("title");
    expect(titles()).toEqual(["akira", "Bocchi the Rock!", "Cowboy Bebop"]);
    await wrapper.find('[data-action="library-direction"]').trigger("click");
    expect(titles()).toEqual(["Cowboy Bebop", "Bocchi the Rock!", "akira"]);
    expect(wrapper.find('[data-action="library-direction"]').text()).toBe("Z to A");

    // back to the date: newest first again, then flipped
    await wrapper.find('[data-testid="library-sort"]').setValue("added");
    expect(titles()).toEqual(["Bocchi the Rock!", "Cowboy Bebop", "akira"]);
    await wrapper.find('[data-action="library-direction"]').trigger("click");
    expect(titles()).toEqual(["akira", "Cowboy Bebop", "Bocchi the Rock!"]);
    expect(wrapper.find('[data-action="library-direction"]').text()).toBe("Oldest first");
    expect(JSON.parse(localStorage.getItem(LIBRARY_SORT_KEY)!).anime).toEqual({
      by: "added",
      desc: false,
    });
  });

  it("shows the unwatched/unread count on the cards above 0 (Phase 6 Stage 2)", async () => {
    libraries.anime = [entry("anime", 1, "A", 1, [], 12), entry("anime", 2, "B", 2, [], 0)];
    libraries.manga = [entry("manga", 9, "M", 1, [], 1)];
    await mountLibrary();

    const counts = () =>
      wrapper.findAll('[data-testid="media-card"]').map((c) => {
        const badge = c.find('[data-testid="unread-count"]');
        return badge.exists() ? [badge.text(), badge.attributes("aria-label")] : null;
      });
    expect(counts()).toEqual([null, ["12", "12 unwatched"]]);

    useMediaKindStore().set("manga");
    await flushPromises();
    expect(counts()).toEqual([["1", "1 unread"]]);
  });

  it("sorts by the unwatched count and by last watched, never-watched last (Phase 6 Stage 2)", async () => {
    libraries.anime = [
      entry("anime", 1, "A", 1, [], 3, 500),
      entry("anime", 2, "B", 2, [], 9, 0),
      entry("anime", 3, "C", 3, [], 0, 900),
    ];
    await mountLibrary();
    const sortSelect = wrapper.find('[data-testid="library-sort"]');
    expect(sortSelect.findAll("option").map((o) => o.text())).toEqual([
      "Date added",
      "Title",
      "Unwatched",
      "Last watched",
    ]);

    await sortSelect.setValue("unread");
    expect(titles()).toEqual(["B", "A", "C"]);
    expect(wrapper.find('[data-action="library-direction"]').text()).toBe("Most first");
    await wrapper.find('[data-action="library-direction"]').trigger("click");
    expect(titles()).toEqual(["C", "A", "B"]);

    await sortSelect.setValue("lastRead");
    expect(titles()).toEqual(["C", "A", "B"]);
    await wrapper.find('[data-action="library-direction"]').trigger("click");
    expect(titles()).toEqual(["A", "C", "B"]);
    expect(JSON.parse(localStorage.getItem(LIBRARY_SORT_KEY)!).anime).toEqual({
      by: "lastRead",
      desc: false,
    });

    useMediaKindStore().set("manga");
    await flushPromises();
    const options = wrapper.find('[data-testid="library-sort"]').findAll("option");
    expect(options.map((o) => o.text()).slice(2)).toEqual(["Unread", "Last read"]);
  });

  it("shows the other kind's library after the switch", async () => {
    await mountLibrary();

    useMediaKindStore().set("manga");
    await flushPromises();

    expect(ops.fetchLibrary).toHaveBeenLastCalledWith("manga", expect.anything());
    expect(titles()).toEqual(["One Piece"]);
    expect(wrapper.text()).toContain("1 saved manga");
  });

  it("links an empty library to Browse", async () => {
    libraries.anime = [];
    await mountLibrary();

    const empty = wrapper.find('[data-testid="library-empty"]');
    expect(empty.text()).toContain("Your anime library is empty.");
    expect(empty.find('[data-action="browse"]').attributes("href")).toBe("/browse");
    expect(wrapper.find('[data-testid="library-grid"]').exists()).toBe(false);
  });

  it("shows an error with a retry", async () => {
    ops.fetchLibrary.mockRejectedValueOnce(
      new ApiError({ kind: "graphql", code: "INTERNAL", message: "boom" }),
    );
    await mountLibrary();

    expect(wrapper.text()).toContain("Couldn't load your anime library");
    await wrapper.find('[role="alert"] button').trigger("click");
    await flushPromises();
    expect(titles()).toHaveLength(3);
  });

  describe("categories (Phase 6 Stage 1)", () => {
    const tabs = () =>
      wrapper
        .findAll('[data-testid="category-tab"]')
        .map((t) => [t.find(".tab-name").text(), t.find(".tab-count").text()]);
    const bodyButton = (action: string) =>
      document.body.querySelector<HTMLButtonElement>(`[data-action="${action}"]`)!;

    beforeEach(() => {
      categoryLists.anime = [
        makeDefaultCategory(),
        makeCategory({ id: 1, order: 1, name: "Watching" }),
        makeCategory({
          id: 2,
          order: 2,
          name: "Later",
          meta: [{ key: "madoyomi.library.sort", value: "title:asc" }],
        }),
      ];
      libraries.anime = [
        entry("anime", 1, "Bocchi the Rock!", 300, [1]),
        entry("anime", 2, "akira", 100, [1, 2]),
        entry("anime", 3, "Cowboy Bebop", 200),
        entry("anime", 4, "Dandadan", 400, [2]),
      ];
    });

    it("shows a tab per category with its count, Default only while it holds entries", async () => {
      await mountLibrary();

      expect(tabs()).toEqual([
        ["Default", "1"],
        ["Watching", "2"],
        ["Later", "2"],
      ]);
      expect(titles()).toEqual(["Cowboy Bebop"]);
      expect(wrapper.text()).toContain("4 saved anime");

      await wrapper.findAll('[data-testid="category-tab"]')[1]!.trigger("click");
      expect(titles()).toEqual(["Bocchi the Rock!", "akira"]);
      expect(JSON.parse(localStorage.getItem(LIBRARY_CATEGORY_KEY)!).anime).toBe(1);

      // with every entry in a category, Default's tab goes
      libraries.anime = libraries.anime.map((e) => (e.id === 3 ? { ...e, categoryIds: [2] } : e));
      await queryClient.invalidateQueries();
      await flushPromises();
      expect(tabs().map((t) => t[0])).toEqual(["Watching", "Later"]);
    });

    it("opens the remembered tab, and moves between tabs with the arrow keys", async () => {
      localStorage.setItem(LIBRARY_CATEGORY_KEY, JSON.stringify({ anime: 2 }));
      await mountLibrary();

      expect(wrapper.find('[aria-selected="true"]').text()).toContain("Later");
      await wrapper.find('[role="tablist"]').trigger("keydown", { key: "ArrowRight" });
      expect(wrapper.find('[aria-selected="true"]').text()).toContain("Default");
      await wrapper.find('[role="tablist"]').trigger("keydown", { key: "ArrowLeft" });
      expect(wrapper.find('[aria-selected="true"]').text()).toContain("Later");
    });

    it("keeps a sort per category in its meta, and a category without one uses the last chosen", async () => {
      localStorage.setItem(LIBRARY_CATEGORY_KEY, JSON.stringify({ anime: 2 }));
      await mountLibrary();

      // Later's own sort: by title, A to Z
      expect(titles()).toEqual(["akira", "Dandadan"]);
      expect(wrapper.find('[data-action="library-direction"]').text()).toBe("A to Z");

      await wrapper.find('[data-action="library-direction"]').trigger("click");
      expect(titles()).toEqual(["Dandadan", "akira"]);
      expect(ops.setCategoryMeta).toHaveBeenLastCalledWith(
        "anime",
        2,
        "madoyomi.library.sort",
        "title:desc",
      );

      // Watching has none: the last chosen one (title, Z to A) until it gets its own
      await wrapper.findAll('[data-testid="category-tab"]')[1]!.trigger("click");
      expect(titles()).toEqual(["Bocchi the Rock!", "akira"]);
      await wrapper.find('[data-testid="library-sort"]').setValue("added");
      expect(titles()).toEqual(["Bocchi the Rock!", "akira"]);
      expect(ops.setCategoryMeta).toHaveBeenLastCalledWith(
        "anime",
        1,
        "madoyomi.library.sort",
        "added:desc",
      );
      // Later kept its own
      await wrapper.findAll('[data-testid="category-tab"]')[2]!.trigger("click");
      expect(titles()).toEqual(["Dandadan", "akira"]);
    });

    it("selects entries by click and Shift+click, and sets their categories at once", async () => {
      localStorage.setItem(LIBRARY_CATEGORY_KEY, JSON.stringify({ anime: 1 }));
      libraries.anime[2]!.categoryIds = [1];
      await mountLibrary();
      // Watching, newest first: Bocchi (300), Cowboy (200), akira (100)
      expect(titles()).toEqual(["Bocchi the Rock!", "Cowboy Bebop", "akira"]);

      await wrapper.find('[data-action="select"]').trigger("click");
      const cards = () => wrapper.findAll('[data-testid="media-card"]');
      expect(cards()[0]!.element.tagName).toBe("BUTTON");
      await cards()[0]!.trigger("click");
      await cards()[2]!.trigger("click", { shiftKey: true });
      expect(wrapper.find('[data-testid="selected-count"]').text()).toBe("3 selected");
      await cards()[1]!.trigger("click");
      expect(wrapper.find('[data-testid="selected-count"]').text()).toBe("2 selected");
      expect(cards()[1]!.attributes("aria-pressed")).toBe("false");

      await wrapper.find('[data-action="set-categories"]').trigger("click");
      await flushPromises();
      const choices = [
        ...document.body.querySelectorAll<HTMLInputElement>('[data-testid="picker-choice"] input'),
      ];
      // Bocchi and akira: both in Watching, only akira in Later
      expect(choices.map((c) => c.dataset.state)).toEqual(["all", "some"]);
      choices[1]!.click();
      choices[0]!.click();
      bodyButton("picker-save").click();
      await flushPromises();

      expect(ops.setEntriesCategories).toHaveBeenCalledWith("anime", [1, 2], {
        addToCategories: [2],
        removeFromCategories: [1],
      });
      expect(wrapper.find('[data-testid="library-bulk"]').exists()).toBe(false);
    });

    it("removes the selected entries from the library after a second click", async () => {
      await mountLibrary();
      await wrapper.findAll('[data-testid="category-tab"]')[1]!.trigger("click");
      await wrapper.find('[data-action="select"]').trigger("click");
      await wrapper.find('[data-action="select-all"]').trigger("click");
      expect(wrapper.find('[data-testid="selected-count"]').text()).toBe("2 selected");

      await wrapper.find('[data-action="remove-selected"]').trigger("click");
      expect(ops.setManyInLibrary).not.toHaveBeenCalled();
      expect(wrapper.find('[data-action="remove-selected"]').text()).toBe("Remove 2 entries?");
      await wrapper.find('[data-action="remove-selected"]').trigger("click");
      await flushPromises();

      expect(ops.setManyInLibrary).toHaveBeenCalledWith("anime", [1, 2], false);
      expect(wrapper.find('[data-testid="library-bulk"]').exists()).toBe(false);
    });

    it("ends the selection with Esc, and cards link to the details again", async () => {
      await mountLibrary();
      await wrapper.find('[data-action="select"]').trigger("click");
      await wrapper.find('[data-testid="media-card"]').trigger("click");
      await wrapper.find(".body").trigger("keydown", { key: "Escape" });

      expect(wrapper.find('[data-testid="library-bulk"]').exists()).toBe(false);
      expect(wrapper.find('[data-testid="media-card"]').attributes("href")).toBe("/anime/3");
    });
  });
});
