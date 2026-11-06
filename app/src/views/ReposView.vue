<script setup lang="ts">
import { computed } from "vue";
import { useExtensionStores, useRefreshRepos } from "../composables/useExtensions";
import { useEventsStore, type RealtimeEvent } from "../stores/events";
import { useBackendStore } from "../stores/backend";
import { mediaKindOf } from "../api/types";
import { formatEpochSeconds, formatTime } from "../utils/format";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";
import ErrorNotice from "../components/ErrorNotice.vue";

/*
 * Stage 2: the repos as the backend lists them, a refresh, and the live events. Stage 3 builds the full screen
 * (add by URL with type detection, remove).
 */
const stores = useExtensionStores();
const refresh = useRefreshRepos();
const events = useEventsStore();
const backend = useBackendStore();

const repos = computed(() =>
  [...(stores.data.value ?? [])].sort(
    (a, b) => a.type.localeCompare(b.type) || a.name.localeCompare(b.name),
  ),
);

function describe(e: RealtimeEvent): string {
  if (e.kind === "storeRefreshed") {
    const r = e.event;
    return r.ok
      ? `Repo refreshed: ${r.indexUrl} (${r.extensionCount} extensions)`
      : `Repo refresh failed: ${r.indexUrl}: ${r.error ?? "unknown error"}`;
  }
  const p = e.event;
  return `${p.pkgName}: ${p.state}${p.message ? ` (${p.message})` : ""}`;
}
</script>

<template>
  <PageLayout title="Repos" subtitle="Where extensions come from. Madoyomi ships with none.">
    <template #actions>
      <button
        type="button"
        class="btn btn-accent"
        data-action="refresh-repos"
        :disabled="refresh.isPending.value || !stores.data.value?.length"
        @click="refresh.mutate(undefined)"
      >
        {{ refresh.isPending.value ? "Refreshing…" : "Refresh all" }}
      </button>
    </template>

    <ErrorNotice
      v-if="refresh.error.value"
      title="The refresh failed"
      :error="refresh.error.value"
      @retry="refresh.mutate(undefined)"
    />

    <ErrorNotice
      v-if="stores.isError.value"
      :error="stores.error.value"
      @retry="stores.refetch()"
    />
    <p v-else-if="stores.isPending.value" class="muted">
      {{ backend.ready ? "Loading repos…" : "Waiting for the backend…" }}
    </p>
    <p v-else-if="repos.length === 0" class="muted">No repos yet.</p>
    <ul v-else class="list" data-testid="repo-list">
      <li v-for="repo in repos" :key="repo.indexUrl" class="row">
        <div class="row-main">
          <p class="row-title">
            {{ repo.name || repo.indexUrl }}
            <span class="badge">{{ mediaKindOf(repo.type) }}</span>
          </p>
          <p class="row-sub">{{ repo.indexUrl }}</p>
          <p v-if="repo.lastError" class="row-error">Last refresh failed: {{ repo.lastError }}</p>
        </div>
        <div class="row-meta">
          <p>{{ repo.extensions.totalCount }} extensions</p>
          <p class="muted">Refreshed {{ formatEpochSeconds(repo.lastRefreshedAt) }}</p>
        </div>
      </li>
    </ul>

    <PagePlaceholder
      stage="Stage 3"
      summary="Adding a repo by URL (the type detected, with an override) and removing one."
    />

    <section class="events" aria-labelledby="events-title">
      <h2 id="events-title">Live events</h2>
      <p v-if="events.recent.length === 0" class="muted">
        Nothing yet. Repo refreshes and extension installs show up here as they happen.
      </p>
      <ol v-else class="event-list" data-testid="event-list">
        <li v-for="(e, i) in events.recent" :key="`${e.at}-${i}`">
          <span class="muted">{{ formatTime(e.at) }}</span> {{ describe(e) }}
        </li>
      </ol>
    </section>
  </PageLayout>
</template>

<style scoped>
.list,
.event-list {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

p {
  margin: 0;
}

.row-main {
  min-width: 0;
}

.row-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-weight: var(--font-weight-semibold);
}

.row-sub {
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-error {
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}

.row-meta {
  flex: none;
  text-align: right;
  font-size: var(--font-size-caption);
}

.badge {
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-regular);
  text-transform: capitalize;
}

.muted {
  color: var(--color-text-tertiary);
}

.events h2 {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-body-large);
  font-weight: var(--font-weight-semibold);
}

.event-list {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
}
</style>
