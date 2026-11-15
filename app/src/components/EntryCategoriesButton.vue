<script setup lang="ts">
import { computed, ref } from "vue";
import type { MediaKind } from "../api/types";
import { useCategories } from "../composables/useCategories";
import { useLibrary } from "../composables/useEntries";
import CategoryPicker from "./CategoryPicker.vue";

/*
 * A details page's "Categories" button (Phase 6 Stage 1), for an entry in the library: it names the categories the
 * entry is in and opens the picker for it. The entry's categories come from the library list (read once for the kind).
 */
const props = defineProps<{ kind: MediaKind; id: number }>();

const library = useLibrary(() => props.kind);
const categories = useCategories(() => props.kind);
const entry = computed(() => library.data.value?.find((e) => e.id === props.id) ?? null);
const names = computed(() => {
  const ids = entry.value?.categoryIds ?? [];
  return (categories.data.value ?? [])
    .filter((c) => !c.isDefaultCategory && ids.includes(c.id))
    .map((c) => c.name);
});
const label = computed(() =>
  names.value.length ? `Categories: ${names.value.join(", ")}` : "Categories",
);
const open = ref(false);
</script>

<template>
  <button
    type="button"
    class="btn"
    data-action="categories"
    :disabled="!entry"
    :title="names.length ? names.join(', ') : 'In Default (no category)'"
    @click="open = true"
  >
    <span class="label">{{ label }}</span>
  </button>
  <CategoryPicker v-if="entry" v-model:open="open" :kind="kind" :entries="[entry]" />
</template>

<style scoped>
.label {
  display: inline-block;
  max-width: 24ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
