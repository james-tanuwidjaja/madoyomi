import { defineStore } from "pinia";
import { ref, watch } from "vue";
import type { MediaKind } from "../api/types";

/** `localStorage` key of the pinned sources: `{"anime": [ids], "manga": [ids]}`. */
export const PINNED_SOURCES_KEY = "madoyomi.pinnedSources";

type Pins = Record<MediaKind, string[]>;

function stored(): Pins {
  try {
    const raw = JSON.parse(localStorage.getItem(PINNED_SOURCES_KEY) ?? "null") as unknown;
    const ids = (v: unknown) =>
      Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
    if (raw && typeof raw === "object") {
      const r = raw as Record<string, unknown>;
      return { anime: ids(r.anime), manga: ids(r.manga) };
    }
  } catch {
    // unreadable or disabled storage: no pins
  }
  return { anime: [], manga: [] };
}

/**
 * Browse's pinned sources, per kind, in the order they were pinned (Phase 3 step 13). Kept in this device's
 * `localStorage`, like Mihon's `pinnedSources` app preference (Phase 3 §9 "Stage 4"). A pin of a source that is no
 * longer installed stays, and shows again if it comes back.
 */
export const usePinnedSourcesStore = defineStore("pinnedSources", () => {
  const pins = ref<Pins>(stored());

  watch(
    pins,
    (p) => {
      try {
        localStorage.setItem(PINNED_SOURCES_KEY, JSON.stringify(p));
      } catch {
        // storage full or disabled: the pins still work for this session
      }
    },
    { deep: true, flush: "sync" },
  );

  const isPinned = (kind: MediaKind, id: string) => pins.value[kind].includes(id);

  function toggle(kind: MediaKind, id: string) {
    const list = pins.value[kind];
    pins.value[kind] = list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
  }

  return { pins, isPinned, toggle };
});
