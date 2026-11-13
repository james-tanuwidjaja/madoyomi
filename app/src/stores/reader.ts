import { defineStore } from "pinia";
import { ref } from "vue";
import type { ReadingDirection } from "../utils/pagedNav";

export type ReaderMode = "paged" | "webtoon";

/**
 * The reader's choices for this app session (Phase 5 Stages 2–3), from the toolbar: the mode (paged by default;
 * Stage 5 applies the default by genre, phase-5 §8), the paged reading direction (right to left by default, Mihon's)
 * and the webtoon page gap (off by default). Stage 5 keeps them on this device and per manga.
 */
export const useReaderStore = defineStore("reader", () => {
  const mode = ref<ReaderMode>("paged");
  const direction = ref<ReadingDirection>("rtl");
  const pageGap = ref(false);

  function toggleMode() {
    mode.value = mode.value === "paged" ? "webtoon" : "paged";
  }

  function toggleDirection() {
    direction.value = direction.value === "rtl" ? "ltr" : "rtl";
  }

  function togglePageGap() {
    pageGap.value = !pageGap.value;
  }

  return { mode, direction, pageGap, toggleMode, toggleDirection, togglePageGap };
});
