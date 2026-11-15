import { defineStore } from "pinia";
import { reactive, watch } from "vue";
import type { MediaKind } from "../api/types";

/** `localStorage` key of the Library's open category tab, per kind. */
export const LIBRARY_CATEGORY_KEY = "madoyomi.libraryCategory";

function stored(): Record<MediaKind, number | null> {
  const tabs: Record<MediaKind, number | null> = { anime: null, manga: null };
  try {
    const raw = JSON.parse(localStorage.getItem(LIBRARY_CATEGORY_KEY) ?? "{}") as Record<
      string,
      unknown
    >;
    for (const kind of ["anime", "manga"] as const) {
      const id = raw[kind];
      if (typeof id === "number" && Number.isInteger(id)) tabs[kind] = id;
    }
  } catch {
    // unreadable: the first tab
  }
  return tabs;
}

/**
 * The Library's open category tab per kind, on this device (Phase 6 Stage 1). A category that is gone (or a
 * Default tab that is hidden) falls back to the first tab, without forgetting the id.
 */
export const useLibraryCategoryStore = defineStore("libraryCategory", () => {
  const selected = reactive(stored());

  watch(
    selected,
    (s) => {
      try {
        localStorage.setItem(LIBRARY_CATEGORY_KEY, JSON.stringify(s));
      } catch {
        // storage full or disabled: kept for this session
      }
    },
    { deep: true, flush: "sync" },
  );

  function set(kind: MediaKind, id: number) {
    selected[kind] = id;
  }

  return { selected, set };
});
