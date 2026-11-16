import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type {
  BackupFile,
  BackupSettings,
  DownloadSettings,
  DownloadTool,
  LibraryUpdateSettings,
  MpvCheck,
  PageCache,
  PlayerSettings,
  ReaderSettings,
  Source,
} from "../../api/types";
import { DEFAULT_LIBRARY_UPDATE_SETTINGS } from "../../composables/useLibraryUpdates";
import { DEFAULT_PLAYER_SETTINGS } from "../../composables/usePlayerSettings";
import { useBackendStore } from "../../stores/backend";
import { AUTO_PLAY_NEXT_KEY } from "../../stores/playerPrefs";
import { THEME_KEY } from "../../stores/theme";
import { makeLibraryCheck, makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const shell = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: () => false, invoke: shell.invoke }));

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchPlayerSettings: vi.fn(),
  fetchPlayerLanguages: vi.fn(),
  setPlayerSettings: vi.fn(),
  checkMpv: vi.fn(),
  fetchReaderSettings: vi.fn(),
  setReaderSettings: vi.fn(),
  fetchPageCache: vi.fn(),
  clearPageCache: vi.fn(),
  fetchLibraryUpdateSettings: vi.fn(),
  setLibraryUpdateSettings: vi.fn(),
  fetchLibraryCheck: vi.fn(),
  fetchDownloadSettings: vi.fn(),
  setDownloadSettings: vi.fn(),
  fetchDownloadTools: vi.fn(),
  fetchBackups: vi.fn(),
  fetchBackupSettings: vi.fn(),
  setBackupSettings: vi.fn(),
  createBackup: vi.fn(),
  deleteBackup: vi.fn(),
  fetchRestoreStatus: vi.fn(),
  uploadBackup: vi.fn(),
  validateBackup: vi.fn(),
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
let reader: ReaderSettings;
let cache: PageCache;
let updateSettings: LibraryUpdateSettings;
let downloadSettings: DownloadSettings;
let backupFiles: BackupFile[];
let backupSettings: BackupSettings;
const manualBackup: BackupFile = {
  name: "Madoyomi_2026-11-16_21-00-00.madoyomi-backup",
  path: "D:\\data\\backups\\Madoyomi_2026-11-16_21-00-00.madoyomi-backup",
  size: 86_016,
  modifiedAt: 1_700_000_000_000,
  automatic: false,
  format: "MADOYOMI",
};

