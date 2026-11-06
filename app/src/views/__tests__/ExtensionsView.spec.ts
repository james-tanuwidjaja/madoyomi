import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import { LOCAL_SOURCE_PKG, type Extension } from "../../api/types";
import { useEventsStore } from "../../stores/events";
import { useMediaKindStore } from "../../stores/mediaKind";
import { installed, makeExtension, makeStore, YUZONO_STORE_URL } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchExtensions: vi.fn(),
  fetchExtensionStores: vi.fn(),
  updateExtension: vi.fn(),
  trustExtension: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import ExtensionsView from "../ExtensionsView.vue";

const pkg = (name: string) => `eu.kanade.tachiyomi.animeextension.${name}`;

const animegg = installed(makeExtension({ pkgName: pkg("en.animegg"), name: "AnimeGG" }));
const flv = makeExtension({ pkgName: pkg("es.animeflv"), name: "AnimeFLV", lang: "es" });
const hentai = makeExtension({ pkgName: pkg("en.hx"), name: "HX", contentWarning: "NSFW" });
const allanime = makeExtension({ pkgName: pkg("all.allanime"), name: "AllAnime", lang: "all" });
const outdated = installed(
  makeExtension({ pkgName: pkg("en.outdated"), name: "Outdated", hasUpdate: true }),
);
const untrusted = installed(makeExtension({ pkgName: pkg("en.stranger"), name: "Stranger" }), {
  isTrusted: false,
  isLoaded: false,
  signatureHash: "deadbeef".repeat(8),
});
const gappy = installed(makeExtension({ pkgName: pkg("en.gappy"), name: "Gappy" }), {
  compatIssues: [
    {
      kind: "MISSING_CLASS",
      target: "android.webkit.WebView",
      origin: "STATIC",
      context: "a.b.C",
      count: "1",
    },
  ],
});
// upstream's built-in Local source has a fake extension row
const local = makeExtension({ pkgName: LOCAL_SOURCE_PKG, name: "Local Source fake extension" });

let list: Extension[];
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;

async function mountExtensions() {
  wrapper = mountView(ExtensionsView, { pinia, queryClient });
  await flushPromises();
}

const names = () => wrapper.findAll('[data-testid="extension-row"] .name').map((n) => n.text());
const row = (p: string) => wrapper.find(`[data-testid="extension-row"][data-pkg="${p}"]`);
const tab = (key: string) => wrapper.find(`[data-tab="${key}"]`);

