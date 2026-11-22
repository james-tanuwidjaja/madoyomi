<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useAssetUrl } from "../api/client";
import type { UpdatesItem } from "../api/types";
import { historyTime, targetProgress } from "../utils/history";
import { updatesRoute } from "../utils/updates";

/**
 * One Updates row: the cover, the entry (to its details), the new episode or chapter with when it
 * was fetched and how far it got, and Play or Read, which opens it (the episode's picker on its details page, the
 * chapter in the reader). One already watched or read is dimmed. Opening emits `open`, so the entry's mark goes.
 */
const props = defineProps<{ item: UpdatesItem }>();
const emit = defineEmits<{ open: [] }>();

const cover = useAssetUrl(() => props.item.entry.thumbnailUrl);
const failed = ref(false);
watch(cover, () => (failed.value = false));

const progress = computed(() => targetProgress(props.item.kind, props.item.item));
const details = computed(() => `/${props.item.kind}/${props.item.entry.id}`);
</script>

<template>
  <li
    class="row"
    :class="{ done: item.item.done }"
    data-testid="updates-row"
    :data-id="item.item.id"
  >
    <RouterLink :to="details" class="cover" tabindex="-1" aria-hidden="true" @click="emit('open')">
      <img v-if="cover && !failed" :src="cover" alt="" loading="lazy" @error="failed = true" />
      <span v-else class="cover-fallback">{{
        item.entry.title.trim().charAt(0).toUpperCase() || "?"
      }}</span>
    </RouterLink>
    <div class="text">
      <RouterLink :to="details" class="title" data-testid="updates-title" @click="emit('open')">{{
        item.entry.title
      }}</RouterLink>
      <span class="what" data-testid="updates-item">{{ item.item.name }}</span>
      <span class="meta muted">
        <span data-testid="updates-time">{{ historyTime(item) }}</span>
        <template v-if="progress">
          · <span data-testid="updates-progress">{{ progress }}</span></template
        >
      </span>
    </div>
    <div class="actions">
      <RouterLink
        :to="updatesRoute(item)"
        class="btn"
        :class="{ 'btn-accent': !item.item.done }"
        data-action="updates-open"
        @click="emit('open')"
        >{{ item.kind === "anime" ? "Play" : "Read" }}</RouterLink
      >
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

.row.done .text {
  opacity: 0.6;
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
