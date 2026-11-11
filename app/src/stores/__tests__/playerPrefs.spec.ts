import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { AUTO_PLAY_NEXT_KEY, usePlayerPrefsStore } from "../playerPrefs";

/* Auto-play of the next episode, a preference on this device (Phase 4 Stage 4). */

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("playerPrefs", () => {
  it("auto-plays by default, and keeps a change in localStorage", () => {
    const store = usePlayerPrefsStore();
    expect(store.autoPlayNext).toBe(true);
    store.autoPlayNext = false;
    expect(localStorage.getItem(AUTO_PLAY_NEXT_KEY)).toBe("false");
    setActivePinia(createPinia());
    expect(usePlayerPrefsStore().autoPlayNext).toBe(false);
  });

  it("reads anything but false as on", () => {
    localStorage.setItem(AUTO_PLAY_NEXT_KEY, "garbage");
    expect(usePlayerPrefsStore().autoPlayNext).toBe(true);
  });
});
