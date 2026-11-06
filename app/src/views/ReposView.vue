<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";
import {
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogOverlay,
  AlertDialogPortal,
  AlertDialogRoot,
  AlertDialogTitle,
} from "reka-ui";
import {
  useAddRepo,
  useExtensionStores,
  useRefreshRepos,
  useRemoveRepo,
  useRepoRefreshState,
  type AddRepoOutcome,
} from "../composables/useExtensions";
import { useEventsStore, type RealtimeEvent } from "../stores/events";
import { useBackendStore } from "../stores/backend";
import type { ExtensionStore, MediaKind, RepoIndexFormat } from "../api/types";
import { mediaKindOf } from "../api/types";
import { formatEpochSeconds, formatTime } from "../utils/format";
import PageLayout from "../components/PageLayout.vue";
import ErrorNotice from "../components/ErrorNotice.vue";

/*
 * Repos (Phase 3 step 11): where extensions come from. Add one by URL (its kind detected, or chosen), remove one
 * (adding trusts its signing key, removing withdraws that), refresh a kind's repos or all of them, and follow the
 * live repo and install events.
 */
const stores = useExtensionStores();
const refresh = useRefreshRepos();
const refreshing = useRepoRefreshState();
const add = useAddRepo();
const remove = useRemoveRepo();
const events = useEventsStore();
const backend = useBackendStore();

const KINDS: MediaKind[] = ["anime", "manga"];

const groups = computed(() =>
  KINDS.map((kind) => ({
    kind,
    repos: (stores.data.value ?? [])
      .filter((s) => mediaKindOf(s.type) === kind)
      .sort((a, b) => a.name.localeCompare(b.name)),
  })),
);

const isRefreshing = (kind: MediaKind) => refreshing.value.has(kind) || refreshing.value.has("all");

// Adding
const url = ref("");
const kindChoice = ref<MediaKind | "auto">("auto");
const format = ref<RepoIndexFormat>("AUTO");
const added = ref<(AddRepoOutcome & { typed: string }) | null>(null);

async function submit() {
  const typed = url.value.trim();
  if (!typed) return;
  added.value = null;
  try {
    const outcome = await add.mutateAsync({
      indexUrl: typed,
      kind: kindChoice.value,
      format: format.value === "AUTO" ? undefined : format.value,
    });
    added.value = { ...outcome, typed };
    url.value = "";
  } catch {
    // shown below, from add.error
  }
}

// Removing, after a confirmation
const removing = ref<ExtensionStore | null>(null);
const removeError = ref<unknown>(null);

function askRemove(repo: ExtensionStore) {
  removeError.value = null;
  removing.value = repo;
}

async function confirmRemove() {
  const repo = removing.value;
  if (!repo) return;
  removing.value = null;
  try {
    await remove.mutateAsync(repo);
    if (added.value?.store.indexUrl === repo.indexUrl) added.value = null;
  } catch (e) {
    removeError.value = e;
  }
}

