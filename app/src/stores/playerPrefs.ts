import { defineStore } from "pinia";
import { ref, watch } from "vue";

/** `localStorage` key of "auto-play the next episode" (`"true"`/`"false"`; unset: on). */
export const AUTO_PLAY_NEXT_KEY = "madoyomi.autoPlayNext";

/** Seconds the next-episode toast counts down before it plays the next episode (with auto-play on). */
export const NEXT_EPISODE_COUNTDOWN_SECONDS = 10;

function stored(): boolean {
  try {
    return localStorage.getItem(AUTO_PLAY_NEXT_KEY) !== "false";
  } catch {
    return true;
  }
}

/**
 * Player preferences kept on this device (Phase 4 Stage 4), like the other UI preferences (Phase 3 §9), until
 * Stage 5's player settings section: whether the next-episode toast plays the next episode by itself when its
 * countdown ends (default on). Off, the toast only offers it.
 */
export const usePlayerPrefsStore = defineStore("playerPrefs", () => {
  const autoPlayNext = ref(stored());

  watch(
    autoPlayNext,
    (v) => {
      try {
        localStorage.setItem(AUTO_PLAY_NEXT_KEY, String(v));
      } catch {
        // storage full or disabled: kept for this session only
      }
    },
    { flush: "sync" },
  );

  return { autoPlayNext };
});
