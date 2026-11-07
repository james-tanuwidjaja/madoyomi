import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Preference, Source, SourcePreferenceChange } from "../../api/types";
import { allPreferenceTypes } from "../../test/preferenceFixtures";
import { makePage, makeSource } from "../../test/fixtures";
import { setBackendReady } from "../../test/mountView";
import { withSetup } from "../../test/withSetup";
import { withChange } from "../../utils/preferences";

const ops = vi.hoisted(() => ({
  fetchSourcePreferences: vi.fn(),
  updateSourcePreference: vi.fn(),
  fetchSources: vi.fn(),
  browseSource: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import { useSourcePreferences, useUpdateSourcePreference } from "../usePreferences";
import { queryKeys, type BrowseKey } from "../queryKeys";
import { useSourceBrowse, useSources } from "../useSources";

/*
 * The optimistic preference change (Phase 3 step 19) over mocked operations: the value shows before the server
 * answers, a failure puts it back, a refusal is the server's own value, and a change that took drops what the old
 * source instance produced.
 */

const SOURCE = "2499283573021220255";
const key = queryKeys.sourcePreferences("anime", SOURCE);
const source = makeSource({ id: SOURCE, isConfigurable: true });

let pinia: Pinia;
let queryClient: QueryClient;
let unmount: (() => void) | null = null;

/** A promise to resolve by hand, for a change still on its way. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => ((resolve = res), (reject = rej)));
  return { promise, resolve, reject };
}

const cached = () => queryClient.getQueryData<Preference[]>(key)!;
const switchValue = () =>
  (cached()[1] as Extract<Preference, { __typename: "SwitchPreference" }>).switchCurrentValue;

function mountHooks() {
  const { result, unmount: u } = withSetup(
    () => ({
      prefs: useSourcePreferences("anime", SOURCE),
      update: useUpdateSourcePreference("anime", SOURCE),
    }),
    { pinia, queryClient },
  );
  unmount = u;
  return result;
}

/** The server's answer: the list with these changes applied (or not, for a refusal). */
const answer = (...changes: SourcePreferenceChange[]) => ({
  preferences: allPreferenceTypes.map((p, i) =>
    changes.reduce((q, c) => (c.position === i ? withChange(q, c) : q), p),
  ),
  source,
});

beforeEach(async () => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  ops.fetchSourcePreferences.mockResolvedValue(allPreferenceTypes);
});

afterEach(() => {
  unmount?.();
  unmount = null;
  queryClient.clear();
  vi.clearAllMocks();
});

describe("useUpdateSourcePreference", () => {
  const on = { position: 1, switchState: true };

  it("shows the new value before the server answers, then the server's list", async () => {
    const { prefs, update } = mountHooks();
    await vi.waitFor(() => expect(prefs.data.value).toHaveLength(7));
    const pending = deferred<ReturnType<typeof answer>>();
    ops.updateSourcePreference.mockReturnValue(pending.promise);

    const done = update.change(on);
    await flushPromises();
    expect(switchValue()).toBe(true);
    expect(update.status[1]).toEqual({ state: "saving" });
    expect(ops.updateSourcePreference).toHaveBeenCalledWith("anime", SOURCE, on);

    pending.resolve(answer(on));
    await done;
    expect(switchValue()).toBe(true);
    expect(update.status[1]).toBeUndefined();
  });

  it("puts the value back and reads the list again when the change fails", async () => {
    const { prefs, update } = mountHooks();
    await vi.waitFor(() => expect(prefs.data.value).toHaveLength(7));
    const error = new ApiError({ kind: "graphql", code: "INTERNAL", message: "boom" });
    ops.updateSourcePreference.mockRejectedValue(error);

    await update.change(on);
    await flushPromises();

    expect(switchValue()).toBe(false);
    expect(update.status[1]).toEqual({ state: "error", error });
    // the list was read again (the first read, plus the one after the failure)
    expect(ops.fetchSourcePreferences).toHaveBeenCalledTimes(2);
  });

  it("reports a refusal: the server kept the old value, which the list then shows", async () => {
    const { prefs, update } = mountHooks();
    await vi.waitFor(() => expect(prefs.data.value).toHaveLength(7));
    ops.updateSourcePreference.mockResolvedValue(answer()); // nothing changed

    await update.change(on);

    expect(switchValue()).toBe(false);
    expect(update.status[1]).toEqual({ state: "refused" });
  });

  it("drops the source's browse pages and filters, and reads the source list again after its next browse", async () => {
    const { prefs, update } = mountHooks();
    await vi.waitFor(() => expect(prefs.data.value).toHaveLength(7));
    const other = makeSource({ id: "77", name: "Other" });
    queryClient.setQueryData<Source[]>(queryKeys.sources("anime"), [source, other]);
    const browseOf = (sourceId: string): BrowseKey => ({
      kind: "anime",
      sourceId,
      type: "POPULAR",
      query: null,
      filters: null,
    });
    queryClient.setQueryData(queryKeys.browse(browseOf(SOURCE)), {
      pages: [makePage(1, 2, false)],
      pageParams: [1],
    });
    queryClient.setQueryData(queryKeys.browse(browseOf("77")), {
      pages: [makePage(1, 2, false)],
      pageParams: [1],
    });
    queryClient.setQueryData(queryKeys.sourceFilters("anime", SOURCE), []);
    ops.updateSourcePreference.mockResolvedValue(answer(on));

    await update.change(on);

    // the answer's source row is the old instance's: the list isn't touched yet
    expect(queryClient.getQueryData<Source[]>(queryKeys.sources("anime"))).toEqual([source, other]);
    expect(queryClient.getQueryData(queryKeys.browse(browseOf(SOURCE)))).toBeUndefined();
    expect(queryClient.getQueryData(queryKeys.browse(browseOf("77")))).toBeDefined();
    expect(queryClient.getQueryState(queryKeys.sourceFilters("anime", SOURCE))?.isInvalidated).toBe(
      true,
    );

    // the next browse of the source loads the new instance; the source list is read again after it, once
    const moved = { ...source, baseUrl: "https://new.example" };
    ops.fetchSources.mockResolvedValue([moved, other]);
    ops.browseSource.mockResolvedValue(makePage(1, 2, false));
    const { result: sourcesQuery, unmount: u2 } = withSetup(() => useSources("anime"), {
      pinia,
      queryClient,
    });
    const { result: browse, unmount: u3 } = withSetup(
      () => useSourceBrowse({ kind: "anime", sourceId: SOURCE, type: "POPULAR" }),
      { pinia, queryClient },
    );
    await vi.waitFor(() => expect(browse.data.value?.pages).toHaveLength(1));
    await vi.waitFor(() => expect(sourcesQuery.data.value).toEqual([moved, other]));
    expect(ops.fetchSources).toHaveBeenCalledTimes(1);
    u2();
    u3();
  });

  it("runs two quick changes in order, and keeps the newer value until the last answer", async () => {
    const { prefs, update } = mountHooks();
    await vi.waitFor(() => expect(prefs.data.value).toHaveLength(7));
    const off = { position: 1, switchState: false };
    const first = deferred<ReturnType<typeof answer>>();
    const second = deferred<ReturnType<typeof answer>>();
    ops.updateSourcePreference
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const a = update.change(on);
    const b = update.change(off);
    await flushPromises();
    // the second waits for the first (one scope), and the newest value shows
    expect(ops.updateSourcePreference).toHaveBeenCalledTimes(1);
    expect(switchValue()).toBe(false);

    first.resolve(answer(on));
    await a;
    await flushPromises();
    // the first answer isn't written while the second is pending
    expect(switchValue()).toBe(false);
    expect(ops.updateSourcePreference).toHaveBeenCalledTimes(2);
    expect(ops.updateSourcePreference.mock.calls[1][2]).toEqual(off);

    second.resolve(answer(on, off));
    await b;
    expect(switchValue()).toBe(false);
    expect(update.status[1]).toBeUndefined();
  });
});
