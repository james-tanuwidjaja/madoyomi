import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";

import BackendGate from "../BackendGate.vue";
import type { BackendStatus } from "../../stores/backend";
import { backendStatus, healthyStatus } from "../../test/backendStatus";

const shell = vi.hoisted(() => ({
  isTauri: vi.fn(() => true),
  invoke: vi.fn(),
  listen: vi.fn(),
  handler: null as ((event: { payload: unknown }) => void) | null,
}));

vi.mock("@tauri-apps/api/core", () => ({ isTauri: shell.isTauri, invoke: shell.invoke }));
vi.mock("@tauri-apps/api/event", () => ({ listen: shell.listen }));

let snapshot: BackendStatus;
let baseUrl: string;

async function emit(status: BackendStatus) {
  shell.handler?.({ payload: status });
  await flushPromises();
}

async function mountGate() {
  const wrapper = mount(BackendGate, {
    slots: { default: '<div class="app-content">app</div>' },
    global: { plugins: [createPinia()] },
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  snapshot = backendStatus();
  baseUrl = "http://127.0.0.1:50266";
  shell.isTauri.mockReturnValue(true);
  shell.listen.mockImplementation(async (_name: string, handler: typeof shell.handler) => {
    shell.handler = handler;
    return () => {};
  });
  shell.invoke.mockImplementation(async (cmd: string) => {
    if (cmd === "get_backend_status") return snapshot;
    if (cmd === "get_backend_info") return { baseUrl, token: "secret-token" };
    return undefined;
  });
});

afterEach(() => {
  vi.clearAllMocks();
  shell.handler = null;
});

describe("BackendGate", () => {
  it("shows the splash while the backend starts", async () => {
    snapshot = backendStatus({ elapsedMs: 12_400 });
    const wrapper = await mountGate();

    expect(wrapper.find(".app-content").exists()).toBe(false);
    expect(wrapper.find(".screen").text()).toContain("Starting the backend");
    expect(wrapper.find(".elapsed").text()).toContain("12 s");
    expect(wrapper.find(".slow").exists()).toBe(false);
  });

  it("explains a slow start and counts the attempt", async () => {
    snapshot = backendStatus({ elapsedMs: 45_000, slow: true, attempt: 2 });
    const wrapper = await mountGate();

    expect(wrapper.find(".slow").text()).toContain("a minute or more");
    expect(wrapper.find(".elapsed").text()).toContain("attempt 2");
  });

  it("shows the app once the backend is healthy", async () => {
    const wrapper = await mountGate();
    await emit(healthyStatus(2));

    expect(shell.invoke).toHaveBeenCalledWith("get_backend_info");
    expect(wrapper.find(".app-content").exists()).toBe(true);
    expect(wrapper.find(".screen").exists()).toBe(false);
    expect(wrapper.find(".overlay").exists()).toBe(false);
  });

  it("keeps the app mounted during a restart and recovers on its own", async () => {
    const wrapper = await mountGate();
    await emit(healthyStatus(2));

    await emit(
      backendStatus({
        seq: 3,
        state: "restarting",
        error: "backend exited (exit code 1)",
        exitCode: 1,
        retryInMs: 2000,
      }),
    );
    expect(wrapper.find(".app-content").exists()).toBe(true);
    const overlay = wrapper.find(".overlay");
    expect(overlay.text()).toContain("The backend stopped");
    expect(overlay.text()).toContain("backend exited (exit code 1)");
    expect(overlay.find(".retry").text()).toContain("Restarting in 2 s");
    expect(overlay.find(".logs").text()).toContain("Madoyomi\\logs");

    await emit(backendStatus({ seq: 4, state: "starting", attempt: 2, elapsedMs: 3000 }));
    expect(wrapper.find(".overlay").text()).toContain("Starting the backend");

    baseUrl = "http://127.0.0.1:50300";
    await emit(healthyStatus(5, 50300));
    expect(wrapper.find(".overlay").exists()).toBe(false);
    expect(wrapper.find(".app-content").exists()).toBe(true);
  });

  it("shows a failure with the log folder, and retries", async () => {
    snapshot = backendStatus({
      state: "failed",
      attempt: 5,
      error: "backend exited before reporting its port (exit code 1)",
      exitCode: 1,
    });
    const wrapper = await mountGate();

    const screen = wrapper.find(".screen");
    expect(screen.find('[role="alert"]').exists()).toBe(true);
    expect(screen.text()).toContain("The backend could not start");
    expect(screen.text()).toContain("backend exited before reporting its port (exit code 1)");
    expect(screen.text()).toContain("after 5 tries");
    expect(screen.find(".logs code").text()).toBe(snapshot.logDir);

    await screen.find(".retry-button").trigger("click");
    expect(shell.invoke).toHaveBeenCalledWith("restart_backend");
    await screen.find(".logs-button").trigger("click");
    expect(shell.invoke).toHaveBeenCalledWith("open_backend_logs");
  });

  it("renders the app with a banner in a plain browser", async () => {
    shell.isTauri.mockReturnValue(false);
    const wrapper = await mountGate();

    expect(wrapper.find(".app-content").exists()).toBe(true);
    expect(wrapper.find(".browser-banner").exists()).toBe(true);
    expect(shell.invoke).not.toHaveBeenCalled();
  });
});
