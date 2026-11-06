import type { BackendStatus } from "../stores/backend";

/** A `backend-status` payload as the shell sends it (src-tauri/src/sidecar.rs `Status`). */
export function backendStatus(overrides: Partial<BackendStatus> = {}): BackendStatus {
  return {
    seq: 1,
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
    dataDir: "C:\\Users\\me\\AppData\\Roaming\\Madoyomi",
    logDir: "C:\\Users\\me\\AppData\\Roaming\\Madoyomi\\logs",
    ...overrides,
  };
}

export function healthyStatus(seq: number, port = 50266): BackendStatus {
  return backendStatus({
    seq,
    state: "healthy",
    baseUrl: `http://127.0.0.1:${port}`,
    pid: 4242,
    serverVersion: "0.1.0",
  });
}
