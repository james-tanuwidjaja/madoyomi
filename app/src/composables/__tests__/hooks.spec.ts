import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick, ref } from "vue";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { useBackendStore } from "../../stores/backend";
import { healthyStatus } from "../../test/backendStatus";
import { withSetup } from "../../test/withSetup";
import type { SourcePage } from "../../api/types";

const ops = vi.hoisted(() => ({
  browseSource: vi.fn(),
  fetchExtensionStores: vi.fn(),
  addRepo: vi.fn(),
  setInLibrary: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  browseSource: ops.browseSource,
  fetchExtensionStores: ops.fetchExtensionStores,
  addRepo: ops.addRepo,
  setInLibrary: ops.setInLibrary,
}));

import { useSourceBrowse } from "../useSources";
import { useSetInLibrary } from "../useEntries";
import { queryKeys } from "../queryKeys";
import { addRepoDetectingKind, guessRepoKind, useExtensionStores } from "../useExtensions";
import { ApiError } from "../../api/client";
import { makeAnime, makeStore } from "../../test/fixtures";

let pinia: Pinia;
let queryClient: QueryClient;
let unmount: (() => void) | null = null;

function page(n: number, hasNextPage: boolean): SourcePage {
  return {
    items: [
      { kind: "anime", id: n * 10, title: `Entry ${n}`, thumbnailUrl: null, inLibrary: false },
    ],
    hasNextPage,
  };
}

function setReady() {
  const backend = useBackendStore();
  backend.info = { baseUrl: "http://127.0.0.1:50266", token: "t" };
  backend.status = healthyStatus(2);
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
});

afterEach(() => {
  unmount?.();
  unmount = null;
  queryClient.clear();
  vi.clearAllMocks();
});

describe("useSourceBrowse", () => {
  it("loads page 1, then the next page while the last says hasNextPage, then stops", async () => {
    setReady();
    ops.browseSource.mockImplementation(async (p: { page: number }) => page(p.page, p.page < 2));

    const { result, unmount: u } = withSetup(
      () => useSourceBrowse({ kind: "anime", sourceId: "123", type: "POPULAR" }),
      { pinia, queryClient },
    );
    unmount = u;
    await vi.waitFor(() => expect(result.data.value?.pages).toHaveLength(1));

    expect(ops.browseSource).toHaveBeenLastCalledWith({
      kind: "anime",
      sourceId: "123",
      type: "POPULAR",
      page: 1,
      query: undefined,
      filters: undefined,
    });
    expect(result.hasNextPage.value).toBe(true);

    await result.fetchNextPage();
    expect(ops.browseSource).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }));
    expect(result.data.value?.pages.map((p) => p.items[0].id)).toEqual([10, 20]);
    expect(result.hasNextPage.value).toBe(false);
  });

  it("starts over at page 1 when the search changes", async () => {
    setReady();
    ops.browseSource.mockImplementation(async (p: { page: number }) => page(p.page, true));
    const query = ref("naruto");

    const { result, unmount: u } = withSetup(
      () =>
        useSourceBrowse(() => ({
          kind: "manga",
          sourceId: "9",
          type: "SEARCH",
          query: query.value,
          filters: [{ position: 0, checkBoxState: true }],
        })),
      { pinia, queryClient },
    );
    unmount = u;
    await vi.waitFor(() => expect(result.data.value?.pages).toHaveLength(1));
    await result.fetchNextPage();
    expect(result.data.value?.pages).toHaveLength(2);

    query.value = "bleach";
    await nextTick();
    await vi.waitFor(() =>
      expect(ops.browseSource).toHaveBeenLastCalledWith(
        expect.objectContaining({ query: "bleach", page: 1 }),
      ),
    );
    await vi.waitFor(() => expect(result.data.value?.pages).toHaveLength(1));
    expect(ops.browseSource).toHaveBeenLastCalledWith(
      expect.objectContaining({ filters: [{ position: 0, checkBoxState: true }] }),
    );
  });

  it("asks for nothing without a source", async () => {
    setReady();
    const { unmount: u } = withSetup(() => useSourceBrowse(null), { pinia, queryClient });
    unmount = u;
    await flushPromises();

    expect(ops.browseSource).not.toHaveBeenCalled();
  });
});

describe("useExtensionStores", () => {
  it("waits for the backend, then loads once it is ready", async () => {
    ops.fetchExtensionStores.mockResolvedValue([]);
    const { result, unmount: u } = withSetup(() => useExtensionStores(), { pinia, queryClient });
    unmount = u;
    await flushPromises();
    expect(ops.fetchExtensionStores).not.toHaveBeenCalled();

    setReady();
    await vi.waitFor(() => expect(result.isSuccess.value).toBe(true));
    expect(ops.fetchExtensionStores).toHaveBeenCalledOnce();
  });
});

