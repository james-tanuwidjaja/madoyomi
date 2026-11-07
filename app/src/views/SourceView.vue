<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { TabsList, TabsRoot, TabsTrigger } from "reka-ui";
import type { BrowseType, EntrySummary } from "../api/types";
import { useOpenInExtensions } from "../composables/useOpenInExtensions";
import { useSourceBrowse, useSourceFilters, useSources } from "../composables/useSources";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { useSourceBrowseStore } from "../stores/sourceBrowse";
import {
  defaultFilterState,
  filterChanges,
  filterSignature,
  type FilterState,
} from "../utils/filters";
import { formatLang } from "../utils/format";
import { canBrowse, sourceAccess } from "../utils/sources";
import PageLayout from "../components/PageLayout.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import FilterSheet from "../components/FilterSheet.vue";
import MediaCard from "../components/MediaCard.vue";
import VirtualGrid from "../components/VirtualGrid.vue";

/*
 * A source (Phase 3 step 14): Popular, Latest (when the source has it) and Search, with the source's filters in a
 * side sheet, as an endless grid of cards that loads the next page near the end. Filters apply to a search, as on the
 * server (and in Mihon): applying them switches to Search. The tab, the search and the filters stay for the session
 * (stores/sourceBrowse.ts).
 */
const props = defineProps<{ sourceId: string }>();

const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const pages = useSourceBrowseStore();
const openInExtensions = useOpenInExtensions();

const kind = computed(() => mediaKind.kind);
const sources = useSources(kind);
const source = computed(() => sources.data.value?.find((s) => s.id === props.sourceId) ?? null);
const access = computed(() => (source.value ? sourceAccess(source.value) : null));
const usable = computed(() => access.value !== null && canBrowse(access.value));

// The page's state, created before anything reads it.
watch([kind, () => props.sourceId], ([k, id]) => pages.page(k, id), { immediate: true });
const state = computed(() => pages.page(kind.value, props.sourceId));

// Filters
const filtersQuery = useSourceFilters(kind, () => (usable.value ? props.sourceId : null));
const filterList = computed(() => filtersQuery.data.value ?? []);
const signature = computed(() => filterSignature(filterList.value));
/** The applied values, as long as they belong to the source's current filter list. */
const appliedFilters = computed<FilterState | null>(() =>
  state.value.filters && filtersQuery.data.value && state.value.signature === signature.value
    ? state.value.filters
    : null,
);
const changes = computed(() =>
  appliedFilters.value ? filterChanges(filterList.value, appliedFilters.value) : [],
);
/** Stored filters wait for the list they belong to, rather than searching without them. */
const waitingForFilters = computed(
  () => state.value.filters !== null && filtersQuery.isPending.value,
);
const sheetOpen = ref(false);

function applyFilters(next: FilterState) {
  const st = state.value;
  const isDefault = filterChanges(filterList.value, next).length === 0;
  st.filters = isDefault ? null : next;
  st.signature = isDefault ? null : signature.value;
  st.tab = "SEARCH";
}

function clearFilters() {
  applyFilters(defaultFilterState(filterList.value));
}

// Tabs and search
const TAB_LABELS: Record<BrowseType, string> = {
  POPULAR: "Popular",
  LATEST: "Latest",
  SEARCH: "Search",
};
const tabs = computed<BrowseType[]>(() =>
  source.value?.supportsLatest === true ? ["POPULAR", "LATEST", "SEARCH"] : ["POPULAR", "SEARCH"],
);
const tab = computed<BrowseType>({
  get: () => (tabs.value.includes(state.value.tab) ? state.value.tab : "POPULAR"),
  set: (t) => (state.value.tab = t),
});

const typed = ref(state.value.query);
watch(
  () => state.value.query,
  (q) => (typed.value = q),
);

function submitSearch() {
  state.value.query = typed.value.trim();
  state.value.tab = "SEARCH";
}

// Results
const params = computed(() => {
  if (!usable.value) return null;
  const base = { kind: kind.value, sourceId: props.sourceId };
  if (tab.value !== "SEARCH") return { ...base, type: tab.value };
  if (waitingForFilters.value) return null;
  if (!state.value.query && changes.value.length === 0) return null;
  return { ...base, type: "SEARCH" as const, query: state.value.query, filters: changes.value };
});
const browse = useSourceBrowse(params);

/** Every page's entries, each once: sources often repeat an entry on the next page. */
const items = computed<EntrySummary[]>(() => {
  const seen = new Set<number>();
  const out: EntrySummary[] = [];
  for (const page of browse.data.value?.pages ?? []) {
    for (const e of page.items) {
      if (seen.has(e.id)) continue;
      seen.add(e.id);
      out.push(e);
    }
  }
  return out;
});

const resetKey = computed(() => JSON.stringify(params.value));
const nextPageFailed = computed(() => browse.isFetchNextPageError.value);
const firstPageFailed = computed(
  () => browse.isError.value && !nextPageFailed.value && items.value.length === 0,
);

function loadMore() {
  if (browse.hasNextPage.value && !browse.isFetching.value && !browse.isFetchNextPageError.value) {
    void browse.fetchNextPage();
  }
}

const subtitle = computed(() => {
  const s = source.value;
  if (!s) return `${kind.value} source`;
  return [formatLang(s.lang), s.baseUrl].filter(Boolean).join(" · ");
});
</script>

