import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Filter, Source } from "../../api/types";
import { serverFixtureFilters } from "../../test/filterFixtures";
import { makePage, makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchSourceFilters: vi.fn(),
  browseSource: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import SourceView from "../SourceView.vue";

/*
 * The source page against mocked operations. The result grid needs a size to ask for the next page, so its scroll
 * area is stubbed: 800 x 600, with a content height the tests set (`scrollHeight`).
 */
const animegg = makeSource();
const noLatest = makeSource({ id: "31", name: "NoLatest", supportsLatest: false });
const untrusted = makeSource({
  id: "32",
  name: "Stranger",
  supportsLatest: null,
  isConfigurable: null,
  extension: { pkgName: "x.stranger", name: "Stranger", isTrusted: false, isLoaded: false },
});

let sources: Source[];
let filters: Filter[];
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let scrollHeight = 5000;
const restore: (() => void)[] = [];

function stub(prop: "clientHeight" | "clientWidth" | "scrollHeight", value: () => number) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
  Object.defineProperty(HTMLElement.prototype, prop, {
    configurable: true,
    get() {
      return (this as HTMLElement).classList.contains("virtual-grid") ? value() : 0;
    },
  });
  restore.push(() => {
    if (original) Object.defineProperty(HTMLElement.prototype, prop, original);
    else delete (HTMLElement.prototype as unknown as Record<string, unknown>)[prop];
  });
}

beforeAll(() => {
  Element.prototype.scrollIntoView ??= () => {};
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.releasePointerCapture ??= () => {};
});

async function mountSource(sourceId = animegg.id) {
  wrapper = mountView(SourceView, { pinia, queryClient }, { sourceId });
  await flushPromises();
}

const cards = () => wrapper.findAll('[data-testid="media-card"]');
const tabs = () => wrapper.findAll("[data-tab]").map((t) => t.attributes("data-tab"));
const lastBrowse = () => ops.browseSource.mock.lastCall?.[0] as Record<string, unknown>;

async function openTab(key: string) {
  await wrapper.find(`[data-tab="${key}"]`).trigger("mousedown", { button: 0 });
  await flushPromises();
}

async function search(text: string) {
  await wrapper.find('[data-testid="source-search"]').setValue(text);
  await wrapper.find("form").trigger("submit");
  await flushPromises();
}

async function scrollToEnd() {
  const el = wrapper.find(".virtual-grid").element as HTMLElement;
  el.scrollTop = scrollHeight - 600;
  el.dispatchEvent(new Event("scroll"));
  await flushPromises();
}

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  sources = [animegg, noLatest, untrusted];
  filters = serverFixtureFilters;
  scrollHeight = 5000;
  stub("clientWidth", () => 800);
  stub("clientHeight", () => 600);
  stub("scrollHeight", () => scrollHeight);
  for (const op of Object.values(ops)) op.mockReset();
  ops.fetchSources.mockImplementation(async () => sources);
  ops.fetchSourceFilters.mockImplementation(async () => filters);
  ops.browseSource.mockImplementation(async (p: { page: number }) =>
    makePage((p.page - 1) * 20, 20, p.page < 3),
  );
});

afterEach(() => {
  wrapper?.unmount();
  queryClient.clear();
  restore.splice(0).forEach((r) => r());
  document.body.innerHTML = "";
});

