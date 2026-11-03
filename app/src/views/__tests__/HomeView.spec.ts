import { describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";

import HomeView from "../HomeView.vue";

vi.mock("@tauri-apps/api/app", () => ({
  getVersion: vi.fn().mockResolvedValue("0.1.0"),
}));

describe("HomeView", () => {
  it("shows the app name and version", async () => {
    const wrapper = mount(HomeView);
    await flushPromises();

    expect(wrapper.find("h1").text()).toBe("Madoyomi");
    expect(wrapper.find(".version").text()).toBe("v0.1.0");
  });
});
