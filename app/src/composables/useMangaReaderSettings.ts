import { computed, ref, toValue, type MaybeRefOrGetter } from "vue";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import { deleteMangaMeta, fetchMangaReaderMeta, setMangaMeta } from "../api/operations";
import type { Manga, MangaReaderMeta } from "../api/types";
import { useBackendStore } from "../stores/backend";
import { useReaderStore } from "../stores/reader";
import type { ReadingDirection } from "../utils/pagedNav";
import {
  MANGA_DIRECTION_KEY,
  MANGA_MODE_KEY,
  parseMangaMeta,
  resolveDirection,
  resolveReaderMode,
  type ReaderMode,
} from "../utils/readerSettings";
import { queryKeys } from "./queryKeys";

/*
 * A manga's reader settings (Phase 5 Stage 5): its own mode and direction from its meta (upstream's `setMangaMeta`,
 * tied to the database's manga id and kept in backups), resolved against its genres, its source's name and the
 * global settings on this device (utils/readerSettings.ts). Changes show at once and are written in order; a write
 * that fails reads the meta again and sets `error`.
 */

export function useMangaReaderSettings(
  mangaId: MaybeRefOrGetter<number>,
  manga: MaybeRefOrGetter<Manga | null | undefined>,
) {
  const backend = useBackendStore();
  const store = useReaderStore();
  const queryClient = useQueryClient();
  const id = () => toValue(mangaId);

  const query = useQuery({
    queryKey: computed(() => queryKeys.mangaReaderMeta(id())),
    queryFn: ({ signal }) => fetchMangaReaderMeta(id(), signal),
    enabled: computed(() => backend.ready && id() > 0),
    staleTime: 5 * 60_000,
  });

  const own = computed(() => parseMangaMeta(query.data.value?.meta ?? []));
  const resolved = computed(() =>
    resolveReaderMode({
      manga: own.value.mode,
      genres: toValue(manga)?.genre ?? [],
      sourceName: query.data.value?.sourceName ?? null,
      global: store.prefs.mode,
    }),
  );
  /** What the manga gets without a mode of its own (its genres, its source, the global default). */
  const fallbackMode = computed(
    () =>
      resolveReaderMode({
        manga: null,
        genres: toValue(manga)?.genre ?? [],
        sourceName: query.data.value?.sourceName ?? null,
        global: store.prefs.mode,
      }).mode,
  );
  const mode = computed(() => resolved.value.mode);
  const origin = computed(() => resolved.value.origin);
  const direction = computed(() => resolveDirection(own.value.direction, store.prefs.direction));
  /** The meta was read (or couldn't be: the defaults then), so the mode won't change under the reader. */
  const ready = computed(() => query.isSuccess.value || query.isError.value);
  const error = ref<unknown>(null);

  let chain: Promise<unknown> = Promise.resolve();

  /** Sets (a string) or removes (null) one key: in the cache at once, then on the backend, in order. */
  function write(key: string, value: string | null) {
    const mid = id();
    const qk = queryKeys.mangaReaderMeta(mid);
    queryClient.setQueryData<MangaReaderMeta>(qk, (old) => {
      const meta = (old?.meta ?? []).filter((m) => m.key !== key);
      if (value !== null) meta.push({ key, value });
      return { mangaId: mid, sourceName: old?.sourceName ?? null, meta };
    });
    error.value = null;
    chain = chain
      .then(() => (value === null ? deleteMangaMeta(mid, key) : setMangaMeta(mid, key, value)))
      .catch((e: unknown) => {
        error.value = e;
        void queryClient.invalidateQueries({ queryKey: qk });
      });
    return chain;
  }

  return {
    /** The mode the reader shows, and where it came from. */
    mode,
    origin,
    direction,
    fallbackMode,
    fallbackDirection: computed(() => store.prefs.direction),
    /** The manga's own values (null: it follows the defaults). */
    own,
    ready,
    error,
    setMode: (m: ReaderMode | null) => write(MANGA_MODE_KEY, m),
    setDirection: (d: ReadingDirection | null) => write(MANGA_DIRECTION_KEY, d),
    /** The manga follows the defaults again. */
    reset: () => Promise.all([write(MANGA_MODE_KEY, null), write(MANGA_DIRECTION_KEY, null)]),
  };
}
