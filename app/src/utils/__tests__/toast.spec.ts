import { afterEach, describe, expect, it, vi } from "vitest";

const core = vi.hoisted(() => ({ isTauri: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => core);
const plugin = vi.hoisted(() => ({
  isPermissionGranted: vi.fn(),
  requestPermission: vi.fn(),
  sendNotification: vi.fn(),
}));
vi.mock("@tauri-apps/plugin-notification", () => plugin);

import { showToast } from "../toast";

/* The Windows toast (Phase 6 Stage 3) through a mocked Tauri notification plugin. */
afterEach(() => vi.clearAllMocks());

const text = { title: "2 new episodes", body: "Oshi no Ko" };

describe("showToast", () => {
  it("does nothing outside the shell", async () => {
    core.isTauri.mockReturnValue(false);
    expect(await showToast(text)).toBe(false);
    expect(plugin.sendNotification).not.toHaveBeenCalled();
  });

  it("sends the toast, asking for the permission once when it isn't granted", async () => {
    core.isTauri.mockReturnValue(true);
    plugin.isPermissionGranted.mockResolvedValue(true);
    expect(await showToast(text)).toBe(true);
    expect(plugin.requestPermission).not.toHaveBeenCalled();
    expect(plugin.sendNotification).toHaveBeenCalledWith(text);

    plugin.isPermissionGranted.mockResolvedValue(false);
    plugin.requestPermission.mockResolvedValue("denied");
    expect(await showToast(text)).toBe(false);
    expect(plugin.sendNotification).toHaveBeenCalledOnce();
  });
});
