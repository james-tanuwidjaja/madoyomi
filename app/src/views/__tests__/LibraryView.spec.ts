import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { LibraryEntry, MediaKind } from "../../api/types";
import { LIBRARY_SORT_KEY } from "../../stores/librarySort";
import { useMediaKindStore } from "../../stores/mediaKind";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({ fetchLibrary: vi.fn() }));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import LibraryView from "../LibraryView.vue";

/* The Library against a mocked `fetchLibrary`. */
const entry = (kind: MediaKind, id: number, title: string, inLibraryAt: number): LibraryEntry => ({
  kind,
  id,
  title,
  thumbnailUrl: `/api/v1/${kind}/${id}/thumbnail`,
  inLibrary: true,
  inLibraryAt,
});

const libraries: Record<MediaKind, LibraryEntry[]> = { anime: [], manga: [] };

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
});