function toolFound(tool: DownloadTool["tool"], path?: string): DownloadTool {
  return {
    tool,
    ok: true,
    path: path ?? (tool === "FFMPEG" ? "C:\\bin\\ffmpeg.exe" : "C:\\bin\\yt-dlp.exe"),
    origin: path ? "SETTINGS" : "ENV",
    version: tool === "FFMPEG" ? "ffmpeg version 7.1" : "2025.10.22",
    message: null,
  };
}

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
  reader = { pageCacheLimitMb: 1024 };
  cache = {
    bytes: String(45.25 * 1024 * 1024),
    files: 312,
    limitBytes: String(1024 * 1024 * 1024),
    path: "D:\\data\\cache\\manga",
  };
  ops.fetchReaderSettings.mockImplementation(async () => reader);
  ops.setReaderSettings.mockImplementation(async (patch: Partial<ReaderSettings>) => {
    reader = { ...reader, ...patch };
    return reader;
  });
  ops.fetchPageCache.mockImplementation(async () => cache);
  ops.clearPageCache.mockImplementation(async () => {
    cache = { ...cache, bytes: "0", files: 0 };
    return cache;
  });
  updateSettings = { ...DEFAULT_LIBRARY_UPDATE_SETTINGS };
  ops.fetchLibraryUpdateSettings.mockImplementation(async () => updateSettings);
  ops.setLibraryUpdateSettings.mockImplementation(
    async (patch: Partial<LibraryUpdateSettings>) =>
      (updateSettings = { ...updateSettings, ...patch }),
  );
  ops.fetchLibraryCheck.mockResolvedValue(makeLibraryCheck());
  downloadSettings = {
    maxParallel: 3,
    perSource: 1,
    autoDownload: false,
    path: "D:\\data\\downloads",
    ffmpegPath: "",
    ytDlpPath: "",
  };
  ops.fetchDownloadSettings.mockImplementation(async () => downloadSettings);
  ops.setDownloadSettings.mockImplementation(
    async (patch: Partial<DownloadSettings>) =>
      (downloadSettings = { ...downloadSettings, ...patch }),
  );
  ops.fetchDownloadTools.mockImplementation(async () => [toolFound("FFMPEG"), toolFound("YT_DLP")]);
  backupFiles = [manualBackup];
  backupSettings = {
    intervalHours: 0,
    keep: 7,
    nextScheduledAt: null,
    folder: "D:\\data\\backups",
    lastError: null,
  };
  ops.fetchBackups.mockImplementation(async () => backupFiles);
  ops.fetchBackupSettings.mockImplementation(async () => backupSettings);
  ops.setBackupSettings.mockImplementation(
    async (patch: Partial<BackupSettings>) =>
      (backupSettings = {
        ...backupSettings,
        ...patch,
        nextScheduledAt:
          (patch.intervalHours ?? backupSettings.intervalHours) > 0 ? 1_700_086_400_000 : null,
      }),
  );
  ops.fetchRestoreStatus.mockResolvedValue({
    state: "IDLE",
    name: null,
    mode: null,
    step: null,
    current: 0,
    total: 0,
    title: null,
    problems: [],
    startedAt: 0,
    finishedAt: 0,
  });
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

describe("SettingsView: Reader (Phase 5 Stage 1)", () => {
  const el = <T extends HTMLElement>(id: string) =>
    wrapper.find(`[data-testid="${id}"]`).element as T;

  it("shows the cache cap and what the cache holds, and saves a new cap", async () => {
    await mountSettings();
    expect(el<HTMLSelectElement>("cache-limit-select").value).toBe("1024");
    expect(
      wrapper.findAll('[data-testid="cache-limit-select"] option').map((o) => o.text()),
    ).toEqual(["256 MB", "512 MB", "1 GB", "2 GB", "5 GB", "10 GB"]);
    expect(wrapper.find('[data-testid="cache-usage"]').text()).toBe("45.3 MB in 312 pages");
    expect(wrapper.find('[data-testid="cache-path"]').text()).toBe("D:\\data\\cache\\manga");

    // a lower cap: saved, and the cache read again (the backend trimmed it before it answered)
    cache = { ...cache, bytes: String(20 * 1024 * 1024), files: 140 };
    await wrapper.find('[data-testid="cache-limit-select"]').setValue("256");
    await flushPromises();
    expect(ops.setReaderSettings).toHaveBeenCalledWith({ pageCacheLimitMb: 256 });
    expect(el<HTMLSelectElement>("cache-limit-select").value).toBe("256");
    expect(wrapper.find('[data-testid="cache-usage"]').text()).toBe("20.0 MB in 140 pages");
  });

  it("clears the cache, and Clear is off when there is nothing to clear", async () => {
    await mountSettings();
    const clear = wrapper.find('[data-action="clear-cache"]');
    expect(clear.attributes("disabled")).toBeUndefined();
    await clear.trigger("click");
    await flushPromises();
    expect(ops.clearPageCache).toHaveBeenCalledOnce();
    expect(wrapper.find('[data-testid="cache-usage"]').text()).toBe("0 bytes in 0 pages");
    expect(wrapper.find('[data-action="clear-cache"]').attributes("disabled")).toBeDefined();
  });

  it("offers a cap a hand-edited file has, and puts the cap back when the backend refuses it", async () => {
    reader = { pageCacheLimitMb: 3000 };
    await mountSettings();
    expect(el<HTMLSelectElement>("cache-limit-select").value).toBe("3000");
    expect(wrapper.findAll('[data-testid="cache-limit-select"] option')).toHaveLength(7);

    ops.setReaderSettings.mockRejectedValueOnce(
      new ApiError({
        kind: "graphql",
        code: "BAD_REQUEST",
        message: "pageCacheLimitMb: must be 256 to 10240",
      }),
    );
    await wrapper.find('[data-testid="cache-limit-select"]').setValue("512");
    await flushPromises();
    expect(wrapper.find('[data-testid="reader-save-error"]').text()).toContain("BAD_REQUEST");
    expect(el<HTMLSelectElement>("cache-limit-select").value).toBe("3000");
  });

  it("says when the cache can't be read", async () => {
    ops.fetchPageCache.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "INTERNAL", message: "disk gone" }),
    );
    await mountSettings();
    expect(wrapper.find('[data-testid="cache-usage"]').text()).toBe("Not known");
    expect(wrapper.find('[data-testid="cache-error"]').text()).toContain(
      "Couldn't read the page cache",
    );
  });
});

