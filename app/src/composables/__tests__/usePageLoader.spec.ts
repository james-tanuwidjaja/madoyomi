import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { nextTick, ref } from "vue";
import { createPinia } from "pinia";
import { QueryClient } from "@tanstack/vue-query";
import { flushPromises } from "@vue/test-utils";

import { ApiError } from "../../api/client";
import { usePageLoader, type PageFetcher, type PageSize } from "../usePageLoader";
import { withSetup } from "../../test/withSetup";

/*
 * The reader's page loader (Phase 5 Stage 2) over a scripted fetcher: at most 4 requests at a time, the current page
 * first, pages leaving the window aborted and their object URLs revoked, and a retry past the cache. Stage 3: a
 * window given by the webtoon strip, decoding before a page counts as loaded, and the sizes kept for the chapter.
 */
interface Call {
  route: string;
  reload: boolean;
  signal: AbortSignal;
  resolve: (b: Blob) => void;
  reject: (e: unknown) => void;
  settled: boolean;
}

let calls: Call[];
let revoked: string[];
let unmount: () => void;

const routes = (n: number) =>
  Array.from({ length: n }, (_, i) => `/api/v1/manga/34/chapter/1/page/${i}`);
const index = (c: Call) => Number(c.route.split("/").pop());

const fetcher: PageFetcher = (route, o) =>
  new Promise<Blob>((resolve, reject) => {
    const call: Call = {
      route,
      reload: o.reload,
      signal: o.signal,
      settled: false,
      resolve: (b) => {
        call.settled = true;
        resolve(b);
      },
      reject: (e) => {
        call.settled = true;
        reject(e);
      },
    };
    calls.push(call);
    o.signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  });

function setup(pageCount: number, start = 0) {
  const pages = ref(routes(pageCount));
  const position = ref(start);
  const s = withSetup(
    () =>
      usePageLoader({
        pages,
        position,
        fetcher,
        createUrl: (b) => `blob:${b.size}:${Math.random()}`,
        revokeUrl: (u) => revoked.push(u),
      }),
    { pinia: createPinia(), queryClient: new QueryClient() },
  );
  unmount = s.unmount;
  return { loader: s.result, pages, position };
}

/** The requests still running: neither answered nor aborted. */
const open = () => calls.filter((c) => !c.settled && !c.signal.aborted);
const settle = async (c: Call, size = 3) => {
  c.resolve(new Blob(["x".repeat(size)]));
  await flushPromises();
};

beforeEach(() => {
  calls = [];
  revoked = [];
});

afterEach(() => unmount());

