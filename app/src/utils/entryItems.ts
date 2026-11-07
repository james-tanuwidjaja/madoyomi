import type { Chapter, Episode } from "../api/types";

/*
 * Episodes and chapters as one kind of row, with the details page's sort and filters (Phase 3 step 15). Pure, so
 * the 1000+ entry lists are sorted and filtered without the view.
 */

/** An episode or a chapter, for the list. */
export interface ListItem {
  id: number;
  name: string;
  /** The episode or chapter number; -1 when the source has none. */
  number: number;
  /** Upload date, epoch milliseconds (0: unknown). */
  date: number;
  /** The source's order, oldest = 1. */
  sourceOrder: number;
  scanlator: string | null;
  /** Watched or read. Phase 6 records it; until then nothing sets it. */
  done: boolean;
  bookmarked: boolean;
  /** Anime only: the source marks it as a filler. */
  filler: boolean;
}

export function fromEpisode(e: Episode): ListItem {
  return {
    id: e.id,
    name: e.name,
    number: e.episodeNumber,
    date: Number(e.uploadDate) || 0,
    sourceOrder: e.sourceOrder,
    scanlator: e.scanlator || null,
    done: e.isWatched,
    bookmarked: e.isBookmarked,
    filler: e.fillermark,
  };
}

export function fromChapter(c: Chapter): ListItem {
  return {
    id: c.id,
    name: c.name,
    number: c.chapterNumber,
    date: Number(c.uploadDate) || 0,
    sourceOrder: c.sourceOrder,
    scanlator: c.scanlator || null,
    done: c.isRead,
    bookmarked: c.isBookmarked,
    filler: false,
  };
}

export type ItemSortKey = "source" | "number" | "date";

export interface ItemSort {
  by: ItemSortKey;
  /** Descending: the newest, highest or latest first. */
  desc: boolean;
}

export type ProgressFilter = "all" | "notDone" | "done";

export interface ItemFilters {
  progress: ProgressFilter;
  bookmarked: boolean;
  /** Only this scanlator's (or fansub group's) rows; null: all. */
  scanlator: string | null;
}

export const DEFAULT_SORT: ItemSort = { by: "source", desc: true };
export const DEFAULT_FILTERS: ItemFilters = { progress: "all", bookmarked: false, scanlator: null };

/** The rows in `sort`'s order (a copy). Equal keys fall back to the source's order, in the same direction. */
export function sortItems(items: readonly ListItem[], sort: ItemSort): ListItem[] {
  const key = (i: ListItem) =>
    sort.by === "number" ? i.number : sort.by === "date" ? i.date : i.sourceOrder;
  const sign = sort.desc ? -1 : 1;
  return [...items].sort(
    (a, b) => sign * (key(a) - key(b) || a.sourceOrder - b.sourceOrder || a.id - b.id),
  );
}

export function filterItems(items: readonly ListItem[], f: ItemFilters): ListItem[] {
  if (f.progress === "all" && !f.bookmarked && f.scanlator === null) return [...items];
  return items.filter(
    (i) =>
      (f.progress === "all" || i.done === (f.progress === "done")) &&
      (!f.bookmarked || i.bookmarked) &&
      (f.scanlator === null || i.scanlator === f.scanlator),
  );
}

/** The scanlators of the rows, by name, each once (rows without one aren't counted). */
export function scanlators(items: readonly ListItem[]): string[] {
  const names = new Set<string>();
  for (const i of items) if (i.scanlator) names.add(i.scanlator);
  return [...names].sort((a, b) => a.localeCompare(b));
}

/** How many filters differ from the defaults (for the "n filters" mark). */
export function activeFilterCount(f: ItemFilters): number {
  return (f.progress !== "all" ? 1 : 0) + (f.bookmarked ? 1 : 0) + (f.scanlator !== null ? 1 : 0);
}
