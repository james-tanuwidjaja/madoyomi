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
import type { Filter, SourceBase } from "../types";
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
 * installed, trusted source of either kind (it skips when there is none) and the source's site.
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
    expect(documents.length).toBeGreaterThanOrEqual(19);
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
