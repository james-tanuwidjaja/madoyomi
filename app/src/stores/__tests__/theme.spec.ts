import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import indexHtml from "../../../index.html?raw";
import { THEME_KEY, useThemeStore } from "../theme";

/* The theme before and after the app loads (Phase 3 §9 "Stage 6"): index.html's inline script sets the stored
   theme before the first paint, which a screenshot can't catch, and the store keeps the choice. */

/** The classic (non-module) inline script of index.html. */
function inlineScript(): string {
  const scripts = [...indexHtml.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]!);
  expect(scripts).toHaveLength(1);
  return scripts[0]!;
}

const root = document.documentElement;

beforeEach(() => {
  localStorage.clear();
  delete root.dataset.theme;
});

afterEach(() => {
  localStorage.clear();
  delete root.dataset.theme;
});

describe("index.html's theme script", () => {
  it("reads the store's key", () => {
    expect(inlineScript()).toContain(JSON.stringify(THEME_KEY));
  });

  it.each(["dark", "light"])("applies a stored %s theme before the app loads", (theme) => {
    localStorage.setItem(THEME_KEY, theme);
    new Function(inlineScript())();
    expect(root.dataset.theme).toBe(theme);
  });

  it.each([null, "system", "purple"])("leaves the theme to Windows for %s", (theme) => {
    if (theme !== null) localStorage.setItem(THEME_KEY, theme);
    new Function(inlineScript())();
    expect(root.dataset.theme).toBeUndefined();
  });

  it("doesn't throw when storage is unavailable", () => {
    const script = inlineScript().split("localStorage").join("brokenStorage");
    const brokenStorage = {
      getItem() {
        throw new Error("denied");
      },
    };
    expect(() => new Function("brokenStorage", script)(brokenStorage)).not.toThrow();
    expect(root.dataset.theme).toBeUndefined();
  });
});

describe("the theme store", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("starts from what the script applied, and keeps the choice", () => {
    localStorage.setItem(THEME_KEY, "light");
    const theme = useThemeStore();
    expect(theme.choice).toBe("light");
    expect(root.dataset.theme).toBe("light");

    theme.set("dark");
    expect(root.dataset.theme).toBe("dark");
    expect(localStorage.getItem(THEME_KEY)).toBe("dark");
  });

  it("removes the key and the attribute for System", () => {
    localStorage.setItem(THEME_KEY, "dark");
    const theme = useThemeStore();
    theme.set("system");
    expect(root.dataset.theme).toBeUndefined();
    expect(localStorage.getItem(THEME_KEY)).toBeNull();
  });

  it("ignores an unknown choice", () => {
    const theme = useThemeStore();
    theme.set("purple" as never);
    expect(theme.choice).toBe("system");
    expect(root.dataset.theme).toBeUndefined();
  });
});
