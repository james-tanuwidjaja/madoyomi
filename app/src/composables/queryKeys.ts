import type { BrowseType, FilterChange, MediaKind } from "../api/types";

/** What identifies one paged browse: everything but the page. */
export interface BrowseKey {
  kind: MediaKind;
  sourceId: string;
  type: BrowseType;
  query: string | null;
  filters: FilterChange[] | null;
}

/**
 * Every vue-query key of the app, under `["api", …]`. A prefix invalidates everything below it, e.g.
 * `queryKeys.extensions()` both kinds' lists.
 */
export const queryKeys = {
  all: ["api"] as const,
  extensionStores: () => ["api", "extensionStores"] as const,
  extensions: (kind?: MediaKind) =>
    kind ? (["api", "extensions", kind] as const) : (["api", "extensions"] as const),
  sources: (kind?: MediaKind) =>
    kind ? (["api", "sources", kind] as const) : (["api", "sources"] as const),
  sourceFilters: (kind: MediaKind, sourceId: string) =>
    ["api", "sources", kind, sourceId, "filters"] as const,
  sourcePreferences: (kind: MediaKind, sourceId: string) =>
    ["api", "sources", kind, sourceId, "preferences"] as const,
  browse: (key: BrowseKey | null) => ["api", "browse", key] as const,
  anime: (id: number) => ["api", "anime", id] as const,
  episodes: (animeId: number) => ["api", "anime", animeId, "episodes"] as const,
  manga: (id: number) => ["api", "manga", id] as const,
  chapters: (mangaId: number) => ["api", "manga", mangaId, "chapters"] as const,
  seasons: (animeId: number) => ["api", "anime", animeId, "seasons"] as const,
  playerSettings: () => ["api", "player", "settings"] as const,
  playerLanguages: () => ["api", "player", "languages"] as const,
  readerSettings: () => ["api", "reader", "settings"] as const,
  pageCache: () => ["api", "reader", "pageCache"] as const,
  library: (kind?: MediaKind) =>
    kind ? (["api", "library", kind] as const) : (["api", "library"] as const),
};
