<script setup lang="ts">
import { computed } from "vue";
import type { Chapter } from "../api/types";
import { fromChapter } from "../utils/entryItems";
import EntryItemList from "./EntryItemList.vue";

/** A manga's chapters, sorted and filtered, virtualized (EntryItemList). Reading them comes in Phase 5. */
const props = defineProps<{ mangaId: number; chapters: readonly Chapter[] }>();

const items = computed(() => props.chapters.map(fromChapter));
</script>

<template>
  <EntryItemList
    kind="manga"
    :entry-id="mangaId"
    :items="items"
    :noun="{ one: 'chapter', many: 'chapters' }"
    :done-labels="{ done: 'Read', notDone: 'Unread' }"
    note="Reading chapters comes in Phase 5, and read marks with Phase 6."
  />
</template>
