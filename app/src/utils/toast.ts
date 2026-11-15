import { isTauri } from "@tauri-apps/api/core";

/*
 * The Windows toast (Phase 6 Stage 3) through Tauri's notification plugin, loaded only in the shell (plain
 * `pnpm dev` in a browser has none). Asks for the permission once if it isn't granted yet.
 */

export interface ToastText {
  title: string;
  body: string;
}

/** Shows the toast; false when it can't (no shell, no permission, the plugin failed). */
export async function showToast(text: ToastText): Promise<boolean> {
  if (!isTauri()) return false;
  try {
    const { isPermissionGranted, requestPermission, sendNotification } =
      await import("@tauri-apps/plugin-notification");
    let granted = await isPermissionGranted();
    if (!granted) granted = (await requestPermission()) === "granted";
    if (!granted) return false;
    sendNotification(text);
    return true;
  } catch (e) {
    console.warn("couldn't show the toast:", e);
    return false;
  }
}

/** Whether the app's window has the focus: the toast is for when it doesn't. */
export function windowFocused(): boolean {
  return typeof document !== "undefined" && document.hasFocus();
}
