<script setup lang="ts">
import { computed } from "vue";
import type { Episode } from "../api/types";
import { usePlayEpisode } from "../composables/usePlayback";
import { fromEpisode } from "../utils/entryItems";
import EntryItemList from "./EntryItemList.vue";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * An anime's episodes, sorted and filtered, virtualized (EntryItemList), each with Play: the episode's first video
 * in mpv's own window (usePlayEpisode). A failed Play shows its error above the rows.
 */
const props = defineProps<{ animeId: number; episodes: readonly Episode[] }>();

const items = computed(() => props.episodes.map(fromEpisode));

const play = usePlayEpisode();
const startingId = computed(() => (play.isPending.value ? play.variables.value : undefined));
</script>

<template>
  <EntryItemList
    kind="anime"
    :entry-id="animeId"
    :items="items"
    :noun="{ one: 'episode', many: 'episodes' }"
    :done-labels="{ done: 'Watched', notDone: 'Unwatched' }"
    note="Play opens the episode's first video in mpv. Choosing the video, resuming and watched marks come next."
  >
    <template #notice>
      <ErrorNotice
        v-if="play.error.value"
        title="Couldn't play the episode"
        :error="play.error.value"
        data-testid="play-error"
      />
    </template>
    <template #actions="{ item }">
      <button
        type="button"
        class="btn"
        data-action="play"
        :disabled="startingId === item.id"
        :aria-label="`Play ${item.name}`"
        @click="play.mutate(item.id)"
      >
        {{ startingId === item.id ? "Starting…" : "Play" }}
      </button>
    </template>
  </EntryItemList>
</template>
