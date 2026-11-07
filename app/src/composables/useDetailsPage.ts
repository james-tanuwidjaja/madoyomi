import { computed, watch, type Ref } from "vue";
import type { ApiError } from "../api/client";
import type { MediaKind } from "../api/types";
import { canBrowse, sourceAccess } from "../utils/sources";
import { claimAutoRefresh, useSetInLibrary } from "./useEntries";
import { useOpenInExtensions } from "./useOpenInExtensions";
import { useSources } from "./useSources";

/** What the page needs of `useRefreshAnime`/`useRefreshManga`. */
export interface RefreshMutation {
  mutate: (id: number) => void;
  reset: () => void;
  isPending: Ref<boolean>;
  data: Ref<{ error: ApiError | null } | undefined>;
  error: Ref<Error | null>;
  variables: Ref<number | undefined>;
}

/** What the page needs of the stored entry. */
interface EntryState {
  sourceId: string;
  initialized: boolean;
  inLibrary: boolean;
}

/**
 * The parts of a details page both kinds share (Phase 3 step 15):
 * - the entry's source, and whether it can be used. A missing, untrusted or unloaded source isn't asked for
 *   anything: the page says why and points to Extensions, and shows what is stored;
 * - the refresh from the source: by the button, and by itself the first time an entry that was never fetched
 *   (`initialized` false) is opened in the session (`claimAutoRefresh`). The source's error is kept with the data;
 * - adding to and removing from the library.
 */
export function useDetailsPage(opts: {
  kind: MediaKind;
  id: () => number;
  entry: Ref<EntryState | undefined>;
  refresh: RefreshMutation;
}) {
  const { kind, id, entry, refresh } = opts;
  const sources = useSources(kind);
  const source = computed(() => {
    const e = entry.value;
    return e ? (sources.data.value?.find((s) => s.id === e.sourceId) ?? null) : null;
  });
  const access = computed(() => (source.value ? sourceAccess(source.value) : null));
  const usable = computed(() => access.value !== null && canBrowse(access.value));
  /** The source list has loaded, so a missing source is really missing. */
  const sourceKnown = computed(() => sources.isSuccess.value);
  const sourceName = computed(
    () => source.value?.name ?? (sourceKnown.value ? "Source not installed" : `${kind} source`),
  );
  const sourceLink = computed(() =>
    source.value && usable.value ? `/browse/${source.value.id}` : null,
  );

  // A new entry in the same view (a season, the parent) starts with no refresh state.
  watch(id, () => refresh.reset());

  const forThisEntry = computed(() => refresh.variables.value === id());
  const refreshing = computed(() => refresh.isPending.value && forThisEntry.value);
  /** The source's error from the last refresh (with the data kept), or the refresh's own failure. */
  const refreshError = computed<unknown>(() => {
    if (!forThisEntry.value || refresh.isPending.value) return null;
    return refresh.data.value?.error ?? refresh.error.value ?? null;
  });

  function refreshNow() {
    if (usable.value && !refresh.isPending.value) refresh.mutate(id());
  }

  watch(
    [entry, usable],
    ([e, ok]) => {
      if (e && !e.initialized && ok && claimAutoRefresh(kind, id())) refreshNow();
    },
    { immediate: true },
  );

  const library = useSetInLibrary();
  const libraryPending = computed(
    () => library.isPending.value && library.variables.value?.id === id(),
  );
  const libraryError = computed(() =>
    library.variables.value?.id === id() ? library.error.value : null,
  );
  function toggleLibrary() {
    const e = entry.value;
    if (!e || library.isPending.value) return;
    library.mutate({ kind, id: id(), inLibrary: !e.inLibrary });
  }

  const openInExtensions = useOpenInExtensions();
  function openExtensions() {
    if (source.value) void openInExtensions(source.value.extension.pkgName);
  }

  return {
    source,
    access,
    usable,
    sourceKnown,
    sourceName,
    sourceLink,
    refreshing,
    refreshError,
    refreshNow,
    libraryPending,
    libraryError,
    toggleLibrary,
    openExtensions,
  };
}
