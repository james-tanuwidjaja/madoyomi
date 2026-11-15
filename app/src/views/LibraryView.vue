<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import type { LibraryEntry } from "../api/types";
import {
  useCategories,
  useRemoveFromLibrary,
  useSetCategorySort,
} from "../composables/useCategories";
import { useLibrary } from "../composables/useEntries";
import { useUpdateMarks } from "../composables/useLibraryUpdates";
import { useBackendStore } from "../stores/backend";
import { useLibraryCategoryStore } from "../stores/libraryCategory";
import {
  naturalDesc,
  sortLibrary,
  useLibrarySortStore,
  type LibrarySortKey,
} from "../stores/librarySort";
import { useMediaKindStore } from "../stores/mediaKind";
import { categorySort, entriesIn, libraryTabs, pickTab } from "../utils/categories";
import { formatCount } from "../utils/format";
import CategoriesDialog from "../components/CategoriesDialog.vue";
import CategoryPicker from "../components/CategoryPicker.vue";
import LibraryUpdateButton from "../components/LibraryUpdateButton.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import MediaCard from "../components/MediaCard.vue";
import PageLayout from "../components/PageLayout.vue";
import VirtualGrid from "../components/VirtualGrid.vue";

/*
 * The Library (Phase 3 step 16): the current kind's saved entries as a grid of covers (virtualized above 60). An
 * empty library links to Browse.
 *
 * Phase 6 Stage 1, categories (one list per kind):
 * - a tab per category, with its count, once the kind has a category besides Default (Default's tab only while it
 *   holds entries); the open tab is remembered per kind on this device;
 * - each tab has its own sort, by title or by when entries were added, either way, kept in the category's meta. A
 *   category without one uses the kind's last chosen sort (on this device), which is all there is without tabs;
 * - "Categories" opens the management dialog; "Select" picks entries (a click, Shift+click for a range, Select all)
 *   for "Set categories" and "Remove from library"; Esc or Done ends it.
 *
 * Phase 6 Stage 2: each card shows its unwatched/unread count (from the library query, fetched again when progress
 * changes), and the sort has "Unwatched/Unread" and "Last watched/read".
 *
 * Phase 6 Stage 3: "Update library" checks the kind's library for new items ("Update <tab>" only the open category),
 * and a card an update check found new items in shows "N new" until the entry is opened.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const sorts = useLibrarySortStore();
const tabStore = useLibraryCategoryStore();

const kind = computed(() => mediaKind.kind);
const library = useLibrary(kind);
const categories = useCategories(kind);
const setCategorySort = useSetCategorySort();
const removeFromLibrary = useRemoveFromLibrary();
const marks = useUpdateMarks(kind);

const all = computed(() => library.data.value ?? []);
const tabs = computed(() => libraryTabs(categories.data.value ?? [], all.value));
const current = computed(() => pickTab(tabs.value, tabStore.selected[kind.value]));

function openTab(id: number) {
  tabStore.set(kind.value, id);
}

function onTabKey(event: KeyboardEvent) {
  const step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
  if (!step || !current.value) return;
  event.preventDefault();
  const i = tabs.value.findIndex((t) => t.category.id === current.value!.category.id);
  const next = tabs.value[(i + step + tabs.value.length) % tabs.value.length];
  if (!next) return;
  openTab(next.category.id);
  (event.currentTarget as HTMLElement)
    .querySelector<HTMLElement>(`[data-id="${next.category.id}"]`)
    ?.focus();
}

const sort = computed(
  () => (current.value && categorySort(current.value.category)) ?? sorts.sorts[kind.value],
);
function setSort(by: LibrarySortKey, desc: boolean) {
  const next = { by, desc };
  sorts.set(kind.value, next);
  const tab = current.value;
  if (tab) setCategorySort.mutate({ kind: kind.value, categoryId: tab.category.id, sort: next });
}
const sortBy = computed({
  get: () => sort.value.by,
  // a new key starts in its natural direction: titles A to Z, the others the most or the newest first
  set: (by: LibrarySortKey) => setSort(by, naturalDesc(by)),
});
function toggleDirection() {
  setSort(sort.value.by, !sort.value.desc);
}

const shown = computed(() =>
  current.value ? entriesIn(all.value, current.value.category) : all.value,
);
const entries = computed(() => sortLibrary(shown.value, sort.value));
const subtitle = computed(() =>
  library.data.value
    ? formatCount(all.value.length, `saved ${kind.value}`, `saved ${kind.value}`)
    : undefined,
);
const resetKey = computed(() =>
  JSON.stringify([kind.value, current.value?.category.id ?? null, sort.value]),
);
const directionLabel = computed(() => {
  const desc = sort.value.desc;
  if (sort.value.by === "title") return desc ? "Z to A" : "A to Z";
  if (sort.value.by === "unread") return desc ? "Most first" : "Fewest first";
  return desc ? "Newest first" : "Oldest first";
});
const verbs = computed(() =>
  kind.value === "anime"
    ? { unread: "Unwatched", last: "Last watched" }
    : { unread: "Unread", last: "Last read" },
);

// ---------------------------------------------------------------------------------------------------------------
// Selection

const selecting = ref(false);
const selected = ref<number[]>([]);
const selectedSet = computed(() => new Set(selected.value));
/** The card a Shift+click extends from: the last one clicked. */
const anchor = ref<number | null>(null);
const confirmingRemove = ref(false);