describe("usePageLoader", () => {
  it("runs at most 4 requests, the current page first, then ahead, then behind", async () => {
    const { loader } = setup(92, 5);

    expect(calls.map(index)).toEqual([5, 6, 7, 8]);
    expect(loader.inFlight).toBe(4);
    expect(loader.state(5)).toEqual({ status: "loading" });
    expect(loader.state(9)).toBeUndefined();

    await settle(calls[0]!);
    expect(loader.state(5)).toMatchObject({ status: "loaded" });
    expect(calls.map(index)).toEqual([5, 6, 7, 8, 9]);
    await settle(calls[1]!);
    expect(calls.map(index)).toEqual([5, 6, 7, 8, 9, 4]);
    expect(open().length).toBeLessThanOrEqual(4);
  });

  it("aborts the pages a jump leaves behind and revokes what was loaded", async () => {
    const { loader, position } = setup(92, 0);
    await settle(calls[0]!);
    const url = (loader.state(0) as { url: string }).url;

    position.value = 50;
    await nextTick();
    await flushPromises();

    expect(calls.slice(0, 4).every((c) => c.signal.aborted || index(c) === 0)).toBe(true);
    expect(revoked).toEqual([url]);
    expect(loader.state(0)).toBeUndefined();
    expect(open().map(index)).toEqual([50, 51, 52, 53]);
  });

  it("lets the page shown take the place of one further ahead when every request is taken", async () => {
    const { loader } = setup(92, 0);
    expect(open().map(index)).toEqual([0, 1, 2, 3]);
    calls[0]!.reject(new Error("HTTP 503"));
    await flushPromises();
    // page 4 took the free request
    expect(open().map(index)).toEqual([1, 2, 3, 4]);

    loader.retry(0);
    await flushPromises();
    expect(open().map(index)).toEqual([1, 2, 3, 0]);
    expect(calls.find((c) => index(c) === 4)!.signal.aborted).toBe(true);
    expect(loader.state(4)).toBeUndefined();
  });

  it("keeps a failed page's error until a retry, which skips the cache", async () => {
    const { loader } = setup(3, 0);
    const failed = new ApiError({
      kind: "http",
      code: "SOURCE_FAILED",
      message: "The source answered HTTP 403 for this page",
      details: { httpCode: 403, retried: true },
      status: 424,
    });
    calls[0]!.reject(failed);
    await flushPromises();

    expect(loader.state(0)).toEqual({ status: "error", error: failed });
    expect(calls.filter((c) => index(c) === 0)).toHaveLength(1);

    loader.retry(0);
    const again = calls.filter((c) => index(c) === 0);
    expect(again).toHaveLength(2);
    expect(again[1]!.reload).toBe(true);
    await settle(again[1]!);
    expect(loader.state(0)).toMatchObject({ status: "loaded" });
  });

  it("fetches the pages that failed while the backend was away again, but not a source's failure", async () => {
    const { loader } = setup(3, 0);
    const away = () =>
      new ApiError({ kind: "unavailable", code: "BACKEND_UNAVAILABLE", message: "not ready" });
    calls[0]!.reject(away());
    calls[1]!.reject(
      new ApiError({ kind: "http", code: "SOURCE_FAILED", message: "HTTP 403", status: 424 }),
    );
    calls[2]!.reject(away());
    await flushPromises();

    loader.retryTransient();
    expect(calls.slice(3).map(index)).toEqual([0, 2]);
    expect(calls.slice(3).every((c) => c.reload)).toBe(true);
    expect(loader.state(1)).toMatchObject({ status: "error" });

    // a click on one page also takes the others that failed while the backend was away
    calls[3]!.reject(away());
    calls[4]!.reject(away());
    await flushPromises();
    loader.retry(1);
    expect(calls.slice(5).map(index).sort()).toEqual([0, 1, 2]);
  });

  it("starts over for another chapter's pages, and keeps them for the same list fetched again", async () => {
    const { loader, pages } = setup(5, 0);
    await settle(calls[0]!);

    pages.value = routes(5); // the same routes, a new array
    await nextTick();
    expect(loader.state(0)).toMatchObject({ status: "loaded" });

    pages.value = routes(5).map((r) => r.replace("chapter/1", "chapter/2"));
    await nextTick();
    await flushPromises();
    expect(revoked).toHaveLength(1);
    expect(open().every((c) => c.route.includes("chapter/2"))).toBe(true);
  });

  it("revokes everything when the reader closes", async () => {
    const { loader } = setup(5, 0);
    await settle(calls[0]!);
    await settle(calls[1]!);
    expect(loader.state(1)).toMatchObject({ status: "loaded" });

    unmount();
    unmount = () => {};

    expect(revoked).toHaveLength(2);
    expect(calls.filter((c) => c.signal.aborted).length).toBeGreaterThan(0);
  });
});

