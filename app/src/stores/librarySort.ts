import { defineStore } from "pinia";
import { reactive, watch } from "vue";
import type { LibraryEntry, MediaKind } from "../api/types";

/** `localStorage` key of the Library's sort, per kind. */
export const LIBRARY_SORT_KEY = "madoyomi.librarySort";

/** Phase 6 Stage 2: `unread`, the unread/unwatched count, and `lastRead`, when it was last read or watched. */
export type LibrarySortKey = "title" | "added" | "unread" | "lastRead";

export const LIBRARY_SORT_KEYS: readonly LibrarySortKey[] = [
  "title",
  "added",
  "unread",
  "lastRead",
];

/** The direction a key starts in when chosen: titles A to Z, the others the most, the newest first. */
export function naturalDesc(by: LibrarySortKey): boolean {
  return by !== "title";
}

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
      if (s && LIBRARY_SORT_KEYS.includes(s.by as LibrarySortKey) && typeof s.desc === "boolean") {
        sorts[kind] = { by: s.by as LibrarySortKey, desc: s.desc };
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

function sortValue(e: LibraryEntry, by: Exclude<LibrarySortKey, "title">): number {
  if (by === "added") return e.inLibraryAt;
  if (by === "unread") return e.unreadCount;
  return e.lastReadAt;
}

/**
 * The entries in `sort`'s order (a copy); equal keys by title (A to Z), then id. By "last read", entries never read
 * or watched go last whichever the direction.
 */
export function sortLibrary(entries: readonly LibraryEntry[], sort: LibrarySort): LibraryEntry[] {
  const sign = sort.desc ? -1 : 1;
  return [...entries].sort((a, b) => {
    const byTitle = collator.compare(a.title, b.title) || a.id - b.id;
    if (sort.by === "title") return sign * byTitle;
    if (sort.by === "lastRead" && (a.lastReadAt === 0) !== (b.lastReadAt === 0)) {
      return a.lastReadAt === 0 ? 1 : -1;
    }
    return sign * (sortValue(a, sort.by) - sortValue(b, sort.by)) || byTitle;
  });
}
