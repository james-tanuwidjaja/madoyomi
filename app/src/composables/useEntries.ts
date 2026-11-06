import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useQuery } from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import { fetchAnime, fetchChapters, fetchEpisodes, fetchManga } from "../api/operations";
import { queryKeys } from "./queryKeys";

/*
 * Stored entries and their episodes/chapters (the database, not the source). A details refresh from the source,
 * which invalidates these, comes with the Details screen (Stage 5).
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
