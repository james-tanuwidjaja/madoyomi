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
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  browseSource: ops.browseSource,
  fetchExtensionStores: ops.fetchExtensionStores,
}));

import { useSourceBrowse } from "../useSources";
import { useExtensionStores } from "../useExtensions";

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