describe("addRepoDetectingKind", () => {
  const animeStore = makeStore({ indexUrl: "https://example.org/anime/index.pb", type: "ANIME" });
  const mismatch = (details: unknown) =>
    new ApiError({ kind: "graphql", code: "REPO_TYPE_MISMATCH", message: "mismatch", details });

  it("guesses anime from the URL, and manga otherwise", () => {
    expect(
      guessRepoKind("https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"),
    ).toBe("anime");
    expect(
      guessRepoKind("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"),
    ).toBe("manga");
  });

  it("tries the other kind once after a package mismatch", async () => {
    ops.addRepo.mockRejectedValueOnce(mismatch({ packages: ["a.animeextension.b"] }));
    ops.addRepo.mockResolvedValueOnce(animeStore);

    const outcome = await addRepoDetectingKind(
      { indexUrl: "  https://example.org/repo/index.min.json ", kind: "auto" },
      [],
    );

    expect(ops.addRepo.mock.calls.map((c) => c[0])).toEqual([
      { indexUrl: "https://example.org/repo/index.min.json", kind: "manga", format: undefined },
      { indexUrl: "https://example.org/repo/index.min.json", kind: "anime", format: undefined },
    ]);
    expect(outcome).toEqual({ store: animeStore, kind: "anime", detected: true, existed: false });
  });

  it("recognises a re-add by the URL the server stored", async () => {
    ops.addRepo.mockResolvedValue(animeStore);

    const outcome = await addRepoDetectingKind(
      { indexUrl: "https://example.org/anime/index.min.json", kind: "auto" },
      [animeStore.indexUrl],
    );

    expect(outcome.existed).toBe(true);
  });

  it("doesn't try the other kind when the repo is already added as one", async () => {
    // RepoService: "… is already added as a manga repo" has no package list
    ops.addRepo.mockRejectedValue(mismatch({ indexUrl: "x", type: "MANGA" }));

    await expect(
      addRepoDetectingKind({ indexUrl: "https://example.org/anime/index.pb", kind: "auto" }, []),
    ).rejects.toMatchObject({ code: "REPO_TYPE_MISMATCH" });
    expect(ops.addRepo).toHaveBeenCalledOnce();
  });

  it("doesn't try the other kind when the kind was chosen", async () => {
    ops.addRepo.mockRejectedValue(mismatch({ packages: ["a.b"] }));

    await expect(
      addRepoDetectingKind(
        { indexUrl: "https://example.org/x", kind: "manga", format: "JSON" },
        [],
      ),
    ).rejects.toBeInstanceOf(ApiError);
    expect(ops.addRepo).toHaveBeenCalledExactlyOnceWith({
      indexUrl: "https://example.org/x",
      kind: "manga",
      format: "JSON",
    });
  });
});

describe("useSetInLibrary", () => {
  it("marks that kind's cached browse cards only, and refetches the library and a season's parent", async () => {
    setReady();
    const animeKey = queryKeys.browse({
      kind: "anime",
      sourceId: "1",
      type: "POPULAR",
      query: null,
      filters: null,
    });
    const mangaKey = queryKeys.browse({
      kind: "manga",
      sourceId: "2",
      type: "POPULAR",
      query: null,
      filters: null,
    });
    const card = (kind: "anime" | "manga") => ({
      pages: [{ items: [{ kind, id: 7, title: "X", thumbnailUrl: null, inLibrary: false }] }],
      pageParams: [1],
    });
    queryClient.setQueryData(animeKey, card("anime"));
    queryClient.setQueryData(mangaKey, card("manga"));
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    ops.setInLibrary.mockResolvedValue(makeAnime({ id: 7, inLibrary: true, parentId: 3 }));

    const { result, unmount: u } = withSetup(() => useSetInLibrary(), { pinia, queryClient });
    unmount = u;
    await result.mutateAsync({ kind: "anime", id: 7, inLibrary: true });

    const inLibrary = (key: readonly unknown[]) =>
      queryClient.getQueryData<{ pages: SourcePage[] }>(key)!.pages[0].items[0].inLibrary;
    expect(inLibrary(animeKey)).toBe(true);
    expect(inLibrary(mangaKey)).toBe(false);
    expect(queryClient.getQueryData(queryKeys.anime(7))).toMatchObject({ inLibrary: true });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.library("anime") });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.seasons(3) });
  });
});
