// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  buildClientSchema,
  getIntrospectionQuery,
  parse,
  validate,
  type IntrospectionQuery,
} from "graphql";

import { ApiError, createApiClient } from "../client";
import { createSubscriptionClient } from "../ws";
import * as operations from "../operations";
import type { Filter, Preference, SourceBase, SourcePreferenceChange } from "../types";
import { changeFor, changeTaken, preferenceValue } from "../../utils/preferences";
import {
  defaultFilterState,
  filterChanges,
  withFilterValue,
  type FilterState,
} from "../../utils/filters";

/*
 * Live check against a running server (Phase 3 §9 "Stage 2": hand-written types, checked here instead of
 * generated). Skipped unless both are set, e.g. for a server from tools/run-server.ps1 or Start-MadoyomiServer:
 *
 *   $env:MADOYOMI_LIVE_URL = 'http://127.0.0.1:<port>'; $env:MADOYOMI_LIVE_TOKEN = '<its MADOYOMI_TOKEN>'
 *   pnpm vitest run src/api/__tests__/operations.live.spec.ts
 *
 * It validates every operation document of src/api/operations against the server's schema (introspection), runs the
 * real client with and without the token, and receives `extensionStoreRefreshed` through the real WebSocket client
 * (Node's WebSocket, which, like a browser's, fails the handshake if a requested subprotocol isn't echoed). The repo
 * refresh needs at least one repo in the server's data dir. The repo-kind check fetches yuzono's index (internet
 * access) and writes nothing: the server refuses the add before storing anything. The filtered search needs an
 * installed, trusted source of either kind (it skips when there is none) and the source's site. The details/library
 * check refreshes the first Popular entry of one source per kind and adds it to the library and removes it again
 * (it puts back an entry that was already there). The preference check changes one preference of a configurable
 * source per kind and puts it back.
 */