describe("usePageLoader with the webtoon strip's window (Stage 3)", () => {
  let decodes: { url: string; resolve: (s: PageSize | null) => void }[];

  function setupStrip(pageCount: number, wanted: number[] | null) {
    decodes = [];
    const pages = ref(routes(pageCount));
    const position = ref(0);
    const window = ref<number[] | null>(wanted);
    const s = withSetup(
      () =>
        usePageLoader({
          pages,
          position,
          wanted: window,
          fetcher,
          createUrl: (b) => `blob:${b.size}:${Math.random()}`,
          revokeUrl: (u) => revoked.push(u),
          decode: (url) =>
            new Promise<PageSize | null>((resolve) => decodes.push({ url, resolve })),
        }),
      { pinia: createPinia(), queryClient: new QueryClient() },
    );
    unmount = s.unmount;
    return { loader: s.result, window, position, pages };
  }

  it("loads the window it is given, in its order, still 4 at a time", async () => {
    const { window, position } = setupStrip(20, [7, 8, 9, 10, 11, 6]);
    expect(calls.map(index)).toEqual([7, 8, 9, 10]);

    position.value = 15; // the paged position doesn't count while a window is given
    await nextTick();
    expect(open().map(index)).toEqual([7, 8, 9, 10]);

    window.value = null; // back to ±N of the position
    await nextTick();
    await flushPromises();
    expect(open().map(index)).toEqual([15, 16, 17, 18]);
  });

  it("counts a page as loaded once it is decoded, and keeps its size after it is released", async () => {
    const { loader, window } = setupStrip(10, [0, 1]);
    await settle(calls[0]!);
    expect(loader.state(0)).toEqual({ status: "loading" });
    expect(decodes).toHaveLength(1);

    decodes[0]!.resolve({ width: 800, height: 1280 });
    await flushPromises();
    expect(loader.state(0)).toMatchObject({ status: "loaded", url: decodes[0]!.url });
    expect(loader.size(0)).toEqual({ width: 800, height: 1280 });

    window.value = [5];
    await nextTick();
    await flushPromises();
    expect(loader.state(0)).toBeUndefined();
    expect(revoked).toContain(decodes[0]!.url);
    expect(loader.size(0)).toEqual({ width: 800, height: 1280 });
  });

  it("revokes a page that leaves the window while it decodes, and forgets sizes for another chapter", async () => {
    const { loader, window, pages } = setupStrip(10, [0, 1]);
    await settle(calls[0]!);
    window.value = [6];
    await nextTick();
    decodes[0]!.resolve({ width: 800, height: 900 });
    await flushPromises();
    expect(revoked).toEqual([decodes[0]!.url]);
    expect(loader.state(0)).toBeUndefined();
    expect(loader.size(0)).toBeUndefined();

    const six = calls.find((c) => index(c) === 6)!;
    await settle(six);
    decodes[1]!.resolve({ width: 800, height: 2400 });
    await flushPromises();
    expect(loader.size(6)).toEqual({ width: 800, height: 2400 });

    pages.value = routes(10).map((r) => r.replace("chapter/1", "chapter/2"));
    await nextTick();
    expect(loader.size(6)).toBeUndefined();
  });

  it("shows a page that can't be measured anyway", async () => {
    const { loader } = setupStrip(3, [0]);
    await settle(calls[0]!);
    decodes[0]!.resolve(null);
    await flushPromises();
    expect(loader.state(0)).toMatchObject({ status: "loaded" });
    expect(loader.size(0)).toBeUndefined();
  });
});

describe("usePageLoader: the preload count (Stage 5)", () => {
  it("loads as many pages ahead as the count says, and follows a new count at once", async () => {
    const ahead = ref(2);
    const position = ref(0);
    const s = withSetup(
      () =>
        usePageLoader({
          pages: routes(30),
          position,
          ahead,
          fetcher,
          createUrl: (b) => `blob:${b.size}:${Math.random()}`,
          revokeUrl: (u) => revoked.push(u),
        }),
      { pinia: createPinia(), queryClient: new QueryClient() },
    );
    unmount = s.unmount;
    expect(calls.map(index)).toEqual([0, 1, 2]);
    for (const c of [...calls]) await settle(c);
    expect(calls.map(index)).toEqual([0, 1, 2]);

    ahead.value = 8;
    await nextTick();
    await flushPromises();
    expect(open().map(index)).toEqual([3, 4, 5, 6]);
    for (const c of open()) await settle(c);
    expect(calls.map(index)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8]);

    // a lower count releases the pages past it
    ahead.value = 2;
    await nextTick();
    expect(s.result.state(8)).toBeUndefined();
    expect(s.result.state(2)).toMatchObject({ status: "loaded" });
  });
});