/** Reka's tabs change on mousedown (a pointer press), not on click. */
async function openTab(key: string) {
  await tab(key).trigger("mousedown", { button: 0 });
  await flushPromises();
}

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  list = [animegg, flv, hentai, allanime, outdated, untrusted, gappy, local];
  ops.fetchExtensions.mockImplementation(async () => list);
  ops.fetchExtensionStores.mockResolvedValue([
    makeStore({ indexUrl: YUZONO_STORE_URL, name: "Yuzono Anime", type: "ANIME" }),
  ]);
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("ExtensionsView", () => {
  it("splits the current kind's extensions into Available, Installed and Updates", async () => {
    await mountExtensions();

    expect(ops.fetchExtensions).toHaveBeenCalledWith("anime", expect.anything());
    // 18+ is hidden by default; the Local source's fake extension never shows
    expect(names()).toEqual(["AllAnime", "AnimeFLV"]);
    expect(tab("available").text()).toBe("Available 2");
    expect(tab("installed").text()).toBe("Installed 4");
    expect(tab("updates").text()).toBe("Updates 1");

    await openTab("installed");
    // what needs attention first: untrusted, then updatable
    expect(names()).toEqual(["Stranger", "Outdated", "AnimeGG", "Gappy"]);
    expect(row(untrusted.pkgName).find('[data-testid="badge-untrusted"]').exists()).toBe(true);
    expect(row(untrusted.pkgName).find('[data-action="trust"]').exists()).toBe(true);
    expect(row(animegg.pkgName).find('[data-action="trust"]').exists()).toBe(false);
    expect(row(animegg.pkgName).text()).toContain("Yuzono Anime");
    expect(row(gappy.pkgName).find('[data-action="compat-issues"]').text()).toBe("1 API gap");

    await openTab("updates");
    expect(names()).toEqual(["Outdated"]);
    expect(row(outdated.pkgName).find('[data-action="update"]').exists()).toBe(true);
  });

  it("searches, filters by language and shows 18+ on request", async () => {
    await mountExtensions();

    await wrapper.find('[data-testid="extension-search"]').setValue("flv");
    expect(names()).toEqual(["AnimeFLV"]);
    await wrapper.find('[data-testid="extension-search"]').setValue("allanime");
    expect(names()).toEqual(["AllAnime"]); // by package name too
    await wrapper.find('[data-testid="extension-search"]').setValue("");

    const lang = wrapper.find('[data-testid="extension-lang"]');
    expect(lang.findAll("option").map((o) => o.text())).toEqual([
      "Any language",
      "EN",
      "ES",
      "Multi-language",
    ]);
    await lang.setValue("es");
    expect(names()).toEqual(["AnimeFLV"]);
    await lang.setValue("");

    await wrapper.find('[data-testid="extension-nsfw"]').trigger("click");
    await flushPromises();
    expect(names()).toEqual(["AllAnime", "AnimeFLV", "HX"]);
    expect(localStorage.getItem("madoyomi.showNsfw")).toBe("true");

    await wrapper.find('[data-testid="extension-search"]').setValue("nothing like it");
    expect(wrapper.find('[data-testid="extensions-none"]').text()).toBe("Nothing matches here.");
  });

  it("shows the install steps from the events store, and a failed step's reason", async () => {
    await mountExtensions();
    const events = useEventsStore();

    events.recordInstall({
      pkgName: flv.pkgName,
      type: "ANIME",
      state: "CONVERTING",
      errorCode: null,
      message: null,
    });
    await flushPromises();
    expect(row(flv.pkgName).find('[data-testid="extension-step"]').text()).toContain("Converting…");

    events.recordInstall({
      pkgName: flv.pkgName,
      type: "ANIME",
      state: "FAILED",
      errorCode: "SIGNATURE_INVALID",
      message: "APK signature verification failed",
    });
    await flushPromises();
    const error = row(flv.pkgName).find('[data-testid="extension-error"]').text();
    expect(error).toContain("SIGNATURE_INVALID");
    expect(error).toContain("isn't properly signed");
    expect(error).toContain("APK signature verification failed");
  });

  it("installs, shows the action while it runs, and refetches the list", async () => {
    let finish!: (e: Extension) => void;
    ops.updateExtension.mockImplementation(
      () => new Promise<Extension>((resolve) => (finish = resolve)),
    );
    await mountExtensions();

    await row(flv.pkgName).find('[data-action="install"]').trigger("click");
    await flushPromises();
    expect(ops.updateExtension).toHaveBeenCalledExactlyOnceWith(flv.pkgName, "install");
    expect(row(flv.pkgName).find('[data-testid="extension-step"]').text()).toContain("Starting…");
    expect(row(flv.pkgName).find('[data-action="install"]').attributes("disabled")).toBeDefined();

    list = list.map((e) => (e.pkgName === flv.pkgName ? installed(flv) : e));
    finish(installed(flv));
    await flushPromises();

    expect(ops.fetchExtensions).toHaveBeenCalledTimes(2);
    expect(names()).toEqual(["AllAnime"]);
    expect(document.body.querySelector('[data-testid="trust-dialog"]')).toBeNull();
  });

  it("shows why an install failed", async () => {
    ops.updateExtension.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "INSTALL_FAILED", message: "dex2jar failed" }),
    );
    await mountExtensions();

    await row(flv.pkgName).find('[data-action="install"]').trigger("click");
    await flushPromises();

    const error = row(flv.pkgName).find('[data-testid="extension-error"]').text();
    expect(error).toContain("INSTALL_FAILED");
    expect(error).toContain("couldn't be installed");
    expect(error).toContain("dex2jar failed");
  });

  it("opens the trust prompt from an untrusted extension", async () => {
    await mountExtensions();
    await openTab("installed");

    await row(untrusted.pkgName).find('[data-action="trust"]').trigger("click");
    await flushPromises();

    const dialog = document.body.querySelector('[data-testid="trust-dialog"]')!;
    expect(dialog.querySelector('[data-testid="trust-pkg"]')!.textContent).toBe(untrusted.pkgName);
    expect(dialog.querySelector('[data-testid="trust-hash"]')!.textContent).toBe(
      untrusted.signatureHash,
    );
  });

  it("asks for trust right after installing an extension whose certificate isn't trusted", async () => {
    const after = installed(flv, { isTrusted: false, isLoaded: false });
    ops.updateExtension.mockResolvedValue(after);
    await mountExtensions();

    await row(flv.pkgName).find('[data-action="install"]').trigger("click");
    await flushPromises();

    const dialog = document.body.querySelector('[data-testid="trust-dialog"]');
    expect(dialog?.querySelector('[data-testid="trust-pkg"]')?.textContent).toBe(flv.pkgName);
  });

  it("uninstalls without asking", async () => {
    ops.updateExtension.mockResolvedValue(flv);
    await mountExtensions();
    await openTab("installed");

    await row(animegg.pkgName).find('[data-action="uninstall"]').trigger("click");
    await flushPromises();

    expect(ops.updateExtension).toHaveBeenCalledExactlyOnceWith(animegg.pkgName, "uninstall");
  });

  it("points to the tab where the search finds something", async () => {
    await mountExtensions();

    await wrapper.find('[data-testid="extension-search"]').setValue("animegg");
    const none = wrapper.find('[data-testid="extensions-none"]');
    expect(none.text()).toBe("Nothing matches here. 1 under Installed");

    await none.find('[data-action="show-installed"]').trigger("click");
    await flushPromises();
    expect(names()).toEqual(["AnimeGG"]);
  });

  it("starts the search over when the kind changes", async () => {
    await mountExtensions();
    await wrapper.find('[data-testid="extension-search"]').setValue("flv");

    useMediaKindStore().set("manga");
    await flushPromises();

    expect(ops.fetchExtensions).toHaveBeenLastCalledWith("manga", expect.anything());
    expect(
      (wrapper.find('[data-testid="extension-search"]').element as HTMLInputElement).value,
    ).toBe("");
  });

  it("points to Repos when there is no repo of the kind", async () => {
    list = [];
    ops.fetchExtensionStores.mockResolvedValue([]);
    await mountExtensions();

    expect(wrapper.find('[data-testid="extensions-empty"]').text()).toContain("add a repo");
  });
});
