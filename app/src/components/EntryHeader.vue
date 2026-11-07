<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useAssetUrl } from "../api/client";
import type { EntryStatus } from "../api/types";
import { formatStatus } from "../utils/format";

/**
 * The top of a details page (Phase 3 step 15): the cover (the backend's thumbnail route through `useAssetUrl`, so it
 * follows a backend restart; the title's first letter without one), the title, author and artist, status, genres,
 * the source and the description, which is clamped to a few lines with "More". The page's buttons go in `actions`,
 * and anything about the entry's place (a season's parent) in `context`.
 */
const props = defineProps<{
  entry: {
    title: string;
    thumbnailUrl: string | null;
    author: string | null;
    artist: string | null;
    status: EntryStatus;
    genre: string[];
    description: string | null;
  };
  /** Links to the source page; null while the source isn't known (not installed). */
  sourceLink: string | null;
  sourceName: string;
}>();

defineSlots<{ actions(): unknown; context(): unknown }>();

const cover = useAssetUrl(() => props.entry.thumbnailUrl);
const failed = ref(false);
watch(cover, () => (failed.value = false));
const initial = computed(() => props.entry.title.trim().charAt(0).toUpperCase() || "?");

const people = computed(() => {
  const { author, artist } = props.entry;
  if (author && artist && author !== artist) return `${author} · ${artist}`;
  return author || artist || "";
});

const description = computed(() => props.entry.description?.trim() ?? "");
const expanded = ref(false);
/** A rough "longer than the clamp" check: the clamp itself is CSS, which can't be measured in a test. */
const long = computed(
  () => description.value.length > 320 || description.value.split("\n").length > 4,
);
</script>

<template>
  <header class="entry-header">
    <span class="cover">
      <img
        v-if="cover && !failed"
        :src="cover"
        alt=""
        decoding="async"
        data-testid="entry-cover"
        @error="failed = true"
      />
      <span v-else class="cover-fallback" aria-hidden="true">{{ initial }}</span>
    </span>
    <div class="info">
      <h1 data-testid="entry-title">{{ entry.title }}</h1>
      <p v-if="people" class="people" data-testid="entry-people">{{ people }}</p>
      <p class="facts">
        <span data-testid="entry-status">{{ formatStatus(entry.status) }}</span>
        <RouterLink v-if="sourceLink" :to="sourceLink" data-testid="entry-source">{{
          sourceName
        }}</RouterLink>
        <span v-else data-testid="entry-source">{{ sourceName }}</span>
      </p>
      <slot name="context" />
      <ul v-if="entry.genre.length" class="genres" aria-label="Genres">
        <li v-for="g in entry.genre" :key="g" class="badge">{{ g }}</li>
      </ul>
      <div class="actions"><slot name="actions" /></div>
      <div v-if="description" class="description-block">
        <p
          class="description"
          :class="{ clamped: long && !expanded }"
          data-testid="entry-description"
        >
          {{ description }}
        </p>
        <button
          v-if="long"
          type="button"
          class="btn btn-small"
          data-action="toggle-description"
          @click="expanded = !expanded"
        >
          {{ expanded ? "Less" : "More" }}
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.entry-header {
  display: flex;
  gap: var(--space-5);
  align-items: flex-start;
}

.cover {
  position: relative;
  display: block;
  flex: none;
  overflow: hidden;
  width: 160px;
  aspect-ratio: 2 / 3;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
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
  font-family: var(--font-family-display);
  font-size: var(--font-size-title);
}

.info {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

h1 {
  margin: 0;
  font-family: var(--font-family-display);
  font-size: var(--font-size-title);
  font-weight: var(--font-weight-semibold);
  overflow-wrap: anywhere;
}

.people,
.facts {
  margin: 0;
  color: var(--color-text-secondary);
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-4);
}

.genres {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.description-block {
  display: grid;
  justify-items: start;
  gap: var(--space-1);
}

.description {
  margin: 0;
  max-width: 80ch;
  white-space: pre-line;
}

.description.clamped {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}
</style>
