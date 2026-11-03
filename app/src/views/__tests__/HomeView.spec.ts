import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";

import HomeView from "../HomeView.vue";

const tauri = vi.hoisted(() => ({
  isTauri: vi.fn(() => true),
  invoke: vi.fn(),
}));

vi.mock("@tauri-apps/api/app", () => ({
  getVersion: vi.fn().mockResolvedValue("0.1.0"),
}));

vi.mock("@tauri-apps/api/core", () => tauri);

describe("HomeView", () => {
  beforeEach(() => {
    tauri.isTauri.mockReturnValue(true);
    tauri.invoke.mockResolvedValue("http://127.0.0.1:50266");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ status: "ok" }) }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("shows the app name and version", async () => {
    const wrapper = mount(HomeView);
    await flushPromises();

    expect(wrapper.find("h1").text()).toBe("Madoyomi");
    expect(wrapper.find(".version").text()).toBe("v0.1.0");
  });

  it("checks the backend health through the sidecar URL", async () => {
    const wrapper = mount(HomeView);
    await flushPromises();

    expect(tauri.invoke).toHaveBeenCalledWith("get_backend_url");
    expect(fetch).toHaveBeenCalledWith("http://127.0.0.1:50266/health");
    expect(wrapper.find(".backend").attributes("data-state")).toBe("ok");
  });

  it("shows an error when the backend does not start", async () => {
    tauri.invoke.mockRejectedValue("backend exited before reporting its port");
    const wrapper = mount(HomeView);
    await flushPromises();

    expect(wrapper.find(".backend").attributes("data-state")).toBe("error");
    expect(wrapper.find(".backend").text()).toContain("backend exited before reporting its port");
  });

  it("skips Tauri calls when running in a plain browser", async () => {
    tauri.isTauri.mockReturnValue(false);
    const wrapper = mount(HomeView);
    await flushPromises();

    expect(tauri.invoke).not.toHaveBeenCalled();
    expect(wrapper.find(".backend").exists()).toBe(false);
  });
});
