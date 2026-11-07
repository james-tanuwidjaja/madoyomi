import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn(), gqlResult: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql, gqlResult: client.gqlResult } };
});

import { ApiError } from "../client";
import {
  CHAPTERS_QUERY,
  FETCH_CHAPTERS_MUTATION,
  FETCH_MANGA_MUTATION,
  fetchLibrary,
  MANGA_QUERY,
  refreshAnime,
  refreshManga,
} from "../operations/entries";
import { makeAnime, makeChapters, makeManga } from "../../test/fixtures";

/* The refresh and library calls over a mocked client: what is kept when the source fails. */
const sourceFailed = new ApiError({ kind: "graphql", code: "SOURCE_FAILED", message: "HTTP 503" });

afterEach(() => vi.clearAllMocks());

describe("refreshAnime", () => {
  it("returns the stored data with the source's error (a partial result)", async () => {
    const anime = makeAnime();
    client.gqlResult.mockResolvedValue({
      data: { fetchAnimeAndEpisodes: { anime, episodes: [], seasons: [] } },
      errors: [sourceFailed],
    });

    const r = await refreshAnime(12);

    expect(r).toEqual({ anime, episodes: [], seasons: [], error: sourceFailed });
  });

  it("throws when there is no data (an unknown entry)", async () => {
    const notFound = new ApiError({ kind: "graphql", code: "NOT_FOUND", message: "No anime" });
    client.gqlResult.mockResolvedValue({ data: null, errors: [notFound] });

    await expect(refreshAnime(99)).rejects.toBe(notFound);
  });
});

describe("refreshManga", () => {
  it("fetches the details, then the chapters", async () => {
    const manga = makeManga();
    const chapters = makeChapters(2);
    client.gql.mockImplementation(async (doc: string) =>
      doc === FETCH_MANGA_MUTATION ? { fetchManga: { manga } } : { fetchChapters: { chapters } },
    );

    expect(await refreshManga(34)).toEqual({ manga, chapters, error: null });
    expect(client.gql.mock.calls.map((c) => c[0])).toEqual([
      FETCH_MANGA_MUTATION,
      FETCH_CHAPTERS_MUTATION,
    ]);
  });

  it("stops at a source failure and returns what is stored", async () => {
    const manga = makeManga();
    const chapters = makeChapters(3);
    client.gql.mockImplementation(async (doc: string) => {
      if (doc === FETCH_MANGA_MUTATION) throw sourceFailed;
      if (doc === MANGA_QUERY) return { manga };
      if (doc === CHAPTERS_QUERY) return { chapters: { nodes: chapters } };
      throw new Error(`unexpected ${doc}`);
    });

    expect(await refreshManga(34)).toEqual({ manga, chapters, error: sourceFailed });
    expect(client.gql.mock.calls.map((c) => c[0])).not.toContain(FETCH_CHAPTERS_MUTATION);
  });

  it("keeps the new details when only the chapters fail", async () => {
    const manga = makeManga({ status: "COMPLETED" });
    const chapters = makeChapters(1);
    client.gql.mockImplementation(async (doc: string) => {
      if (doc === FETCH_MANGA_MUTATION) return { fetchManga: { manga } };
      if (doc === FETCH_CHAPTERS_MUTATION) throw sourceFailed;
      if (doc === CHAPTERS_QUERY) return { chapters: { nodes: chapters } };
      throw new Error(`unexpected ${doc}`);
    });

    expect(await refreshManga(34)).toEqual({ manga, chapters, error: sourceFailed });
  });

  it("throws when the backend itself is away", async () => {
    const down = new ApiError({ kind: "network", code: "NETWORK", message: "no answer" });
    client.gql.mockRejectedValue(down);

    await expect(refreshManga(34)).rejects.toBe(down);
  });
});

describe("fetchLibrary", () => {
  it("lists the kind's library with the added time as a number", async () => {
    client.gql.mockResolvedValue({
      mangas: {
        nodes: [{ id: 3, title: "A", thumbnailUrl: null, inLibrary: true, inLibraryAt: "1700" }],
      },
    });

    expect(await fetchLibrary("manga")).toEqual([
      { kind: "manga", id: 3, title: "A", thumbnailUrl: null, inLibrary: true, inLibraryAt: 1700 },
    ]);
  });
});
