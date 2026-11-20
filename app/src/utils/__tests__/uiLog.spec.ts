import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, defineComponent, h } from "vue";

const shell = vi.hoisted(() => ({ isTauri: vi.fn(() => true), invoke: vi.fn() }));
vi.mock("@tauri-apps/api/core", () => ({ isTauri: shell.isTauri, invoke: shell.invoke }));

import { MAX_PER_MINUTE, installUiErrorLog, logToShell, resetUiLog } from "../uiLog";

beforeEach(() => {
  resetUiLog();
  shell.isTauri.mockReturnValue(true);
  shell.invoke.mockResolvedValue(undefined);
});

afterEach(() => {
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe("the UI's error log", () => {
  it("sends lines to the shell's log, at most MAX_PER_MINUTE a minute", () => {
    vi.useFakeTimers();
    logToShell("error", "boom");
    expect(shell.invoke).toHaveBeenCalledWith("log_from_ui", { level: "error", message: "boom" });
    for (let i = 0; i < MAX_PER_MINUTE + 10; i++) logToShell("warn", `again ${i}`);
    expect(shell.invoke).toHaveBeenCalledTimes(MAX_PER_MINUTE);
    vi.advanceTimersByTime(61_000);
    logToShell("error", "a minute later");
    expect(shell.invoke).toHaveBeenLastCalledWith("log_from_ui", {
      level: "error",
      message: "a minute later",
    });
  });

  it("does nothing outside the shell, and a failing shell doesn't throw", () => {
    shell.isTauri.mockReturnValue(false);
    logToShell("error", "boom");
    expect(shell.invoke).not.toHaveBeenCalled();
    shell.isTauri.mockReturnValue(true);
    shell.invoke.mockRejectedValue(new Error("no shell"));
    expect(() => logToShell("error", "boom")).not.toThrow();
  });

  it("logs Vue's errors, uncaught errors and unhandled rejections", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const target = new EventTarget() as unknown as Window;
    const Broken = defineComponent({
      setup() {
        throw new Error("render failed");
      },
      render: () => h("div"),
    });
    const app = createApp(Broken);
    installUiErrorLog(app, target);
    app.mount(document.createElement("div"));
    expect(shell.invoke).toHaveBeenCalledWith("log_from_ui", {
      level: "error",
      message: expect.stringMatching(/^vue \(setup function\): Error: render failed/),
    });

    const error = new Event("error") as ErrorEvent;
    Object.assign(error, { error: new TypeError("x is undefined"), message: "x is undefined" });
    target.dispatchEvent(error);
    expect(shell.invoke).toHaveBeenLastCalledWith("log_from_ui", {
      level: "error",
      message: expect.stringContaining("uncaught: TypeError: x is undefined"),
    });

    const rejection = new Event("unhandledrejection");
    Object.assign(rejection, { reason: { code: "NETWORK" } });
    target.dispatchEvent(rejection);
    expect(shell.invoke).toHaveBeenLastCalledWith("log_from_ui", {
      level: "error",
      message: 'unhandled rejection: {"code":"NETWORK"}',
    });
  });
});