describe("SettingsView: Reader settings on this device (Phase 5 Stage 5)", () => {
  it("shows the reader's global settings with their defaults, and keeps a change", async () => {
    await mountSettings();
    const prefs = wrapper.find('[data-testid="reader-prefs-card"]');
    expect(prefs.exists()).toBe(true);
    const value = (p: string) => prefs.find<HTMLSelectElement>(`[data-pref="${p}"]`).element.value;
    expect(["mode", "direction", "fit", "stripWidth", "background", "preload"].map(value)).toEqual([
      "paged",
      "rtl",
      "screen",
      "800",
      "dark",
      "4",
    ]);
    expect(
      prefs
        .find('[data-pref="fit"]')
        .findAll("option")
        .map((o) => o.text()),
    ).toEqual(["Fit screen", "Fit width", "Fit height", "Original size"]);

    await prefs.find('[data-pref="mode"]').setValue("webtoon");
    await prefs.find('[data-pref="stripWidth"]').setValue("0");
    expect(JSON.parse(localStorage.getItem("madoyomi.reader")!)).toMatchObject({
      mode: "webtoon",
      stripWidth: 0,
    });
    expect(prefs.find('[data-pref="stripWidth"] option:checked').text()).toBe("Window width");
    // the backend's cache settings are still beside them
    expect(wrapper.find('[data-testid="cache-limit-select"]').exists()).toBe(true);
  });
});

describe("SettingsView: Updates (Phase 6 Stage 3)", () => {
  const el = <T extends HTMLElement>(id: string) =>
    wrapper.find(`[data-testid="${id}"]`).element as T;
  async function choose(id: string, value: string) {
    await wrapper.find(`[data-testid="${id}"]`).setValue(value);
    await flushPromises();
  }

  it("shows the update settings, off and on by the defaults, and saves each change (Phase 6 Stage 3)", async () => {
    await mountSettings();
    const section = wrapper.find('[data-testid="update-settings"]');
    expect(el<HTMLSelectElement>("interval-select").value).toBe("0");
    expect(section.findAll('[data-testid="interval-select"] option').map((o) => o.text())).toEqual([
      "Off",
      "Every 6 hours",
      "Every 12 hours",
      "Every 24 hours",
    ]);
    const state = (id: string) => wrapper.find(`[data-testid="${id}"]`).attributes("data-state");
    expect(state("skip-completed-switch")).toBe("checked");
    expect(state("skip-unseen-switch")).toBe("unchecked");
    expect(state("skip-not-started-switch")).toBe("unchecked");
    expect(state("notify-switch")).toBe("unchecked");

    ops.fetchLibraryCheck.mockResolvedValue(
      makeLibraryCheck({ nextScheduledAt: String(new Date().setHours(18, 0, 0, 0)) }),
    );
    await choose("interval-select", "12");
    expect(ops.setLibraryUpdateSettings).toHaveBeenLastCalledWith({ intervalHours: 12 });
    await flushPromises();
    expect(section.text()).toContain("Next check at 6:00 PM.");

    await wrapper.find('[data-testid="notify-switch"]').trigger("click");
    await flushPromises();
    expect(ops.setLibraryUpdateSettings).toHaveBeenLastCalledWith({ notify: true });
    await wrapper.find('[data-testid="skip-completed-switch"]').trigger("click");
    await flushPromises();
    expect(ops.setLibraryUpdateSettings).toHaveBeenLastCalledWith({ skipCompleted: false });
    expect(updateSettings).toMatchObject({ intervalHours: 12, notify: true, skipCompleted: false });
    expect(state("notify-switch")).toBe("checked");
  });
});