describe("usePageLoader: the neighbours' opening pages held ahead (Stage 6)", () => {
  const next = (i: number) => `/api/v1/manga/34/chapter/2/page/${i}`;

  function setupHeld(pageCount: number, wanted: number[], heldRoutes: string[]) {
    const pages = ref(routes(pageCount));
    const window = ref<number[] | null>(wanted);
    const heldList = ref(heldRoutes);
    const s = withSetup(
      () =>
        usePageLoader({
          pages,
          position: 0,
          wanted: window,
          held: heldList,
          fetcher,
          createUrl: (b) => `blob:${b.size}:${Math.random()}`,
          revokeUrl: (u) => revoked.push(u),
          decode: async () => ({ width: 800, height: 1500 }),
        }),
      { pinia: createPinia(), queryClient: new QueryClient() },
    );
    unmount = s.unmount;
    return { loader: s.result, pages, window, heldList };
  }

  it("waits until every page of the window has its request, and uses only free requests", async () => {
    setupHeld(10, [5, 6, 7, 8, 9], [next(0), next(1)]);
    expect(calls.map((c) => c.route)).toEqual(routes(10).slice(5, 9));

    await settle(calls[0]!); // page 9 gets the free request first
    expect(calls.map((c) => c.route).slice(4)).toEqual([routes(10)[9]]);

    await settle(calls[1]!); // the window has its requests: the next chapter's first page
    expect(calls.map((c) => c.route).slice(5)).toEqual([next(0)]);
    await settle(calls[2]!);
    expect(calls.map((c) => c.route).slice(5)).toEqual([next(0), next(1)]);
    expect(open().length).toBeLessThanOrEqual(4);
  });

  it("gives a held page's request to a page of the window that needs one", async () => {
    const { window } = setupHeld(10, [0], [next(0), next(1), next(2), next(3)]);
    await settle(calls[0]!);
    expect(open().map((c) => c.route)).toEqual([next(0), next(1), next(2), next(3)]);

    window.value = [1];
    await nextTick();
    await flushPromises();
    expect(open().map((c) => c.route)).toContain(routes(10)[1]);
    expect(open()).toHaveLength(4);
  });

  it("takes a held page over, with its size, when the next chapter's list holds it", async () => {
    const { loader, pages, window } = setupHeld(3, [0], [next(0)]);
    await settle(calls[0]!);
    const held = calls.find((c) => c.route === next(0))!;
    await settle(held, 7);
    const fetched = calls.length;

    window.value = [0, 1];
    pages.value = [next(0), next(1), next(2)];
    await nextTick();
    await flushPromises();
    expect(loader.state(0)).toMatchObject({
      status: "loaded",
      url: expect.stringMatching(/^blob:7:/),
    });
    expect(loader.size(0)).toEqual({ width: 800, height: 1500 });
    expect(calls.slice(fetched).map((c) => c.route)).toEqual([next(1)]);
    expect(revoked.some((u) => u.startsWith("blob:7:"))).toBe(false);
  });

  it("drops and revokes a held page that isn't held any more, and every held page on close", async () => {
    const { heldList } = setupHeld(2, [0], [next(0), next(1)]);
    await settle(calls[0]!);
    await settle(
      calls.find((c) => c.route === next(0))!,
      5,
    );
    await settle(
      calls.find((c) => c.route === next(1))!,
      6,
    );

    heldList.value = [next(1)];
    await nextTick();
    await flushPromises();
    expect(revoked.filter((u) => u.startsWith("blob:5:"))).toHaveLength(1);

    unmount();
    unmount = () => {};
    expect(revoked.filter((u) => u.startsWith("blob:6:"))).toHaveLength(1);
  });

  it("forgets a held page that failed: the chapter's own loader asks for it", async () => {
    const { loader, pages } = setupHeld(1, [0], [next(0)]);
    await settle(calls[0]!);
    calls.find((c) => c.route === next(0))!.reject(new Error("offline"));
    await flushPromises();

    pages.value = [next(0)];
    await nextTick();
    await flushPromises();
    expect(loader.state(0)).toEqual({ status: "loading" });
    expect(calls.filter((c) => c.route === next(0))).toHaveLength(2);
  });
});
