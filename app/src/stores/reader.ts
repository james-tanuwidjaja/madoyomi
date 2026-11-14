import { defineStore } from "pinia";
import { ref } from "vue";
import type { ReadingDirection } from "../utils/pagedNav";

/** The reader's modes: one page at a time, two side by side (Stage 4), or the vertical strip (Stage 3). */
export type ReaderMode = "paged" | "double" | "webtoon";

export const READER_MODES: readonly ReaderMode[] = ["paged", "double", "webtoon"];

/**
 * The reader's choices for this app session (Phase 5 Stages 2–4), from the toolbar: the mode (paged by default;
 * Stage 5 applies the default by genre, phase-5 §8), the reading direction of the paged and double-page modes (right
 * to left by default, Mihon's), the double-page "shift by one" (off: the first page alone) and the webtoon page gap
 * (off by default). Stage 5 keeps them on this device and per manga.
 */
export const useReaderStore = defineStore("reader", () => {
  const mode = ref<ReaderMode>("paged");
  const direction = ref<ReadingDirection>("rtl");
  const pageGap = ref(false);
  const shift = ref(false);

  function setMode(m: ReaderMode) {
    mode.value = m;
  }

  function toggleDirection() {
    direction.value = direction.value === "rtl" ? "ltr" : "rtl";
  }

  function togglePageGap() {
    pageGap.value = !pageGap.value;
  }

  function toggleShift() {
    shift.value = !shift.value;
  }

  return {
    mode,
    direction,
    pageGap,
    shift,
    setMode,
    toggleDirection,
    togglePageGap,
    toggleShift,
  };
});
