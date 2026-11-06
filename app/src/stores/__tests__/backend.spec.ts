import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises } from "@vue/test-utils";

import { STATUS_EVENT, useBackendStore, type BackendStatus } from "../backend";
import { backendStatus, healthyStatus } from "../../test/backendStatus";

const shell = vi.hoisted(() => ({
  isTauri: vi.fn(() => true),
  invoke: vi.fn(),
  listen: vi.fn(),
  unlisten: vi.fn(),
  handler: null as ((event: { payload: unknown }) => void) | null,
}));

vi.mock("@tauri-apps/api/core", () => ({ isTauri: shell.isTauri, invoke: shell.invoke }));
vi.mock("@tauri-apps/api/event", () => ({ listen: shell.listen }));

const emit = (status: BackendStatus) => shell.handler?.({ payload: status });

let snapshot: BackendStatus;
let info: { baseUrl: string; token: string };

beforeEach(() => {
  setActivePinia(createPinia());
  snapshot = backendStatus();
  info = { baseUrl: "http://127.0.0.1:50266", token: "secret-token" };
  shell.isTauri.mockReturnValue(true);
  shell.listen.mockImplementation(async (_name: string, handler: typeof shell.handler) => {
    shell.handler = handler;
    return shell.unlisten;
  });
  shell.invoke.mockImplementation(async (cmd: string) => {
    if (cmd === "get_backend_status") return snapshot;
    if (cmd === "get_backend_info") return info;
    return undefined;
  });
});

afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  shell.handler = null;
});

describe("backend store in Tauri", () => {
  it("listens for status events before it reads the snapshot", async () => {
    const store = useBackendStore();
    await store.init();

    expect(shell.listen).toHaveBeenCalledWith(STATUS_EVENT, expect.any(Function));
    expect(shell.invoke).toHaveBeenCalledWith("get_backend_status");
    expect(shell.listen.mock.invocationCallOrder[0]).toBeLessThan(
      shell.invoke.mock.invocationCallOrder[0],
    );
    expect(store.mode).toBe("tauri");
    expect(store.status?.state).toBe("starting");
    expect(store.ready).toBe(false);
    expect(store.info).toBeNull();
  });

  it("gets the base URL and token once the backend is healthy", async () => {
    const store = useBackendStore();
    await store.init();
    emit(healthyStatus(2));
    await flushPromises();

    expect(shell.invoke).toHaveBeenCalledWith("get_backend_info");
    expect(store.info).toEqual(info);
    expect(store.ready).toBe(true);
    expect(store.hasBeenReady).toBe(true);
  });

  it("gets the info again after a restart on a new port, and only then", async () => {
    const store = useBackendStore();
    await store.init();
    emit(healthyStatus(2));
    await flushPromises();

    emit(backendStatus({ seq: 3, state: "restarting", error: "backend exited (exit code 1)" }));
    await flushPromises();
    expect(store.ready).toBe(false);
    expect(store.hasBeenReady).toBe(true);

    info = { baseUrl: "http://127.0.0.1:50300", token: "secret-token" };
    emit(healthyStatus(4, 50300));
    await flushPromises();
    emit(healthyStatus(5, 50300));
    await flushPromises();

    const infoCalls = shell.invoke.mock.calls.filter(([cmd]) => cmd === "get_backend_info");
    expect(infoCalls).toHaveLength(2);
    expect(store.info?.baseUrl).toBe("http://127.0.0.1:50300");
    expect(store.ready).toBe(true);
  });

  it("drops a snapshot older than an event it already applied", async () => {
    shell.invoke.mockImplementation(async (cmd: string) => {
      if (cmd !== "get_backend_status") return info;
      // The event overtakes the snapshot.
      emit(backendStatus({ seq: 2, state: "restarting", retryInMs: 1000 }));
      return backendStatus({ seq: 1, state: "starting" });
    });
    const store = useBackendStore();
    await store.init();
    await flushPromises();

    expect(store.status?.seq).toBe(2);
    expect(store.status?.state).toBe("restarting");
  });

  it("asks the shell to restart and to show the logs", async () => {
    const store = useBackendStore();
    await store.init();
    await store.retry();
    await store.openLogs();

    expect(shell.invoke).toHaveBeenCalledWith("restart_backend");
    expect(shell.invoke).toHaveBeenCalledWith("open_backend_logs");
  });

  it("stops listening on dispose", async () => {
    const store = useBackendStore();
    await store.init();
    store.dispose();

    expect(shell.unlisten).toHaveBeenCalledOnce();
  });
});

describe("backend store outside Tauri", () => {
  beforeEach(() => shell.isTauri.mockReturnValue(false));

  it("has no backend in a plain browser", async () => {
    const store = useBackendStore();
    await store.init();

    expect(store.mode).toBe("browser");
    expect(shell.listen).not.toHaveBeenCalled();
    expect(shell.invoke).not.toHaveBeenCalled();
    expect(store.ready).toBe(false);
  });

  it("connects to a server given by VITE_MADOYOMI_URL and VITE_MADOYOMI_TOKEN", async () => {
    vi.stubEnv("VITE_MADOYOMI_URL", "http://127.0.0.1:50266/");
    vi.stubEnv("VITE_MADOYOMI_TOKEN", "dev-token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ status: "ok", version: "0.1.0", pid: 7 }),
      }),
    );
    const store = useBackendStore();
    await store.init();

    expect(fetch).toHaveBeenCalledWith("http://127.0.0.1:50266/health");
    expect(store.mode).toBe("external");
    expect(store.info).toEqual({ baseUrl: "http://127.0.0.1:50266", token: "dev-token" });
    expect(store.status?.serverVersion).toBe("0.1.0");
    expect(store.ready).toBe(true);
  });

  it("reports an external server that doesn't answer", async () => {
    vi.stubEnv("VITE_MADOYOMI_URL", "http://127.0.0.1:50266");
    vi.stubEnv("VITE_MADOYOMI_TOKEN", "dev-token");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    const store = useBackendStore();
    await store.init();

    expect(store.status?.state).toBe("failed");
    expect(store.status?.error).toBe("http://127.0.0.1:50266/health: Failed to fetch");
    expect(store.ready).toBe(false);
  });
});
