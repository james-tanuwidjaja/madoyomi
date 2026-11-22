import { defineStore } from "pinia";
import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

/**
 * The app's own updates, run by the shell (src-tauri/src/updater.rs): the setting "Check for app
 * updates" (off by default), the check on start (once the backend is healthy, only when the setting is on and the
 * app is installed; offline or no feed yet is quiet), "Check now", and the update dialog's install with its progress.
 * Its callers run it only when the shell runs the backend (`backend.mode === "tauri"`).
 */

/** The Tauri event with an {@link InstallProgress} (`PROGRESS_EVENT` in updater.rs). */
export const PROGRESS_EVENT = "app-update-progress";

/** How this copy was installed: only `nsis` (the per-user installer) updates itself. */
export type InstallKind = "nsis" | "msi" | "none";

/** Mirrors `AppUpdateSettings`. */
export interface AppUpdateSettings {
  checkOnStart: boolean;
  skippedVersion: string | null;
  currentVersion: string;
  install: InstallKind;
  releasesUrl: string;
}

/** Mirrors `AvailableUpdate`. */
export interface AvailableUpdate {
  version: string;
  currentVersion: string;
  notes: string | null;
  date: string | null;
  canInstall: boolean;
}

/** Mirrors `CheckOutcome`. */
export type CheckOutcome =
  | { result: "off" }
  | { result: "upToDate"; currentVersion: string }
  | { result: "skipped"; version: string }
  | { result: "quiet"; reason: string }
  | { result: "available"; update: AvailableUpdate };

/** Mirrors `InstallProgress`. */
export type InstallProgress =
  | { phase: "downloading"; downloaded: number; total: number | null }
  | { phase: "stopping" }
  | { phase: "installing" };

/** Mirrors `InstallError`: what a failed install rejects with. */
export interface InstallError {
  message: string;
  needsRestart: boolean;
}

const errorMessage = (e: unknown) => (e instanceof Error ? e.message : String(e));

export const useAppUpdateStore = defineStore("appUpdate", () => {
  const settings = ref<AppUpdateSettings | null>(null);
  /** The update the dialog shows (open while set). */
  const available = ref<AvailableUpdate | null>(null);
  /** The last "Check now" answer, for Settings. */
  const lastCheck = ref<CheckOutcome | null>(null);
  const checking = ref(false);
  const checkError = ref<string | null>(null);
  /** A failed check on start that isn't offline or "no feed yet": the dialog says so once. */
  const startError = ref<string | null>(null);
  const progress = ref<InstallProgress | null>(null);
  const installing = ref(false);
  const installError = ref<string | null>(null);
  /** The installer didn't start after the backend stopped: only a restart helps. */
  const needsRestart = ref(false);

  let checkedOnStart = false;
  let unlisten: UnlistenFn | null = null;

  async function loadSettings() {
    try {
      settings.value = await invoke<AppUpdateSettings>("get_app_update_settings");
    } catch (e) {
      console.error("get_app_update_settings failed:", errorMessage(e));
    }
  }

  async function setCheckOnStart(on: boolean) {
    settings.value = await invoke<AppUpdateSettings>("set_app_update_check", {
      checkOnStart: on,
    });
  }

  /** The check on start: once per app run, after the backend is healthy. Says nothing unless a version is out. */
  async function checkOnStart() {
    if (checkedOnStart) return;
    checkedOnStart = true;
    try {
      const outcome = await invoke<CheckOutcome>("check_app_update", { onStart: true });
      if (outcome.result === "available") available.value = outcome.update;
    } catch (e) {
      // not offline and not "no feed yet" (those come back as `quiet`): the user sees it
      startError.value = errorMessage(e);
      checkError.value = startError.value;
    }
  }

  /** "Check now": every answer and error is shown, and a version that is out opens the dialog (skipped or not). */
  async function checkNow() {
    checking.value = true;
    checkError.value = null;
    try {
      const outcome = await invoke<CheckOutcome>("check_app_update", { onStart: false });
      lastCheck.value = outcome;
      if (outcome.result === "available") available.value = outcome.update;
    } catch (e) {
      lastCheck.value = null;
      checkError.value = errorMessage(e);
    } finally {
      checking.value = false;
    }
  }

  /** "Later": closes the dialog; the next start's check asks again. */
  function later() {
    if (installing.value) return;
    available.value = null;
    startError.value = null;
    installError.value = null;
  }

  /** "Skip this version": no dialog on start for it again. */
  async function skip() {
    const version = available.value?.version;
    if (!version || installing.value) return;
    settings.value = await invoke<AppUpdateSettings>("skip_app_update", { version });
    available.value = null;
  }

  /** Downloads and installs. On success the app exits (the installer starts the new version). */
  async function install() {
    if (installing.value || !available.value?.canInstall) return;
    installing.value = true;
    installError.value = null;
    progress.value = null;
    unlisten ??= await listen<InstallProgress>(PROGRESS_EVENT, (event) => {
      progress.value = event.payload;
    });
    try {
      await invoke("install_app_update");
    } catch (e) {
      // `InstallError`: after a failed installer start the backend is stopped, so only a restart helps
      const failure = e as Partial<InstallError> | null;
      installError.value = typeof failure?.message === "string" ? failure.message : errorMessage(e);
      needsRestart.value = failure?.needsRestart === true;
      installing.value = false;
      progress.value = null;
    }
  }

  async function restartApp() {
    await invoke("restart_app");
  }

  async function openReleases() {
    const url = settings.value?.releasesUrl;
    if (!url) return;
    const { openUrl } = await import("@tauri-apps/plugin-opener");
    await openUrl(url);
  }

  return {
    settings,
    available,
    lastCheck,
    checking,
    checkError,
    startError,
    progress,
    installing,
    installError,
    needsRestart,
    loadSettings,
    setCheckOnStart,
    checkOnStart,
    checkNow,
    later,
    skip,
    install,
    restartApp,
    openReleases,
  };
});
