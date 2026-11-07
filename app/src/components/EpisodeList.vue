<script setup lang="ts">
import { computed } from "vue";
import type { Episode } from "../api/types";
import { fromEpisode } from "../utils/entryItems";
import EntryItemList from "./EntryItemList.vue";

/** An anime's episodes, sorted and filtered, virtualized (EntryItemList). Playing them comes in Phase 4. */
const props = defineProps<{ animeId: number; episodes: readonly Episode[] }>();

const items = computed(() => props.episodes.map(fromEpisode));
</script>

<template>
  <EntryItemList
    kind="anime"
    :entry-id="animeId"
    :items="items"
    :noun="{ one: 'episode', many: 'episodes' }"
    :done-labels="{ done: 'Watched', notDone: 'Unwatched' }"
    note="Playing episodes comes in Phase 4, and watched marks with Phase 6."
  />
</template>
