import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const shell = vi.hoisted(() => ({ isTauri: vi.fn(() => true), invoke: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: shell.isTauri, invoke: shell.invoke }));
const ops = vi.hoisted(() => ({ fetchDiagnostics: vi.fn() }));
vi.mock("../../api/operations", () => ops);

import { DIAGNOSTICS_TIMEOUT_MS, canExportLogs, exportLogs, fileNameOf } from "../logExport";

const summary = {
  path: "C:\\Users\\me\\Downloads\\Madoyomi-logs-2026-11-20-1448.zip",
  bytes: 4096,
  files: ["README.txt"],
};

beforeEach(() => {
  shell.invoke.mockResolvedValue(summary);
  ops.fetchDiagnostics.mockResolvedValue('{"backend": {}}');
});

afterEach(() => {
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe("exportLogs", () => {
  it("asks the backend for its report, without the library unless asked, and hands it to the shell", async () => {
    expect(await exportLogs({ includeLibrary: false, backendReady: true })).toEqual(summary);
    expect(ops.fetchDiagnostics).toHaveBeenCalledWith(false, expect.any(AbortSignal));
    expect(shell.invoke).toHaveBeenCalledWith("export_logs", { diagnostics: '{"backend": {}}' });

    await exportLogs({ includeLibrary: true, backendReady: true });
    expect(ops.fetchDiagnostics).toHaveBeenLastCalledWith(true, expect.any(AbortSignal));
  });

  it("exports without the report when the backend is down or fails to answer", async () => {
    await exportLogs({ includeLibrary: true, backendReady: false });
    expect(ops.fetchDiagnostics).not.toHaveBeenCalled();
    expect(shell.invoke).toHaveBeenLastCalledWith("export_logs", { diagnostics: null });

    ops.fetchDiagnostics.mockRejectedValue(new Error("NETWORK"));
    vi.spyOn(console, "warn").mockImplementation(() => {});
    await exportLogs({ includeLibrary: false, backendReady: true });
    expect(shell.invoke).toHaveBeenLastCalledWith("export_logs", { diagnostics: null });
  });

  it("gives up on a report that takes too long", async () => {
    vi.useFakeTimers();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    ops.fetchDiagnostics.mockImplementation(
      (_lib: boolean, signal: AbortSignal) =>
        new Promise((_, reject) =>
          signal.addEventListener("abort", () => reject(new Error("aborted"))),
        ),
    );
    const done = exportLogs({ includeLibrary: false, backendReady: true });
    await vi.advanceTimersByTimeAsync(DIAGNOSTICS_TIMEOUT_MS);
    await done;
    expect(shell.invoke).toHaveBeenLastCalledWith("export_logs", { diagnostics: null });
  });

  it("returns null when the save dialog is cancelled", async () => {
    shell.invoke.mockResolvedValue(null);
    expect(await exportLogs({ includeLibrary: false, backendReady: false })).toBeNull();
  });

  it("names the file and knows whether it runs in the shell", () => {
    expect(fileNameOf(summary.path)).toBe("Madoyomi-logs-2026-11-20-1448.zip");
    expect(fileNameOf("/tmp/x.zip")).toBe("x.zip");
    expect(canExportLogs()).toBe(true);
    shell.isTauri.mockReturnValue(false);
    expect(canExportLogs()).toBe(false);
  });
});
