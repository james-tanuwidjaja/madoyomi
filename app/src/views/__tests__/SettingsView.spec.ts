import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { Source } from "../../api/types";
import { useBackendStore } from "../../stores/backend";
import { AUTO_PLAY_NEXT_KEY } from "../../stores/playerPrefs";
import { THEME_KEY } from "../../stores/theme";
import { makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const shell = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: () => false, invoke: shell.invoke }));

const ops = vi.hoisted(() => ({ fetchSources: vi.fn() }));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import SettingsView from "../SettingsView.vue";

/* Settings over mocked sources and a mocked shell: the theme, the backend's folders, the sources with settings. */

const kaa = makeSource({ id: "41", name: "KickAssAnime", isConfigurable: true });
const pahe = makeSource({ id: "42", name: "AnimePahe", isConfigurable: true });
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

async function mountSettings() {
  wrapper = mountView(SettingsView, { pinia, queryClient });
  await flushPromises();
}

const html = () => document.documentElement;
const themeOption = (c: string) => wrapper.find(`[data-theme-choice="${c}"]`);

beforeEach(() => {
  localStorage.clear();
  delete html().dataset.theme;
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  sources = [kaa, pahe, plain, untrusted];
  ops.fetchSources.mockImplementation(async () => sources);
  setBackendReady();
  useBackendStore().mode = "tauri";
});

afterEach(() => {
  wrapper?.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  delete html().dataset.theme;
  localStorage.clear();
});

describe("SettingsView", () => {
  it("switches the theme on <html> and keeps the choice", async () => {
    await mountSettings();
    expect(themeOption("system").attributes("data-state")).toBe("on");
    expect(html().dataset.theme).toBeUndefined();

    await themeOption("dark").trigger("click");
    expect(html().dataset.theme).toBe("dark");
    expect(localStorage.getItem(THEME_KEY)).toBe("dark");

    await themeOption("light").trigger("click");
    expect(html().dataset.theme).toBe("light");
    expect(themeOption("light").attributes("data-state")).toBe("on");

    // a click on the chosen option keeps it
    await themeOption("light").trigger("click");
    expect(html().dataset.theme).toBe("light");

    await themeOption("system").trigger("click");
    expect(html().dataset.theme).toBeUndefined();
    expect(localStorage.getItem(THEME_KEY)).toBeNull();
  });

  it("starts from the stored theme", async () => {
    localStorage.setItem(THEME_KEY, "light");
    await mountSettings();
    expect(themeOption("light").attributes("data-state")).toBe("on");
    expect(html().dataset.theme).toBe("light");
  });

  it("switches auto-play of the next episode, on by default, and keeps the choice", async () => {
    await mountSettings();
    const toggle = wrapper.find('[data-testid="autoplay-switch"]');
    expect(toggle.attributes("data-state")).toBe("checked");
    await toggle.trigger("click");
    expect(toggle.attributes("data-state")).toBe("unchecked");
    expect(localStorage.getItem(AUTO_PLAY_NEXT_KEY)).toBe("false");
  });

  it("says English is the only language", async () => {
    await mountSettings();
    expect(wrapper.find("[data-testid='language']").text()).toBe("English");
    expect(wrapper.text()).toContain("English is the only language so far.");
  });

  it("shows the backend's version, URL and folders, and opens the logs", async () => {
    await mountSettings();
    expect(wrapper.find("[data-testid='backend-state']").text()).toBe("Running");
    expect(wrapper.find("[data-testid='backend-server']").text()).toBe(
      "Madoyomi-Server 0.1.0 · http://127.0.0.1:50266",
    );
    expect(wrapper.find("[data-testid='backend-data-dir']").text()).toBe(
      "C:\\Users\\me\\AppData\\Roaming\\Madoyomi",
    );
    expect(wrapper.find("[data-testid='backend-log-dir']").text()).toBe(
      "C:\\Users\\me\\AppData\\Roaming\\Madoyomi\\logs",
    );

    await wrapper.find("[data-action='open-logs']").trigger("click");
    expect(shell.invoke).toHaveBeenCalledWith("open_backend_logs");
  });

  it("says when the backend wasn't started by the app", async () => {
    useBackendStore().mode = "external";
    await mountSettings();
    expect(wrapper.find("[data-testid='backend-external']").exists()).toBe(true);
    expect(wrapper.find("[data-action='open-logs']").exists()).toBe(false);
  });

  it("lists the kind's sources with settings, by name, each linking to its settings", async () => {
    await mountSettings();
    const rows = wrapper.findAll("[data-testid='settings-source']");
    expect(rows.map((r) => r.text())).toEqual([
      expect.stringContaining("AnimePahe"),
      expect.stringContaining("KickAssAnime"),
    ]);
    expect(rows[0].attributes("href")).toBe("/settings/source/42");
    expect(wrapper.find("[data-testid='settings-sources-unknown']").text()).toContain(
      "1 source isn't loaded",
    );
  });

  it("says when no source has settings", async () => {
    sources = [plain];
    await mountSettings();
    expect(wrapper.find("[data-testid='settings-sources-empty']").text()).toBe(
      "None of your anime sources has settings of its own.",
    );
    expect(wrapper.find("[data-testid='settings-sources-unknown']").exists()).toBe(false);
  });
});
