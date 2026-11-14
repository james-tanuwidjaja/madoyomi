import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { READER_PREFS_KEY, useReaderStore } from "../reader";
import { DEFAULT_READER_PREFS } from "../../utils/readerSettings";

/* The reader's global settings on this device (Phase 5 Stage 5): kept in one localStorage value, read field by field;
   "shift by one" stays for the session. */

const stored = () => JSON.parse(localStorage.getItem(READER_PREFS_KEY) ?? "null") as unknown;

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("useReaderStore", () => {
  it("starts from the defaults when nothing is stored", () => {
    const r = useReaderStore();
    expect({ ...r.prefs }).toEqual(DEFAULT_READER_PREFS);
    expect(r.shift).toBe(false);
  });

  it("keeps each change, and a new app session starts from it", () => {
    const r = useReaderStore();
    r.set({ mode: "webtoon", fit: "width", background: "white", preload: 8 });
    r.togglePageGap();
    expect(stored()).toEqual({
      ...DEFAULT_READER_PREFS,
      mode: "webtoon",
      fit: "width",
      background: "white",
      preload: 8,
      pageGap: true,
    });

    setActivePinia(createPinia());
    const again = useReaderStore();
    expect(again.prefs.mode).toBe("webtoon");
    expect(again.prefs.pageGap).toBe(true);
    expect(again.prefs.preload).toBe(8);
  });

  it("reads an old or hand-edited value field by field, and unreadable JSON as the defaults", () => {
    localStorage.setItem(
      READER_PREFS_KEY,
      JSON.stringify({ mode: "double", fit: "zoom", preload: 3 }),
    );
    expect({ ...useReaderStore().prefs }).toEqual({ ...DEFAULT_READER_PREFS, mode: "double" });

    setActivePinia(createPinia());
    localStorage.setItem(READER_PREFS_KEY, "{not json");
    expect({ ...useReaderStore().prefs }).toEqual(DEFAULT_READER_PREFS);
  });

  it("ignores a value it doesn't know", () => {
    const r = useReaderStore();
    r.set({ stripWidth: 700, mode: "vertical" as never, direction: "ltr" });
    expect(r.prefs.stripWidth).toBe(800);
    expect(r.prefs.mode).toBe("paged");
    expect(r.prefs.direction).toBe("ltr");
  });

  it("resets every global setting, and keeps Shift for the session only", () => {
    const r = useReaderStore();
    r.set({ mode: "double", background: "black" });
    r.toggleShift();
    r.reset();
    expect({ ...r.prefs }).toEqual(DEFAULT_READER_PREFS);
    expect(stored()).toEqual(DEFAULT_READER_PREFS);
    expect(r.shift).toBe(true);
    expect(JSON.stringify(stored())).not.toContain("shift");
  });
});