function signingKeyState(repo: ExtensionStore): { label: string; tone: string; title: string } {
  if (repo.signingKeyTrusted) {
    return { label: "Signing key trusted", tone: "ok", title: repo.signingKey };
  }
  if (!repo.signingKey) {
    return {
      label: "No signing key",
      tone: "warn",
      title: "The repo names no signing key, so each of its extensions needs your trust.",
    };
  }
  return {
    label: "Signing key not trusted",
    tone: "warn",
    title: "Remove and re-add the repo to trust its key.",
  };
}

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
      <RouterLink to="/extensions" class="btn">Extensions</RouterLink>
      <button
        type="button"
        class="btn btn-accent"
        data-action="refresh-repos"
        :disabled="refreshing.size > 0 || !stores.data.value?.length"
        @click="refresh.mutate(undefined)"
      >
        {{ refreshing.has("all") ? "Refreshing…" : "Refresh all" }}
      </button>
    </template>

    <form class="add" data-testid="add-repo" @submit.prevent="submit">
      <h2>Add a repo</h2>
      <div class="add-row">
        <input
          v-model="url"
          class="field url"
          type="url"
          spellcheck="false"
          required
          placeholder="https://…/index.min.json or …/index.pb"
          aria-label="Repo index URL"
          data-testid="repo-url"
        />
        <select v-model="kindChoice" class="field" aria-label="Repo type" data-testid="repo-kind">
          <option value="auto">Detect the type</option>
          <option value="anime">Anime (Aniyomi)</option>
          <option value="manga">Manga (Mihon)</option>
        </select>
        <button
          type="submit"
          class="btn btn-accent"
          data-action="add-repo"
          :disabled="add.isPending.value || !url.trim()"
        >
          {{ add.isPending.value ? "Adding…" : "Add" }}
        </button>
      </div>
      <details class="advanced">
        <summary>Index format</summary>
        <label>
          Read the index as
          <select v-model="format" class="field" data-testid="repo-format">
            <option value="AUTO">Detect (and follow repo.json's index_v2)</option>
            <option value="JSON">JSON (index.min.json)</option>
            <option value="PROTOBUF">Protobuf (index.pb)</option>
          </select>
        </label>
      </details>
      <p class="note">
        Adding a repo trusts its signing key: its extensions can be installed and run without
        another prompt.
      </p>

      <ErrorNotice v-if="add.error.value" title="Couldn't add the repo" :error="add.error.value" />
      <p v-else-if="added" class="added" role="status" data-testid="repo-added">
        {{ added.existed ? "Updated" : "Added" }} <strong>{{ added.store.name }}</strong> as
        {{ added.kind === "anime" ? "an anime" : "a manga" }} repo{{
          added.detected ? " (type detected)" : ""
        }}, with {{ added.store.extensions.totalCount }} extensions.
        <template v-if="added.store.indexUrl !== added.typed">
          It is stored as <code>{{ added.store.indexUrl }}</code
          >, where its index resolved to.
        </template>
      </p>
    </form>

    <ErrorNotice
      v-if="refresh.error.value"
      title="The refresh failed"
      :error="refresh.error.value"
      @retry="refresh.mutate(undefined)"
    />
    <ErrorNotice v-if="removeError" title="Couldn't remove the repo" :error="removeError" />

    <ErrorNotice
      v-if="stores.isError.value"
      :error="stores.error.value"
      @retry="stores.refetch()"
    />
    <p v-else-if="stores.isPending.value" class="muted">
      {{ backend.ready ? "Loading repos…" : "Waiting for the backend…" }}
    </p>
    <template v-else>
      <section
        v-for="g in groups"
        :key="g.kind"
        class="group"
        :data-kind="g.kind"
        :aria-labelledby="`repos-${g.kind}`"
      >
        <header class="group-header">
          <h2 :id="`repos-${g.kind}`">{{ g.kind === "anime" ? "Anime" : "Manga" }} repos</h2>
          <button
            v-if="g.repos.length > 0"
            type="button"
            class="btn btn-small"
            :data-action="`refresh-${g.kind}`"
            :title="`Refreshes every ${g.kind} repo (the backend refreshes a kind at a time)`"
            :disabled="refreshing.size > 0"
            @click="refresh.mutate(g.kind)"
          >
            {{ isRefreshing(g.kind) ? "Refreshing…" : `Refresh ${g.kind} repos` }}
          </button>
        </header>
        <p v-if="g.repos.length === 0" class="muted">No {{ g.kind }} repos yet.</p>
        <ul v-else class="list" :data-testid="`repo-list-${g.kind}`">
          <li
            v-for="repo in g.repos"
            :key="repo.indexUrl"
            class="row"
            data-testid="repo-row"
            :data-url="repo.indexUrl"
          >
            <div class="row-main">
              <p class="row-title">
                {{ repo.name || repo.indexUrl }}
                <span class="badge">{{ mediaKindOf(repo.type) }}</span>
                <span
                  class="badge"
                  :class="signingKeyState(repo).tone"
                  :title="signingKeyState(repo).title"
                  data-testid="repo-key"
                >
                  {{ signingKeyState(repo).label }}
                </span>
              </p>
              <p class="row-sub" :title="repo.indexUrl">{{ repo.indexUrl }}</p>
              <p v-if="repo.lastError" class="row-error" data-testid="repo-error">
                The last refresh failed, so its last good list is kept: {{ repo.lastError }}
              </p>
            </div>
            <div class="row-meta">
              <p>{{ repo.extensions.totalCount }} extensions</p>
              <p class="muted">Refreshed {{ formatEpochSeconds(repo.lastRefreshedAt) }}</p>
            </div>
            <button
              type="button"
              class="btn btn-small"
              data-action="remove-repo"
              :disabled="remove.isPending.value"
              @click="askRemove(repo)"
            >
              Remove
            </button>
          </li>
        </ul>
      </section>
    </template>

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

    <AlertDialogRoot
      :open="removing !== null"
      @update:open="(open: boolean) => !open && (removing = null)"
    >
      <AlertDialogPortal>
        <AlertDialogOverlay class="dialog-overlay" />
        <AlertDialogContent class="dialog" data-testid="remove-repo-dialog">
          <AlertDialogTitle class="dialog-title">Remove {{ removing?.name }}?</AlertDialogTitle>
          <AlertDialogDescription class="dialog-description">
            Its extensions that aren't installed leave the list. Installed ones stay, but those
            signed with this repo's key stop loading until you trust them yourself or add the repo
            again.
          </AlertDialogDescription>
          <p class="dialog-url">
            <code>{{ removing?.indexUrl }}</code>
          </p>
          <div class="dialog-actions">
            <AlertDialogCancel class="btn" data-action="cancel-remove">Cancel</AlertDialogCancel>
            <button
              type="button"
              class="btn btn-accent"
              data-action="confirm-remove"
              @click="confirmRemove"
            >
              Remove
            </button>
          </div>
        </AlertDialogContent>
      </AlertDialogPortal>
    </AlertDialogRoot>
  </PageLayout>
</template>

<style scoped>
h2 {
  margin: 0;
  font-size: var(--font-size-body-large);
  font-weight: var(--font-weight-semibold);
}

p {
  margin: 0;
}

.add {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.add-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.url {
  flex: 1;
  min-width: 280px;
}

.advanced summary {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  cursor: pointer;
}

.advanced label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.note {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}

.added {
  color: var(--color-success);
  overflow-wrap: anywhere;
}

.added code {
  color: var(--color-text);
}

.group {
  display: grid;
  gap: var(--space-2);
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

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
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.row-main {
  flex: 1;
  min-width: 0;
}

.row-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  font-weight: var(--font-weight-semibold);
}

.row-title .badge {
  text-transform: none;
}

.row-title .badge:first-of-type {
  text-transform: capitalize;
}

.row-sub {
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
  user-select: all;
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

.muted {
  color: var(--color-text-tertiary);
}

.events {
  display: grid;
  gap: var(--space-2);
}

.event-list {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
}

.dialog-url {
  overflow-wrap: anywhere;
}
</style>
