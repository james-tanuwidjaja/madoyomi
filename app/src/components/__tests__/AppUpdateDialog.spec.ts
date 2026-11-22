import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { useBackendStore } from "../../stores/backend";
import { usePlaybackStore } from "../../stores/playback";
import type { AppUpdateSettings, CheckOutcome, InstallProgress } from "../../stores/appUpdate";
import { makeDownloadItem, makeDownloadQueue, makePlaybackSession } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const shell = vi.hoisted(() => ({
  invoke: vi.fn(),
  progress: null as ((e: { payload: unknown }) => void) | null,
}));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: () => true, invoke: shell.invoke }));
vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn(async (_name: string, cb: (e: { payload: unknown }) => void) => {
    shell.progress = cb;
    return () => {};
  }),
}));
const opener = vi.hoisted(() => ({ openUrl: vi.fn() }));
vi.mock("@tauri-apps/plugin-opener", () => opener);
const ops = vi.hoisted(() => ({ fetchDownloadQueue: vi.fn() }));
vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import AppUpdateDialog from "../AppUpdateDialog.vue";

/*
 * The app's update dialog: the check on start (only when the setting is on, which the shell
 * decides), the version and notes, Install now / Later / Skip this version, what Install stops while an episode plays
 * or downloads run, the progress, and the errors.
 */
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let outcome: CheckOutcome;
let install: () => Promise<unknown>;

const settings: AppUpdateSettings = {
  checkOnStart: true,
  skippedVersion: null,
  currentVersion: "0.1.0",
  install: "nsis",
  releasesUrl: "https://github.com/james-tanuwidjaja/madoyomi/releases/latest",
};
const available = (canInstall = true): CheckOutcome => ({
  result: "available",
  update: {
    version: "0.2.0",
    currentVersion: "0.1.0",
    notes: "New: the app updates itself.\nFixed: a crash.",
    date: "2026-11-21T10:00:00Z",
    canInstall,
  },
});

const dialog = () => document.querySelector("[data-testid='app-update-dialog']");
const button = (action: string) =>
  document.querySelector<HTMLButtonElement>(`[data-action='${action}']`);
const calls = (cmd: string) => shell.invoke.mock.calls.filter(([c]) => c === cmd);

async function mountDialog() {
  wrapper = mountView(AppUpdateDialog, { pinia, queryClient });
  await flushPromises();
}

function emit(p: InstallProgress) {
  shell.progress?.({ payload: p });
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  useBackendStore().mode = "tauri";
  outcome = { result: "off" };
  install = () => new Promise(() => {});
  ops.fetchDownloadQueue.mockResolvedValue(makeDownloadQueue());
  shell.invoke.mockImplementation(async (cmd: string, args?: Record<string, unknown>) => {
    if (cmd === "get_app_update_settings") return settings;
    if (cmd === "check_app_update") return outcome;
    if (cmd === "skip_app_update") return { ...settings, skippedVersion: args?.version };
    if (cmd === "install_app_update") return install();
    return undefined;
  });
});

afterEach(() => {
  wrapper?.unmount();
  vi.clearAllMocks();
  shell.progress = null;
  document.body.innerHTML = "";
});

