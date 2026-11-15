import type { LibraryCategory, LibraryEntry } from "../api/types";
import type { LibrarySort, LibrarySortKey } from "../stores/librarySort";

/*
 * The Library's categories (Phase 6 Stage 1), pure: the tabs, which entries a tab shows, a category's own sort (in its
 * meta), the name rules, and the tri-state of the assign dialog.
 */

/** The meta key of a category's sort ("title:asc", "added:desc"). */
export const CATEGORY_SORT_KEY = "madoyomi.library.sort";

/** The most characters a category's name has (upstream's column). */
export const CATEGORY_NAME_MAX = 64;

export interface CategoryTab {
  category: LibraryCategory;
  /** The entries it shows. */
  count: number;
}

/** The entries of `category` (Default: those in no other category). */
export function entriesIn(
  entries: readonly LibraryEntry[],
  category: LibraryCategory,
): LibraryEntry[] {
  return category.isDefaultCategory
    ? entries.filter((e) => e.categoryIds.length === 0)
    : entries.filter((e) => e.categoryIds.includes(category.id));
}

/**
 * The Library's tabs, as Mihon shows them: none while there is no category but Default (the whole library shows);
 * otherwise every category in order, Default only while it holds entries.
 */
export function libraryTabs(
  categories: readonly LibraryCategory[],
  entries: readonly LibraryEntry[],
): CategoryTab[] {
  if (!categories.some((c) => !c.isDefaultCategory)) return [];
  return categories
    .map((category) => ({ category, count: entriesIn(entries, category).length }))
    .filter((t) => !t.category.isDefaultCategory || t.count > 0);
}

/** The tab to show: the remembered one if it is still there, else the first. */
export function pickTab(
  tabs: readonly CategoryTab[],
  remembered: number | null,
): CategoryTab | null {
  return tabs.find((t) => t.category.id === remembered) ?? tabs[0] ?? null;
}

const SORT_KEYS: readonly LibrarySortKey[] = ["title", "added"];

/** A stored sort ("title:asc"), or null when it isn't one. */
export function parseSort(value: string | null | undefined): LibrarySort | null {
  const [by, dir] = (value ?? "").split(":");
  if (!SORT_KEYS.includes(by as LibrarySortKey) || (dir !== "asc" && dir !== "desc")) return null;
  return { by: by as LibrarySortKey, desc: dir === "desc" };
}

export function formatSort(sort: LibrarySort): string {
  return `${sort.by}:${sort.desc ? "desc" : "asc"}`;
}

/** A category's own sort, from its meta; null when it has none. */
export function categorySort(category: LibraryCategory): LibrarySort | null {
  return parseSort(category.meta.find((m) => m.key === CATEGORY_SORT_KEY)?.value);
}

/** The category with its sort set in its meta (for the cache). */
export function withSort(category: LibraryCategory, sort: LibrarySort): LibraryCategory {
  return {
    ...category,
    meta: [
      ...category.meta.filter((m) => m.key !== CATEGORY_SORT_KEY),
      { key: CATEGORY_SORT_KEY, value: formatSort(sort) },
    ],
  };
}

/**
 * What is wrong with `name` for a category of this list (`exceptId`: the one being renamed), or null. The backend
 * checks anime names the same way; upstream checks a new manga name only, case-sensitively, so the app checks both.
 */
export function categoryNameError(
  name: string,
  categories: readonly LibraryCategory[],
  exceptId?: number,
): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Enter a name.";
  if (trimmed.length > CATEGORY_NAME_MAX) return `Use at most ${CATEGORY_NAME_MAX} characters.`;
  const lower = trimmed.toLowerCase();
  if (lower === "default") return '"Default" is taken by the built-in category.';
  if (categories.some((c) => c.id !== exceptId && c.name.trim().toLowerCase() === lower))
    return `There is a category named "${trimmed}" already.`;
  return null;
}

/** Whether all, some or none of `entries` are in `categoryId`. */
export type Membership = "all" | "some" | "none";

export function membership(entries: readonly LibraryEntry[], categoryId: number): Membership {
  const n = entries.filter((e) => e.categoryIds.includes(categoryId)).length;
  if (n === 0) return "none";
  return n === entries.length ? "all" : "some";
}

/**
 * The change the assign dialog makes: a category now `all` that wasn't goes into `add`, one now `none` that wasn't
 * into `remove`; one left at `some` stays as it is for each entry.
 */
export function assignmentPatch(
  before: ReadonlyMap<number, Membership>,
  after: ReadonlyMap<number, Membership>,
): { add: number[]; remove: number[] } {
  const add: number[] = [];
  const remove: number[] = [];
  for (const [id, state] of after) {
    const was = before.get(id) ?? "none";
    if (state === was) continue;
    if (state === "all") add.push(id);
    else if (state === "none") remove.push(id);
  }
  return { add, remove };
}

/** The next state when a box is clicked: a mixed one becomes all, all becomes none, none becomes all. */
export function nextMembership(state: Membership): Membership {
  return state === "all" ? "none" : "all";
}

/** The entries with a patch applied (for the cache). */
export function applyAssignment(
  entries: readonly LibraryEntry[],
  ids: readonly number[],
  patch: { add: readonly number[]; remove: readonly number[] },
): LibraryEntry[] {
  const chosen = new Set(ids);
  return entries.map((e) => {
    if (!chosen.has(e.id)) return e;
    const next = e.categoryIds.filter((c) => !patch.remove.includes(c));
    for (const c of patch.add) if (!next.includes(c)) next.push(c);
    return { ...e, categoryIds: next };
  });
}
