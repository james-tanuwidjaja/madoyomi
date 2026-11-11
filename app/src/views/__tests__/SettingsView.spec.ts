import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { MpvCheck, PlayerSettings, Source } from "../../api/types";
import { DEFAULT_PLAYER_SETTINGS } from "../../composables/usePlayerSettings";
import { useBackendStore } from "../../stores/backend";
import { AUTO_PLAY_NEXT_KEY } from "../../stores/playerPrefs";
import { THEME_KEY } from "../../stores/theme";
import { makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const shell = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: () => false, invoke: shell.invoke }));

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchPlayerSettings: vi.fn(),
  fetchPlayerLanguages: vi.fn(),
  setPlayerSettings: vi.fn(),
  checkMpv: vi.fn(),
}));
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
let player: PlayerSettings;

function mpvFound(path: string | null): MpvCheck {
  return {
    ok: true,
    path: path ?? "C:\\Program Files\\MPV Player\\mpv.exe",
    origin: path ? "SETTINGS" : "PATH",
    version: "mpv v0.40.0",
    errorCode: null,
    message: null,
  };
}
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
  player = { ...DEFAULT_PLAYER_SETTINGS };
  ops.fetchPlayerSettings.mockImplementation(async () => player);
  ops.fetchPlayerLanguages.mockResolvedValue([
    { code: "en", name: "English" },
    { code: "ja", name: "Japanese" },
  ]);
  ops.setPlayerSettings.mockImplementation(async (patch: Partial<PlayerSettings>) => {
    player = { ...player, ...patch };
    return player;
  });
  ops.checkMpv.mockImplementation(async (path: string | null) => mpvFound(path));
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

describe("SettingsView: Player (Phase 4 Stage 5)", () => {
  const el = <T extends HTMLElement>(id: string) =>
    wrapper.find(`[data-testid="${id}"]`).element as T;
  async function choose(id: string, value: string) {
    const select = wrapper.find(`[data-testid="${id}"]`);
    await select.setValue(value);
    await flushPromises();
  }

  it("shows the backend's settings and saves each change at once", async () => {
    player = {
      ...player,
      watchedThreshold: 0.9,
      preferredQuality: "P720",
      subtitleLanguage: "off",
    };
    await mountSettings();
    expect(el<HTMLSelectElement>("threshold-select").value).toBe("90");
    expect(wrapper.findAll('[data-testid="threshold-select"] option').map((o) => o.text())).toEqual(
      ["50 %", "55 %", "60 %", "65 %", "70 %", "75 %", "80 %", "85 %", "90 %", "95 %"],
    );
    expect(el<HTMLSelectElement>("quality-select").value).toBe("P720");
    expect(el<HTMLSelectElement>("subtitle-select").value).toBe("off");
    expect(wrapper.findAll('[data-testid="audio-select"] option').map((o) => o.text())).toEqual([
      "The source's choice",
      "English",
      "Japanese",
    ]);

    await choose("threshold-select", "75");
    expect(ops.setPlayerSettings).toHaveBeenLastCalledWith({ watchedThreshold: 0.75 });
    await choose("quality-select", "HIGHEST");
    expect(ops.setPlayerSettings).toHaveBeenLastCalledWith({ preferredQuality: "HIGHEST" });
    await choose("subtitle-select", "en");
    expect(ops.setPlayerSettings).toHaveBeenLastCalledWith({ subtitleLanguage: "en" });
    await choose("audio-select", "ja");
    expect(ops.setPlayerSettings).toHaveBeenLastCalledWith({ audioLanguage: "ja" });
    expect(player).toMatchObject({
      watchedThreshold: 0.75,
      preferredQuality: "HIGHEST",
      subtitleLanguage: "en",
      audioLanguage: "ja",
    });
    expect(el<HTMLSelectElement>("threshold-select").value).toBe("75");
  });

  it("saves the preferred server when the field is left, trimmed, and not when it didn't change", async () => {
    await mountSettings();
    const input = wrapper.find('[data-testid="server-input"]');
    await input.setValue("  VidStreaming ");
    await input.trigger("blur");
    await flushPromises();
    expect(ops.setPlayerSettings).toHaveBeenCalledWith({ preferredServer: "VidStreaming" });
    expect(el<HTMLInputElement>("server-input").value).toBe("VidStreaming");
    ops.setPlayerSettings.mockClear();
    await input.trigger("keydown", { key: "Enter" });
    await flushPromises();
    expect(ops.setPlayerSettings).not.toHaveBeenCalled();
  });

  it("shows the mpv in use, saves a path (quotes taken off), checks it, and checks a typed one", async () => {
    await mountSettings();
    expect(ops.checkMpv).toHaveBeenCalledWith(null);
    expect(wrapper.find('[data-testid="mpv-status"]').text()).toContain("mpv v0.40.0");
    expect(wrapper.find('[data-testid="mpv-status"]').text()).toContain("(from PATH)");

    const input = wrapper.find('[data-testid="mpv-input"]');
    await input.setValue('"D:\\mpv\\mpv.exe"');
    await input.trigger("keydown", { key: "Enter" });
    await flushPromises();
    expect(ops.setPlayerSettings).toHaveBeenCalledWith({ mpvPath: "D:\\mpv\\mpv.exe" });
    expect(ops.checkMpv).toHaveBeenLastCalledWith(null);

    ops.checkMpv.mockResolvedValueOnce({
      ok: false,
      path: "C:\\nowhere\\mpv.exe",
      origin: "SETTINGS",
      version: null,
      errorCode: "PLAYER_NOT_FOUND",
      message: "mpv was not found at C:\\nowhere\\mpv.exe (set in Settings → Player)",
    });
    await input.setValue("C:\\nowhere\\mpv.exe");
    await wrapper.find('[data-action="check-mpv"]').trigger("click");
    await flushPromises();
    expect(ops.checkMpv).toHaveBeenLastCalledWith("C:\\nowhere\\mpv.exe");
    expect(wrapper.find('[data-testid="mpv-status"]').text()).toContain("Settings → Player");
  });

  it("puts a setting back when the backend refuses it", async () => {
    await mountSettings();
    ops.setPlayerSettings.mockRejectedValueOnce(
      new ApiError({
        kind: "graphql",
        code: "BAD_REQUEST",
        message: "watchedThreshold must be 0.5 to 0.95",
      }),
    );
    await choose("threshold-select", "60");
    expect(wrapper.find('[data-testid="player-save-error"]').text()).toContain("BAD_REQUEST");
    expect(el<HTMLSelectElement>("threshold-select").value).toBe("85");
  });
});