describe("SettingsView: the download tools (Phase 6 Stage 5)", () => {
  it("shows ffmpeg and yt-dlp as the backend finds them, saves a path and checks typed ones", async () => {
    await mountSettings();
    expect(ops.fetchDownloadTools).toHaveBeenCalledWith({});
    const status = (t: string) => wrapper.find(`[data-testid="tool-status-${t}"]`);
    expect(status("FFMPEG").text()).toContain("ffmpeg version 7.1");
    expect(status("FFMPEG").text()).toContain("(from MADOYOMI_FFMPEG)");
    expect(status("YT_DLP").text()).toContain("(from MADOYOMI_YTDLP)");

    const input = wrapper.find('[data-testid="tool-input-FFMPEG"]');
    await input.setValue('"D:\\tools\\ffmpeg.exe"');
    await input.trigger("keydown", { key: "Enter" });
    await flushPromises();
    expect(ops.setDownloadSettings).toHaveBeenCalledWith({ ffmpegPath: "D:\\tools\\ffmpeg.exe" });

    ops.fetchDownloadTools.mockResolvedValueOnce([
      toolFound("FFMPEG"),
      {
        tool: "YT_DLP",
        ok: false,
        path: "C:\\nowhere\\yt-dlp.exe",
        origin: "SETTINGS",
        version: null,
        message: "yt-dlp was not found at C:\\nowhere\\yt-dlp.exe (set in Settings → Downloads)",
      },
    ]);
    await wrapper.find('[data-testid="tool-input-YT_DLP"]').setValue("C:\\nowhere\\yt-dlp.exe");
    await wrapper.find('[data-action="check-YT_DLP"]').trigger("click");
    await flushPromises();
    expect(ops.fetchDownloadTools).toHaveBeenLastCalledWith({
      ffmpegPath: "D:\\tools\\ffmpeg.exe",
      ytDlpPath: "C:\\nowhere\\yt-dlp.exe",
    });
    expect(status("YT_DLP").classes()).toContain("danger");
    expect(status("YT_DLP").text()).toContain("Settings → Downloads");
  });
});

