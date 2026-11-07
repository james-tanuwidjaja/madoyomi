import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { Source } from "../../api/types";
import { useExtensionFiltersStore } from "../../stores/extensionFilters";
import { useMediaKindStore } from "../../stores/mediaKind";
import { PINNED_SOURCES_KEY } from "../../stores/pinnedSources";
import { makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({ fetchSources: vi.fn() }));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import BrowseView from "../BrowseView.vue";

const animegg = makeSource();
const allanime = makeSource({ id: "11", name: "AllAnime", lang: "all" });
const flv = makeSource({ id: "12", name: "AnimeFLV", lang: "es" });
const jkanime = makeSource({ id: "13", name: "JKAnime", lang: "es" });
const stranger = makeSource({
  id: "14",
  name: "Stranger",
  extension: { pkgName: "x.stranger", name: "Stranger", isTrusted: false, isLoaded: false },
  supportsLatest: null,
  isConfigurable: null,
});
const broken = makeSource({
  id: "15",
  name: "Broken",
  extension: { pkgName: "x.broken", name: "Broken", isTrusted: true, isLoaded: false },
});
// upstream's Local source: its fake extension has no signature, so it reads as untrusted
const local = makeSource({
  kind: "manga",
  id: "0",
  name: "Local source",
  lang: "localsourcelang",
  baseUrl: null,
  extension: {
    pkgName: "eu.kanade.tachiyomi.source.local",
    name: "Local Source",
    isTrusted: false,
    isLoaded: false,
  },
});
const mangapill = makeSource({
  kind: "manga",
  id: "21",
  name: "MangaPill",
  extension: {
    pkgName: "eu.kanade.tachiyomi.extension.en.mangapill",
    name: "MangaPill",
    isTrusted: true,
    isLoaded: true,
  },
});

let lists: Record<string, Source[]>;
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;

async function mountBrowse() {
  wrapper = mountView(BrowseView, { pinia, queryClient });
  await flushPromises();
}

const groups = () =>
  wrapper.findAll('[data-testid="source-group"]').map((g) => ({
    key: g.attributes("data-group"),
    title: g.find("h2").text(),
    names: g.findAll(".row-title").map((n) => n.text()),
  }));
const row = (id: string) => wrapper.find(`[data-testid="source-row"][data-id="${id}"]`);

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  lists = { anime: [animegg, allanime, flv, jkanime, stranger, broken], manga: [local, mangapill] };
  ops.fetchSources.mockReset();
  ops.fetchSources.mockImplementation(async (kind: string) => lists[kind]);
});

afterEach(() => {
  wrapper?.unmount();
  queryClient.clear();
});

describe("BrowseView", () => {
  it("groups the current kind's sources by language, multi-language first, by name", async () => {
    await mountBrowse();

    expect(ops.fetchSources).toHaveBeenCalledWith("anime", expect.anything());
    expect(groups()).toEqual([
      { key: "lang:all", title: "All languages", names: ["AllAnime"] },
      { key: "lang:en", title: "EN", names: ["AnimeGG", "Broken", "Stranger"] },
      { key: "lang:es", title: "ES", names: ["AnimeFLV", "JKAnime"] },
    ]);
    expect(row(animegg.id).find("a").attributes("href")).toBe(`/browse/${animegg.id}`);
  });

  it("pins sources to the top, in pin order, and keeps the pins across sessions", async () => {
    await mountBrowse();

    await row(flv.id).find('[data-action="pin"]').trigger("click");
    await row(animegg.id).find('[data-action="pin"]').trigger("click");

    expect(groups()[0]).toEqual({ key: "pinned", title: "Pinned", names: ["AnimeFLV", "AnimeGG"] });
    // a pinned source shows once, with its language
    expect(
      groups()
        .flatMap((g) => g.names)
        .filter((n) => n === "AnimeGG"),
    ).toHaveLength(1);
    expect(row(flv.id).find(".row-sub").text()).toContain("ES");
    expect(row(flv.id).find('[data-action="pin"]').attributes("aria-pressed")).toBe("true");
    expect(JSON.parse(localStorage.getItem(PINNED_SOURCES_KEY)!)).toEqual({
      anime: [flv.id, animegg.id],
      manga: [],
    });

    // a new session reads them back
    wrapper.unmount();
    pinia = createPinia();
    setActivePinia(pinia);
    setBackendReady();
    await mountBrowse();
    expect(groups()[0].names).toEqual(["AnimeFLV", "AnimeGG"]);

    // unpinned, it goes back to its language
    await row(flv.id).find('[data-action="pin"]').trigger("click");
    expect(groups()[0].names).toEqual(["AnimeGG"]);
    expect(groups().find((g) => g.key === "lang:es")?.names).toEqual(["AnimeFLV", "JKAnime"]);
  });

  it("keeps pins per kind", async () => {
    await mountBrowse();
    await row(animegg.id).find('[data-action="pin"]').trigger("click");

    useMediaKindStore().set("manga");
    await flushPromises();

    expect(groups().map((g) => g.key)).not.toContain("pinned");
  });

  it("shows the Local source as built in, never as untrusted, and browsable", async () => {
    useMediaKindStore().set("manga");
    await mountBrowse();

    expect(groups()).toEqual([
      { key: "lang:en", title: "EN", names: ["MangaPill"] },
      { key: "builtIn", title: "Built in", names: ["Local source"] },
    ]);
    const localRow = row("0");
    expect(localRow.attributes("data-access")).toBe("builtIn");
    expect(localRow.text()).toContain("Built in");
    expect(localRow.text()).not.toContain("Untrusted");
    expect(localRow.find("a").attributes("href")).toBe("/browse/0");
  });

  it("doesn't open a source the backend can't use, and points to Extensions instead", async () => {
    await mountBrowse();

    const untrusted = row(stranger.id);
    expect(untrusted.attributes("data-access")).toBe("untrusted");
    expect(untrusted.find(".badge.warn").text()).toBe("Untrusted");
    expect(untrusted.find("a").exists()).toBe(false);
    expect(row(broken.id).find(".badge.warn").text()).toBe("Not loaded");
    expect(row(broken.id).find("a").exists()).toBe(false);

    const router = wrapper.vm.$router;
    await untrusted.find('[data-action="open-extensions"]').trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/extensions");
    const filters = useExtensionFiltersStore();
    expect(filters.tab).toBe("installed");
    expect(filters.search).toBe("x.stranger");
  });

  it("links a configurable source to its settings", async () => {
    lists.anime = [makeSource({ id: "21", name: "KickAssAnime", isConfigurable: true }), animegg];
    await mountBrowse();

    expect(row("21").find('[data-action="source-settings"]').attributes("href")).toBe(
      "/settings/source/21",
    );
    expect(row(animegg.id).find('[data-action="source-settings"]').exists()).toBe(false);
  });

  it("says when there are no sources yet", async () => {
    lists.anime = [];
    await mountBrowse();

    expect(wrapper.find('[data-testid="sources-empty"]').text()).toContain("No anime sources yet");
  });
});
