import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql } };
});

import {
  ANIME_HISTORY_QUERY,
  CLEAR_ANIME_HISTORY_MUTATION,
  CLEAR_MANGA_HISTORY_MUTATION,
  clearHistory,
  DELETE_ANIME_HISTORY_MUTATION,
  DELETE_MANGA_HISTORY_MUTATION,
  deleteHistory,
  fetchHistory,
  MANGA_HISTORY_QUERY,
} from "../operations/history";

/* The history calls (Phase 6 Stage 2) over a mocked client: each kind gets its own documents. */
afterEach(() => vi.clearAllMocks());

const entry = {
  id: 4,
  title: "Frieren",
  thumbnailUrl: "/api/v1/anime/4/thumbnail",
  inLibrary: true,
};

describe("fetchHistory", () => {
  it("reads an anime page: the episode, what to resume, the time and the progress in milliseconds", async () => {
    client.gql.mockResolvedValue({
      animeHistory: {
        hasMore: true,
        items: [
          {
            lastSeenAt: "1700000000",
            anime: entry,
            episode: {
              id: 11,
              name: "Episode 3",
              isWatched: true,
              lastSecondSeen: "1400000",
              totalSeconds: "1440000",
            },
            resume: {
              id: 12,
              name: "Episode 4",
              isWatched: false,
              lastSecondSeen: "0",
              totalSeconds: "0",
            },
          },
        ],
      },
    });

    const page = await fetchHistory("anime", 50, 50);

    expect(client.gql).toHaveBeenCalledWith(
      ANIME_HISTORY_QUERY,
      { limit: 50, offset: 50 },
      { signal: undefined },
    );
    expect(page).toEqual({
      hasMore: true,
      items: [
        {
          kind: "anime",
          entry: { kind: "anime", ...entry },
          item: {
            id: 11,
            name: "Episode 3",
            done: true,
            progress: { positionMs: 1400000, durationMs: 1440000 },
          },
          resume: {
            id: 12,
            name: "Episode 4",
            done: false,
            progress: { positionMs: 0, durationMs: 0 },
          },
          at: 1700000000,
        },
      ],
    });
  });

  it("reads a manga page with the chapters' pages", async () => {
    const manga = { ...entry, id: 7, thumbnailUrl: null, inLibrary: false };
    const chapter = { id: 30, name: "Chapter 5", isRead: false, lastPageRead: 12, pageCount: 40 };
    client.gql.mockResolvedValue({
      mangaHistory: {
        hasMore: false,
        items: [{ lastReadAt: "9", manga, chapter, resume: chapter }],
      },
    });

    const page = await fetchHistory("manga", 0, 50);

    expect(client.gql).toHaveBeenCalledWith(
      MANGA_HISTORY_QUERY,
      { limit: 50, offset: 0 },
      { signal: undefined },
    );
    expect(page.hasMore).toBe(false);
    expect(page.items[0]).toEqual({
      kind: "manga",
      entry: { kind: "manga", ...manga },
      item: { id: 30, name: "Chapter 5", done: false, pages: { lastPageRead: 12, pageCount: 40 } },
      resume: {
        id: 30,
        name: "Chapter 5",
        done: false,
        pages: { lastPageRead: 12, pageCount: 40 },
      },
      at: 9,
    });
  });
});

describe("deleteHistory and clearHistory", () => {
  it("send each kind's document", async () => {
    client.gql
      .mockResolvedValueOnce({ deleteAnimeHistory: { removed: 3 } })
      .mockResolvedValueOnce({ deleteMangaHistory: { removed: 2 } })
      .mockResolvedValueOnce({ clearAnimeHistory: { removed: 9 } })
      .mockResolvedValueOnce({ clearMangaHistory: { removed: 1 } });

    expect(await deleteHistory("anime", [4])).toBe(3);
    expect(await deleteHistory("manga", [7, 8])).toBe(2);
    expect(await clearHistory("anime")).toBe(9);
    expect(await clearHistory("manga")).toBe(1);

    expect(client.gql.mock.calls).toEqual([
      [DELETE_ANIME_HISTORY_MUTATION, { ids: [4] }],
      [DELETE_MANGA_HISTORY_MUTATION, { ids: [7, 8] }],
      [CLEAR_ANIME_HISTORY_MUTATION],
      [CLEAR_MANGA_HISTORY_MUTATION],
    ]);
  });
});
