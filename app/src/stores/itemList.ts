import { defineStore } from "pinia";
import { reactive, watch } from "vue";
import type { MediaKind } from "../api/types";
import {
  DEFAULT_FILTERS,
  DEFAULT_SORT,
  type ItemFilters,
  type ItemSort,
  type ItemSortKey,
} from "../utils/entryItems";

/** `localStorage` key of the episode and chapter lists' sort, per kind. */
export const ITEM_SORT_KEY = "madoyomi.itemSort";

const SORT_KEYS: ItemSortKey[] = ["source", "number", "date"];

function storedSorts(): Record<MediaKind, ItemSort> {
  const sorts = { anime: { ...DEFAULT_SORT }, manga: { ...DEFAULT_SORT } };
  try {
    const raw = JSON.parse(localStorage.getItem(ITEM_SORT_KEY) ?? "{}") as Record<string, unknown>;
    for (const kind of ["anime", "manga"] as const) {
      const s = raw[kind] as Partial<ItemSort> | undefined;
      if (s && SORT_KEYS.includes(s.by as ItemSortKey) && typeof s.desc === "boolean") {
        sorts[kind] = { by: s.by as ItemSortKey, desc: s.desc };
      }
    }
  } catch {
    // unreadable: the defaults
  }
  return sorts;
}

/**
 * The details pages' episode and chapter lists:
 * - the sort, per kind and kept across sessions (a way of reading, like the anime/manga switch). The default is the
 *   source's order, newest first, as in Mihon;
 * - the filters, per entry and only for the session: a scanlator belongs to one entry, and a "bookmarked only" left
 *   on would hide the next entry's list.
 */
export const useItemListStore = defineStore("itemList", () => {
  const sorts = reactive(storedSorts());
  const filters = reactive(new Map<string, ItemFilters>());

  watch(
    sorts,
    (s) => {
      try {
        localStorage.setItem(ITEM_SORT_KEY, JSON.stringify(s));
      } catch {
        // storage full or disabled: kept for this session
      }
    },
    { deep: true, flush: "sync" },
  );

  function sortOf(kind: MediaKind): ItemSort {
    return sorts[kind];
  }

  function setSort(kind: MediaKind, next: ItemSort) {
    sorts[kind] = { ...next };
  }

  function filtersOf(kind: MediaKind, id: number): ItemFilters {
    return filters.get(`${kind}:${id}`) ?? DEFAULT_FILTERS;
  }

  function setFilters(kind: MediaKind, id: number, next: ItemFilters) {
    filters.set(`${kind}:${id}`, { ...next });
  }

  return { sorts, sortOf, setSort, filtersOf, setFilters };
});
