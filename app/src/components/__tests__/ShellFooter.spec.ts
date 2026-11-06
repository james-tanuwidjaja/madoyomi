import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";

import ShellFooter from "../ShellFooter.vue";
import { useBackendStore } from "../../stores/backend";
import { useEventsStore } from "../../stores/events";
import { backendStatus, healthyStatus } from "../../test/backendStatus";

// Ported from Stage 1's HomeView tests: the sidebar footer now shows what the placeholder home did.
const tauri = vi.hoisted(() => ({
  isTauri: vi.fn(() => true),
  invoke: vi.fn(),
  getVersion: vi.fn(),
}));

vi.mock("@tauri-apps/api/app", () => ({ getVersion: tauri.getVersion }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: tauri.isTauri, invoke: tauri.invoke }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

let pinia: Pinia;

async function mountFooter() {
  const wrapper = mount(ShellFooter, { global: { plugins: [pinia] } });
  await flushPromises();
  return wrapper;
}

function setReady() {
  const backend = useBackendStore();
  backend.mode = "tauri";
  backend.status = healthyStatus(2);
  backend.info = { baseUrl: "http://127.0.0.1:50266", token: "secret-token" };
}

describe("ShellFooter", () => {
  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    tauri.isTauri.mockReturnValue(true);
    tauri.getVersion.mockResolvedValue("0.1.0");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("shows the app name and version", async () => {
    const wrapper = await mountFooter();

    expect(wrapper.find(".name").text()).toBe("Madoyomi");
    expect(wrapper.find(".version").text()).toBe("v0.1.0");
  });

  it("shows the server version and URL once the backend is ready, never the token", async () => {
    setReady();
    const wrapper = await mountFooter();

    const line = wrapper.find(".backend");
    expect(line.attributes("data-state")).toBe("healthy");
    expect(line.text()).toBe("Madoyomi-Server 0.1.0 · http://127.0.0.1:50266");
    expect(wrapper.text()).not.toContain("secret-token");
  });

  it("shows no backend line while the backend is not ready", async () => {
    const backend = useBackendStore();
    backend.mode = "tauri";
    backend.status = backendStatus({ seq: 3, state: "restarting" });
    backend.info = { baseUrl: "http://127.0.0.1:50266", token: "secret-token" };
    const wrapper = await mountFooter();

    expect(wrapper.find(".backend").exists()).toBe(false);
    expect(wrapper.find(".live").attributes("data-state")).toBe("offline");
  });

  it("skips Tauri calls when running in a plain browser", async () => {
    tauri.isTauri.mockReturnValue(false);
    const wrapper = await mountFooter();

    expect(tauri.getVersion).not.toHaveBeenCalled();
    expect(wrapper.find(".version").exists()).toBe(false);
  });

  it("shows whether live updates are connected", async () => {
    setReady();
    const events = useEventsStore();
    const wrapper = await mountFooter();
    expect(wrapper.find(".live").attributes("data-state")).toBe("connecting");

    events.wsState = "connected";
    await flushPromises();
    expect(wrapper.find(".live").attributes("data-state")).toBe("live");
    expect(wrapper.find(".live").text()).toBe("Live updates");
  });
});
