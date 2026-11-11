<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { Episode } from "../api/types";
import { useUpdateEpisodes } from "../composables/useEntries";
import { usePlaybackStore } from "../stores/playback";
import { fromEpisode } from "../utils/entryItems";
import EntryItemList from "./EntryItemList.vue";
import ErrorNotice from "./ErrorNotice.vue";
import VideoPicker from "./VideoPicker.vue";

/**
 * An anime's episodes, sorted and filtered, virtualized (EntryItemList), each with Play, which opens the video
 * picker (VideoPicker: the episode's hosters and videos, the remembered choice, resume or start over).
 *
 * Each row shows how far it was watched, from the stored position, and live from the playback events while mpv
 * plays it (stores/playback.ts: no refetch per second). Rows can be selected for "Mark watched", "Mark unwatched"
 * (which also clears the position, as in Aniyomi) and, with one row, "Mark previous as watched" (every earlier
 * episode in the source's order), through `updateEpisodes`.
 */
defineOptions({ inheritAttrs: false });

const props = defineProps<{
  animeId: number;
  episodes: readonly Episode[];
  /** The anime's source: the picker remembers the last choice per source. */
  sourceId: string;
  sourceName: string;
}>();

const playback = usePlaybackStore();
const live = computed(() =>
  playback.session && playback.session.animeId === props.animeId ? playback.session : null,
);
const items = computed(() => props.episodes.map((e) => fromEpisode(e, live.value)));

const pickerOpen = ref(false);
const pickerEpisode = ref<Episode | null>(null);
function openPicker(id: number) {
  pickerEpisode.value = props.episodes.find((e) => e.id === id) ?? null;
  pickerOpen.value = pickerEpisode.value !== null;
}
// The picker reads the stored progress of the episode as it is now (a refetch after a session replaces the row).
watch(
  () => props.episodes,
  (list) => {
    const current = pickerEpisode.value;
    if (current) pickerEpisode.value = list.find((e) => e.id === current.id) ?? current;
  },
);

const selected = ref<number[]>([]);
const update = useUpdateEpisodes(() => props.animeId);

const selectedOne = computed(() =>
  selected.value.length === 1 ? props.episodes.find((e) => e.id === selected.value[0]) : undefined,
);
/** The episodes before the selected one in the source's order that aren't watched yet. */
const previousIds = computed(() => {
  const one = selectedOne.value;
  if (!one) return [];
  return props.episodes
    .filter((e) => e.sourceOrder < one.sourceOrder && !e.isWatched)
    .map((e) => e.id);
});

async function mark(ids: number[], isWatched: boolean) {
  if (ids.length === 0) return;
  try {
    await update.mutateAsync({ ids, patch: { isWatched } });
    selected.value = [];
  } catch {
    // shown above the rows, from update.error
  }
}
</script>

<template>
  <EntryItemList
    v-bind="$attrs"
    v-model:selected="selected"
    kind="anime"
    :entry-id="animeId"
    :items="items"
    :noun="{ one: 'episode', many: 'episodes' }"
    :done-labels="{ done: 'Watched', notDone: 'Unwatched' }"
    note="Play lets you choose the video, and resumes where you stopped."
    selectable
  >
    <template #notice>
      <ErrorNotice
        v-if="update.error.value"
        title="Couldn't change the episodes"
        :error="update.error.value"
        data-testid="mark-error"
      />
    </template>
    <template #bulk-actions="{ ids }">
      <button
        type="button"
        class="btn"
        data-action="mark-watched"
        :disabled="update.isPending.value"
        @click="mark(ids, true)"
      >
        Mark watched
      </button>
      <button
        type="button"
        class="btn"
        data-action="mark-unwatched"
        :disabled="update.isPending.value"
        @click="mark(ids, false)"
      >
        Mark unwatched
      </button>
      <button
        v-if="selectedOne"
        type="button"
        class="btn"
        data-action="mark-previous"
        :disabled="update.isPending.value || previousIds.length === 0"
        :title="previousIds.length === 0 ? 'Every earlier episode is watched' : undefined"
        @click="mark(previousIds, true)"
      >
        Mark previous as watched
      </button>
    </template>
    <template #actions="{ item }">
      <button
        type="button"
        class="btn"
        data-action="play"
        :aria-label="`Play ${item.name}`"
        @click="openPicker(item.id)"
      >
        Play
      </button>
    </template>
  </EntryItemList>
  <VideoPicker
    v-model:open="pickerOpen"
    :episode="pickerEpisode"
    :source-id="sourceId"
    :source-name="sourceName"
  />
</template>
