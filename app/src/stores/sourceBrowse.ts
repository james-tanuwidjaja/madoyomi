import { defineStore } from "pinia";
import { reactive } from "vue";
import type { BrowseType, MediaKind } from "../api/types";
import type { FilterState } from "../utils/filters";

/** One source page's state: what it shows and what was applied. */
export interface SourcePageState {
  tab: BrowseType;
  /** The search that was submitted (not what is being typed). */
  query: string;
  /** The applied filter values, or null for the source's defaults. */
  filters: FilterState | null;
  /** `filterSignature` of the list `filters` belongs to; a different list drops them. */
  signature: string | null;
}

const initial = (): SourcePageState => ({
  tab: "POPULAR",
  query: "",
  filters: null,
  signature: null,
});

/**
 * The state of each source page while the app runs (Phase 3 step 14), keyed by kind and source: the tab, the search
 * and the applied filters stay when you open an entry and come back. Nothing is persisted: a new session starts at
 * Popular with the source's defaults.
 */
export const useSourceBrowseStore = defineStore("sourceBrowse", () => {
  const pages = reactive<Record<string, SourcePageState>>({});

  /** The (reactive) state of one source page, created on first use. */
  function page(kind: MediaKind, sourceId: string): SourcePageState {
    const key = `${kind}:${sourceId}`;
    if (!pages[key]) pages[key] = initial();
    return pages[key];
  }

  return { pages, page };
});
