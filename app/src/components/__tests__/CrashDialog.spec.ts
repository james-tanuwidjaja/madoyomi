import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";

const shell = vi.hoisted(() => ({ isTauri: vi.fn(() => true), invoke: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: shell.isTauri, invoke: shell.invoke }));
const ops = vi.hoisted(() => ({ fetchDiagnostics: vi.fn() }));
vi.mock("../../api/operations", () => ops);

import CrashDialog from "../CrashDialog.vue";
import { useBackendStore, type BackendStatus } from "../../stores/backend";
import { backendStatus, healthyStatus } from "../../test/backendStatus";

let pinia: Pinia;
let wrapper: VueWrapper;

const dialog = () => document.querySelector("[data-testid='crash-dialog']");
const button = (action: string) =>
  document.querySelector<HTMLButtonElement>(`[data-action='${action}']`)!;

async function mountDialog(status: BackendStatus) {
  const backend = useBackendStore();
  backend.mode = "tauri";
  backend.info = { baseUrl: "http://127.0.0.1:50266", token: "t" };
  backend.status = status;
  wrapper = mount(CrashDialog, { attachTo: document.body, global: { plugins: [pinia] } });
  await flushPromises();
}

async function setStatus(status: BackendStatus) {
  useBackendStore().status = status;
  await flushPromises();
}

const crashed = (seq: number, crashCount: number, overrides: Partial<BackendStatus> = {}) => ({
  ...healthyStatus(seq),
  recentCrashes: 3,
  crashCount,
  crashAlert: true,
  ...overrides,
});

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  shell.invoke.mockResolvedValue(undefined);
  ops.fetchDiagnostics.mockResolvedValue("{}");
});

afterEach(() => {
  wrapper?.unmount();
  vi.clearAllMocks();
  document.body.innerHTML = "";
});

describe("CrashDialog", () => {
  it("stays hidden until the backend crashed three times in ten minutes", async () => {
    await mountDialog({ ...healthyStatus(2), recentCrashes: 2, crashCount: 2 });
    expect(dialog()).toBeNull();
    await setStatus(crashed(3, 3, { state: "restarting", error: "backend exited (exit code 1)" }));
    expect(dialog()?.textContent).toContain("It stopped 3 times in the last 10 minutes");
    expect(document.querySelector("[data-testid='crash-error']")?.textContent).toContain(
      "backend exited (exit code 1)",
    );
    expect(document.querySelector("[data-testid='export-logs']")).not.toBeNull();
  });

  it("opens the logs folder and exports the logs", async () => {
    await mountDialog(crashed(3, 3));
    button("open-logs-folder").click();
    expect(shell.invoke).toHaveBeenCalledWith("open_backend_logs");

    shell.invoke.mockResolvedValueOnce({
      path: "C:\\x\\Madoyomi-logs.zip",
      bytes: 2048,
      files: ["a", "b"],
    });
    button("export-logs").click();
    await flushPromises();
    expect(shell.invoke).toHaveBeenCalledWith("export_logs", { diagnostics: "{}" });
    expect(document.querySelector("[data-testid='export-result']")?.textContent).toContain(
      "Saved Madoyomi-logs.zip",
    );
  });

  it("stays closed after Close until the next crash", async () => {
    await mountDialog(crashed(3, 3));
    button("close-crash").click();
    await flushPromises();
    expect(dialog()).toBeNull();
    await setStatus(crashed(4, 3));
    expect(dialog()).toBeNull();
    await setStatus(crashed(5, 4, { recentCrashes: 4 }));
    expect(dialog()?.textContent).toContain("4 times");
  });

  it("leaves a failed backend to the full-screen panel, and does nothing outside the shell", async () => {
    await mountDialog(crashed(3, 5, { state: "failed" }));
    expect(dialog()).toBeNull();
    useBackendStore().mode = "external";
    await setStatus(crashed(4, 6));
    expect(dialog()).toBeNull();
    expect(backendStatus().crashAlert).toBe(false);
  });
});
