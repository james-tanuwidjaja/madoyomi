<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { useLibrary } from "../composables/useEntries";
import { useBackendStore } from "../stores/backend";
import { sortLibrary, useLibrarySortStore, type LibrarySortKey } from "../stores/librarySort";
import { useMediaKindStore } from "../stores/mediaKind";
import { formatCount } from "../utils/format";
import ErrorNotice from "../components/ErrorNotice.vue";
import MediaCard from "../components/MediaCard.vue";
import PageLayout from "../components/PageLayout.vue";
import VirtualGrid from "../components/VirtualGrid.vue";

/*
 * The Library (Phase 3 step 16): the current kind's saved entries as a grid of covers (virtualized above 60), sorted by
 * title or by when they were added, either way (kept per kind). An empty library links to Browse.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const sorts = useLibrarySortStore();

const kind = computed(() => mediaKind.kind);
const library = useLibrary(kind);

const sort = computed(() => sorts.sorts[kind.value]);
const sortBy = computed({
  get: () => sort.value.by,
  // a new key starts in its natural direction: titles A to Z, additions newest first
  set: (by: LibrarySortKey) => sorts.set(kind.value, { by, desc: by === "added" }),
});
function toggleDirection() {
  sorts.set(kind.value, { ...sort.value, desc: !sort.value.desc });
}

const entries = computed(() => sortLibrary(library.data.value ?? [], sort.value));
const subtitle = computed(() =>
  library.data.value
    ? formatCount(entries.value.length, `saved ${kind.value}`, `saved ${kind.value}`)
    : undefined,
);
const resetKey = computed(() => JSON.stringify([kind.value, sort.value]));
const directionLabel = computed(() =>
  sort.value.by === "title"
    ? sort.value.desc
      ? "Z to A"
      : "A to Z"
    : sort.value.desc
      ? "Newest first"
      : "Oldest first",
);
</script>

<template>
  <PageLayout title="Library" :subtitle="subtitle" fill>
    <ErrorNotice
      v-if="library.isError.value"
      :title="`Couldn't load your ${kind} library`"
      :error="library.error.value"
      @retry="library.refetch()"
    />
    <p v-else-if="library.isPending.value" class="muted">
      {{ backend.ready ? "Loading…" : "Waiting for the backend…" }}
    </p>
    <div v-else-if="entries.length === 0" class="empty" data-testid="library-empty">
      <p>Your {{ kind }} library is empty.</p>
      <p class="muted">Find something in Browse, open it and choose "Add to library".</p>
      <RouterLink to="/browse" class="btn btn-accent" data-action="browse"
        >Browse {{ kind }} sources</RouterLink
      >
    </div>
    <div v-else class="body">
      <div class="toolbar">
        <label class="control">
          <span class="label">Sort</span>
          <select v-model="sortBy" class="field" data-testid="library-sort">
            <option value="added">Date added</option>
            <option value="title">Title</option>
          </select>
        </label>
        <button type="button" class="btn" data-action="library-direction" @click="toggleDirection">
          {{ directionLabel }}
        </button>
      </div>
      <VirtualGrid
        class="grid"
        :items="entries"
        :item-key="(e) => e.id"
        :reset-key="resetKey"
        :label="`Your ${kind} library`"
        data-testid="library-grid"
      >
        <template #default="{ item }">
          <MediaCard :entry="item" hide-library-badge />
        </template>
      </VirtualGrid>
    </div>
  </PageLayout>
</template>

<style scoped>
.body {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-height: 0;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
}

.control {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.label {
  color: var(--color-text-secondary);
}

.grid {
  flex: 1;
  min-height: 0;
}

.empty {
  display: grid;
  justify-items: start;
  align-content: start;
  gap: var(--space-2);
}

.empty p,
.muted {
  margin: 0;
}

.muted {
  color: var(--color-text-tertiary);
}
</style>