describe("AppUpdateDialog", () => {
  it("checks once on start and stays hidden when the check is off or finds nothing", async () => {
    await mountDialog();
    expect(calls("check_app_update")).toEqual([["check_app_update", { onStart: true }]]);
    expect(dialog()).toBeNull();
  });

  it("waits for the backend before checking", async () => {
    useBackendStore().status = null;
    await mountDialog();
    expect(calls("check_app_update")).toHaveLength(0);
    setBackendReady();
    await flushPromises();
    expect(calls("check_app_update")).toHaveLength(1);
  });

  it("is quiet when the check on start is offline", async () => {
    outcome = { result: "quiet", reason: "Couldn't reach the update feed (offline?)" };
    await mountDialog();
    expect(dialog()).toBeNull();
  });

  it("shows a check on start that failed for another reason, once", async () => {
    shell.invoke.mockImplementation(async (cmd: string) => {
      if (cmd === "check_app_update") throw "The update check failed: the signature is invalid";
      return cmd === "get_app_update_settings" ? settings : undefined;
    });
    await mountDialog();
    const failed = () => document.querySelector("[data-testid='app-update-check-failed']");
    expect(failed()?.textContent).toContain("the signature is invalid");
    button("close-update-error")!.click();
    await flushPromises();
    expect(failed()).toBeNull();
  });

  it("shows the version, the date and the release notes", async () => {
    outcome = available();
    await mountDialog();
    expect(dialog()?.textContent).toContain("Madoyomi 0.2.0 is out");
    expect(dialog()?.textContent).toContain("You have 0.1.0.");
    expect(document.querySelector("[data-testid='app-update-notes']")?.textContent).toBe(
      "New: the app updates itself.\nFixed: a crash.",
    );
    expect(button("install-update")?.textContent?.trim()).toBe("Install now");
  });

  it("closes on Later without remembering anything", async () => {
    outcome = available();
    await mountDialog();
    button("later-update")!.click();
    await flushPromises();
    expect(dialog()).toBeNull();
    expect(calls("skip_app_update")).toHaveLength(0);
  });

  it("remembers Skip this version", async () => {
    outcome = available();
    await mountDialog();
    button("skip-update")!.click();
    await flushPromises();
    expect(calls("skip_app_update")).toEqual([["skip_app_update", { version: "0.2.0" }]]);
    expect(dialog()).toBeNull();
  });

  it("says what Install stops while an episode plays and a download runs", async () => {
    outcome = available();
    ops.fetchDownloadQueue.mockResolvedValue(
      makeDownloadQueue([
        makeDownloadItem({ id: 1, state: "DOWNLOADING" }),
        makeDownloadItem({ id: 2 }),
      ]),
    );
    usePlaybackStore().session = makePlaybackSession();
    await mountDialog();
    expect(document.querySelector("[data-testid='app-update-busy']")?.textContent).toContain(
      "the episode playing stops, and 1 download pauses and goes on after the update",
    );
    expect(button("install-update")?.textContent?.trim()).toBe("Install anyway");
  });

  it("installs with its progress and can't be closed meanwhile", async () => {
    outcome = available();
    await mountDialog();
    button("install-update")!.click();
    await flushPromises();
    expect(calls("install_app_update")).toHaveLength(1);
    const progress = () =>
      document.querySelector("[data-testid='app-update-progress']")?.textContent ?? "";
    expect(progress()).toContain("Starting the download");
    emit({ phase: "downloading", downloaded: 50 * 1024 * 1024, total: 200 * 1024 * 1024 });
    await flushPromises();
    expect(progress()).toContain("Downloading: 50.0 of 200.0 MB");
    expect(document.querySelector("progress")?.getAttribute("value")).toBe("25");
    emit({ phase: "stopping" });
    await flushPromises();
    expect(progress()).toContain("Stopping the backend and the player");
    expect(button("later-update")?.disabled).toBe(true);
    expect(button("skip-update")?.disabled).toBe(true);
    // a second press does nothing
    button("install-update")!.click();
    await flushPromises();
    expect(calls("install_app_update")).toHaveLength(1);
  });

  it("shows a failed download and lets the user try again", async () => {
    outcome = available();
    install = () =>
      Promise.reject({ message: "The download failed: offline", needsRestart: false });
    await mountDialog();
    button("install-update")!.click();
    await flushPromises();
    expect(document.querySelector("[data-testid='app-update-error']")?.textContent).toContain(
      "The download failed: offline",
    );
    expect(button("install-update")?.disabled).toBe(false);
    expect(button("restart-app")).toBeNull();
  });

  it("offers a restart when the installer didn't start after the backend stopped", async () => {
    outcome = available();
    install = () =>
      Promise.reject({ message: "The installer didn't start: denied", needsRestart: true });
    await mountDialog();
    button("install-update")!.click();
    await flushPromises();
    expect(button("install-update")).toBeNull();
    button("restart-app")!.click();
    await flushPromises();
    expect(calls("restart_app")).toHaveLength(1);
  });

  it("links the release page for an MSI install instead of installing", async () => {
    outcome = available(false);
    await mountDialog();
    expect(dialog()?.textContent).toContain("installed with the MSI installer");
    expect(button("install-update")).toBeNull();
    button("open-releases")!.click();
    await flushPromises();
    expect(opener.openUrl).toHaveBeenCalledWith(settings.releasesUrl);
  });
});
