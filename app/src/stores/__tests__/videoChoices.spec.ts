import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { VIDEO_CHOICES_KEY, useVideoChoicesStore } from "../videoChoices";

/* The video picker's last choice per source, kept on this device. */

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("videoChoices", () => {
  it("remembers a choice per source and keeps it in localStorage", () => {
    const store = useVideoChoicesStore();
    expect(store.choiceOf("1")).toBeNull();

    store.remember("1", { hosterName: "VidStreaming", videoTitle: "1080p", resolution: 1080 });
    store.remember("2", { hosterName: "Main", videoTitle: "[DUBBED] AnimeGG", resolution: null });
    expect(store.choiceOf("1")?.hosterName).toBe("VidStreaming");
    expect(JSON.parse(localStorage.getItem(VIDEO_CHOICES_KEY)!)).toEqual({
      "1": { hosterName: "VidStreaming", videoTitle: "1080p", resolution: 1080 },
      "2": { hosterName: "Main", videoTitle: "[DUBBED] AnimeGG", resolution: null },
    });

    // a new session reads them back
    setActivePinia(createPinia());
    expect(useVideoChoicesStore().choiceOf("2")?.videoTitle).toBe("[DUBBED] AnimeGG");
  });

  it("ignores stored entries it can't read", () => {
    localStorage.setItem(
      VIDEO_CHOICES_KEY,
      JSON.stringify({
        "1": { hosterName: 5 },
        "2": { hosterName: "A", videoTitle: "B", resolution: 720 },
      }),
    );
    const store = useVideoChoicesStore();
    expect(store.choiceOf("1")).toBeNull();
    expect(store.choiceOf("2")).toEqual({ hosterName: "A", videoTitle: "B", resolution: 720 });

    localStorage.setItem(VIDEO_CHOICES_KEY, "{not json");
    setActivePinia(createPinia());
    expect(useVideoChoicesStore().choiceOf("2")).toBeNull();
  });
});
