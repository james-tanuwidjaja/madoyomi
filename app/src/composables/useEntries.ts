import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import {
  fetchAnime,
  fetchChapters,
  fetchEpisodes,
  fetchLibrary,
  fetchManga,
  fetchSeasons,
  refreshAnime,
  refreshManga,
  setInLibrary,
  updateEpisodes,
} from "../api/operations";
import type { Anime, Episode, EpisodePatch, MediaKind, SourcePage } from "../api/types";
import { queryKeys, type BrowseKey } from "./queryKeys";

/*
 * Stored entries and their episodes/chapters/seasons (the database, not the source), the refresh from the source,
 * the library flag and the library lists (Phase 3 Stage 5). A refresh or a library change writes what the server
 * returns into the cache, so nothing is fetched twice and the source's site isn't asked again.
 */

const STALE = 30_000;

function useId(id: MaybeRefOrGetter<number | null>) {
  const backend = useBackendStore();
  return {
    value: () => toValue(id) ?? 0,
    enabled: computed(() => backend.ready && (toValue(id) ?? 0) > 0),
  };
}

export function useAnime(id: MaybeRefOrGetter<number | null>) {
  const i = useId(id);
  return useQuery({
    queryKey: computed(() => queryKeys.anime(i.value())),
    queryFn: ({ signal }) => fetchAnime(i.value(), signal),
    enabled: i.enabled,
    staleTime: STALE,
  });
}

export function useEpisodes(animeId: MaybeRefOrGetter<number | null>) {
  const i = useId(animeId);
  return useQuery({
    queryKey: computed(() => queryKeys.episodes(i.value())),
    queryFn: ({ signal }) => fetchEpisodes(i.value(), signal),
    enabled: i.enabled,
    staleTime: STALE,
  });
}

/** The seasons of a `Seasons` anime; pass null for an entry that has episodes. */
export function useSeasons(animeId: MaybeRefOrGetter<number | null>) {
  const i = useId(animeId);
  return useQuery({
    queryKey: computed(() => queryKeys.seasons(i.value())),
    queryFn: ({ signal }) => fetchSeasons(i.value(), signal),
    enabled: i.enabled,
    staleTime: STALE,
  });
}

export function useManga(id: MaybeRefOrGetter<number | null>) {
  const i = useId(id);
  return useQuery({
    queryKey: computed(() => queryKeys.manga(i.value())),
    queryFn: ({ signal }) => fetchManga(i.value(), signal),
    enabled: i.enabled,
    staleTime: STALE,
  });
}

export function useChapters(mangaId: MaybeRefOrGetter<number | null>) {
  const i = useId(mangaId);
  return useQuery({
    queryKey: computed(() => queryKeys.chapters(i.value())),
    queryFn: ({ signal }) => fetchChapters(i.value(), signal),
    enabled: i.enabled,
    staleTime: STALE,
  });
}

/** The library of `kind`, unsorted (the view sorts it). */
export function useLibrary(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.library(toValue(kind))),
    queryFn: ({ signal }) => fetchLibrary(toValue(kind), signal),
    enabled: computed(() => backend.ready),
    staleTime: STALE,
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Refresh from the source

/**
 * Refreshes an anime from its source (details and episodes, or seasons). The result, which is the stored data even
 * when the source failed (`error`), goes into the cache. The mutation fails only without data (the backend down).
 */
export function useRefreshAnime() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => refreshAnime(id),
    onSuccess: (r, id) => {
      queryClient.setQueryData(queryKeys.anime(id), r.anime);
      queryClient.setQueryData(queryKeys.episodes(id), r.episodes);
      queryClient.setQueryData(queryKeys.seasons(id), r.seasons);
      if (r.anime.inLibrary)
        void queryClient.invalidateQueries({ queryKey: queryKeys.library("anime") });
    },
  });
}

/** {@link useRefreshAnime} for manga: details, then chapters. */
export function useRefreshManga() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => refreshManga(id),
    onSuccess: (r, id) => {
      queryClient.setQueryData(queryKeys.manga(id), r.manga);
      queryClient.setQueryData(queryKeys.chapters(id), r.chapters);
      if (r.manga.inLibrary)
        void queryClient.invalidateQueries({ queryKey: queryKeys.library("manga") });
    },
  });
}

/*
 * A details page refreshes an entry that was never fetched (`initialized` false) by itself, once per entry for the
 * app session: a site that fails doesn't get asked again on every visit (the page has a Refresh button).
 */
const autoRefreshed = new Set<string>();

/** True the first time it is asked for this entry in the session. */
export function claimAutoRefresh(kind: MediaKind, id: number): boolean {
  const key = `${kind}:${id}`;
  if (autoRefreshed.has(key)) return false;
  autoRefreshed.add(key);
  return true;
}

/** For tests. */
export function resetAutoRefresh() {
  autoRefreshed.clear();
}

// ---------------------------------------------------------------------------------------------------------------
// The library

export interface SetInLibraryRequest {
  kind: MediaKind;
  id: number;
  inLibrary: boolean;
}

/**
 * Adds an entry to the library or removes it. The entry as stored goes into the cache, every cached browse page of
 * that kind gets the new mark (patched in place, so the source isn't browsed again), and the library list and a
 * season's parent's season list are refetched.
 */
export function useSetInLibrary() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: SetInLibraryRequest) => setInLibrary(r.kind, r.id, r.inLibrary),
    onSuccess: (entry, r) => {
      queryClient.setQueryData(
        r.kind === "anime" ? queryKeys.anime(r.id) : queryKeys.manga(r.id),
        entry,
      );
      queryClient.setQueriesData<InfiniteData<SourcePage>>(
        {
          queryKey: ["api", "browse"],
          predicate: (q) => (q.queryKey[2] as BrowseKey | null)?.kind === r.kind,
        },
        (data) =>
          data && {
            ...data,
            pages: data.pages.map((page) => ({
              ...page,
              items: page.items.map((e) =>
                e.id === r.id ? { ...e, inLibrary: entry.inLibrary } : e,
              ),
            })),
          },
      );
      void queryClient.invalidateQueries({ queryKey: queryKeys.library(r.kind) });
      const parentId = r.kind === "anime" ? (entry as Anime).parentId : null;
      if (parentId) void queryClient.invalidateQueries({ queryKey: queryKeys.seasons(parentId) });
    },
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Watched marks (Phase 4 Stage 3)

export interface UpdateEpisodesRequest {
  ids: number[];
  patch: EpisodePatch;
}

/**
 * Marks episodes watched or unwatched (`updateEpisodes`; unwatched also clears the position, as in Aniyomi). The
 * episodes as stored replace their rows in the anime's cached list, so the list isn't fetched again.
 */
export function useUpdateEpisodes(animeId: MaybeRefOrGetter<number>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: UpdateEpisodesRequest) => updateEpisodes(r.ids, r.patch),
    onSuccess: (updated) => {
      const byId = new Map(updated.map((e) => [e.id, e]));
      queryClient.setQueryData<Episode[]>(
        queryKeys.episodes(toValue(animeId)),
        (list) => list && list.map((e) => byId.get(e.id) ?? e),
      );
    },
  });
}
