import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "../client";
import type { BackendInfo } from "../../stores/backend";

const TOKEN = "test-token-0123";

let info: BackendInfo | null;
const fetchMock = vi.fn();

const client = createApiClient(() => info);

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function caught(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error("expected the request to fail");
}

beforeEach(() => {
  info = { baseUrl: "http://127.0.0.1:50266", token: TOKEN };
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

describe("gql", () => {
  it("posts the operation with the bearer token and returns the data", async () => {
    fetchMock.mockResolvedValue(json({ data: { extensionStores: { nodes: [] } } }));

    const data = await client.gql("query X { a }", { id: 1 });

    expect(data).toEqual({ extensionStores: { nodes: [] } });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:50266/api/graphql");
    expect(init.method).toBe("POST");
    expect(init.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(JSON.parse(init.body)).toEqual({ query: "query X { a }", variables: { id: 1 } });
  });

  it("turns a GraphQL error into an ApiError with the code, details and path", async () => {
    fetchMock.mockResolvedValue(
      json({
        data: null,
        errors: [
          {
            message: "Extension's signing key isn't trusted",
            path: ["animeSources", "nodes", 0, "filters"],
            extensions: { code: "UNTRUSTED", details: { hash: "ab12" } },
          },
        ],
      }),
    );

    const e = await caught(client.gql("query { x }"));

    expect(e.kind).toBe("graphql");
    expect(e.code).toBe("UNTRUSTED");
    expect(e.message).toBe("Extension's signing key isn't trusted");
    expect(e.details).toEqual({ hash: "ab12" });
    expect(e.path).toEqual(["animeSources", "nodes", 0, "filters"]);
    expect(e.status).toBe(200);
    expect(e.isTransient).toBe(false);
  });

  it("reads GraphQL errors on a non-200 status, BAD_REQUEST when there is no code", async () => {
    fetchMock.mockResolvedValue(
      json({ errors: [{ message: "Validation error: field 'nope' undefined" }] }, 400),
    );

    const e = await caught(client.gql("query { nope }"));

    expect(e.code).toBe("BAD_REQUEST");
    expect(e.status).toBe(400);
  });

  it("returns partial data with the errors from gqlResult", async () => {
    fetchMock.mockResolvedValue(
      json({
        data: { fetchAnimeAndEpisodes: { anime: { id: 1 } } },
        errors: [
          { message: "HTTP error 503", extensions: { code: "SOURCE_FAILED", details: null } },
        ],
      }),
    );

    const r = await client.gqlResult<{ fetchAnimeAndEpisodes: { anime: { id: number } } }>(
      "mutation { x }",
    );

    expect(r.data?.fetchAnimeAndEpisodes.anime.id).toBe(1);
    expect(r.errors.map((e) => e.code)).toEqual(["SOURCE_FAILED"]);
  });

  it("turns the REST error body into an ApiError", async () => {
    fetchMock.mockResolvedValue(
      json({ code: "SOURCE_FAILED", message: "HTTP error 404", details: { status: 404 } }, 424),
    );

    const e = await caught(client.gql("query { x }"));

    expect(e.kind).toBe("http");
    expect(e.code).toBe("SOURCE_FAILED");
    expect(e.message).toBe("HTTP error 404");
    expect(e.details).toEqual({ status: 404 });
    expect(e.status).toBe(424);
  });

  it("reports a rejected token as UNAUTHORIZED", async () => {
    fetchMock.mockResolvedValue(
      json({ code: "UNAUTHORIZED", message: "Missing or invalid API token", details: null }, 401),
    );

    const e = await caught(client.gql("query { x }"));

    expect(e.code).toBe("UNAUTHORIZED");
    expect(e.status).toBe(401);
    expect(e.message).not.toContain(TOKEN);
  });

  it("reports an answer that is neither GraphQL nor an error body as BAD_RESPONSE", async () => {
    fetchMock.mockResolvedValue(new Response("<html>Server Error</html>", { status: 500 }));

    const e = await caught(client.gql("query { x }"));

    expect(e.code).toBe("BAD_RESPONSE");
    expect(e.status).toBe(500);
  });

  it("reports a failed fetch as a transient NETWORK error, without the token", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const e = await caught(client.gql("query { x }"));

    expect(e.kind).toBe("network");
    expect(e.code).toBe("NETWORK");
    expect(e.isTransient).toBe(true);
    expect(e.message).toContain("Failed to fetch");
    expect(e.message).not.toContain(TOKEN);
  });

  it("fails at once with BACKEND_UNAVAILABLE while the backend isn't ready", async () => {
    info = null;

    const e = await caught(client.gql("query { x }"));

    expect(e.kind).toBe("unavailable");
    expect(e.code).toBe("BACKEND_UNAVAILABLE");
    expect(e.isTransient).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the next request to the new port after a backend restart", async () => {
    fetchMock.mockImplementation(async () => json({ data: { ok: true } }));

    await client.gql("query { ok }");
    info = { baseUrl: "http://127.0.0.1:61000", token: TOKEN };
    await client.gql("query { ok }");

    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "http://127.0.0.1:50266/api/graphql",
      "http://127.0.0.1:61000/api/graphql",
    ]);
  });

  it("lets an abort through unchanged", async () => {
    fetchMock.mockRejectedValue(new DOMException("The operation was aborted.", "AbortError"));

    await expect(client.gql("query { x }")).rejects.toMatchObject({ name: "AbortError" });
  });
});

describe("assetUrl and subscriptionUrl", () => {
  it("adds the token to the backend's own routes", () => {
    expect(client.assetUrl("/api/v1/anime/7/thumbnail")).toBe(
      `http://127.0.0.1:50266/api/v1/anime/7/thumbnail?token=${TOKEN}`,
    );
    expect(client.assetUrl("/api/v1/manga/3/thumbnail?x=1")).toBe(
      `http://127.0.0.1:50266/api/v1/manga/3/thumbnail?x=1&token=${TOKEN}`,
    );
  });

  it("never gives the token to another server", () => {
    const url = "https://cdn.example.org/cover.jpg";
    expect(client.assetUrl(url)).toBe(url);
  });

  it("gives null while the backend isn't ready, or for no path", () => {
    expect(client.assetUrl(null)).toBeNull();
    info = null;
    expect(client.assetUrl("/api/v1/anime/7/thumbnail")).toBeNull();
    expect(client.subscriptionUrl()).toBeNull();
  });

  it("builds the WebSocket URL with the token", () => {
    expect(client.subscriptionUrl()).toBe(`ws://127.0.0.1:50266/api/graphql?token=${TOKEN}`);
  });
});
