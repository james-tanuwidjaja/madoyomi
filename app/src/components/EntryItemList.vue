<script setup lang="ts">
import { computed } from "vue";
import type { MediaKind } from "../api/types";
import { useItemListStore } from "../stores/itemList";
import {
  activeFilterCount,
  filterItems,
  scanlators,
  sortItems,
  type ItemSortKey,
  type ListItem,
  type ProgressFilter,
} from "../utils/entryItems";
import { formatCount, formatDate } from "../utils/format";
import VirtualList from "./VirtualList.vue";

/**
 * An entry's episodes or chapters (Phase 3 step 15), behind EpisodeList and ChapterList: a sort (the source's order,
 * the number or the upload date, either way) and filters (watched/read, bookmarked, scanlator), over a VirtualList,
 * so a list of 1000+ rows keeps only what is in view in the DOM. The sort is kept per kind, the filters per entry
 * for the session (stores/itemList.ts). Rows don't act yet: playing and reading come in Phases 4 and 5.
 */
const props = defineProps<{
  kind: MediaKind;
  entryId: number;
  items: readonly ListItem[];
  /** "episode"/"episodes", "chapter"/"chapters". */
  noun: { one: string; many: string };
  /** "Watched"/"Unwatched", "Read"/"Unread". */
  doneLabels: { done: string; notDone: string };
  /** What the rows can't do yet. */
  note: string;
}>();

const store = useItemListStore();

const SORT_LABELS: Record<ItemSortKey, string> = {
  source: "Source order",
  number: "Number",
  date: "Upload date",
};

const sort = computed(() => store.sortOf(props.kind));
const filters = computed(() => store.filtersOf(props.kind, props.entryId));

const sortBy = computed({
  get: () => sort.value.by,
  set: (by: ItemSortKey) => store.setSort(props.kind, { ...sort.value, by }),
});
function toggleDirection() {
  store.setSort(props.kind, { ...sort.value, desc: !sort.value.desc });
}

const progress = computed({
  get: () => filters.value.progress,
  set: (p: ProgressFilter) =>
    store.setFilters(props.kind, props.entryId, { ...filters.value, progress: p }),
});
const bookmarked = computed({
  get: () => filters.value.bookmarked,
  set: (b: boolean) =>
    store.setFilters(props.kind, props.entryId, { ...filters.value, bookmarked: b }),
});
const scanlatorList = computed(() => scanlators(props.items));
const scanlator = computed({
  get: () =>
    filters.value.scanlator !== null && scanlatorList.value.includes(filters.value.scanlator)
      ? filters.value.scanlator
      : "",
  set: (s: string) =>
    store.setFilters(props.kind, props.entryId, { ...filters.value, scanlator: s || null }),
});
const effectiveFilters = computed(() => ({
  ...filters.value,
  scanlator: scanlator.value || null,
}));
const filterCount = computed(() => activeFilterCount(effectiveFilters.value));

function clearFilters() {
  store.setFilters(props.kind, props.entryId, {
    progress: "all",
    bookmarked: false,
    scanlator: null,
  });
}

const shown = computed(() =>
  sortItems(filterItems(props.items, effectiveFilters.value), sort.value),
);
const resetKey = computed(() =>
  JSON.stringify([props.entryId, sort.value, effectiveFilters.value]),
);

const countLine = computed(() => {
  const total = formatCount(props.items.length, props.noun.one, props.noun.many);
  return filterCount.value
    ? `${total} · ${shown.value.length.toLocaleString("en-US")} shown`
    : total;
});
</script>

<template>
  <section class="item-list" :aria-label="noun.many">
    <div class="toolbar">
      <label class="control">
        <span class="label">Sort</span>
        <select v-model="sortBy" class="field" data-testid="item-sort">
          <option v-for="(label, key) in SORT_LABELS" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <button
        type="button"
        class="btn"
        data-action="sort-direction"
        :aria-label="
          sort.desc ? 'Descending, switch to ascending' : 'Ascending, switch to descending'
        "
        @click="toggleDirection"
      >
        {{ sort.desc ? "↓ Descending" : "↑ Ascending" }}
      </button>
      <label class="control">
        <span class="label">Show</span>
        <select v-model="progress" class="field" data-testid="item-progress">
          <option value="all">All</option>
          <option value="notDone">{{ doneLabels.notDone }}</option>
          <option value="done">{{ doneLabels.done }}</option>
        </select>
      </label>
      <label class="check">
        <input v-model="bookmarked" type="checkbox" data-testid="item-bookmarked" />
        Bookmarked only
      </label>
      <label v-if="scanlatorList.length > 1" class="control">
        <span class="label">Scanlator</span>
        <select v-model="scanlator" class="field" data-testid="item-scanlator">
          <option value="">All</option>
          <option v-for="s in scanlatorList" :key="s" :value="s">{{ s }}</option>
        </select>
      </label>
      <button
        v-if="filterCount"
        type="button"
        class="btn"
        data-action="clear-item-filters"
        @click="clearFilters"
      >
        Clear filters
      </button>
    </div>
    <p class="muted meta">
      <span data-testid="item-count">{{ countLine }}</span>
      <span>{{ note }}</span>
    </p>

    <p v-if="items.length === 0" class="muted" data-testid="items-none">No {{ noun.many }} yet.</p>
    <p v-else-if="shown.length === 0" class="muted" data-testid="items-filtered-out">
      No {{ noun.many }} match the filters.
    </p>
    <VirtualList
      v-else
      class="rows"
      :items="shown"
      :item-key="(i) => i.id"
      :estimate-size="56"
      :reset-key="resetKey"
      :label="noun.many"
      data-testid="item-rows"
    >
      <template #default="{ item }">
        <div class="row" :class="{ done: item.done }" data-testid="item-row" :data-id="item.id">
          <div class="text">
            <span class="name">{{ item.name }}</span>
            <span class="sub">
              <span v-if="item.date">{{ formatDate(item.date) }}</span>
              <span v-if="item.scanlator">{{ item.scanlator }}</span>
            </span>
          </div>
          <span class="badges">
            <span v-if="item.filler" class="badge warn">Filler</span>
            <span v-if="item.bookmarked" class="badge" data-testid="bookmarked">Bookmarked</span>
            <span v-if="item.done" class="badge ok">{{ doneLabels.done }}</span>
          </span>
        </div>
      </template>
    </VirtualList>
  </section>
</template>

<style scoped>
.item-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
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

.check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-4);
  margin: 0;
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}

.rows {
  flex: 1;
  min-height: 0;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  min-height: 48px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.row.done .name {
  color: var(--color-text-tertiary);
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  display: flex;
  gap: var(--space-3);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}

.badges {
  display: flex;
  flex: none;
  gap: var(--space-1);
}
</style>
