<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { MediaKind } from "../api/types";
import { useItemListStore } from "../stores/itemList";
import {
  activeFilterCount,
  chapterProgress,
  filterItems,
  scanlators,
  sortItems,
  type ItemSortKey,
  type ListItem,
  type ProgressFilter,
} from "../utils/entryItems";
import { formatCount, formatDate } from "../utils/format";
import { progressShare, progressText } from "../utils/progress";
import VirtualList from "./VirtualList.vue";

/**
 * An entry's episodes or chapters (Phase 3 step 15), behind EpisodeList and ChapterList: a sort (the source's order,
 * the number or the upload date, either way) and filters (watched/read, bookmarked, scanlator), over a VirtualList,
 * so a list of 1000+ rows keeps only what is in view in the DOM. The sort is kept per kind, the filters per entry
 * for the session (stores/itemList.ts). A row's actions come from the `actions` slot (EpisodeList: Play), and a
 * notice above the rows (an action's error) from the `notice` slot.
 *
 * With `selectable` (EpisodeList, Phase 4 Stage 3) each row has a checkbox: Shift+click selects a range of the rows
 * as shown, and a bar above the rows shows the count, the `bulk-actions` slot (with the selected ids), "Select all
 * shown" and Clear. The selection (`v-model:selected`, ids) is cleared when the entry changes. A row with
 * `progress` shows how far it was watched (a bar and "mm:ss left"), and `playing` marks the one mpv plays.
 *
 * Phase 5 Stage 2: a chapter row with `pages` shows how far it was read ("Page 13 of 92" and a bar). With `openable`
 * (ChapterList), a click on a row (not on its checkbox or buttons) emits `open` with its id: it opens the reader.
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
  /** Rows can be selected (a checkbox each) for the `bulk-actions` slot. */
  selectable?: boolean;
  /** A click on a row emits `open` (ChapterList: read it). */
  openable?: boolean;
}>();
const emit = defineEmits<{ open: [id: number] }>();

function onRowClick(event: MouseEvent, id: number) {
  if (!props.openable) return;
  const target = event.target as Element | null;
  if (target?.closest("button, input, a, select, label")) return;
  emit("open", id);
}
const selected = defineModel<number[]>("selected", { default: () => [] });

defineSlots<{
  notice(): unknown;
  actions(props: { item: ListItem }): unknown;
  "bulk-actions"(props: { ids: number[] }): unknown;
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

// ---------------------------------------------------------------------------------------------------------------
// Selection

const selectedSet = computed(() => new Set(selected.value));
/** The row a Shift+click extends from: the last one clicked. */
const anchor = ref<number | null>(null);

watch(
  () => props.entryId,
  () => {
    selected.value = [];
    anchor.value = null;
  },
);

function onSelect(event: MouseEvent, id: number) {
  const next = new Set(selectedSet.value);
  const on = !next.has(id);
  const ids = shown.value.map((i) => i.id);
  const from = anchor.value === null ? -1 : ids.indexOf(anchor.value);
  const to = ids.indexOf(id);
  if (event.shiftKey && from >= 0 && to >= 0) {
    for (const i of ids.slice(Math.min(from, to), Math.max(from, to) + 1)) {
      if (on) next.add(i);
      else next.delete(i);
    }
  } else if (on) next.add(id);
  else next.delete(id);
  anchor.value = id;
  selected.value = [...next];
}

function selectAllShown() {
  selected.value = [...new Set([...selected.value, ...shown.value.map((i) => i.id)])];
}

function clearSelection() {
  selected.value = [];
  anchor.value = null;
}

// ---------------------------------------------------------------------------------------------------------------
// Progress

const PLAYING_LABELS: Record<NonNullable<ListItem["playing"]>, string> = {
  playing: "Playing",
  paused: "Paused",
  retrying: "Retrying…",
};

/**
 * A row's progress to show: an episode with a position that isn't watched (unless it is playing again), or a chapter
 * read partway.
 */
function progressOf(item: ListItem) {
  const pages = chapterProgress(item);
  if (pages) {
    return {
      text: pages.text,
      percent: pages.share === null ? 0 : Math.round(pages.share * 1000) / 10,
      known: pages.share !== null,
    };
  }
  const p = item.progress;
  if (!p || p.positionMs <= 0 || (item.done && !item.playing)) return null;
  return {
    text: progressText(p),
    percent: Math.round(progressShare(p) * 1000) / 10,
    known: p.durationMs > 0,
  };
}

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
    <slot name="notice" />
    <div
      v-if="selectable && selected.length"
      class="bulk"
      role="toolbar"
      aria-label="Selected"
      data-testid="bulk-bar"
    >
      <span data-testid="selected-count"
        >{{ selected.length.toLocaleString("en-US") }} selected</span
      >
      <slot name="bulk-actions" :ids="selected" />
      <button type="button" class="btn" data-action="select-all" @click="selectAllShown">
        Select all shown
      </button>
      <button type="button" class="btn" data-action="clear-selection" @click="clearSelection">
        Clear
      </button>
    </div>

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
        <div
          class="row"
          :class="{ done: item.done, selected: selectedSet.has(item.id), openable }"
          data-testid="item-row"
          :data-id="item.id"
          @click="onRowClick($event, item.id)"
        >
          <input
            v-if="selectable"
            type="checkbox"
            class="select"
            :checked="selectedSet.has(item.id)"
            :aria-label="`Select ${item.name}`"
            data-testid="item-select"
            @click="onSelect($event, item.id)"
          />
          <div class="text">
            <span class="name">{{ item.name }}</span>
            <span class="sub">
              <span v-if="item.playing" class="playing" data-testid="item-playing">{{
                PLAYING_LABELS[item.playing]
              }}</span>
              <span v-if="progressOf(item)" data-testid="item-progress-text">{{
                progressOf(item)!.text
              }}</span>
              <span v-if="item.date">{{ formatDate(item.date) }}</span>
              <span v-if="item.scanlator">{{ item.scanlator }}</span>
            </span>
            <span
              v-if="progressOf(item)?.known"
              class="bar"
              role="progressbar"
              aria-label="Progress"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-valuenow="progressOf(item)!.percent"
              data-testid="item-progress-bar"
            >
              <span class="bar-fill" :style="{ width: `${progressOf(item)!.percent}%` }" />
            </span>
          </div>
          <span class="badges">
            <span v-if="item.filler" class="badge warn">Filler</span>
            <span v-if="item.bookmarked" class="badge" data-testid="bookmarked">Bookmarked</span>
            <span v-if="item.done" class="badge ok">{{ doneLabels.done }}</span>
            <slot name="actions" :item="item" />
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

.row.selected {
  border-color: var(--color-accent);
}

.row.openable {
  cursor: pointer;
}

.row.openable:hover {
  background-image: linear-gradient(var(--color-surface-hover), var(--color-surface-hover));
}

.select {
  flex: none;
  margin: 0;
}

.bulk {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.playing {
  color: var(--color-accent);
  font-weight: var(--font-weight-semibold);
}

.bar {
  display: block;
  width: 100%;
  max-width: 240px;
  height: 3px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--color-border-strong);
}

.bar-fill {
  display: block;
  height: 100%;
  background: var(--color-accent);
}

.row.done .name {
  color: var(--color-text-tertiary);
}

.text {
  display: grid;
  flex: 1;
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
  align-items: center;
  gap: var(--space-1);
}
</style>
