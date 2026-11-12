import { onScopeDispose, shallowReactive, toValue, watch, type MaybeRefOrGetter } from "vue";
import { api, isApiError } from "../api/client";
import { pagesToLoad } from "../utils/pagedNav";

/*
 * The reader's page images (Phase 5 Stage 2): each page route is fetched as a blob (`api.fetchAsset`, the token in
 * the header) and shown from an object URL. At most `maxConcurrent` requests run at a time, the current page first,
 * then the ones ahead and behind (`pagesToLoad`). A page that leaves the window is aborted if it is still loading,
 * and its object URL is revoked, so a long chapter holds only the pages around the reader. When every request is
 * taken, a more urgent page (the one shown after a jump, a retry) takes the place of the least urgent one. A failed
 * page keeps its error until `retry`, which fetches it past the webview's cache. Pages that failed because the backend
 * was away (`NETWORK`, `BACKEND_UNAVAILABLE`) are fetched again with any retry, and by `retryTransient` (the reader
 * calls it when the backend is ready again); a source's failure waits for its own click.
 */

export type PageState =
  { status: "loading" } | { status: "loaded"; url: string } | { status: "error"; error: unknown };

export type PageFetcher = (
  route: string,
  options: { signal: AbortSignal; reload: boolean },
) => Promise<Blob>;

export interface PageLoaderOptions {
  /** The chapter's page routes (from `fetchChapterPages`); a new list starts over. */
  pages: MaybeRefOrGetter<readonly string[]>;
  /** The reader's position (a page index, or a transition panel). */
  position: MaybeRefOrGetter<number>;
  behind?: number;
  ahead?: number;
  maxConcurrent?: number;
  fetcher?: PageFetcher;
  createUrl?: (blob: Blob) => string;
  revokeUrl?: (url: string) => void;
}

export const MAX_PAGE_REQUESTS = 4;
export const PAGES_BEHIND = 1;
export const PAGES_AHEAD = 4;

export function usePageLoader(opts: PageLoaderOptions) {
  const fetcher: PageFetcher = opts.fetcher ?? ((route, o) => api.fetchAsset(route, o));
  const createUrl = opts.createUrl ?? ((b: Blob) => URL.createObjectURL(b));
  const revokeUrl = opts.revokeUrl ?? ((u: string) => URL.revokeObjectURL(u));
  const max = opts.maxConcurrent ?? MAX_PAGE_REQUESTS;

  const states = shallowReactive(new Map<number, PageState>());
  const running = new Map<number, AbortController>();
  /** Pages to fetch past the cache next time (after a retry). */
  const reload = new Set<number>();

  function drop(i: number) {
    running.get(i)?.abort();
    running.delete(i);
    const s = states.get(i);
    if (s?.status === "loaded") revokeUrl(s.url);
    states.delete(i);
  }

  function reset() {
    for (const i of [...states.keys(), ...running.keys()]) drop(i);
    reload.clear();
  }

  function start(i: number, route: string) {
    const controller = new AbortController();
    running.set(i, controller);
    states.set(i, { status: "loading" });
    const fresh = reload.delete(i);
    fetcher(route, { signal: controller.signal, reload: fresh })
      .then((blob) => {
        if (running.get(i) !== controller) return;
        states.set(i, { status: "loaded", url: createUrl(blob) });
      })
      .catch((error: unknown) => {
        if (running.get(i) !== controller) return;
        states.set(i, { status: "error", error });
      })
      .finally(() => {
        if (running.get(i) === controller) running.delete(i);
        pump();
      });
  }

  function pump() {
    const pages = toValue(opts.pages);
    const wanted = pagesToLoad(
      toValue(opts.position),
      pages.length,
      opts.behind ?? PAGES_BEHIND,
      opts.ahead ?? PAGES_AHEAD,
    );
    const keep = new Set(wanted);
    for (const i of [...states.keys()]) if (!keep.has(i)) drop(i);
    for (const [rank, i] of wanted.entries()) {
      const route = pages[i];
      if (states.has(i) || !route) continue;
      if (running.size >= max) {
        // A more urgent page (the one shown, a retry) doesn't wait behind pages further ahead: the least urgent
        // request gives way, and is asked for again later (the backend has kept fetching it into its cache).
        const victim = [...running.keys()]
          .filter((r) => wanted.indexOf(r) > rank)
          .sort((a, b) => wanted.indexOf(b) - wanted.indexOf(a))[0];
        if (victim === undefined) break;
        drop(victim);
      }
      start(i, route);
    }
  }

  // By content: the same chapter's list fetched again (a new array) keeps what is loaded.
  /** Fetches again every page that failed because the backend was away (not a source's failure). */
  function retryTransient() {
    for (const [i, st] of [...states]) {
      if (st.status === "error" && isApiError(st.error) && st.error.isTransient) {
        states.delete(i);
        reload.add(i);
      }
    }
    pump();
  }

  watch(
    () => toValue(opts.pages).join("\n"),
    () => {
      reset();
      pump();
    },
  );
  watch(() => toValue(opts.position), pump);
  pump();
  onScopeDispose(reset);

  return {
    /** A page's state; undefined while it waits for a free request (or is outside the window). */
    state: (i: number): PageState | undefined => states.get(i),
    /** Fetches a failed page again, past the webview's cache, and every page that failed while the backend was away. */
    retry(i: number) {
      if (states.get(i)?.status === "error") {
        states.delete(i);
        reload.add(i);
      }
      retryTransient();
    },
    retryTransient,
    /** How many requests run now (tests). */
    get inFlight() {
      return running.size;
    },
  };
}