describe("SettingsView: Backup (Phase 6 Stage 6)", () => {
  const section = () => wrapper.find('[data-testid="backup-settings"]');

  it("lists the backups, backs up now, and turns the schedule on with what to keep", async () => {
    await mountSettings();
    expect(section().text()).toContain("Only when you back up.");
    expect(wrapper.find('[data-testid="backup-keep-select"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="backup-path"]').text()).toBe("D:\\data\\backups");
    const row = wrapper.find(`[data-backup="${manualBackup.name}"]`);
    expect(row.text()).toContain("84 KB · Manual");

    const scheduled: BackupFile = { ...manualBackup, name: "Madoyomi_new.madoyomi-backup" };
    ops.createBackup.mockResolvedValue(scheduled);
    await wrapper.find('[data-action="backup-now"]').trigger("click");
    await flushPromises();
    expect(ops.createBackup).toHaveBeenCalledOnce();
    expect(wrapper.find('[data-testid="backup-created"]').text()).toContain(
      "Madoyomi_new.madoyomi-backup",
    );
    expect(wrapper.findAll("[data-backup]").map((r) => r.attributes("data-backup"))).toEqual([
      "Madoyomi_new.madoyomi-backup",
      manualBackup.name,
    ]);

    const interval = wrapper.find<HTMLSelectElement>('[data-testid="backup-interval-select"]');
    interval.element.value = "24";
    await interval.trigger("change");
    await flushPromises();
    expect(ops.setBackupSettings).toHaveBeenCalledWith({ intervalHours: 24 });
    expect(section().text()).toContain("Next at");
    const keep = wrapper.find<HTMLSelectElement>('[data-testid="backup-keep-select"]');
    keep.element.value = "3";
    await keep.trigger("change");
    await flushPromises();
    expect(ops.setBackupSettings).toHaveBeenLastCalledWith({ keep: 3 });
  });

  it("deletes a backup on the second click, and opens the restore dialog for one", async () => {
    ops.deleteBackup.mockResolvedValue([]);
    await mountSettings();
    const del = () =>
      wrapper.find(`[data-backup="${manualBackup.name}"] [data-action="delete-backup"]`);
    await del().trigger("click");
    expect(del().text()).toBe("Delete it?");
    expect(ops.deleteBackup).not.toHaveBeenCalled();
    await del().trigger("click");
    await flushPromises();
    expect(ops.deleteBackup).toHaveBeenCalledWith(manualBackup.name);
    expect(wrapper.find('[data-testid="backup-empty"]').exists()).toBe(true);
  });

  it("fetches the list again when a delete finds the file gone (the schedule pruned it)", async () => {
    await mountSettings();
    expect(ops.fetchBackups).toHaveBeenCalledTimes(1);
    backupFiles = [];
    ops.deleteBackup.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "NOT_FOUND", message: "No backup named x" }),
    );
    const del = () =>
      wrapper.find(`[data-backup="${manualBackup.name}"] [data-action="delete-backup"]`);
    await del().trigger("click");
    await del().trigger("click");
    await flushPromises();
    expect(ops.fetchBackups).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="backup-empty"]').exists()).toBe(true);
    expect(section().element.parentElement!.textContent).toContain("No backup named x");
  });

  it("keeps a picked file in the folder, then opens it in the restore dialog", async () => {
    ops.uploadBackup.mockResolvedValue("picked.madoyomi-backup");
    ops.validateBackup.mockReturnValue(new Promise(() => {}));
    await mountSettings();
    const input = wrapper.find<HTMLInputElement>('[data-testid="backup-file-input"]');
    const file = new File(["x"], "picked.madoyomi-backup");
    Object.defineProperty(input.element, "files", { value: [file], configurable: true });
    await input.trigger("change");
    await flushPromises();
    expect(ops.uploadBackup).toHaveBeenCalledWith(file, "picked.madoyomi-backup");
    expect(ops.validateBackup).toHaveBeenCalledWith("picked.madoyomi-backup");
    expect(document.body.querySelector('[data-testid="restore-dialog"]')).not.toBeNull();
  });

  it("shows a restore's progress and, once done, its problems", async () => {
    ops.fetchRestoreStatus.mockResolvedValue({
      state: "SUCCESS",
      name: manualBackup.name,
      mode: "MERGE",
      step: "MANGA",
      current: 7,
      total: 7,
      title: null,
      problems: ["Repo Keiyoushi: no answer"],
      startedAt: 1_700_000_000_000,
      finishedAt: 1_700_000_009_000,
    });
    await mountSettings();
    const status = wrapper.find('[data-testid="restore-status"]');
    expect(status.text()).toContain("Restored, with 1 problem");
    expect(wrapper.find('[data-testid="restore-problems"]').text()).toBe(
      "Repo Keiyoushi: no answer",
    );
  });
});
