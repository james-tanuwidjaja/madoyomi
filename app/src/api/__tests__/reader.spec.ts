import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql } };
});

import {
  CLEAR_PAGE_CACHE_MUTATION,
  clearPageCache,
  FETCH_CHAPTER_PAGES_MUTATION,
  fetchChapterPages,
  fetchPageCache,
  PAGE_CACHE_QUERY,
  PREFETCH_CHAPTER_PAGES_MUTATION,
  prefetchChapterPages,
  SET_READER_SETTINGS_MUTATION,
  setReaderSettings,
} from "../operations/reader";
import { formatBytes, formatLimit } from "../../composables/useReaderSettings";
import { makeChapters } from "../../test/fixtures";

/* The reader's calls over a mocked client (Phase 5 Stage 1): what each sends and what it returns. */

afterEach(() => vi.clearAllMocks());

describe("reader operations", () => {
  it("fetches a chapter's page routes with the chapter", async () => {
    const [chapter] = makeChapters(1);
    const pages = ["/api/v1/manga/1/chapter/1/page/0", "/api/v1/manga/1/chapter/1/page/1"];
    client.gql.mockResolvedValue({ fetchChapterPages: { pages, chapter } });

    expect(await fetchChapterPages(7)).toEqual({ pages, chapter });
    expect(client.gql).toHaveBeenCalledWith(
      FETCH_CHAPTER_PAGES_MUTATION,
      { chapterId: 7 },
      { signal: undefined },
    );
    expect(FETCH_CHAPTER_PAGES_MUTATION).toContain("lastPageRead");
  });

  it("asks for a prefetch of a range and returns what was queued", async () => {
    const result = { chapterId: 7, pageCount: 40, queued: [3, 4, 5], cached: [2] };
    client.gql.mockResolvedValue({ prefetchChapterPages: result });

    expect(await prefetchChapterPages(7, 2, 4)).toEqual(result);
    expect(client.gql).toHaveBeenCalledWith(PREFETCH_CHAPTER_PAGES_MUTATION, {
      chapterId: 7,
      from: 2,
      count: 4,
    });
  });

  it("changes the cache cap, reads the cache and clears it", async () => {
    client.gql.mockResolvedValueOnce({
      setReaderSettings: { settings: { pageCacheLimitMb: 512 } },
    });
    expect(await setReaderSettings({ pageCacheLimitMb: 512 })).toEqual({ pageCacheLimitMb: 512 });
    expect(client.gql).toHaveBeenLastCalledWith(SET_READER_SETTINGS_MUTATION, {
      patch: { pageCacheLimitMb: 512 },
    });

    const cache = {
      bytes: "2048",
      files: 2,
      limitBytes: "536870912",
      path: "D:\\data\\cache\\manga",
    };
    client.gql.mockResolvedValueOnce({ pageCache: cache });
    expect(await fetchPageCache()).toEqual(cache);
    expect(client.gql.mock.lastCall?.[0]).toBe(PAGE_CACHE_QUERY);

    client.gql.mockResolvedValueOnce({
      clearPageCache: { cache: { ...cache, bytes: "0", files: 0 } },
    });
    expect(await clearPageCache()).toMatchObject({ bytes: "0", files: 0 });
    expect(client.gql.mock.lastCall?.[0]).toBe(CLEAR_PAGE_CACHE_MUTATION);
  });
});

describe("formatBytes and formatLimit", () => {
  it("names sizes as Explorer does", () => {
    expect(formatBytes(0)).toBe("0 bytes");
    expect(formatBytes("1023")).toBe("1023 bytes");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(String(312.4 * 1024 * 1024))).toBe("312 MB");
    expect(formatBytes(45.25 * 1024 * 1024)).toBe("45.3 MB");
    expect(formatBytes(1.2 * 1024 ** 3)).toBe("1.2 GB");
    expect(formatBytes("not a number")).toBe("0 bytes");
  });

  it("names the caps", () => {
    expect([256, 512, 1024, 2048, 5120, 10240, 1536].map(formatLimit)).toEqual([
      "256 MB",
      "512 MB",
      "1 GB",
      "2 GB",
      "5 GB",
      "10 GB",
      "1.5 GB",
    ]);
  });
});
