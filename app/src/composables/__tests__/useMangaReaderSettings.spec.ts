import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ref } from "vue";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Manga, MangaReaderMeta } from "../../api/types";
import { useReaderStore } from "../../stores/reader";
import { makeManga } from "../../test/fixtures";
import { setBackendReady } from "../../test/mountView";
import { withSetup } from "../../test/withSetup";

const ops = vi.hoisted(() => ({
  fetchMangaReaderMeta: vi.fn(),
  setMangaMeta: vi.fn(),
  deleteMangaMeta: vi.fn(),
}));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import { useMangaReaderSettings } from "../useMangaReaderSettings";

/* A manga's reader settings (Phase 5 Stage 5): its meta read and written through upstream's manga meta (mocked here;
   the live spec runs the real round trip), resolved against its genres, its source and the global settings. */

let pinia: Pinia;
let queryClient: QueryClient;
let unmount: (() => void) | null = null;
/** The backend's meta of manga 34, as the mocked operations keep it. */
let meta: { key: string; value: string }[];
let sourceName: string | null;

function setup(manga: Manga | null = makeManga()) {
  const m = ref(manga);
  const s = withSetup(() => useMangaReaderSettings(34, m), { pinia, queryClient });
  unmount = s.unmount;
  return { s: s.result, manga: m };
}

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  meta = [];
  sourceName = "MangaPill";
  ops.fetchMangaReaderMeta.mockImplementation(async (): Promise<MangaReaderMeta> => ({
    mangaId: 34,
    meta: [...meta],
    sourceName,
  }));
  ops.setMangaMeta.mockImplementation(async (_id: number, key: string, value: string) => {
    meta = [...meta.filter((m) => m.key !== key), { key, value }];
  });
  ops.deleteMangaMeta.mockImplementation(async (_id: number, key: string) => {
    meta = meta.filter((m) => m.key !== key);
  });
});

afterEach(() => {
  unmount?.();
  unmount = null;
  queryClient.clear();
  vi.clearAllMocks();
});

describe("useMangaReaderSettings", () => {
  it("follows the global defaults for a manga with no settings of its own, once its meta is read", async () => {
    const { s } = setup();
    expect(s.ready.value).toBe(false);
    await flushPromises();
    expect(s.ready.value).toBe(true);
    expect(s.mode.value).toBe("paged");
    expect(s.origin.value).toEqual({ kind: "default" });
    expect(s.direction.value).toBe("rtl");
    expect(s.own.value).toEqual({ mode: null, direction: null });

    useReaderStore().set({ mode: "double", direction: "ltr" });
    expect(s.mode.value).toBe("double");
    expect(s.direction.value).toBe("ltr");
  });

  it("picks webtoon by genre, then by source, before the default", async () => {
    const { s, manga } = setup(makeManga({ genre: ["Action", "Long Strip"] }));
    await flushPromises();
    expect(s.mode.value).toBe("webtoon");
    expect(s.origin.value).toEqual({ kind: "genre", genre: "Long Strip" });

    manga.value = makeManga({ genre: ["Fantasy"] });
    expect(s.origin.value).toEqual({ kind: "default" });
    unmount?.();

    sourceName = "Webtoons.com";
    queryClient.clear();
    const again = setup(makeManga({ genre: ["Fantasy"] }));
    await flushPromises();
    expect(again.s.mode.value).toBe("webtoon");
    expect(again.s.origin.value).toEqual({ kind: "source", source: "Webtoons.com" });
    expect(again.s.fallbackMode.value).toBe("webtoon");
  });

  it("saves the manga's own mode and direction in its meta, shown at once, and reads them back", async () => {
    const { s } = setup(makeManga({ genre: ["Manhwa"] }));
    await flushPromises();
    void s.setMode("paged");
    void s.setDirection("ltr");
    // at once, before the backend answers
    expect(s.mode.value).toBe("paged");
    expect(s.origin.value).toEqual({ kind: "manga" });
    expect(s.direction.value).toBe("ltr");
    await flushPromises();
    expect(ops.setMangaMeta.mock.calls).toEqual([
      [34, "madoyomi.reader.mode", "paged"],
      [34, "madoyomi.reader.direction", "ltr"],
    ]);
    expect(meta).toEqual([
      { key: "madoyomi.reader.mode", value: "paged" },
      { key: "madoyomi.reader.direction", value: "ltr" },
    ]);
    // the genre would say webtoon: the manga's own comes first
    expect(s.fallbackMode.value).toBe("webtoon");
    unmount?.();

    // another reader (a reopen, a restart): read from the backend
    queryClient.clear();
    const again = setup(makeManga({ genre: ["Manhwa"] }));
    await flushPromises();
    expect(again.s.own.value).toEqual({ mode: "paged", direction: "ltr" });
    expect(again.s.mode.value).toBe("paged");
  });

  it("goes back to the defaults with Use default and Reset, removing the meta", async () => {
    meta = [
      { key: "madoyomi.reader.mode", value: "double" },
      { key: "madoyomi.reader.direction", value: "ltr" },
      { key: "other.app", value: "kept" },
    ];
    const { s } = setup();
    await flushPromises();
    expect(s.mode.value).toBe("double");
    void s.setMode(null);
    await flushPromises();
    expect(ops.deleteMangaMeta).toHaveBeenCalledWith(34, "madoyomi.reader.mode");
    expect(s.mode.value).toBe("paged");
    expect(s.direction.value).toBe("ltr");

    await s.reset();
    await flushPromises();
    expect(s.direction.value).toBe("rtl");
    expect(meta).toEqual([{ key: "other.app", value: "kept" }]);
  });

  it("writes in order, and on a failure says so and reads the meta again", async () => {
    const { s } = setup();
    await flushPromises();
    ops.setMangaMeta.mockRejectedValueOnce(
      new ApiError({ kind: "graphql", code: "INTERNAL", message: "down" }),
    );
    void s.setMode("webtoon");
    expect(s.mode.value).toBe("webtoon");
    await flushPromises();
    expect(s.error.value).toBeInstanceOf(ApiError);
    // read again: the backend has no mode for it
    expect(s.mode.value).toBe("paged");
    expect(ops.fetchMangaReaderMeta).toHaveBeenCalledTimes(2);

    void s.setMode("double");
    expect(s.error.value).toBeNull();
    await flushPromises();
    expect(meta).toEqual([{ key: "madoyomi.reader.mode", value: "double" }]);
  });

  it("is ready with the defaults when the meta can't be read", async () => {
    ops.fetchMangaReaderMeta.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "INTERNAL", message: "no" }),
    );
    const { s } = setup(makeManga({ genre: ["Long Strip"] }));
    await flushPromises();
    expect(s.ready.value).toBe(true);
    expect(s.mode.value).toBe("webtoon");
    expect(s.own.value).toEqual({ mode: null, direction: null });
  });
});