<template>
  <PageLayout :title="source?.name ?? 'Source'" :subtitle="subtitle" fill>
    <template #actions>
      <RouterLink
        v-if="usable && source?.isConfigurable === true"
        :to="`/settings/source/${sourceId}`"
        class="btn"
        data-action="source-settings"
      >
        Settings
      </RouterLink>
      <RouterLink to="/browse" class="btn">All sources</RouterLink>
    </template>

    <FilterSheet
      v-if="usable && filterList.length"
      v-model:open="sheetOpen"
      :filters="filterList"
      :applied="appliedFilters"
      @apply="applyFilters"
    />

    <ErrorNotice
      v-if="sources.isError.value"
      :error="sources.error.value"
      @retry="sources.refetch()"
    />
    <p v-else-if="sources.isPending.value" class="muted">
      {{ backend.ready ? "Loading the source…" : "Waiting for the backend…" }}
    </p>
    <div v-else-if="!source" class="notice" data-testid="source-missing">
      <p>This {{ kind }} source isn't installed.</p>
      <RouterLink to="/browse">See your {{ kind }} sources</RouterLink>
    </div>
    <div v-else-if="!usable" class="notice" data-testid="source-unusable">
      <p v-if="access === 'untrusted'">
        {{ source.extension.name }}'s signing certificate isn't trusted, so the backend doesn't load
        it. Trust it in Extensions to browse this source.
      </p>
      <p v-else>
        {{ source.extension.name }} is installed, but the backend couldn't load it. Extensions shows
        why.
      </p>
      <button
        type="button"
        class="btn"
        data-action="open-extensions"
        @click="openInExtensions(source.extension.pkgName)"
      >
        Open in Extensions
      </button>
    </div>

    <div v-else class="body">
      <div class="toolbar">
        <TabsRoot v-model="tab" activation-mode="manual">
          <TabsList class="tab-list" aria-label="Browse">
            <TabsTrigger v-for="t in tabs" :key="t" :value="t" class="tab" :data-tab="t">
              {{ TAB_LABELS[t] }}
            </TabsTrigger>
          </TabsList>
        </TabsRoot>
        <form class="search" role="search" @submit.prevent="submitSearch">
          <input
            v-model="typed"
            class="field"
            type="search"
            spellcheck="false"
            :placeholder="`Search ${source.name}`"
            aria-label="Search this source"
            data-testid="source-search"
          />
        </form>
        <button
          v-if="filterList.length"
          type="button"
          class="btn"
          data-action="filters"
          @click="sheetOpen = true"
        >
          Filters
          <span v-if="changes.length" class="badge" data-testid="filter-count">{{
            changes.length
          }}</span>
        </button>
        <button
          v-if="changes.length"
          type="button"
          class="btn"
          data-action="clear-filters"
          @click="clearFilters"
        >
          Clear filters
        </button>
      </div>
      <p v-if="source.supportsLatest === null" class="muted">
        The backend can't say whether this source has a Latest list right now.
      </p>
      <ErrorNotice
        v-if="filtersQuery.isError.value"
        title="Couldn't load the source's filters"
        :error="filtersQuery.error.value"
        @retry="filtersQuery.refetch()"
      />

      <p v-if="params === null" class="muted" data-testid="search-prompt">
        {{ waitingForFilters ? "Loading the filters…" : "Type a search, or set filters." }}
      </p>
      <ErrorNotice
        v-else-if="firstPageFailed"
        :title="`Couldn't load ${source.name}`"
        :error="browse.error.value"
        @retry="browse.refetch()"
      />
      <p v-else-if="browse.isPending.value" class="muted" data-testid="results-loading">Loading…</p>
      <p v-else-if="items.length === 0" class="muted" data-testid="results-empty">
        {{ tab === "SEARCH" ? "Nothing matches." : "The source returned nothing." }}
      </p>
      <VirtualGrid
        v-else
        class="results"
        :items="items"
        :item-key="(e) => e.id"
        :reset-key="resetKey"
        :label="`${TAB_LABELS[tab]} on ${source.name}`"
        data-testid="results"
        @end-reached="loadMore"
      >
        <template #default="{ item }">
          <MediaCard :entry="item" />
        </template>
        <template #footer>
          <ErrorNotice
            v-if="nextPageFailed"
            title="Couldn't load the next page"
            :error="browse.error.value"
            @retry="browse.fetchNextPage()"
          />
          <p v-else-if="browse.isFetchingNextPage.value" class="muted" data-testid="loading-more">
            Loading more…
          </p>
          <button
            v-else-if="browse.hasNextPage.value"
            type="button"
            class="btn"
            data-action="load-more"
            @click="loadMore"
          >
            Load more
          </button>
          <p v-else class="muted" data-testid="results-end">
            {{ items.length }} {{ items.length === 1 ? "entry" : "entries" }}, the end of the list.
          </p>
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

.tab-list {
  display: flex;
  gap: var(--space-1);
}

.tab {
  position: relative;
  min-height: var(--control-height);
  padding: 0 var(--space-3);
  border: 0;
  background: none;
  color: var(--color-text-secondary);
  font: inherit;
  cursor: pointer;
}

.tab:hover {
  color: var(--color-text);
}

.tab[data-state="active"] {
  color: var(--color-text);
  font-weight: var(--font-weight-semibold);
}

.tab[data-state="active"]::after {
  position: absolute;
  right: var(--space-3);
  bottom: -2px;
  left: var(--space-3);
  height: 3px;
  border-radius: 2px;
  background: var(--color-accent);
  content: "";
}

.search {
  display: flex;
  flex: 1;
  min-width: 200px;
  max-width: 420px;
}

.search .field {
  flex: 1;
}

.results {
  flex: 1;
  min-height: 0;
}

.notice {
  display: grid;
  justify-items: start;
  gap: var(--space-2);
}

.notice p,
.muted {
  margin: 0;
}

.muted {
  color: var(--color-text-tertiary);
}
</style>
