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
 *
 * Stage 3 (webtoon): the window can be given (`wanted`, the strip's pages around the viewport, in order) instead of
 * ±N of the position. A fetched page is decoded (`HTMLImageElement.decode()`) before it counts as loaded, so its
 * `<img>` paints at once and its natural size is known; the sizes stay for the chapter after a page is released, so
 * the strip keeps each page's height.
 *
 * Stage 6 (the exit run): the pages a neighbouring chapter opens on can be held ahead (`held`, by route): fetched and
 * decoded once every page of the window has its request, and only while a request is free; a page of the window
 * that needs one takes it from them. When a new page list holds such a route, its loaded page is taken over as it
 * is, so the step into the next (previous) chapter shows its first (last) page in the first frame instead of two or
 * three blank frames while it decodes.
 */

export type PageState =
  { status: "loading" } | { status: "loaded"; url: string } | { status: "error"; error: unknown };

export interface PageSize {
  width: number;
  height: number;
}

export type PageFetcher = (
  route: string,
  options: { signal: AbortSignal; reload: boolean },
) => Promise<Blob>;

export interface PageLoaderOptions {
  /** The chapter's page routes (from `fetchChapterPages`); a new list starts over. */
  pages: MaybeRefOrGetter<readonly string[]>;
  /** The reader's position (a page index, or a transition panel). */
  position: MaybeRefOrGetter<number>;
  /** The pages to load, most urgent first; when null, the ones around `position` (`behind`, `ahead`). */
  wanted?: MaybeRefOrGetter<readonly number[] | null>;
  behind?: number;
  /** Pages ahead of the position when `wanted` is null (the preload count, Stage 5; a new value reloads the window). */
  ahead?: MaybeRefOrGetter<number>;
  maxConcurrent?: number;
  fetcher?: PageFetcher;
  createUrl?: (blob: Blob) => string;
  revokeUrl?: (url: string) => void;
  /** Decodes a loaded page and gives its natural size (null when it can't be decoded or measured). */
  decode?: (url: string) => Promise<PageSize | null>;
  /**
   * Routes outside this page list to load ahead, most urgent first (the pages the next and previous chapters open
   * on): taken over when a new page list holds them.
   */
  held?: MaybeRefOrGetter<readonly string[]>;
}

interface HeldPage {
  controller: AbortController | null;
  url: string | null;
  size: PageSize | null;
}

/** `img.decode()` where the platform has it (not jsdom): the image is decoded before it is shown, and measured. */
export async function decodeImage(url: string): Promise<PageSize | null> {
  if (typeof Image === "undefined") return null;
  const img = new Image();
  img.src = url;
  if (typeof img.decode !== "function") return null;
  try {
    await img.decode();
  } catch {
    return null;
  }
  return img.naturalWidth > 0 ? { width: img.naturalWidth, height: img.naturalHeight } : null;
}

export const MAX_PAGE_REQUESTS = 4;
export const PAGES_BEHIND = 1;
export const PAGES_AHEAD = 4;

export function usePageLoader(opts: PageLoaderOptions) {
  const fetcher: PageFetcher = opts.fetcher ?? ((route, o) => api.fetchAsset(route, o));
  const createUrl = opts.createUrl ?? ((b: Blob) => URL.createObjectURL(b));
  const revokeUrl = opts.revokeUrl ?? ((u: string) => URL.revokeObjectURL(u));
  const max = opts.maxConcurrent ?? MAX_PAGE_REQUESTS;
  const decode = opts.decode ?? decodeImage;

  const states = shallowReactive(new Map<number, PageState>());
  /** Natural sizes of the pages decoded so far in this chapter (kept after a page is released). */
  const sizes = shallowReactive(new Map<number, PageSize>());
  const running = new Map<number, AbortController>();
  /** Pages to fetch past the cache next time (after a retry). */
  const reload = new Set<number>();
  /** The held pages by route: loading (a controller) or loaded (a URL). */
  const held = new Map<string, HeldPage>();
  /** Held routes whose fetch failed: not tried again while they stay held (the chapter's own window will). */
  const heldFailed = new Set<string>();

  function dropHeld(route: string) {
    const h = held.get(route);
    if (!h) return;
    h.controller?.abort();
    if (h.url) revokeUrl(h.url);
    held.delete(route);
  }

  function heldRunning(): string[] {
    return [...held].filter(([, h]) => h.controller !== null).map(([r]) => r);
  }

  function startHeld(route: string) {
    const controller = new AbortController();
    const h: HeldPage = { controller, url: null, size: null };
    held.set(route, h);
    fetcher(route, { signal: controller.signal, reload: false })
      .then(async (blob) => {
        if (held.get(route) !== h) return;
        const url = createUrl(blob);
        const size = await decode(url);
        if (held.get(route) !== h) {
          revokeUrl(url);
          return;
        }
        h.url = url;
        h.size = size;
        h.controller = null;
      })
      .catch(() => {
        // A held page is only a head start: the chapter's own loader asks for it again.
        if (held.get(route) !== h) return;
        held.delete(route);
        heldFailed.add(route);
      })
      .finally(() => {
        if (held.get(route) === h) h.controller = null;
        pump();
      });
  }

  /** Held pages that are in the page list now (a new chapter's) become its loaded pages, as they are. */
  function takeOver(pages: readonly string[]) {
    for (const [i, route] of pages.entries()) {
      const h = held.get(route);
      if (!h?.url || states.has(i)) continue;
      held.delete(route);
      if (h.size) sizes.set(i, h.size);
      states.set(i, { status: "loaded", url: h.url });
    }
  }

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
    sizes.clear();
  }

  function start(i: number, route: string) {
    const controller = new AbortController();
    running.set(i, controller);
    states.set(i, { status: "loading" });
    const fresh = reload.delete(i);
    fetcher(route, { signal: controller.signal, reload: fresh })
      .then(async (blob) => {
        if (running.get(i) !== controller) return;
        const url = createUrl(blob);
        const size = await decode(url);
        if (running.get(i) !== controller) {
          revokeUrl(url);
          return;
        }
        if (size) sizes.set(i, size);
        states.set(i, { status: "loaded", url });
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

  function wantedPages(count: number): number[] {
    const given = toValue(opts.wanted);
    if (given) return given.filter((i) => i >= 0 && i < count);
    return pagesToLoad(
      toValue(opts.position),
      count,
      opts.behind ?? PAGES_BEHIND,
      toValue(opts.ahead) ?? PAGES_AHEAD,
    );
  }

  /** The page list the loaded pages belong to (by content: the same list fetched again keeps them). */
  let listKey = toValue(opts.pages).join("\n");

  function pump() {
    const pages = toValue(opts.pages);
    // Another chapter's list: start over here, not in the list's own watcher, so a watcher that runs first (the
    // window's, the position's) never sees the old pages under the new list and drops a held page it should take.
    const key = pages.join("\n");
    if (key !== listKey) {
      listKey = key;
      reset();
    }
    takeOver(pages);
    const wanted = wantedPages(pages.length);
    const keep = new Set(wanted);
    for (const i of [...states.keys()]) if (!keep.has(i)) drop(i);
    for (const [rank, i] of wanted.entries()) {
      const route = pages[i];
      if (states.has(i) || !route) continue;
      if (running.size + heldRunning().length >= max) {
        const busy = heldRunning();
        const h = busy[busy.length - 1];
        if (h !== undefined) {
          dropHeld(h);
          start(i, route);
          continue;
        }
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
    pumpHeld(pages, wanted);
  }

  function pumpHeld(pages: readonly string[], wanted: readonly number[]) {
    const list = (toValue(opts.held) ?? []).filter((r) => !pages.includes(r));
    const keep = new Set(list);
    // Not held any more, or in the page list now (taken over above, else the window asks for it itself).
    for (const r of [...held.keys()]) if (!keep.has(r)) dropHeld(r);
    for (const r of [...heldFailed]) if (!keep.has(r)) heldFailed.delete(r);
    // Only once every page of the window has its request: the chapter being read comes first.
    if (!wanted.every((i) => states.has(i) || !pages[i])) return;
    for (const r of list) {
      if (held.has(r) || heldFailed.has(r)) continue;
      if (running.size + heldRunning().length >= max) break;
      startHeld(r);
    }
  }

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

  // By content: the same chapter's list fetched again (a new array) keeps what is loaded (pump starts over otherwise).
  watch(() => toValue(opts.pages).join("\n"), pump);
  watch(() => toValue(opts.position), pump);
  watch(() => toValue(opts.wanted)?.join(",") ?? null, pump);
  watch(() => toValue(opts.ahead), pump);
  watch(() => toValue(opts.held)?.join("\n") ?? "", pump);
  pump();
  onScopeDispose(() => {
    reset();
    for (const r of [...held.keys()]) dropHeld(r);
  });

  return {
    /** A page's state; undefined while it waits for a free request (or is outside the window). */
    state: (i: number): PageState | undefined => states.get(i),
    /** A page's natural size, once it was decoded in this chapter. */
    size: (i: number): PageSize | undefined => sizes.get(i),
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
