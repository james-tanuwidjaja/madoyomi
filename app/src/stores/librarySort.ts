import { defineStore } from "pinia";
import { reactive, watch } from "vue";
import type { LibraryEntry, MediaKind } from "../api/types";

/** `localStorage` key of the Library's sort, per kind. */
export const LIBRARY_SORT_KEY = "madoyomi.librarySort";

export type LibrarySortKey = "title" | "added";

export interface LibrarySort {
  by: LibrarySortKey;
  desc: boolean;
}

/** The newest additions first. */
export const DEFAULT_LIBRARY_SORT: LibrarySort = { by: "added", desc: true };

function stored(): Record<MediaKind, LibrarySort> {
  const sorts = { anime: { ...DEFAULT_LIBRARY_SORT }, manga: { ...DEFAULT_LIBRARY_SORT } };
  try {
    const raw = JSON.parse(localStorage.getItem(LIBRARY_SORT_KEY) ?? "{}") as Record<
      string,
      unknown
    >;
    for (const kind of ["anime", "manga"] as const) {
      const s = raw[kind] as Partial<LibrarySort> | undefined;
      if (s && (s.by === "title" || s.by === "added") && typeof s.desc === "boolean") {
        sorts[kind] = { by: s.by, desc: s.desc };
      }
    }
  } catch {
    // unreadable: the defaults
  }
  return sorts;
}

/** The Library's sort, per kind, kept across sessions. */
export const useLibrarySortStore = defineStore("librarySort", () => {
  const sorts = reactive(stored());

  watch(
    sorts,
    (s) => {
      try {
        localStorage.setItem(LIBRARY_SORT_KEY, JSON.stringify(s));
      } catch {
        // storage full or disabled: kept for this session
      }
    },
    { deep: true, flush: "sync" },
  );

  function set(kind: MediaKind, next: LibrarySort) {
    sorts[kind] = { ...next };
  }

  return { sorts, set };
});

const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });

/** The entries in `sort`'s order (a copy); equal keys by title, then id. */
export function sortLibrary(entries: readonly LibraryEntry[], sort: LibrarySort): LibraryEntry[] {
  const sign = sort.desc ? -1 : 1;
  return [...entries].sort((a, b) => {
    const byTitle = collator.compare(a.title, b.title) || a.id - b.id;
    if (sort.by === "title") return sign * byTitle;
    return sign * (a.inLibraryAt - b.inLibraryAt) || byTitle;
  });
}
