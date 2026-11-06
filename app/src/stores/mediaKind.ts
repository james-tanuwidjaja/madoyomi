import { defineStore } from "pinia";
import { ref, watch } from "vue";
import type { MediaKind } from "../api/types";

/** `localStorage` key of the anime/manga switch. */
export const MEDIA_KIND_KEY = "madoyomi.kind";

function stored(): MediaKind {
  try {
    const v = localStorage.getItem(MEDIA_KIND_KEY);
    return v === "manga" ? "manga" : "anime";
  } catch {
    return "anime";
  }
}

/**
 * The sidebar's anime/manga switch: which kind Library, Browse, Extensions and Settings show. Kept across sessions.
 * The detail routes set it from their path (`/anime/:id`, `/manga/:id`; src/router/index.ts).
 */
export const useMediaKindStore = defineStore("mediaKind", () => {
  const kind = ref<MediaKind>(stored());

  watch(
    kind,
    (k) => {
      try {
        localStorage.setItem(MEDIA_KIND_KEY, k);
      } catch {
        // storage full or disabled: the switch still works for this session
      }
    },
    { flush: "sync" },
  );

  function set(next: MediaKind) {
    kind.value = next;
  }

  return { kind, set };
});
