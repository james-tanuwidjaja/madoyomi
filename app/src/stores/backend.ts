import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { invoke, isTauri } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

/** The Tauri event with a {@link BackendStatus} (`STATUS_EVENT` in src-tauri/src/sidecar.rs). */
export const STATUS_EVENT = "backend-status";

export type BackendState = "starting" | "healthy" | "restarting" | "failed" | "stopped";

/** Mirrors `Status` in src-tauri/src/sidecar.rs. It never carries the token. */
export interface BackendStatus {
  /** Grows with every change: a snapshot older than the last applied event is dropped. */
  seq: number;
  state: BackendState;
  /** Start attempt in the current failure streak, from 1. */
  attempt: number;
  /** Time since this start began (`starting`). */
  elapsedMs: number;
  /** Starting for longer than 30 s. */
  slow: boolean;
  baseUrl: string | null;
  pid: number | null;
  serverVersion: string | null;
  /** Why the last run ended (`restarting`, `failed`). */
  error: string | null;
  exitCode: number | null;
  /** Delay before the next start (`restarting`). */
  retryInMs: number | null;
  dataDir: string;
  logDir: string;
}

/** What the API client needs. The token stays in memory only. */
export interface BackendInfo {
  baseUrl: string;
  token: string;
}

/**
 * - `tauri`: the shell runs the backend (`pnpm tauri dev`, the installed app).
 * - `external`: `pnpm dev` in a browser, pointed at a running server by `VITE_MADOYOMI_URL` and
 *   `VITE_MADOYOMI_TOKEN` (e.g. in `.env.local`, for `tools/run-server.ps1`).
 * - `browser`: `pnpm dev` with no backend.
 */
export type BackendMode = "tauri" | "external" | "browser";

const errorMessage = (e: unknown) => (e instanceof Error ? e.message : String(e));

export const useBackendStore = defineStore("backend", () => {
  const mode = ref<BackendMode | null>(null);
  const status = ref<BackendStatus | null>(null);
  const info = ref<BackendInfo | null>(null);
  /** Once true the app's views stay mounted, and a restart shows an overlay instead of the splash. */
  const hasBeenReady = ref(false);

  /** Healthy, with the base URL and token of this run. */
  const ready = computed(
    () =>
      status.value?.state === "healthy" &&
      info.value !== null &&
      info.value.baseUrl === status.value.baseUrl,
  );

  let starting: Promise<void> | null = null;
  let unlisten: UnlistenFn | null = null;

  /** Starts following the backend. Safe to call more than once. */
  function init(): Promise<void> {
    starting ??= isTauri() ? followShell() : connectExternal();
    return starting;
  }

  async function followShell() {
    mode.value = "tauri";
    // Listen first, then read the snapshot, so no change falls between the two. `seq` drops the older one.
    unlisten = await listen<BackendStatus>(STATUS_EVENT, (event) => void apply(event.payload));
    try {
      await apply(await invoke<BackendStatus>("get_backend_status"));
    } catch (e) {
      // Only if the command itself is missing; the events still arrive.
      console.error("get_backend_status failed:", errorMessage(e));
    }
  }

  async function apply(next: BackendStatus) {
    if (status.value && next.seq <= status.value.seq) return;
    status.value = next;
    if (next.state === "healthy" && info.value?.baseUrl !== next.baseUrl) {
      try {
        // The port changes with every restart; the token doesn't.
        info.value = await invoke<BackendInfo>("get_backend_info");
      } catch {
        // It went down again; the next healthy status fetches it.
      }
    }
    if (ready.value) hasBeenReady.value = true;
  }

  async function connectExternal() {
    const url = import.meta.env.VITE_MADOYOMI_URL?.replace(/\/+$/, "");
    const token = import.meta.env.VITE_MADOYOMI_TOKEN;
    if (!url || !token) {
      mode.value = "browser";
      return;
    }
    mode.value = "external";
    const base: BackendStatus = {
      seq: (status.value?.seq ?? 0) + 1,
      state: "starting",
      attempt: 1,
      elapsedMs: 0,
      slow: false,
      baseUrl: null,
      pid: null,
      serverVersion: null,
      error: null,
      exitCode: null,
      retryInMs: null,
      dataDir: "",
      logDir: "",
    };
    status.value = base;
    try {
      const res = await fetch(`${url}/health`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = (await res.json()) as { status?: string; version?: string; pid?: number };
      if (body.status !== "ok") throw new Error(`unexpected status ${String(body.status)}`);
      info.value = { baseUrl: url, token };
      status.value = {
        ...base,
        seq: base.seq + 1,
        state: "healthy",
        baseUrl: url,
        pid: body.pid ?? null,
        serverVersion: body.version ?? null,
      };
      hasBeenReady.value = true;
    } catch (e) {
      status.value = {
        ...base,
        seq: base.seq + 1,
        state: "failed",
        error: `${url}/health: ${errorMessage(e)}`,
      };
    }
  }

  /** Starts the backend again now (from `failed`, or skipping the backoff while `restarting`). */
  async function retry() {
    if (mode.value === "tauri") await invoke("restart_backend");
    else if (mode.value === "external") await connectExternal();
  }

  /** Shows the shell's `sidecar.log` in Explorer. */
  async function openLogs() {
    if (mode.value === "tauri") await invoke("open_backend_logs");
  }

  function dispose() {
    unlisten?.();
    unlisten = null;
  }

  return { mode, status, info, ready, hasBeenReady, init, retry, openLogs, dispose };
});
