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
/**
 * `hideLibraryBadge`: for the Library itself, where every card is in it. `selectable` (the Library's selection, Phase 6):
 * the card is a toggle button instead of a link, `selected` marks it, and a click emits `select` with the event (for
 * Shift+click ranges). `count` (Phase 6 Stage 2): the unwatched/unread badge, shown above 0.
 */
const props = defineProps<{
  entry: EntrySummary;
  hideLibraryBadge?: boolean;
  selectable?: boolean;
  selected?: boolean;
  count?: number;
}>();
const emit = defineEmits<{ select: [event: MouseEvent] }>();

const cover = useAssetUrl(() => props.entry.thumbnailUrl);
const failed = ref(false);
// a new URL (a backend restart's new port) gets another try
watch(cover, () => (failed.value = false));

const initial = computed(() => props.entry.title.trim().charAt(0).toUpperCase() || "?");
const countLabel = computed(() => {
  const n = props.count ?? 0;
  const what = props.entry.kind === "anime" ? "unwatched" : "unread";
  return `${n.toLocaleString("en-US")} ${what}`;
});
</script>

<template>
  <component
    :is="selectable ? 'button' : RouterLink"
    v-bind="
      selectable
        ? { type: 'button', 'aria-pressed': !!selected }
        : { to: `/${entry.kind}/${entry.id}` }
    "
    class="card"
    :class="{ selectable, selected }"
    data-testid="media-card"
    :data-id="entry.id"
    :title="entry.title"
    @click="selectable && emit('select', $event)"
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
      <span
        v-if="entry.inLibrary && !hideLibraryBadge"
        class="badge ok in-library"
        data-testid="in-library"
      >
        In library
      </span>
      <span
        v-if="(count ?? 0) > 0"
        class="count"
        data-testid="unread-count"
        :title="countLabel"
        :aria-label="countLabel"
        >{{ count!.toLocaleString("en-US") }}</span
      >
      <span v-if="selectable" class="check" :class="{ on: selected }" aria-hidden="true">{{
        selected ? "✓" : ""
      }}</span>
    </span>
    <span class="title">{{ entry.title }}</span>
  </component>
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

button.card {
  width: 100%;
  border: 0;
  background: none;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

button.card:hover {
  background: var(--color-surface-hover);
}

.card.selected .cover {
  outline: 3px solid var(--color-accent);
  outline-offset: -3px;
}

.check {
  position: absolute;
  top: var(--space-2);
  right: var(--space-2);
  display: grid;
  width: 1.5rem;
  height: 1.5rem;
  place-items: center;
  border: 2px solid var(--color-border-strong);
  border-radius: 50%;
  background: var(--color-surface);
  font-weight: var(--font-weight-semibold);
}

.check.on {
  border-color: var(--color-accent);
  background: var(--color-accent);
  color: var(--color-on-accent);
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

.count {
  position: absolute;
  top: var(--space-2);
  left: var(--space-2);
  min-width: 1.5rem;
  padding: 0 var(--space-1);
  border-radius: var(--radius-sm);
  background: var(--color-accent);
  color: var(--color-on-accent);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-semibold);
  line-height: 1.5rem;
  text-align: center;
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
