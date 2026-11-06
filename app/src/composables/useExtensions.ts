import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import { fetchExtensionStores, fetchExtensions, refreshRepos } from "../api/operations";
import type { MediaKind } from "../api/types";
import { queryKeys } from "./queryKeys";

/** The repos of both kinds. They change by user action and refreshes, both of which invalidate this. */
export function useExtensionStores() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.extensionStores(),
    queryFn: ({ signal }) => fetchExtensionStores(signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
}

/** Every listed extension of `kind` (installed or not), or of both kinds. */
export function useExtensions(kind?: MaybeRefOrGetter<MediaKind | undefined>) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.extensions(toValue(kind))),
    queryFn: ({ signal }) => fetchExtensions(toValue(kind), signal),
    enabled: computed(() => backend.ready),
    staleTime: 30_000,
  });
}

/**
 * Refreshes the repos (`fetchExtensions`). The per-repo results arrive as `extensionStoreRefreshed` too; this
 * invalidates the lists itself as well, so it works while the WebSocket is reconnecting.
 */
export function useRefreshRepos() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (kind?: MediaKind) => refreshRepos(kind),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.extensionStores() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.extensions() });
    },
  });
}
