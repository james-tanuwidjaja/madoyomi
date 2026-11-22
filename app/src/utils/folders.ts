import { isTauri } from "@tauri-apps/api/core";

/*
 * Showing a folder in Explorer (Settings → Downloads) through Tauri's opener plugin, loaded only in
 * the shell (plain `pnpm dev` in a browser has none).
 */

/** Shows `path` in Explorer; false when it can't (no shell, a path that isn't there, the plugin failed). */
export async function revealFolder(path: string): Promise<boolean> {
  if (!isTauri() || !path) return false;
  try {
    const { revealItemInDir } = await import("@tauri-apps/plugin-opener");
    await revealItemInDir(path);
    return true;
  } catch (e) {
    console.warn("couldn't show the folder:", e);
    return false;
  }
}