function startSelecting() {
  selecting.value = true;
}

function stopSelecting() {
  selecting.value = false;
  selected.value = [];
  anchor.value = null;
  confirmingRemove.value = false;
}

watch(kind, stopSelecting);
// entries that left the library (or the kind) leave the selection
watch(all, (list) => {
  const ids = new Set(list.map((e) => e.id));
  if (selected.value.some((id) => !ids.has(id)))
    selected.value = selected.value.filter((id) => ids.has(id));
});

function onCardClick(event: MouseEvent, id: number) {
  if (!selecting.value) return;
  event.preventDefault();
  const next = new Set(selectedSet.value);
  const on = !next.has(id);
  const ids = entries.value.map((e) => e.id);
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
  confirmingRemove.value = false;
}

function selectAll() {
  selected.value = [...new Set([...selected.value, ...entries.value.map((e) => e.id)])];
}

const selectedEntries = computed<LibraryEntry[]>(() =>
  all.value.filter((e) => selectedSet.value.has(e.id)),
);

const pickerOpen = ref(false);
const manageOpen = ref(false);

async function removeSelected() {
  if (!confirmingRemove.value) {
    confirmingRemove.value = true;
    return;
  }
  try {
    await removeFromLibrary.mutateAsync({ kind: kind.value, ids: [...selected.value] });
    stopSelecting();
  } catch {
    // shown in the bar
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape" && selecting.value && !pickerOpen.value && !manageOpen.value) {
    event.preventDefault();
    stopSelecting();
  }
}
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
    <div v-else-if="all.length === 0" class="empty" data-testid="library-empty">
      <p>Your {{ kind }} library is empty.</p>
      <p class="muted">Find something in Browse, open it and choose "Add to library".</p>
      <RouterLink to="/browse" class="btn btn-accent" data-action="browse"
        >Browse {{ kind }} sources</RouterLink
      >
    </div>
    <div v-else class="body" @keydown="onKeydown">
      <div
        v-if="tabs.length"
        class="tabs"
        role="tablist"
        :aria-label="`${kind} categories`"
        data-testid="category-tabs"
        @keydown="onTabKey"
      >
        <button
          v-for="t in tabs"
          :key="t.category.id"
          type="button"
          role="tab"
          class="tab"
          :class="{ active: t.category.id === current?.category.id }"
          :aria-selected="t.category.id === current?.category.id"
          :tabindex="t.category.id === current?.category.id ? 0 : -1"
          :data-id="t.category.id"
          data-testid="category-tab"
          @click="openTab(t.category.id)"
        >
          <span class="tab-name">{{ t.category.name }}</span>
          <span class="tab-count">{{ t.count.toLocaleString("en-US") }}</span>
        </button>
      </div>

      <div v-if="!selecting" class="toolbar">
        <label class="control">
          <span class="label">Sort</span>
          <select v-model="sortBy" class="field" data-testid="library-sort">
            <option value="added">Date added</option>
            <option value="title">Title</option>
            <option value="unread">{{ verbs.unread }}</option>
            <option value="lastRead">{{ verbs.last }}</option>
          </select>
        </label>
        <button type="button" class="btn" data-action="library-direction" @click="toggleDirection">
          {{ directionLabel }}
        </button>
        <span class="spacer" />
        <LibraryUpdateButton
          :kind="kind"
          :category="current ? { id: current.category.id, name: current.category.name } : null"
        />
        <button type="button" class="btn" data-action="select" @click="startSelecting">
          Select
        </button>
        <button
          type="button"
          class="btn"
          data-action="manage-categories"
          @click="manageOpen = true"
        >
          Categories
        </button>
      </div>
      <div v-else class="bulk" role="toolbar" aria-label="Selected" data-testid="library-bulk">
        <span data-testid="selected-count">{{
          selected.length
            ? `${selected.length.toLocaleString("en-US")} selected`
            : "Click entries to select them"
        }}</span>
        <button
          type="button"
          class="btn"
          data-action="set-categories"
          :disabled="!selected.length"
          @click="pickerOpen = true"
        >
          Set categories
        </button>
        <button
          type="button"
          class="btn"
          :class="{ danger: confirmingRemove }"
          data-action="remove-selected"
          :disabled="!selected.length || removeFromLibrary.isPending.value"
          @click="removeSelected"
        >
          {{
            confirmingRemove
              ? `Remove ${formatCount(selected.length, "entry", "entries")}?`
              : "Remove from library"
          }}
        </button>
        <button type="button" class="btn" data-action="select-all" @click="selectAll">
          Select all
        </button>
        <span class="spacer" />
        <button type="button" class="btn" data-action="done-selecting" @click="stopSelecting">
          Done
        </button>
        <ErrorNotice
          v-if="removeFromLibrary.error.value"
          class="bulk-error"
          title="Couldn't remove them"
          :error="removeFromLibrary.error.value"
        />
      </div>

      <p v-if="entries.length === 0" class="muted" data-testid="category-empty">
        Nothing in {{ current?.category.name }} yet. Use Select, then Set categories, to add
        entries.
      </p>
      <VirtualGrid
        v-else
        class="grid"
        :items="entries"
        :item-key="(e) => e.id"
        :reset-key="resetKey"
        :label="`Your ${kind} library`"
        data-testid="library-grid"
      >
        <template #default="{ item }">
          <MediaCard
            :entry="item"
            hide-library-badge
            :count="item.unreadCount"
            :new-count="marks.data.value?.[item.id] ?? 0"
            :selectable="selecting"
            :selected="selectedSet.has(item.id)"
            @select="onCardClick($event, item.id)"
          />
        </template>
      </VirtualGrid>

      <CategoriesDialog v-model:open="manageOpen" :kind="kind" :entries="all" />
      <CategoryPicker
        v-model:open="pickerOpen"
        :kind="kind"
        :entries="selectedEntries"
        @saved="stopSelecting"
      />
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

.tabs {
  display: flex;
  gap: var(--space-1);
  overflow-x: auto;
  border-bottom: 1px solid var(--color-border);
}

.tab {
  display: inline-flex;
  flex: none;
  align-items: baseline;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: var(--color-text-secondary);
  font: inherit;
  cursor: pointer;
}

.tab:hover {
  color: var(--color-text);
}

.tab.active {
  border-bottom-color: var(--color-accent);
  color: var(--color-text);
  font-weight: var(--font-weight-semibold);
}

.tab-count {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
  font-weight: normal;
}

.toolbar,
.bulk {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
}

.bulk {
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.bulk-error {
  flex-basis: 100%;
}

.spacer {
  flex: 1;
}

.danger {
  border-color: var(--color-danger);
  color: var(--color-danger);
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
