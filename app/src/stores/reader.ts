import { defineStore } from "pinia";
import { reactive, ref, watch } from "vue";
import {
  DEFAULT_READER_PREFS,
  parseReaderPrefs,
  type ReaderMode,
  type ReaderPrefs,
} from "../utils/readerSettings";

export { READER_MODES, type ReaderMode } from "../utils/readerSettings";

/** `localStorage` key of the reader's global settings (one JSON object, read field by field). */
export const READER_PREFS_KEY = "madoyomi.reader";

function stored(): ReaderPrefs {
  try {
    return parseReaderPrefs(JSON.parse(localStorage.getItem(READER_PREFS_KEY) ?? "null"));
  } catch {
    return { ...DEFAULT_READER_PREFS };
  }
}

/**
 * The reader's global settings (Phase 5 Stage 5), kept on this device like the theme: the default mode and direction
 * (a manga's own, in its meta, come first: `useMangaReaderSettings`), the page fit of the paged modes, the strip's
 * width, the page gap, the background and the preload count. A value that isn't known reads as its default
 * (utils/readerSettings.ts). The double-page "shift by one" (Stage 4) stays a switch for the app session: it is about
 * how a chapter's spreads fall, not a preference.
 */
export const useReaderStore = defineStore("reader", () => {
  const prefs = reactive<ReaderPrefs>(stored());
  const shift = ref(false);

  watch(
    prefs,
    (p) => {
      try {
        localStorage.setItem(READER_PREFS_KEY, JSON.stringify(p));
      } catch {
        // storage full or disabled: kept for this session
      }
    },
    { deep: true, flush: "sync" },
  );

  /** Changes the given settings; values that aren't known are ignored. */
  function set(patch: Partial<ReaderPrefs>) {
    const next = parseReaderPrefs({ ...prefs, ...patch });
    Object.assign(prefs, next);
  }

  function setMode(m: ReaderMode) {
    set({ mode: m });
  }

  function togglePageGap() {
    prefs.pageGap = !prefs.pageGap;
  }

  function toggleShift() {
    shift.value = !shift.value;
  }

  /** Every global setting back to its default. */
  function reset() {
    Object.assign(prefs, DEFAULT_READER_PREFS);
  }

  return { prefs, shift, set, setMode, togglePageGap, toggleShift, reset };
});
