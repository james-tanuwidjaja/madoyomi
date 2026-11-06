import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useInfiniteQuery, useQuery } from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import { browseSource, fetchSourceFilters, fetchSources } from "../api/operations";
import type { BrowseType, FilterChange, MediaKind } from "../api/types";
import { queryKeys, type BrowseKey } from "./queryKeys";

/** The sources of `kind`'s installed extensions. They change with installs, which invalidate this. */
export function useSources(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.sources(toValue(kind))),
    queryFn: ({ signal }) => fetchSources(toValue(kind), signal),
    enabled: computed(() => backend.ready),
    staleTime: 5 * 60_000,
  });
}

/** A source's filter list. It only changes with the extension (an update invalidates `sources`, and this with it). */
export function useSourceFilters(
  kind: MaybeRefOrGetter<MediaKind>,
  sourceId: MaybeRefOrGetter<string | null>,
) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.sourceFilters(toValue(kind), toValue(sourceId) ?? "")),
    queryFn: ({ signal }) => fetchSourceFilters(toValue(kind), toValue(sourceId)!, signal),
    enabled: computed(() => backend.ready && !!toValue(sourceId)),
    staleTime: Infinity,
  });
}

export interface BrowseParams {
  kind: MediaKind;
  sourceId: string;
  type: BrowseType;
  query?: string;
  filters?: FilterChange[];
}

function browseKey(p: BrowseParams): BrowseKey {
  return {
    kind: p.kind,
    sourceId: p.sourceId,
    type: p.type,
    query: p.type === "SEARCH" ? (p.query ?? "") : null,
    filters: p.filters?.length ? p.filters : null,
  };
}

/**
 * A source's popular, latest or search results, page by page (Phase 3 step 8). Browsing is a mutation on the server
 * (it stores the entries), wrapped here in an infinite query: page N+1 is asked for while the last page says
 * `hasNextPage`. A change of kind, source, type, query or filters starts over at page 1.
 *
 * The request isn't aborted when the view goes away: the server has done the work by then, and the page stays
 * cached for a return visit (5 min).
 */
export function useSourceBrowse(params: MaybeRefOrGetter<BrowseParams | null>) {
  const backend = useBackendStore();
  const key = computed(() => {
    const p = toValue(params);
    return p ? browseKey(p) : null;
  });
  return useInfiniteQuery({
    queryKey: computed(() => queryKeys.browse(key.value)),
    queryFn: ({ queryKey, pageParam }) => {
      const k = queryKey[2] as BrowseKey;
      return browseSource({
        kind: k.kind,
        sourceId: k.sourceId,
        type: k.type,
        page: pageParam,
        query: k.query ?? undefined,
        filters: k.filters ?? undefined,
      });
    },
    initialPageParam: 1,
    getNextPageParam: (last, pages) => (last.hasNextPage ? pages.length + 1 : undefined),
    enabled: computed(() => backend.ready && key.value !== null),
    staleTime: 5 * 60_000,
    gcTime: 10 * 60_000,
  });
}
