import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useBackendStore, type BackendInfo } from "../stores/backend";
import type { ErrorCode, GraphQLErrorJson, RestErrorBody } from "./types";

/**
 * The typed API client (Phase 3 step 5): GraphQL over `POST /api/graphql` with the session token, and token-bearing
 * URLs for the binary REST routes.
 *
 * The base URL and token are read for every request, so the first request after a backend restart already goes to
 * the new port. Nothing is waited for: a request while the backend isn't ready fails at once with
 * `BACKEND_UNAVAILABLE`, and vue-query retries it (src/api/realtime.ts).
 */

/** Where an {@link ApiError} came from. */
export type ApiErrorKind = "graphql" | "http" | "network" | "unavailable";

export class ApiError extends Error {
  override readonly name = "ApiError";
  readonly code: ErrorCode | (string & {});
  readonly details: unknown;
  /** The GraphQL path of the failed field, if any. */
  readonly path: (string | number)[] | null;
  /** HTTP status, if a response arrived. */
  readonly status: number | null;
  readonly kind: ApiErrorKind;

  constructor(init: {
    code: ErrorCode | (string & {});
    message: string;
    kind: ApiErrorKind;
    details?: unknown;
    path?: (string | number)[] | null;
    status?: number | null;
  }) {
    super(init.message);
    this.code = init.code;
    this.kind = init.kind;
    this.details = init.details ?? null;
    this.path = init.path ?? null;
    this.status = init.status ?? null;
  }

  /** The backend is down or restarting: worth retrying once it's back. */
  get isTransient(): boolean {
    return this.code === "NETWORK" || this.code === "BACKEND_UNAVAILABLE";
  }
}

export const isApiError = (e: unknown): e is ApiError => e instanceof ApiError;

export interface GqlResult<T> {
  /** Null only when the server returned no data at all. */
  data: T | null;
  errors: ApiError[];
}

export interface RequestOptions {
  signal?: AbortSignal;
}

export interface ApiClient {
  /** Runs an operation; throws the first error as an {@link ApiError}. */
  gql<T>(query: string, variables?: Record<string, unknown>, options?: RequestOptions): Promise<T>;
  /** Runs an operation and returns the data along with any errors (partial results, e.g. `fetchAnimeAndEpisodes`). */
  gqlResult<T>(
    query: string,
    variables?: Record<string, unknown>,
    options?: RequestOptions,
  ): Promise<GqlResult<T>>;
  /**
   * The URL to load a server-relative binary route (`/api/v1/...` thumbnails, icons) from, with `?token=`. An
   * absolute URL (a source's own image) is returned unchanged and never gets the token. Null while not ready.
   */
  assetUrl(path: string | null | undefined): string | null;
  /** `ws://…/api/graphql?token=…` for subscriptions, null while not ready. */
  subscriptionUrl(): string | null;
}

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

function graphQLError(e: GraphQLErrorJson, status: number): ApiError {
  return new ApiError({
    kind: "graphql",
    code: typeof e.extensions?.code === "string" ? e.extensions.code : "BAD_REQUEST",
    message: typeof e.message === "string" ? e.message : "GraphQL error",
    details: e.extensions?.details ?? null,
    path: Array.isArray(e.path) ? e.path : null,
    status,
  });
}

function isRestErrorBody(v: unknown): v is RestErrorBody {
  return isRecord(v) && typeof v.code === "string" && typeof v.message === "string";
}

/** `getInfo` gives the backend's base URL and token, or null while it isn't ready. */
export function createApiClient(getInfo: () => BackendInfo | null): ApiClient {
  async function post(
    query: string,
    variables: Record<string, unknown> | undefined,
    options: RequestOptions | undefined,
  ): Promise<{ body: unknown; status: number }> {
    const info = getInfo();
    if (!info) {
      throw new ApiError({
        kind: "unavailable",
        code: "BACKEND_UNAVAILABLE",
        message: "The backend isn't ready",
      });
    }
    let res: Response;
    try {
      res = await fetch(`${info.baseUrl}/api/graphql`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          Authorization: `Bearer ${info.token}`,
        },
        body: JSON.stringify({ query, variables: variables ?? {} }),
        signal: options?.signal,
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") throw e;
      throw new ApiError({
        kind: "network",
        code: "NETWORK",
        message: `The backend didn't answer: ${e instanceof Error ? e.message : String(e)}`,
      });
    }
    let body: unknown = null;
    const text = await res.text().catch(() => "");
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    // GraphQL errors may come with any status (graphql-java's validation errors can be a 400).
    if (isRecord(body) && ("data" in body || Array.isArray(body.errors))) {
      return { body, status: res.status };
    }
    if (isRestErrorBody(body)) {
      throw new ApiError({
        kind: "http",
        code: body.code,
        message: body.message,
        details: body.details,
        status: res.status,
      });
    }
    throw new ApiError({
      kind: "http",
      code: "BAD_RESPONSE",
      message: `Unexpected response from the backend (HTTP ${res.status})`,
      status: res.status,
    });
  }

  async function gqlResult<T>(
    query: string,
    variables?: Record<string, unknown>,
    options?: RequestOptions,
  ): Promise<GqlResult<T>> {
    const { body, status } = await post(query, variables, options);
    const json = body as { data?: T | null; errors?: GraphQLErrorJson[] };
    const errors = (json.errors ?? []).map((e) => graphQLError(e, status));
    return { data: json.data ?? null, errors };
  }

  async function gql<T>(
    query: string,
    variables?: Record<string, unknown>,
    options?: RequestOptions,
  ): Promise<T> {
    const { data, errors } = await gqlResult<T>(query, variables, options);
    if (errors.length > 0) throw errors[0];
    if (data === null) {
      throw new ApiError({
        kind: "graphql",
        code: "BAD_RESPONSE",
        message: "The backend returned no data",
      });
    }
    return data;
  }

  function assetUrl(path: string | null | undefined): string | null {
    if (!path) return null;
    // Only the backend's own routes get the token; a source's image URL is somebody else's server.
    if (/^[a-z][a-z0-9+.-]*:/i.test(path)) return path;
    const info = getInfo();
    if (!info) return null;
    const rel = path.startsWith("/") ? path : `/${path}`;
    const sep = rel.includes("?") ? "&" : "?";
    return `${info.baseUrl}${rel}${sep}token=${encodeURIComponent(info.token)}`;
  }

  function subscriptionUrl(): string | null {
    const info = getInfo();
    if (!info) return null;
    return `${info.baseUrl.replace(/^http/, "ws")}/api/graphql?token=${encodeURIComponent(info.token)}`;
  }

  return { gql, gqlResult, assetUrl, subscriptionUrl };
}

/** The app's client: base URL and token from {@link useBackendStore}, only while it is ready. */
export const api: ApiClient = createApiClient(() => {
  const backend = useBackendStore();
  return backend.ready ? backend.info : null;
});

/** {@link ApiClient.assetUrl} as a computed that follows backend restarts (for `<img :src>`). */
export function useAssetUrl(path: MaybeRefOrGetter<string | null | undefined>) {
  const backend = useBackendStore();
  return computed(() => {
    // Reading the store here makes the computed follow a restart's new base URL.
    void backend.ready;
    void backend.info;
    return api.assetUrl(toValue(path));
  });
}
