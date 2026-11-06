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
 * access) and writes nothing: the server refuses the add before storing anything.
 */

// TypeScript 6 loads no @types by default, so `process` is reached through globalThis.
const env =
  (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const baseUrl = env.MADOYOMI_LIVE_URL?.replace(/\/+$/, "");
const token = env.MADOYOMI_LIVE_TOKEN;

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
