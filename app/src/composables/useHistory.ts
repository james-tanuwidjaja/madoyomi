import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { clearHistory, deleteHistory, fetchHistory } from "../api/operations";
import type { HistoryItem, HistoryPage, MediaKind } from "../api/types";
import { useBackendStore } from "../stores/backend";
import { queryKeys } from "./queryKeys";
import { invalidateProgress } from "./useEntries";

/*
 * History: a kind's history in pages of `HISTORY_PAGE` (one item per entry, newest first), and
 * delete one entry or clear all. A change takes the entries out of the cached pages at once, then the history and the
 * library (its "last read" sort) are fetched again.
 */

export const HISTORY_PAGE = 50;

export function useHistory(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  const query = useInfiniteQuery({
    queryKey: computed(() => queryKeys.history(toValue(kind))),
    queryFn: ({ pageParam, signal }) =>
      fetchHistory(toValue(kind), pageParam, HISTORY_PAGE, signal),
    initialPageParam: 0,
    getNextPageParam: (last: HistoryPage, pages: HistoryPage[]) =>
      last.hasMore ? pages.reduce((n, p) => n + p.items.length, 0) : undefined,
    enabled: computed(() => backend.ready),
    staleTime: 10_000,
  });
  /** Every loaded item, each entry once (a page boundary that moved can repeat one). */
  const items = computed<HistoryItem[]>(() => {
    const seen = new Set<number>();
    return (query.data.value?.pages ?? [])
      .flatMap((p) => p.items)
      .filter((i) => !seen.has(i.entry.id) && (seen.add(i.entry.id), true));
  });
  return { ...query, items };
}

type HistoryData = { pages: HistoryPage[]; pageParams: number[] };

function dropFromCache(
  queryClient: ReturnType<typeof useQueryClient>,
  kind: MediaKind,
  keep: (item: HistoryItem) => boolean,
) {
  queryClient.setQueryData<HistoryData>(
    queryKeys.history(kind),
    (data) =>
      data && { ...data, pages: data.pages.map((p) => ({ ...p, items: p.items.filter(keep) })) },
  );
}

/** Removes entries from their kind's history (their watched/read marks and positions stay). */
export function useDeleteHistory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: { kind: MediaKind; ids: number[] }) => deleteHistory(r.kind, r.ids),
    onSuccess: (_n, r) => {
      const gone = new Set(r.ids);
      dropFromCache(queryClient, r.kind, (i) => !gone.has(i.entry.id));
      invalidateProgress(queryClient, r.kind);
    },
  });
}

/** Clears a kind's history. */
export function useClearHistory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (kind: MediaKind) => clearHistory(kind),
    onSuccess: (_n, kind) => {
      dropFromCache(queryClient, kind, () => false);
      invalidateProgress(queryClient, kind);
    },
  });
}
