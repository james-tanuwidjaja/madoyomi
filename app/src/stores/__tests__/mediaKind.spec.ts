import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { MEDIA_KIND_KEY, useMediaKindStore } from "../mediaKind";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

afterEach(() => localStorage.clear());

describe("media kind store", () => {
  it("starts on anime", () => {
    expect(useMediaKindStore().kind).toBe("anime");
  });

  it("keeps the choice across sessions", () => {
    useMediaKindStore().set("manga");
    expect(localStorage.getItem(MEDIA_KIND_KEY)).toBe("manga");

    setActivePinia(createPinia());
    expect(useMediaKindStore().kind).toBe("manga");
  });

  it("ignores a stored value it doesn't know", () => {
    localStorage.setItem(MEDIA_KIND_KEY, "novels");
    expect(useMediaKindStore().kind).toBe("anime");
  });
});
