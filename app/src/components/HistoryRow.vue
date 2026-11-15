<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useAssetUrl } from "../api/client";
import type { HistoryItem } from "../api/types";
import { historyTime, resumeLabel, resumeRoute, targetProgress } from "../utils/history";

/**
 * One History row (Phase 6 Stage 2): the cover, the entry (to its details), its episode or chapter played or read
 * last with the time and how far it got, Resume (what `resume` names: the item, or the next one when it is finished)
 * and Remove (the entry's history; its marks and positions stay).
 */
const props = defineProps<{ item: HistoryItem; removing?: boolean }>();
const emit = defineEmits<{ remove: [] }>();

const cover = useAssetUrl(() => props.item.entry.thumbnailUrl);
const failed = ref(false);
watch(cover, () => (failed.value = false));

const progress = computed(() => targetProgress(props.item.kind, props.item.item));
</script>

<template>
  <li class="row" data-testid="history-row" :data-id="item.entry.id">
    <RouterLink
      :to="`/${item.kind}/${item.entry.id}`"
      class="cover"
      tabindex="-1"
      aria-hidden="true"
    >
      <img v-if="cover && !failed" :src="cover" alt="" loading="lazy" @error="failed = true" />
      <span v-else class="cover-fallback">{{
        item.entry.title.trim().charAt(0).toUpperCase() || "?"
      }}</span>
    </RouterLink>
    <div class="text">
      <RouterLink
        :to="`/${item.kind}/${item.entry.id}`"
        class="title"
        data-testid="history-title"
        >{{ item.entry.title }}</RouterLink
      >
      <span class="what" data-testid="history-item">{{ item.item.name }}</span>
      <span class="meta muted">
        <span data-testid="history-time">{{ historyTime(item) }}</span>
        <template v-if="progress">
          · <span data-testid="history-progress">{{ progress }}</span></template
        >
      </span>
    </div>
    <div class="actions">
      <RouterLink :to="resumeRoute(item)" class="btn btn-accent" data-action="history-resume">{{
        resumeLabel(item)
      }}</RouterLink>
      <button
        type="button"
        class="btn"
        data-action="remove-history"
        :disabled="removing"
        :aria-label="`Remove ${item.entry.title} from history`"
        title="Remove from history (the watched and read marks stay)"
        @click="emit('remove')"
      >
        Remove
      </button>
    </div>
  </li>
</template>

<style scoped>
.row {
  display: grid;
  grid-template-columns: 3rem 1fr auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2);
  border-radius: var(--radius-md);
}

.row:hover {
  background: var(--color-surface-hover);
}

.cover {
  display: block;
  overflow: hidden;
  aspect-ratio: 2 / 3;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-fallback {
  display: grid;
  height: 100%;
  place-items: center;
  color: var(--color-text-tertiary);
}

.text {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.title {
  overflow: hidden;
  color: var(--color-text);
  font-weight: var(--font-weight-semibold);
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title:hover {
  text-decoration: underline;
}

.what,
.meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.actions {
  display: flex;
  gap: var(--space-2);
}
</style>
