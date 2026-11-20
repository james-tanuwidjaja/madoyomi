import { computed, toValue, watch, type MaybeRefOrGetter } from "vue";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  changeDownloads,
  clearCompletedDownloads,
  deleteDownloads,
  enqueueDownloads,
  fetchCompletedDownloads,
  fetchDownloadQueue,
  fetchDownloadSettings,
  fetchDownloadsFolder,
  reorderDownload,
  setDownloadSettings,
  setDownloadsFolder,
  type DownloadAction,
} from "../api/operations";
import type {
  CompletedDownloadsPage,
  DownloadItem,
  DownloadQueue,
  DownloadSettings,
  DownloadsFolder,
  DownloadsFolderMode,
  MediaKind,
} from "../api/types";
import { useBackendStore } from "../stores/backend";
import { byItem } from "../utils/downloads";
import { queryKeys } from "./queryKeys";

/*
 * The download queue (Phase 6 Stages 4–5): the queue (fetched once, then kept current by the `downloadsChanged`
 * subscription in api/realtime.ts), the completed list of a kind in pages of `COMPLETED_PAGE`, the actions (queue,
 * pause, resume, retry, cancel, reorder, delete, clear), and Settings → Downloads. Every action writes the queue it
 * gets back into the cache, so the screen and the chapter rows change at once.
 */

export const COMPLETED_PAGE = 50;

export const DEFAULT_DOWNLOAD_SETTINGS: Omit<DownloadSettings, "path"> = {
  maxParallel: 3,
  perSource: 1,
  autoDownload: false,
  ffmpegPath: "",
  ytDlpPath: "",
};

export function useDownloadQueue() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.downloadQueue(),
    queryFn: ({ signal }) => fetchDownloadQueue(signal),
    enabled: computed(() => backend.ready),
    // the subscription keeps it current
    staleTime: Infinity,
  });
}

/** An entry's unfinished downloads by chapter (or episode) id, for its item list. */
export function useEntryDownloads(kind: MediaKind, entryId: MaybeRefOrGetter<number>) {
  const queue = useDownloadQueue();
  return computed(() => byItem(queue.data.value, kind, toValue(entryId)));
}

/** What changes when downloads finish or go: the entries' item lists (the downloaded marks), the library, the lists. */
function useAfterChange() {
  const queryClient = useQueryClient();
  return (kind: MediaKind, entryIds: Iterable<number>) => {
    for (const id of new Set(entryIds)) {
      void queryClient.invalidateQueries({
        queryKey: kind === "anime" ? queryKeys.episodes(id) : queryKeys.chapters(id),
      });
    }
    void queryClient.invalidateQueries({ queryKey: queryKeys.library(kind) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.completedDownloads(kind) });
  };
}

/** Queues chapters (or episodes) of one entry; already downloaded or queued ones are left as they are. */
export function useEnqueueDownloads() {
  const queryClient = useQueryClient();
  const after = useAfterChange();
  return useMutation({
    mutationFn: (r: { kind: MediaKind; entryId: number; itemIds: number[] }) =>
      enqueueDownloads(r.kind, r.itemIds),
    onSuccess: ({ queue }, r) => {
      queryClient.setQueryData<DownloadQueue>(queryKeys.downloadQueue(), queue);
      // chapters downloaded already were only marked as the user's
      after(r.kind, [r.entryId]);
    },
  });
}

/** Pause, resume, retry or cancel some downloads (ids), or all of a kind that can. */
export function useChangeDownloads() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: { action: DownloadAction; kind?: MediaKind; ids?: number[] }) =>
      changeDownloads(r.action, { kind: r.kind, ids: r.ids }),
    onSuccess: ({ queue }) =>
      queryClient.setQueryData<DownloadQueue>(queryKeys.downloadQueue(), queue),
  });
}

export function useReorderDownload() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: { id: number; to: number }) => reorderDownload(r.id, r.to),
    onSuccess: (queue) => queryClient.setQueryData<DownloadQueue>(queryKeys.downloadQueue(), queue),
  });
}

/** Deletes the downloads of chapters (files and queue items) of one entry. */
export function useDeleteDownloads() {
  const queryClient = useQueryClient();
  const after = useAfterChange();
  return useMutation({
    mutationFn: (r: { kind: MediaKind; entryIds: number[]; itemIds: number[] }) =>
      deleteDownloads(r.kind, r.itemIds),
    onSuccess: ({ queue }, r) => {
      queryClient.setQueryData<DownloadQueue>(queryKeys.downloadQueue(), queue);
      after(r.kind, r.entryIds);
    },
  });
}

