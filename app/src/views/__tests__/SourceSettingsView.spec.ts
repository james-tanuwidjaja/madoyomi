import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Source } from "../../api/types";
import { allPreferenceTypes } from "../../test/preferenceFixtures";
import { makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";
import { useExtensionFiltersStore } from "../../stores/extensionFilters";
import { withChange } from "../../utils/preferences";

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchSourcePreferences: vi.fn(),
  updateSourcePreference: vi.fn(),
}));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import SourceSettingsView from "../SourceSettingsView.vue";

/* A source's settings page over mocked operations: its states, and a change going out by position. */

const pahe = makeSource({
  id: "42",
  name: "AnimePahe",
  isConfigurable: true,
  extension: {
    pkgName: "eu.kanade.tachiyomi.animeextension.en.animepahe",
    name: "AnimePahe",
    isTrusted: true,
    isLoaded: true,
  },
});
const plain = makeSource({ id: "43", name: "AnimeGG", isConfigurable: false });
const untrusted = makeSource({
  id: "44",
  name: "Stranger",
  isConfigurable: null,
  extension: { pkgName: "x.stranger", name: "Stranger", isTrusted: false, isLoaded: false },
});

let sources: Source[];
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;

beforeAll(() => {
  Element.prototype.scrollIntoView ??= () => {};
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.releasePointerCapture ??= () => {};
});

async function mountSettings(id = pahe.id) {
  wrapper = mountView(SourceSettingsView, { pinia, queryClient }, { id });
  await flushPromises();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  sources = [pahe, plain, untrusted];
  ops.fetchSources.mockImplementation(async () => sources);
  ops.fetchSourcePreferences.mockResolvedValue(allPreferenceTypes);
  setBackendReady();
});

afterEach(() => {
  wrapper?.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  document.body.innerHTML = "";
});

describe("SourceSettingsView", () => {
  it("shows the source's preferences, and sends a change by its position", async () => {
    await mountSettings();
    expect(wrapper.find("h1").text()).toBe("AnimePahe");
    expect(wrapper.text()).toContain("Anime source · EN · AnimePahe");
    expect(ops.fetchSourcePreferences).toHaveBeenCalledWith("anime", "42", expect.anything());
    expect(wrapper.findAll("[data-preference]")).toHaveLength(6); // one is hidden
    expect(wrapper.find("[data-action='browse-source']").attributes("href")).toBe("/browse/42");

    const change = { position: 1, switchState: true };
    ops.updateSourcePreference.mockResolvedValue({
      preferences: allPreferenceTypes.map((p, i) => (i === 1 ? withChange(p, change) : p)),
      source: pahe,
    });
    await wrapper.find("[data-position='1'] [role='switch']").trigger("click");
    await flushPromises();

    expect(ops.updateSourcePreference).toHaveBeenCalledWith("anime", "42", change);
    expect(wrapper.find("[data-position='1'] [role='switch']").attributes("aria-checked")).toBe(
      "true",
    );
  });

  it("names the extension when it refuses a value", async () => {
    await mountSettings();
    ops.updateSourcePreference.mockResolvedValue({ preferences: allPreferenceTypes, source: pahe });
    await wrapper.find("[data-position='1'] [role='switch']").trigger("click");
    await flushPromises();

    expect(wrapper.find("[data-position='1'] [data-testid='preference-refused']").text()).toBe(
      "AnimePahe didn't accept that value and kept off.",
    );
    expect(wrapper.find("[data-position='1'] [role='switch']").attributes("aria-checked")).toBe(
      "false",
    );
  });

  it("points an unusable source to Extensions and asks for nothing", async () => {
    await mountSettings(untrusted.id);
    expect(wrapper.find("[data-testid='source-unusable']").text()).toContain(
      "signing certificate isn't trusted",
    );
    expect(ops.fetchSourcePreferences).not.toHaveBeenCalled();
    expect(wrapper.find("[data-action='browse-source']").exists()).toBe(false);

    await wrapper.find("[data-action='open-extensions']").trigger("click");
    expect(useExtensionFiltersStore().search).toBe("x.stranger");
  });

  it("says when the source isn't installed, or has no settings", async () => {
    await mountSettings("999");
    expect(wrapper.find("h1").text()).toBe("Source settings");
    expect(wrapper.find("[data-testid='source-missing']").exists()).toBe(true);
    wrapper.unmount();

    await mountSettings(plain.id);
    expect(wrapper.find("[data-testid='source-not-configurable']").text()).toBe(
      "AnimeGG has no settings of its own.",
    );
    expect(ops.fetchSourcePreferences).not.toHaveBeenCalled();
  });

  it("shows a failed read with a retry", async () => {
    ops.fetchSourcePreferences.mockRejectedValueOnce(
      new ApiError({ kind: "graphql", code: "LOAD_FAILED", message: "no class" }),
    );
    await mountSettings();
    expect(wrapper.find("[role='alert']").text()).toContain("Couldn't load AnimePahe's settings");

    await wrapper.find("[role='alert'] button").trigger("click");
    await flushPromises();
    expect(wrapper.findAll("[data-preference]")).toHaveLength(6);
  });
});
