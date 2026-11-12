import { computed } from "vue";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  clearPageCache,
  fetchPageCache,
  fetchReaderSettings,
  setReaderSettings,
} from "../api/operations";
import type { PageCache, ReaderSettings, ReaderSettingsPatch } from "../api/types";
import { useBackendStore } from "../stores/backend";
import { queryKeys } from "./queryKeys";

/*
 * The reader settings the backend applies (Phase 5 Stage 1, Settings → Reader): the chapter-page cache's cap. The
 * reader's own preferences (mode, direction, fit, …) are the app's, from Stage 2 on.
 */

/** As `ReaderSettings()` in Kotlin. */
export const DEFAULT_READER_SETTINGS: ReaderSettings = { pageCacheLimitMb: 1024 };

/** The caps Settings offers, in MiB (the backend takes 256 to 10240). */
export const PAGE_CACHE_LIMITS_MB = [256, 512, 1024, 2048, 5120, 10240] as const;

export function useReaderSettings() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.readerSettings(),
    queryFn: ({ signal }) => fetchReaderSettings(signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
}

/** What the page cache holds: read each time Settings opens (pages come and go while reading). */
export function usePageCache() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.pageCache(),
    queryFn: ({ signal }) => fetchPageCache(signal),
    enabled: computed(() => backend.ready),
    staleTime: 0,
  });
}

/**
 * Changes reader settings, shown at once and put back on a failure. A lower cap trims the cache on the backend
 * before it answers, so what the cache holds is read again after.
 */
export function useSetReaderSettings() {
  const queryClient = useQueryClient();
  const key = queryKeys.readerSettings();
  return useMutation({
    mutationFn: (patch: ReaderSettingsPatch) => setReaderSettings(patch),
    scope: { id: "readerSettings" },
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<ReaderSettings>(key);
      queryClient.setQueryData<ReaderSettings>(key, {
        ...DEFAULT_READER_SETTINGS,
        ...previous,
        ...patch,
      });
      return { previous };
    },
    onError: (_e, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onSuccess: (settings) => {
      queryClient.setQueryData(key, settings);
      void queryClient.invalidateQueries({ queryKey: queryKeys.pageCache() });
    },
  });
}

export function useClearPageCache() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => clearPageCache(),
    onSuccess: (cache: PageCache) => queryClient.setQueryData(queryKeys.pageCache(), cache),
  });
}

/** "312.4 MB", "1.2 GB": bytes in binary units with the usual names, as Explorer shows them. */
export function formatBytes(bytes: number | string): string {
  const n = typeof bytes === "string" ? Number(bytes) : bytes;
  if (!Number.isFinite(n) || n < 1024) return `${Math.max(0, Math.round(n || 0))} bytes`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

/** A cap in MiB as Settings names it: "256 MB", "1 GB", "10 GB". */
export function formatLimit(mb: number): string {
  return mb >= 1024 ? `${Math.round((mb / 1024) * 10) / 10} GB` : `${mb} MB`;
}