describe("SourceView", () => {
  it("shows the source's popular entries as cards with covers and links", async () => {
    await mountSource();

    expect(wrapper.find("h1").text()).toBe("AnimeGG");
    expect(wrapper.text()).toContain("EN · https://www.animegg.org");
    expect(lastBrowse()).toEqual({
      kind: "anime",
      sourceId: animegg.id,
      type: "POPULAR",
      page: 1,
      query: undefined,
      filters: undefined,
    });
    expect(cards()).toHaveLength(20);
    const first = cards()[0];
    expect(first.attributes("href")).toBe("/anime/0");
    // the backend's thumbnail route, with the token (useAssetUrl)
    expect(first.find("img").attributes("src")).toBe(
      "http://127.0.0.1:50266/api/v1/anime/0/thumbnail?token=t",
    );
  });

  it("offers Latest only when the source has it", async () => {
    await mountSource();
    expect(tabs()).toEqual(["POPULAR", "LATEST", "SEARCH"]);
    await openTab("LATEST");
    expect(lastBrowse()).toMatchObject({ type: "LATEST", page: 1 });

    wrapper.unmount();
    await mountSource(noLatest.id);
    expect(tabs()).toEqual(["POPULAR", "SEARCH"]);
  });

  it("searches, and asks for a search on an empty Search tab", async () => {
    await mountSource();
    await openTab("SEARCH");
    const calls = ops.browseSource.mock.calls.length;
    expect(wrapper.find('[data-testid="search-prompt"]').text()).toContain("Type a search");
    expect(ops.browseSource.mock.calls.length).toBe(calls);

    await search("  frieren ");
    expect(lastBrowse()).toMatchObject({
      type: "SEARCH",
      query: "frieren",
      filters: undefined,
      page: 1,
    });
    expect(wrapper.find('[data-tab="SEARCH"]').attributes("data-state")).toBe("active");
  });

  it("applies filters from the sheet to a search, counts them, and clears them", async () => {
    await mountSource();
    await wrapper.find('[data-action="filters"]').trigger("click");
    await flushPromises();
    const sheet = document.body.querySelector<HTMLElement>('[data-testid="filter-sheet"]')!;
    sheet.querySelector<HTMLElement>("[data-position='3'] [role='checkbox']")!.click();
    await flushPromises();
    sheet.querySelector<HTMLElement>("[data-action='apply']")!.click();
    await flushPromises();

    expect(lastBrowse()).toMatchObject({
      type: "SEARCH",
      query: "",
      filters: [{ position: 3, checkBoxState: true }],
    });
    expect(wrapper.find('[data-testid="filter-count"]').text()).toBe("1");

    await search("frieren");
    expect(lastBrowse()).toMatchObject({
      query: "frieren",
      filters: [{ position: 3, checkBoxState: true }],
    });

    await wrapper.find('[data-action="clear-filters"]').trigger("click");
    await flushPromises();
    expect(lastBrowse()).toMatchObject({ query: "frieren", filters: undefined });
    expect(wrapper.find('[data-testid="filter-count"]').exists()).toBe(false);
  });

  it("loads the next page near the end, shows each entry once, and says when it's the end", async () => {
    // page 2 repeats the last 5 entries of page 1, as sources do
    ops.browseSource.mockImplementation(async (p: { page: number }) =>
      p.page === 1 ? makePage(0, 20, true) : makePage(15, 20, false),
    );
    await mountSource();
    expect(ops.browseSource).toHaveBeenCalledTimes(1);

    await scrollToEnd();

    expect(lastBrowse()).toMatchObject({ type: "POPULAR", page: 2 });
    const ids = cards().map((c) => c.attributes("data-id"));
    expect(ids).toHaveLength(35);
    expect(new Set(ids).size).toBe(35);
    expect(wrapper.find('[data-testid="results-end"]').text()).toContain("35 entries");

    await scrollToEnd();
    expect(ops.browseSource).toHaveBeenCalledTimes(2);
  });

  it("asks for the next page at once when the first doesn't fill the view", async () => {
    scrollHeight = 400;
    await mountSource();
    await flushPromises();

    // pages 1, 2 and 3 (the last)
    expect(ops.browseSource).toHaveBeenCalledTimes(3);
    expect(cards()).toHaveLength(60);
  });

  it("shows a source error readably, with a retry", async () => {
    ops.browseSource.mockRejectedValueOnce(
      new ApiError({ kind: "graphql", code: "SOURCE_FAILED", message: "HTTP error 503" }),
    );
    await mountSource();

    const alert = wrapper.find('[role="alert"]');
    expect(alert.text()).toContain("Couldn't load AnimeGG");
    expect(alert.text()).toContain("The source's website failed or couldn't be reached.");
    expect(alert.text()).toContain("SOURCE_FAILED");

    await alert.find("button").trigger("click");
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(cards()).toHaveLength(20);
  });

  it("keeps the loaded pages when the next one fails, and retries it", async () => {
    await mountSource();
    ops.browseSource.mockRejectedValueOnce(
      new ApiError({ kind: "graphql", code: "SOURCE_FAILED", message: "timeout" }),
    );
    await scrollToEnd();

    expect(cards()).toHaveLength(20);
    const alert = wrapper.find('[role="alert"]');
    expect(alert.text()).toContain("Couldn't load the next page");
    // no endless retries from scrolling
    await scrollToEnd();
    expect(ops.browseSource).toHaveBeenCalledTimes(2);

    await alert.find("button").trigger("click");
    await flushPromises();
    // page 2, then page 3: the view is still at the end
    expect(cards()).toHaveLength(60);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });

  it("doesn't browse a source the backend can't use, and points to Extensions", async () => {
    await mountSource(untrusted.id);

    expect(wrapper.find('[data-testid="source-unusable"]').text()).toContain(
      "signing certificate isn't trusted",
    );
    expect(ops.browseSource).not.toHaveBeenCalled();
    expect(ops.fetchSourceFilters).not.toHaveBeenCalled();
    await wrapper.find('[data-action="open-extensions"]').trigger("click");
    await flushPromises();
    expect(wrapper.vm.$router.currentRoute.value.path).toBe("/extensions");
  });

  it("links to the source's settings only when it has some", async () => {
    sources = [makeSource({ isConfigurable: true }), noLatest, untrusted];
    await mountSource();
    expect(wrapper.find('[data-action="source-settings"]').attributes("href")).toBe(
      `/settings/source/${animegg.id}`,
    );
    wrapper.unmount();

    sources = [animegg, noLatest, untrusted];
    queryClient.clear();
    await mountSource();
    expect(wrapper.find('[data-action="source-settings"]').exists()).toBe(false);
  });

  it("says when the source isn't installed", async () => {
    await mountSource("999");
    expect(wrapper.find('[data-testid="source-missing"]').text()).toContain("isn't installed");
    expect(ops.browseSource).not.toHaveBeenCalled();
  });

  it("keeps each source's tab, search and filters while the app runs", async () => {
    await mountSource();
    await search("frieren");
    wrapper.unmount();

    await mountSource();
    expect(wrapper.find('[data-tab="SEARCH"]').attributes("data-state")).toBe("active");
    expect((wrapper.find('[data-testid="source-search"]').element as HTMLInputElement).value).toBe(
      "frieren",
    );
    wrapper.unmount();

    await mountSource(noLatest.id);
    expect(wrapper.find('[data-tab="POPULAR"]').attributes("data-state")).toBe("active");
  });

  it("drops stored filters when the source's filter list changes", async () => {
    await mountSource();
    await wrapper.find('[data-action="filters"]').trigger("click");
    await flushPromises();
    const sheet = document.body.querySelector<HTMLElement>('[data-testid="filter-sheet"]')!;
    sheet.querySelector<HTMLElement>("[data-position='3'] [role='checkbox']")!.click();
    await flushPromises();
    sheet.querySelector<HTMLElement>("[data-action='apply']")!.click();
    await flushPromises();
    wrapper.unmount();

    // an extension update renamed a filter: the old positions may point elsewhere
    filters = serverFixtureFilters.map((f, i) => (i === 3 ? { ...f, name: "Dub only" } : f));
    queryClient.clear();
    await mountSource();
    await search("x");
    expect(lastBrowse()).toMatchObject({ query: "x", filters: undefined });
  });
});
