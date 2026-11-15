import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql } };
});

import {
  ANIME_UPDATES_QUERY,
  CLEAR_UPDATE_MARKS_MUTATION,
  clearUpdateMarks,
  fetchUpdateMarks,
  fetchUpdates,
  MANGA_UPDATES_QUERY,
  SET_LIBRARY_UPDATE_SETTINGS_MUTATION,
  setLibraryUpdateSettings,
  START_LIBRARY_UPDATE_MUTATION,
  startLibraryUpdate,
  UPDATE_MARKS_QUERY,
} from "../operations/updates";
import { makeLibraryCheck } from "../../test/fixtures";

/* The library update calls (Phase 6 Stage 3) over a mocked client. */
afterEach(() => vi.clearAllMocks());

const entry = { id: 4, title: "Frieren", thumbnailUrl: null, inLibrary: true };

describe("library update operations", () => {
  it("starts a check of a kind, or of one category", async () => {
    client.gql.mockResolvedValue({
      startLibraryUpdate: { libraryCheck: makeLibraryCheck({ running: true }) },
    });

    expect((await startLibraryUpdate("anime")).running).toBe(true);
    expect(client.gql).toHaveBeenLastCalledWith(START_LIBRARY_UPDATE_MUTATION, {
      kind: "ANIME",
      categoryId: null,
    });
    await startLibraryUpdate("manga", 3);
    expect(client.gql).toHaveBeenLastCalledWith(START_LIBRARY_UPDATE_MUTATION, {
      kind: "MANGA",
      categoryId: 3,
    });
  });

  it("reads each kind's Updates page as rows with the fetch time", async () => {
    client.gql.mockResolvedValueOnce({
      animeUpdates: {
        hasMore: true,
        items: [
          {
            fetchedAt: "1700000000",
            anime: entry,
            episode: {
              id: 11,
              name: "Episode 3",
              isWatched: false,
              lastSecondSeen: "60000",
              totalSeconds: "1440000",
            },
          },
        ],
      },
    });
    const anime = await fetchUpdates("anime", 0, 50);
    expect(client.gql).toHaveBeenLastCalledWith(
      ANIME_UPDATES_QUERY,
      { limit: 50, offset: 0 },
      { signal: undefined },
    );
    expect(anime).toEqual({
      hasMore: true,
      items: [
        {
          kind: "anime",
          entry: { kind: "anime", ...entry },
          item: {
            id: 11,
            name: "Episode 3",
            done: false,
            progress: { positionMs: 60000, durationMs: 1440000 },
          },
          at: 1700000000,
        },
      ],
    });

    client.gql.mockResolvedValueOnce({
      mangaUpdates: {
        hasMore: false,
        items: [
          {
            fetchedAt: "1700000100",
            manga: entry,
            chapter: { id: 21, name: "Chapter 829", isRead: true, lastPageRead: 0, pageCount: -1 },
          },
        ],
      },
    });
    const manga = await fetchUpdates("manga", 50, 50);
    expect(client.gql).toHaveBeenLastCalledWith(
      MANGA_UPDATES_QUERY,
      { limit: 50, offset: 50 },
      { signal: undefined },
    );
    expect(manga.items[0]).toEqual({
      kind: "manga",
      entry: { kind: "manga", ...entry },
      item: { id: 21, name: "Chapter 829", done: true, pages: { lastPageRead: 0, pageCount: -1 } },
      at: 1700000100,
    });
  });

  it("reads the marks as entry id to count, and clears some or all", async () => {
    client.gql.mockResolvedValueOnce({
      updateMarks: [
        { entryId: 4, newCount: 2, markedAt: "1700000000" },
        { entryId: 9, newCount: 1, markedAt: "1690000000" },
      ],
    });
    expect(await fetchUpdateMarks("manga")).toEqual({ 4: 2, 9: 1 });
    expect(client.gql).toHaveBeenLastCalledWith(
      UPDATE_MARKS_QUERY,
      { kind: "MANGA" },
      { signal: undefined },
    );

    client.gql.mockResolvedValue({ clearUpdateMarks: { cleared: 1 } });
    expect(await clearUpdateMarks("anime", [4])).toBe(1);
    expect(client.gql).toHaveBeenLastCalledWith(CLEAR_UPDATE_MARKS_MUTATION, {
      kind: "ANIME",
      entryIds: [4],
    });
    await clearUpdateMarks("anime");
    expect(client.gql).toHaveBeenLastCalledWith(CLEAR_UPDATE_MARKS_MUTATION, {
      kind: "ANIME",
      entryIds: null,
    });
  });

  it("sends only the settings given", async () => {
    client.gql.mockResolvedValue({ setLibraryUpdateSettings: { settings: { intervalHours: 6 } } });
    await setLibraryUpdateSettings({ intervalHours: 6 });
    expect(client.gql).toHaveBeenLastCalledWith(SET_LIBRARY_UPDATE_SETTINGS_MUTATION, {
      intervalHours: 6,
    });
  });
});