// TypeScript 6 loads no @types by default, so `process` is reached through globalThis.
const env =
  (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const baseUrl = env.MADOYOMI_LIVE_URL?.replace(/\/+$/, "");
const token = env.MADOYOMI_LIVE_TOKEN;

/** The source's defaults with one filter changed: a select's next option, else a sort, else a tri-state included. */
function someFilterChanged(filters: Filter[]): FilterState {
  const state = defaultFilterState(filters);
  const leaves: {
    f: Exclude<Filter, { __typename: "GroupFilter" }>;
    path: [number] | [number, number];
  }[] = [];
  filters.forEach((f, i) => {
    if (f.__typename === "GroupFilter")
      f.filters.forEach((c, j) => leaves.push({ f: c, path: [i, j] }));
    else leaves.push({ f, path: [i] });
  });
  const select = leaves.find((l) => l.f.__typename === "SelectFilter" && l.f.values.length > 1);
  if (select && select.f.__typename === "SelectFilter") {
    return withFilterValue(
      state,
      select.path,
      (select.f.selectDefault + 1) % select.f.values.length,
    );
  }
  const sort = leaves.find((l) => l.f.__typename === "SortFilter" && l.f.values.length > 0);
  if (sort && sort.f.__typename === "SortFilter") {
    const d = sort.f.sortDefault;
    return withFilterValue(state, sort.path, {
      index: d?.index ?? 0,
      ascending: !(d?.ascending ?? false),
    });
  }
  const tri = leaves.find((l) => l.f.__typename === "TriStateFilter");
  return tri ? withFilterValue(state, tri.path, "INCLUDE") : state;
}

const documents = Object.entries(operations).filter(
  (e): e is [string, string] =>
    typeof e[1] === "string" && /_(QUERY|MUTATION|SUBSCRIPTION)$/.test(e[0]),
);

describe.skipIf(!baseUrl || !token)("operations against the live server", () => {
  const client = createApiClient(() => ({ baseUrl: baseUrl!, token: token! }));

  it("finds the documents to check", () => {
    expect(documents.length).toBeGreaterThanOrEqual(31);
  });

  it("validates every operation document against the server's schema", async () => {
    const introspection = await client.gql<IntrospectionQuery>(getIntrospectionQuery());
    // assumeValid: upstream's schema has two input types without fields (DownloadChangedInput,
    // LibraryUpdateStatusChangedInput), which graphql-js rejects as a schema; our documents are still validated.
    const schema = buildClientSchema(introspection, { assumeValid: true });

    const problems = documents.flatMap(([name, doc]) =>
      validate(schema, parse(doc)).map((e) => `${name}: ${e.message}`),
    );
    expect(problems).toEqual([]);
  });

  it("is refused without the right token", async () => {
    const wrong = createApiClient(() => ({ baseUrl: baseUrl!, token: "not-the-token" }));
    const e = await wrong.gql(operations.EXTENSION_STORES_QUERY).catch((x: unknown) => x);

    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).code).toBe("UNAUTHORIZED");
    expect((e as ApiError).status).toBe(401);
  });

  it("runs the list queries", async () => {
    const stores = await client.gql<{ extensionStores: { nodes: unknown[] } }>(
      operations.EXTENSION_STORES_QUERY,
    );
    expect(Array.isArray(stores.extensionStores.nodes)).toBe(true);
    for (const doc of [operations.ANIME_SOURCES_QUERY, operations.MANGA_SOURCES_QUERY]) {
      await expect(client.gql(doc)).resolves.toBeTruthy();
    }
    await expect(client.gql(operations.EXTENSIONS_QUERY, { type: "ANIME" })).resolves.toBeTruthy();
  });

  // The Repos screen detects a repo's kind by trying one and, on this error, the other (Phase 3 §9 "Stage 3").
  it("refuses a repo added as the wrong kind with REPO_TYPE_MISMATCH and the packages", async () => {
    const e = await client
      .gql(operations.ADD_REPO_MUTATION, {
        indexUrl: "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json",
        type: "MANGA",
        format: null,
      })
      .catch((x: unknown) => x);

    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).code).toBe("REPO_TYPE_MISMATCH");
    const details = (e as ApiError).details as { packages?: unknown };
    expect(Array.isArray(details.packages)).toBe(true);
    expect((details.packages as string[]).length).toBeGreaterThan(0);
  }, 60_000);

  // The source page's filter sheet (Phase 3 §9 "Stage 4"): FilterRenderer's state, through filterChanges, is a change
  // list the real server takes, and the filtered search finds entries. A filter-only search has an empty query: the
  // sources tried apply their filters only then. Needs an installed, trusted source of a kind (it skips when there is
  // none) and the source's site. A source whose site fails (AnimeGG's own genre filter gives a 404) is recorded, and
  // the next one of the kind is tried; the kind passes when one works.
  it("runs a filtered search on an installed source of each kind", async (ctx) => {
    const report: string[] = [];
    let kindsTried = 0;
    for (const kind of ["anime", "manga"] as const) {
      const list =
        kind === "anime"
          ? (
              await client.gql<{ animeSources: { nodes: SourceBase[] } }>(
                operations.ANIME_SOURCES_QUERY,
              )
            ).animeSources.nodes
          : (await client.gql<{ sources: { nodes: SourceBase[] } }>(operations.MANGA_SOURCES_QUERY))
              .sources.nodes;
      const usable = list.filter(
        (s) => s.id !== "0" && s.extension.isTrusted && s.extension.isLoaded,
      );
      if (usable.length === 0) continue;
      kindsTried++;

      let worked = false;
      for (const source of usable) {
        const filters =
          kind === "anime"
            ? (
                await client.gql<{ animeSources: { nodes: { filters: Filter[] }[] } }>(
                  operations.ANIME_SOURCE_FILTERS_QUERY,
                  { id: source.id },
                )
              ).animeSources.nodes[0].filters
            : (
                await client.gql<{ source: { filters: Filter[] } }>(
                  operations.MANGA_SOURCE_FILTERS_QUERY,
                  { id: source.id },
                )
              ).source.filters;
        const changes = filterChanges(filters, someFilterChanged(filters));
        if (changes.length === 0) {
          report.push(`${kind} ${source.name}: no filter to change`);
          continue;
        }

        const search = async (filterList: unknown) => {
          const input = {
            source: source.id,
            type: "SEARCH",
            page: 1,
            query: "",
            filters: filterList,
          };
          if (kind === "anime") {
            const d = await client.gql<{ fetchSourceAnime: { animes: { id: number }[] } }>(
              operations.BROWSE_ANIME_MUTATION,
              { input },
            );
            return d.fetchSourceAnime.animes;
          }
          const d = await client.gql<{ fetchSourceManga: { mangas: { id: number }[] } }>(
            operations.BROWSE_MANGA_MUTATION,
            { input },
          );
          return d.fetchSourceManga.mangas;
        };
        const outcome = (p: Promise<{ id: number }[]>) =>
          p.then(
            (r) => ({ ok: true as const, count: r.length }),
            (e: unknown) => {
              // only the site's failure is a reason to try another source; anything else is ours
              if (e instanceof ApiError && e.code === "SOURCE_FAILED") {
                return { ok: false as const, error: `${e.code} ${e.message}` };
              }
              throw e;
            },
          );
        const plain = await outcome(search(null));
        const filtered = await outcome(search(changes));
        report.push(
          `${kind} ${source.name}: ${JSON.stringify(changes)} → ${
            filtered.ok ? `${filtered.count} entries` : filtered.error
          } (unfiltered: ${plain.ok ? plain.count : plain.error})`,
        );
        if (filtered.ok && filtered.count > 0) {
          worked = true;
          break;
        }
      }
      expect(worked, report.join("\n")).toBe(true);
    }
    console.info(report.join("\n"));
    if (kindsTried === 0) ctx.skip();
  }, 300_000);

  // The details page and the Library (Phase 3 §9 "Stage 5"): a stored entry of each kind (the first of a usable
  // source's Popular) is refreshed from the source the way the page does it (anime: one call, a partial result on a
  // source failure; manga: fetchManga, then fetchChapters), then added to the library, found in the library list with
  // its time, and removed again (an entry that was already in the library is put back). The library round trip is
  // the database only; the refresh needs the source's site, and a SOURCE_FAILED is recorded as the site's.
  it("refreshes a stored entry of each kind and puts it in the library and back out", async (ctx) => {
    const report: string[] = [];
    let kindsTried = 0;
    for (const kind of ["anime", "manga"] as const) {
      const list =
        kind === "anime"
          ? (
              await client.gql<{ animeSources: { nodes: SourceBase[] } }>(
                operations.ANIME_SOURCES_QUERY,
              )
            ).animeSources.nodes
          : (await client.gql<{ sources: { nodes: SourceBase[] } }>(operations.MANGA_SOURCES_QUERY))
              .sources.nodes;
      const usable = list.filter(
        (s) => s.id !== "0" && s.extension.isTrusted && s.extension.isLoaded,
      );
      if (usable.length === 0) continue;
      kindsTried++;

      // the first usable source whose Popular has entries (a MangaDex language source can have none)
      const popular = async (sourceId: string) => {
        const input = { source: sourceId, type: "POPULAR", page: 1, query: null, filters: null };
        return kind === "anime"
          ? (
              await client.gql<{
                fetchSourceAnime: { animes: { id: number; inLibrary: boolean }[] };
              }>(operations.BROWSE_ANIME_MUTATION, { input })
            ).fetchSourceAnime.animes
          : (
              await client.gql<{
                fetchSourceManga: { mangas: { id: number; inLibrary: boolean }[] };
              }>(operations.BROWSE_MANGA_MUTATION, { input })
            ).fetchSourceManga.mangas;
      };
      let source = usable[0];
      let entries: { id: number; inLibrary: boolean }[] = [];
      for (const s of usable) {
        source = s;
        entries = await popular(s.id);
        if (entries.length > 0) break;
        report.push(`${kind} ${s.name} [${s.lang}]: Popular is empty, trying the next source`);
      }
      expect(entries.length, `${kind} Popular of every usable source`).toBeGreaterThan(0);
      const { id, inLibrary: wasInLibrary } = entries[0];

      // The refresh
      if (kind === "anime") {
        const { data, errors } = await client.gqlResult<{
          fetchAnimeAndEpisodes: {
            anime: { id: number; initialized: boolean };
            episodes: unknown[];
          };
        }>(operations.FETCH_ANIME_AND_EPISODES_MUTATION, { id });
        expect(data?.fetchAnimeAndEpisodes.anime.id).toBe(id);
        expect(
          errors.every((e) => e.code === "SOURCE_FAILED"),
          String(errors[0]),
        ).toBe(true);
        report.push(
          `anime ${source.name} #${id}: ${
            errors[0]
              ? `${errors[0].code} ${errors[0].message}`
              : `${data!.fetchAnimeAndEpisodes.episodes.length} episodes`
          }`,
        );
      } else {
        const outcome = await client
          .gql<{ fetchManga: { manga: { id: number } } }>(operations.FETCH_MANGA_MUTATION, { id })
          .then(() =>
            client.gql<{ fetchChapters: { chapters: unknown[] } }>(
              operations.FETCH_CHAPTERS_MUTATION,
              { mangaId: id },
            ),
          )
          .then(
            (d) => `${d.fetchChapters.chapters.length} chapters`,
            (e: unknown) => {
              if (e instanceof ApiError && e.code === "SOURCE_FAILED")
                return `${e.code} ${e.message}`;
              throw e;
            },
          );
        report.push(`manga ${source.name} #${id}: ${outcome}`);
      }

      // The library
      const update =
        kind === "anime" ? operations.UPDATE_ANIME_MUTATION : operations.UPDATE_MANGA_MUTATION;
      const library = async () => {
        const d = await client.gql<
          Record<string, { nodes: { id: number; inLibraryAt: string }[] }>
        >(kind === "anime" ? operations.LIBRARY_ANIME_QUERY : operations.LIBRARY_MANGA_QUERY);
        return d[kind === "anime" ? "animes" : "mangas"].nodes;
      };
      try {
        const added = await client.gql<Record<string, Record<string, { inLibrary: boolean }>>>(
          update,
          {
            id,
            inLibrary: true,
          },
        );
        expect(Object.values(added)[0][kind].inLibrary).toBe(true);
        const row = (await library()).find((e) => e.id === id);
        expect(row, `${kind} #${id} in the library list`).toBeTruthy();
        expect(Number(row!.inLibraryAt)).toBeGreaterThan(0);

        await client.gql(update, { id, inLibrary: false });
        expect((await library()).some((e) => e.id === id)).toBe(false);
      } finally {
        if (wasInLibrary) await client.gql(update, { id, inLibrary: true });
      }
    }
    console.info(report.join("\n"));
    if (kindsTried === 0) ctx.skip();
  }, 300_000);

  // The source settings page (Phase 3 §9 "Stage 6"): one preference of a configurable source of each kind is changed
  // the way the page does it (manga: read, then change), read back, and restored. Only a switch, checkbox or list
  // that is enabled and visible is changed. The database and the source's SharedPreferences only, no site.
  it("changes a preference of a configurable source of each kind, reads it back and restores it", async (ctx) => {
    const report: string[] = [];
    let kindsTried = 0;
    for (const kind of ["anime", "manga"] as const) {
      const list =
        kind === "anime"
          ? (
              await client.gql<{ animeSources: { nodes: SourceBase[] } }>(
                operations.ANIME_SOURCES_QUERY,
              )
            ).animeSources.nodes
          : (await client.gql<{ sources: { nodes: SourceBase[] } }>(operations.MANGA_SOURCES_QUERY))
              .sources.nodes;
      const configurable = list.filter(
        (s) =>
          s.id !== "0" &&
          s.extension.isTrusted &&
          s.extension.isLoaded &&
          s.isConfigurable === true,
      );

      const read = async (id: string) =>
        kind === "anime"
          ? (
              await client.gql<{ animeSources: { nodes: { preferences: Preference[] }[] } }>(
                operations.ANIME_SOURCE_PREFERENCES_QUERY,
                { id },
              )
            ).animeSources.nodes[0].preferences
          : (
              await client.gql<{ source: { preferences: Preference[] } }>(
                operations.MANGA_SOURCE_PREFERENCES_QUERY,
                { id },
              )
            ).source.preferences;
      const change = async (id: string, c: SourcePreferenceChange) => {
        if (kind === "anime") {
          const d = await client.gql<{
            updateAnimeSourcePreference: { preferences: Preference[]; source: { id: string } };
          }>(operations.UPDATE_ANIME_SOURCE_PREFERENCE_MUTATION, {
            input: { source: id, change: c },
          });
          return d.updateAnimeSourcePreference;
        }
        await read(id); // upstream's change needs a read first (operations/preferences.ts)
        const d = await client.gql<{
          updateSourcePreference: { preferences: Preference[]; source: { id: string } };
        }>(operations.UPDATE_MANGA_SOURCE_PREFERENCE_MUTATION, {
          input: { source: id, change: c },
        });
        return d.updateSourcePreference;
      };

      for (const source of configurable) {
        const prefs = await read(source.id);
        const position = prefs.findIndex(
          (p) =>
            p.enabled &&
            p.visible &&
            (p.__typename === "SwitchPreference" ||
              p.__typename === "CheckBoxPreference" ||
              (p.__typename === "ListPreference" && p.entryValues.length > 1)),
        );
        if (position < 0) {
          report.push(`${kind} ${source.name}: no switch, checkbox or list to change`);
          continue;
        }
        kindsTried++;
        const p = prefs[position];
        const before = preferenceValue(p);
        const next =
          p.__typename === "ListPreference"
            ? p.entryValues[(p.entryValues.indexOf(before as string) + 1) % p.entryValues.length]
            : !before;
        const sent = changeFor(position, p, next);
        try {
          const r = await change(source.id, sent);
          expect(r.source.id).toBe(source.id);
          const taken = changeTaken(r.preferences[position], sent);
          const readBack = preferenceValue((await read(source.id))[position]);
          expect(readBack).toEqual(preferenceValue(r.preferences[position]));
          report.push(
            `${kind} ${source.name} #${position} ${p.key}: ${JSON.stringify(before)} → ${JSON.stringify(
              next,
            )} ${taken ? "taken" : "refused by the extension"}, read back ${JSON.stringify(readBack)}`,
          );
          // a refusal is legitimate, but the extensions tried so far take these values
          expect(taken, report.join("\n")).toBe(true);
        } finally {
          await change(source.id, changeFor(position, p, before));
        }
        expect(preferenceValue((await read(source.id))[position])).toEqual(before);
        break;
      }
    }
    console.info(report.join("\n"));
    if (kindsTried === 0) ctx.skip();
  }, 120_000);

  it("receives extensionStoreRefreshed over the WebSocket after a refresh", async () => {
    const received: { indexUrl: string; ok: boolean }[] = [];
    let onConnected!: () => void;
    const connected = new Promise<void>((resolve) => (onConnected = resolve));
    const ws = createSubscriptionClient({ onConnected: () => onConnected() });
    try {
      ws.subscribe<{ extensionStoreRefreshed: { indexUrl: string; ok: boolean } }>(
        operations.EXTENSION_STORE_REFRESHED_SUBSCRIPTION,
        {},
        { next: (d) => received.push(d.extensionStoreRefreshed) },
      );
      ws.setEndpoint(client.subscriptionUrl());
      await connected;
      // The server registers the subscription right after the ack; nothing is replayed.
      await new Promise((r) => setTimeout(r, 500));

      const result = await client.gql<{ fetchExtensions: { extensionStores: unknown[] } }>(
        operations.REFRESH_REPOS_MUTATION,
        { type: null },
      );
      expect(result.fetchExtensions.extensionStores.length).toBeGreaterThan(0);

      await expect.poll(() => received.length, { timeout: 30_000 }).toBeGreaterThan(0);
    } finally {
      ws.dispose();
    }
  }, 120_000);
});
