<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useAssetUrl } from "../api/client";
import type { EntrySummary } from "../api/types";

/**
 * A browse or library card: the cover, the title and an "In library" mark, linking to the entry's details. The
 * cover is the backend's thumbnail route (`thumbnailUrl`, which applies the source's headers; Phase 3 step 21), with
 * `?token=` added by `useAssetUrl`, so it follows a backend restart. Without a cover, or when it fails, the card
 * shows the title's first letter.
 */
const props = defineProps<{ entry: EntrySummary }>();

const cover = useAssetUrl(() => props.entry.thumbnailUrl);
const failed = ref(false);
// a new URL (a backend restart's new port) gets another try
watch(cover, () => (failed.value = false));

const initial = computed(() => props.entry.title.trim().charAt(0).toUpperCase() || "?");
</script>

<template>
  <RouterLink
    :to="`/${entry.kind}/${entry.id}`"
    class="card"
    data-testid="media-card"
    :data-id="entry.id"
    :title="entry.title"
  >
    <span class="cover">
      <img
        v-if="cover && !failed"
        :src="cover"
        alt=""
        loading="lazy"
        decoding="async"
        @error="failed = true"
      />
      <span v-else class="cover-fallback" aria-hidden="true">{{ initial }}</span>
      <span v-if="entry.inLibrary" class="badge ok in-library" data-testid="in-library">
        In library
      </span>
    </span>
    <span class="title">{{ entry.title }}</span>
  </RouterLink>
</template>

<style scoped>
.card {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-1);
  border-radius: var(--radius-md);
  color: var(--color-text);
  text-decoration: none;
}

.card:hover {
  background: var(--color-surface-hover);
}

.cover {
  position: relative;
  display: block;
  overflow: hidden;
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

.in-library {
  position: absolute;
  top: var(--space-2);
  left: var(--space-2);
  background: var(--color-surface);
}

.title {
  display: -webkit-box;
  overflow: hidden;
  min-height: calc(2 * var(--line-height) * 1em);
  font-size: var(--font-size-body);
  line-height: var(--line-height);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
}
</style>
