import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql } };
});

import { ApiError } from "../client";
import {
  ANIME_SOURCE_PREFERENCES_QUERY,
  fetchSourcePreferences,
  MANGA_SOURCE_PREFERENCES_QUERY,
  UPDATE_ANIME_SOURCE_PREFERENCE_MUTATION,
  UPDATE_MANGA_SOURCE_PREFERENCE_MUTATION,
  updateSourcePreference,
} from "../operations/preferences";
import type { Source, SourceBase } from "../types";
import { allPreferenceTypes } from "../../test/preferenceFixtures";
import { makeMangaSource, makeSource } from "../../test/fixtures";

/* The preference calls over a mocked client: which documents run, in which order, and what comes back. */

afterEach(() => vi.clearAllMocks());

/** A source as the server sends it: without the `kind` the client adds. */
function serverSource(s: Source): SourceBase {
  const copy: Partial<Source> = { ...s };
  delete copy.kind;
  return copy as SourceBase;
}
const animeSource = serverSource(makeSource({ isConfigurable: true }));
const mangaSource = serverSource(makeMangaSource({ isConfigurable: true }));

describe("fetchSourcePreferences", () => {
  it("reads an anime source's preferences, and an unlisted id is NOT_FOUND", async () => {
    client.gql.mockResolvedValueOnce({
      animeSources: { nodes: [{ id: animeSource.id, preferences: allPreferenceTypes }] },
    });
    await expect(fetchSourcePreferences("anime", animeSource.id)).resolves.toEqual(
      allPreferenceTypes,
    );
    expect(client.gql.mock.calls[0][0]).toBe(ANIME_SOURCE_PREFERENCES_QUERY);

    client.gql.mockResolvedValueOnce({ animeSources: { nodes: [] } });
    const e = await fetchSourcePreferences("anime", "404").catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).code).toBe("NOT_FOUND");
  });

  it("reads a manga source's preferences through upstream's source(id)", async () => {
    client.gql.mockResolvedValueOnce({ source: { id: mangaSource.id, preferences: [] } });
    await expect(fetchSourcePreferences("manga", mangaSource.id)).resolves.toEqual([]);
    expect(client.gql.mock.calls[0]).toEqual([
      MANGA_SOURCE_PREFERENCES_QUERY,
      { id: mangaSource.id },
      { signal: undefined },
    ]);
  });
});

describe("updateSourcePreference", () => {
  const change = { position: 1, switchState: true };

  it("changes an anime preference in one call", async () => {
    client.gql.mockResolvedValueOnce({
      updateAnimeSourcePreference: { preferences: allPreferenceTypes, source: animeSource },
    });

    const r = await updateSourcePreference("anime", animeSource.id, change);

    expect(client.gql).toHaveBeenCalledTimes(1);
    expect(client.gql.mock.calls[0]).toEqual([
      UPDATE_ANIME_SOURCE_PREFERENCE_MUTATION,
      { input: { source: animeSource.id, change } },
    ]);
    expect(r).toEqual({
      preferences: allPreferenceTypes,
      source: { ...animeSource, kind: "anime" },
    });
  });

  // upstream's manga change uses the screen the last read cached: none after a backend restart
  it("reads a manga source's preferences first, then changes one", async () => {
    client.gql
      .mockResolvedValueOnce({ source: { id: mangaSource.id, preferences: allPreferenceTypes } })
      .mockResolvedValueOnce({
        updateSourcePreference: { preferences: allPreferenceTypes, source: mangaSource },
      });

    const r = await updateSourcePreference("manga", mangaSource.id, change);

    expect(client.gql.mock.calls.map((c) => c[0])).toEqual([
      MANGA_SOURCE_PREFERENCES_QUERY,
      UPDATE_MANGA_SOURCE_PREFERENCE_MUTATION,
    ]);
    expect(client.gql.mock.calls[1][1]).toEqual({ input: { source: mangaSource.id, change } });
    expect(r.source).toEqual({ ...mangaSource, kind: "manga" });
  });
});
