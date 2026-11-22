import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  clearUpdateMarks,
  fetchLibraryCheck,
  fetchLibraryUpdateSettings,
  fetchUpdateMarks,
  fetchUpdates,
  setLibraryUpdateSettings,
  startLibraryUpdate,
  stopLibraryUpdate,
} from "../api/operations";
import type {
  LibraryCheck,
  LibraryUpdateSettings,
  MediaKind,
  UpdatesItem,
  UpdatesPage,
} from "../api/types";
import { useBackendStore } from "../stores/backend";
import { queryKeys } from "./queryKeys";

/*
 * The library update check: its status (fetched once, then kept current by the `libraryUpdates`
 * subscription in api/realtime.ts), start and stop, the settings (Settings → Updates), the Updates feed of a kind in
 * pages of `UPDATES_PAGE`, and the entries' "new" marks (the Library's cards, the sidebar's count), cleared when an
 * entry is opened.
 */

export const UPDATES_PAGE = 50;

export const DEFAULT_LIBRARY_UPDATE_SETTINGS: LibraryUpdateSettings = {
  intervalHours: 0,
  skipCompleted: true,
  skipUnseen: false,
  skipNotStarted: false,
  notify: false,
};

/** The check that runs, else the last one. */
export function useLibraryCheck() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.libraryCheck(),
    queryFn: ({ signal }) => fetchLibraryCheck(signal),
    enabled: computed(() => backend.ready),
    // the subscription keeps it current
    staleTime: Infinity,
  });
}

/** Starts a check of a kind's library (or one category of it); the status comes back at once. */
export function useStartLibraryUpdate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: { kind: MediaKind; categoryId?: number }) =>
      startLibraryUpdate(r.kind, r.categoryId),
    onSuccess: (check) => queryClient.setQueryData<LibraryCheck>(queryKeys.libraryCheck(), check),
  });
}

export function useStopLibraryUpdate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => stopLibraryUpdate(),
    onSuccess: (check) => queryClient.setQueryData<LibraryCheck>(queryKeys.libraryCheck(), check),
  });
}

export function useLibraryUpdateSettings() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.libraryUpdateSettings(),
    queryFn: ({ signal }) => fetchLibraryUpdateSettings(signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
}

/** Changes settings: shown at once, rolled back on an error; a new interval reschedules (the next check moves). */
export function useSetLibraryUpdateSettings() {
  const queryClient = useQueryClient();
  const key = queryKeys.libraryUpdateSettings();
  return useMutation({
    mutationFn: (patch: Partial<LibraryUpdateSettings>) => setLibraryUpdateSettings(patch),
    scope: { id: "libraryUpdateSettings" },
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<LibraryUpdateSettings>(key);
      queryClient.setQueryData<LibraryUpdateSettings>(key, {
        ...DEFAULT_LIBRARY_UPDATE_SETTINGS,
        ...previous,
        ...patch,
      });
      return { previous };
    },
    onError: (_e, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onSuccess: (settings, patch) => {
      queryClient.setQueryData(key, settings);
      if (patch.intervalHours !== undefined)
        void queryClient.invalidateQueries({ queryKey: queryKeys.libraryCheck() });
    },
  });
}

/** A kind's Updates, newest fetch first. */
export function useUpdates(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  const query = useInfiniteQuery({
    queryKey: computed(() => queryKeys.updates(toValue(kind))),
    queryFn: ({ pageParam, signal }) =>
      fetchUpdates(toValue(kind), pageParam, UPDATES_PAGE, signal),
    initialPageParam: 0,
    getNextPageParam: (last: UpdatesPage, pages: UpdatesPage[]) =>
      last.hasMore ? pages.reduce((n, p) => n + p.items.length, 0) : undefined,
    enabled: computed(() => backend.ready),
    staleTime: 10_000,
  });
  /** Every loaded item, each once (a page boundary that moved can repeat one). */
  const items = computed<UpdatesItem[]>(() => {
    const seen = new Set<number>();
    return (query.data.value?.pages ?? [])
      .flatMap((p) => p.items)
      .filter((i) => !seen.has(i.item.id) && (seen.add(i.item.id), true));
  });
  return { ...query, items };
}

/** The new item count of each marked library entry of a kind (entry id → count). */
export function useUpdateMarks(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  const query = useQuery({
    queryKey: computed(() => queryKeys.updateMarks(toValue(kind))),
    queryFn: ({ signal }) => fetchUpdateMarks(toValue(kind), signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
  const total = computed(() =>
    Object.values(query.data.value ?? {}).reduce((sum, n) => sum + n, 0),
  );
  return { ...query, total };
}

/** Clears marks (entries opened), or all of a kind; taken out of the cached marks at once. */
export function useClearUpdateMarks() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: { kind: MediaKind; ids?: number[] }) => clearUpdateMarks(r.kind, r.ids),
    onMutate: (r) => {
      const key = queryKeys.updateMarks(r.kind);
      const previous = queryClient.getQueryData<Record<number, number>>(key);
      queryClient.setQueryData<Record<number, number>>(key, (marks) => {
        if (!marks || !r.ids) return {};
        const gone = new Set(r.ids);
        return Object.fromEntries(Object.entries(marks).filter(([id]) => !gone.has(Number(id))));
      });
      return { previous };
    },
    onError: (_e, r, context) => {
      if (context?.previous)
        queryClient.setQueryData(queryKeys.updateMarks(r.kind), context.previous);
    },
  });
}

/**
 * Clears an entry's mark when it is opened (its details page, an item from Updates): only when the cached marks
 * have it, so opening an entry costs no request otherwise.
 */
export function useClearMarkOnOpen() {
  const queryClient = useQueryClient();
  const clear = useClearUpdateMarks();
  return (kind: MediaKind, id: number) => {
    const marks = queryClient.getQueryData<Record<number, number>>(queryKeys.updateMarks(kind));
    if (marks && marks[id]) clear.mutate({ kind, ids: [id] });
  };
}
