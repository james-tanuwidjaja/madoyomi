import { computed, reactive, toValue, type MaybeRefOrGetter } from "vue";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import { fetchSourcePreferences, updateSourcePreference } from "../api/operations";
import type { MediaKind, Preference, SourcePreferenceChange } from "../api/types";
import { changeTaken, withChange } from "../utils/preferences";
import { queryKeys, type BrowseKey } from "./queryKeys";
import { markSourceChanged } from "./useSources";

/** A source's preferences, in the source's order. Not cached as fresh: any change can change the whole screen. */
export function useSourcePreferences(
  kind: MaybeRefOrGetter<MediaKind>,
  sourceId: MaybeRefOrGetter<string | null>,
) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.sourcePreferences(toValue(kind), toValue(sourceId) ?? "")),
    queryFn: ({ signal }) => fetchSourcePreferences(toValue(kind), toValue(sourceId)!, signal),
    enabled: computed(() => backend.ready && !!toValue(sourceId)),
    staleTime: 0,
  });
}

/** What became of the last change of one preference, shown on its row. */
export type PreferenceStatus =
  | { state: "saving" }
  | { state: "error"; error: unknown }
  /** The extension's change listener refused the value: the row shows the value it kept. */
  | { state: "refused" };

interface ChangeRequest {
  kind: MediaKind;
  sourceId: string;
  change: SourcePreferenceChange;
}

const MUTATION_KEY = ["sourcePreference"] as const;

/**
 * Changes one preference of a source (Phase 3 step 19), with an optimistic update (Phase 3 §9 "Stage 6"):
 * - the new value shows at once (written into the cached list);
 * - changes run one at a time, in order (one mutation scope), so the server sees them as they were made;
 * - when the last pending change of the source answers, the server's list replaces the cached one. Its value at the
 *   position may differ from the one sent: the extension's listener refused it, which the status says;
 * - a failed change puts the preference back as it was and reads the list again;
 * - a change drops the source's cached browse pages and filters: the server makes a new instance of the source,
 *   which reads the new settings. The server stores that instance's `baseUrl` (a mirror setting changes it) when it
 *   loads, at the next browse, so the source list is read again after that browse (`markSourceChanged`).
 *
 * `status` holds the outcome of the last change per position, for this page.
 */
export function useUpdateSourcePreference(
  kind: MaybeRefOrGetter<MediaKind>,
  sourceId: MaybeRefOrGetter<string>,
) {
  const queryClient = useQueryClient();
  const status = reactive<Record<number, PreferenceStatus>>({});

  const othersPending = (r: ChangeRequest) =>
    queryClient.isMutating({
      mutationKey: MUTATION_KEY,
      predicate: (m) => {
        const v = m.state.variables as ChangeRequest | undefined;
        return v?.kind === r.kind && v.sourceId === r.sourceId;
      },
    }) > 1;

  const mutation = useMutation({
    mutationKey: MUTATION_KEY,
    scope: { id: "sourcePreferences" },
    mutationFn: (r: ChangeRequest) => updateSourcePreference(r.kind, r.sourceId, r.change),
    onMutate: async (r) => {
      const key = queryKeys.sourcePreferences(r.kind, r.sourceId);
      await queryClient.cancelQueries({ queryKey: key });
      const before = queryClient.getQueryData<Preference[]>(key)?.[r.change.position];
      queryClient.setQueryData<Preference[]>(key, (list) =>
        list?.map((p, i) => (i === r.change.position ? withChange(p, r.change) : p)),
      );
      return { before };
    },
    onError: (_e, r, context) => {
      const key = queryKeys.sourcePreferences(r.kind, r.sourceId);
      const before = context?.before;
      if (before) {
        queryClient.setQueryData<Preference[]>(key, (list) =>
          list?.map((p, i) => (i === r.change.position ? before : p)),
        );
      }
      if (!othersPending(r)) void queryClient.invalidateQueries({ queryKey: key });
    },
    onSuccess: (result, r) => {
      if (!othersPending(r)) {
        queryClient.setQueryData(
          queryKeys.sourcePreferences(r.kind, r.sourceId),
          result.preferences,
        );
      }
      // the source's row (its baseUrl) changes only when the new instance loads: at its next browse
      markSourceChanged(r.kind, r.sourceId);
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sourceFilters(r.kind, r.sourceId),
      });
      queryClient.removeQueries({
        queryKey: ["api", "browse"],
        predicate: (q) => {
          const k = q.queryKey[2] as BrowseKey | null;
          return k?.kind === r.kind && k.sourceId === r.sourceId;
        },
      });
    },
  });

  // the last change sent per position: an older change's answer doesn't overwrite a newer one's status
  const latest = new Map<number, SourcePreferenceChange>();

  /** Sends `c`; its outcome lands in `status[c.position]`. Never throws. */
  async function change(c: SourcePreferenceChange) {
    latest.set(c.position, c);
    status[c.position] = { state: "saving" };
    try {
      const result = await mutation.mutateAsync({
        kind: toValue(kind),
        sourceId: toValue(sourceId),
        change: c,
      });
      if (latest.get(c.position) !== c) return;
      const after = result.preferences[c.position];
      if (after && !changeTaken(after, c)) status[c.position] = { state: "refused" };
      else delete status[c.position];
    } catch (error) {
      if (latest.get(c.position) === c) status[c.position] = { state: "error", error };
    }
  }

  return { status, change };
}