export function useClearCompletedDownloads() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (kind: MediaKind) => clearCompletedDownloads(kind),
    onSuccess: (_n, kind) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.completedDownloads(kind) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.downloadQueue() });
    },
  });
}

/** A kind's completed downloads, the last finished first. */
export function useCompletedDownloads(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  const query = useInfiniteQuery({
    queryKey: computed(() => queryKeys.completedDownloads(toValue(kind))),
    queryFn: ({ pageParam, signal }) =>
      fetchCompletedDownloads(toValue(kind), pageParam, COMPLETED_PAGE, signal),
    initialPageParam: 0,
    getNextPageParam: (last: CompletedDownloadsPage, pages: CompletedDownloadsPage[]) =>
      last.hasMore ? pages.reduce((n, p) => n + p.items.length, 0) : undefined,
    enabled: computed(() => backend.ready),
    staleTime: 10_000,
  });
  const items = computed<DownloadItem[]>(() => {
    const seen = new Set<number>();
    return (query.data.value?.pages ?? [])
      .flatMap((p) => p.items)
      .filter((i) => !seen.has(i.id) && (seen.add(i.id), true));
  });
  return { ...query, items };
}

export function useDownloadSettings() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.downloadSettings(),
    queryFn: ({ signal }) => fetchDownloadSettings(signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
}

/** Changes settings: shown at once, rolled back on an error. */
export function useSetDownloadSettings() {
  const queryClient = useQueryClient();
  const key = queryKeys.downloadSettings();
  return useMutation({
    mutationFn: (patch: Partial<Omit<DownloadSettings, "path">>) => setDownloadSettings(patch),
    scope: { id: "downloadSettings" },
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<DownloadSettings>(key);
      if (previous) queryClient.setQueryData<DownloadSettings>(key, { ...previous, ...patch });
      return { previous };
    },
    onError: (_e, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onSuccess: (settings) => queryClient.setQueryData(key, settings),
  });
}

/** How often the downloads folder is asked again while its files move. */
export const FOLDER_MOVE_POLL_MS = 500;

/** How often a downloads folder that isn't reachable is asked again (the backend's queue looks every 5 s too). */
export const FOLDER_GONE_POLL_MS = 5_000;

/**
 * The downloads folder (Phase 7 Stage 3): fetched again every {@link FOLDER_MOVE_POLL_MS} while its files move, and every
 * {@link FOLDER_GONE_POLL_MS} while it isn't reachable (found live: the warning stayed after the drive was back). When a
 * change ends, everything the API serves is fetched again: the downloaded marks, the counts, the queue, the path.
 */
export function useDownloadsFolder() {
  const backend = useBackendStore();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: queryKeys.downloadsFolder(),
    queryFn: ({ signal }) => fetchDownloadsFolder(signal),
    enabled: computed(() => backend.ready),
    staleTime: 30_000,
    // while files move, and while the folder is out of reach (it is noticed when its drive is back, as the queue does)
    refetchInterval: (q) =>
      q.state.data?.change?.state === "MOVING"
        ? FOLDER_MOVE_POLL_MS
        : q.state.data?.reachable === false
          ? FOLDER_GONE_POLL_MS
          : false,
  });
  watch(
    () => query.data.value?.change,
    (change, before) => {
      if (before?.state === "MOVING" && change && change.state !== "MOVING") {
        void queryClient.invalidateQueries({ queryKey: ["api"] });
      }
    },
  );
  return query;
}

/** Changes the downloads folder; the change goes on in the backend, followed by {@link useDownloadsFolder}. */
export function useSetDownloadsFolder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (v: { path: string | null; mode: DownloadsFolderMode }) =>
      setDownloadsFolder(v.path, v.mode),
    onSuccess: (folder) => {
      queryClient.setQueryData<DownloadsFolder>(queryKeys.downloadsFolder(), folder);
      // a change that ended at once (nothing to move) refreshes now; a moving one when it ends
      if (folder.change?.state !== "MOVING")
        void queryClient.invalidateQueries({ queryKey: ["api"] });
    },
  });
}
