import { invoke, isTauri } from "@tauri-apps/api/core";
import { fetchDiagnostics } from "../api/operations";

/*
 * Export logs (Phase 7 Stage 3): the shell writes the zip (src-tauri/src/diagnostics.rs), so it works with the backend
 * down; when the backend answers, its report (versions, repos, extensions, settings without secrets, and the library
 * only when `includeLibrary`) goes in as diagnostics.json.
 */

/** What the shell wrote (`ExportSummary` in src-tauri/src/diagnostics.rs). */
export interface ExportSummary {
  path: string;
  bytes: number;
  /** The zip's entries. */
  files: string[];
}

/** How long the backend gets to write its report before the export goes on without it. */
export const DIAGNOSTICS_TIMEOUT_MS = 15_000;

/** Whether this window can export (only the shell can write the zip). */
export function canExportLogs(): boolean {
  return isTauri();
}

/**
 * Asks where to save (the shell's save dialog), then writes the zip. Returns what was written, or null when the dialog
 * was cancelled. The backend's report is asked for only when `backendReady`; if it fails or takes too long the zip
 * is written without it (the shell lists the extensions' folders instead).
 */
export async function exportLogs(opts: {
  includeLibrary: boolean;
  backendReady: boolean;
}): Promise<ExportSummary | null> {
  let diagnostics: string | null = null;
  if (opts.backendReady) {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), DIAGNOSTICS_TIMEOUT_MS);
    try {
      diagnostics = await fetchDiagnostics(opts.includeLibrary, abort.signal);
    } catch (e) {
      console.warn("the backend's report couldn't be read; exporting without it:", e);
    } finally {
      clearTimeout(timer);
    }
  }
  return invoke<ExportSummary | null>("export_logs", { diagnostics });
}

/** `C:\…\Madoyomi-logs-….zip` → `Madoyomi-logs-….zip` */
export function fileNameOf(path: string): string {
  return path.split(/[\\/]/).pop() ?? path;
}
