import type { App } from "vue";
import { invoke, isTauri } from "@tauri-apps/api/core";

/*
 * The UI's uncaught errors go to the shell's sidecar.log as `[ui]` lines, so Export logs has them.
 * Only in the shell; at most `MAX_PER_MINUTE` a minute, so a loop of errors can't fill the log.
 */

export const MAX_PER_MINUTE = 30;

let windowStart = 0;
let sent = 0;

/** Tests: forget the rate limit. */
export function resetUiLog() {
  windowStart = 0;
  sent = 0;
}

function describe(e: unknown): string {
  if (e instanceof Error) return e.stack ?? `${e.name}: ${e.message}`;
  if (typeof e === "string") return e;
  try {
    return JSON.stringify(e) ?? String(e);
  } catch {
    return String(e);
  }
}

/** Writes one line to sidecar.log (best effort, never throws). */
export function logToShell(level: "error" | "warn", message: string): void {
  if (!isTauri()) return;
  const now = Date.now();
  if (now - windowStart > 60_000) {
    windowStart = now;
    sent = 0;
  }
  if (sent >= MAX_PER_MINUTE) return;
  sent++;
  invoke("log_from_ui", { level, message }).catch(() => {});
}

/** Sends Vue's errors, uncaught errors and unhandled rejections to the shell's log. */
export function installUiErrorLog(app: App, target: Window = window): void {
  const previous = app.config.errorHandler;
  app.config.errorHandler = (err, instance, info) => {
    logToShell("error", `vue (${info}): ${describe(err)}`);
    if (previous) previous(err, instance, info);
    else console.error(err);
  };
  target.addEventListener("error", (event) => {
    logToShell("error", `uncaught: ${describe(event.error ?? event.message)}`);
  });
  target.addEventListener("unhandledrejection", (event) => {
    logToShell("error", `unhandled rejection: ${describe(event.reason)}`);
  });
}
