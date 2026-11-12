import { defineStore } from "pinia";
import { ref } from "vue";
import type { ReadingDirection } from "../utils/pagedNav";

/**
 * The reader's choices for this app session (Phase 5 Stage 2): the paged reading direction, right to left by
 * default (Mihon's; phase-5 §8), flipped from the toolbar. Stage 5 keeps it on this device and per manga.
 */
export const useReaderStore = defineStore("reader", () => {
  const direction = ref<ReadingDirection>("rtl");

  function toggleDirection() {
    direction.value = direction.value === "rtl" ? "ltr" : "rtl";
  }

  return { direction, toggleDirection };
});
