<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { ToggleGroupItem, ToggleGroupRoot, type AcceptableValue } from "reka-ui";
import { useSources } from "../composables/useSources";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { THEME_CHOICES, useThemeStore, type ThemeChoice } from "../stores/theme";
import { formatLang } from "../utils/format";
import { canBrowse, sourceAccess } from "../utils/sources";
import ErrorNotice from "../components/ErrorNotice.vue";
import PageLayout from "../components/PageLayout.vue";

/*
 * Settings (Phase 3 step 17): the theme and language, where the backend keeps its data and logs, and the current
 * kind's sources that have settings of their own (each opens /settings/source/:id).
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const theme = useThemeStore();

const THEME_LABELS: Record<ThemeChoice, string> = {
  system: "System",
  dark: "Dark",
  light: "Light",
};

function onTheme(v: AcceptableValue | AcceptableValue[] | undefined) {
  // a click on the chosen option unsets a single toggle group: keep the choice
  if (typeof v === "string") theme.set(v as ThemeChoice);
}

const STATE_LABELS: Record<string, string> = {
  starting: "Starting",
  healthy: "Running",
  restarting: "Restarting",
  failed: "Stopped after failing to start",
  stopped: "Stopped",
};
const status = computed(() => backend.status);
const managed = computed(() => backend.mode === "tauri");

const kind = computed(() => mediaKind.kind);
const sources = useSources(kind);
const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });
const configurable = computed(() =>
  (sources.data.value ?? [])
    .filter((s) => s.isConfigurable === true && canBrowse(sourceAccess(s)))
    .sort((a, b) => collator.compare(a.name, b.name) || collator.compare(a.lang, b.lang)),
);
/** Sources the backend can't use right now: whether they have settings isn't known. */
const unknown = computed(
  () => (sources.data.value ?? []).filter((s) => !canBrowse(sourceAccess(s))).length,
);
</script>

<template>
  <PageLayout title="Settings">
    <section class="group" aria-labelledby="settings-general">
      <h2 id="settings-general">General</h2>
      <div class="card">
        <div class="row">
          <div class="text">
            <span id="settings-theme">Theme</span>
            <span class="summary">System follows Windows' light or dark mode.</span>
          </div>
          <ToggleGroupRoot
            type="single"
            class="segmented"
            aria-labelledby="settings-theme"
            :model-value="theme.choice"
            data-testid="theme-switch"
            @update:model-value="onTheme"
          >
            <ToggleGroupItem
              v-for="c in THEME_CHOICES"
              :key="c"
              :value="c"
              class="segment"
              :data-theme-choice="c"
            >
              {{ THEME_LABELS[c] }}
            </ToggleGroupItem>
          </ToggleGroupRoot>
        </div>
        <div class="row">
          <div class="text">
            <span>Language</span>
            <span class="summary">English is the only language so far.</span>
          </div>
          <span class="value" data-testid="language">English</span>
        </div>
      </div>
    </section>

    <section class="group" aria-labelledby="settings-backend">
      <h2 id="settings-backend">Backend</h2>
      <div class="card" data-testid="backend-info">
        <div class="row">
          <div class="text">
            <span>Server</span>
            <span class="summary" data-testid="backend-state">
              {{ status ? (STATE_LABELS[status.state] ?? status.state) : "Not known yet" }}
            </span>
          </div>
          <span v-if="backend.ready && status" class="value" data-testid="backend-server">
            Madoyomi-Server {{ status.serverVersion ?? "?" }} · {{ status.baseUrl }}
          </span>
        </div>
        <template v-if="managed && status">
          <div class="row">
            <div class="text">
              <span>Data folder</span>
              <span class="summary">The library, extensions and settings.</span>
            </div>
            <code class="path" data-testid="backend-data-dir">{{ status.dataDir }}</code>
          </div>
          <div class="row">
            <div class="text">
              <span>Log folder</span>
              <span class="summary">
                sidecar.log (the app's view of the backend) and madoyomi.log (the backend's own).
              </span>
            </div>
            <div class="path-actions">
              <code class="path" data-testid="backend-log-dir">{{ status.logDir }}</code>
              <button type="button" class="btn" data-action="open-logs" @click="backend.openLogs()">
                Open logs
              </button>
            </div>
          </div>
        </template>
        <p v-else class="row muted" data-testid="backend-external">
          This backend wasn't started by the app, so its folders aren't known here.
        </p>
      </div>
    </section>

    <section class="group" aria-labelledby="settings-sources">
      <h2 id="settings-sources">{{ kind === "anime" ? "Anime" : "Manga" }} source settings</h2>
      <ErrorNotice
        v-if="sources.isError.value"
        :error="sources.error.value"
        @retry="sources.refetch()"
      />
      <p v-else-if="sources.isPending.value" class="muted">
        {{ backend.ready ? "Loading the sources…" : "Waiting for the backend…" }}
      </p>
      <p v-else-if="configurable.length === 0" class="muted" data-testid="settings-sources-empty">
        None of your {{ kind }} sources has settings of its own.
      </p>
      <ul v-else class="card list" data-testid="settings-sources">
        <li v-for="s in configurable" :key="s.id">
          <RouterLink
            :to="`/settings/source/${s.id}`"
            class="row source-link"
            data-testid="settings-source"
          >
            <span class="text">
              <span>{{ s.name }}</span>
              <span class="summary">{{ formatLang(s.lang) }} · {{ s.extension.name }}</span>
            </span>
            <span aria-hidden="true" class="chevron">›</span>
          </RouterLink>
        </li>
      </ul>
      <p v-if="unknown > 0" class="muted note" data-testid="settings-sources-unknown">
        {{ unknown }} {{ unknown === 1 ? "source isn't" : "sources aren't" }} loaded, so
        {{ unknown === 1 ? "its settings aren't" : "their settings aren't" }} known.
        <RouterLink to="/extensions">Extensions</RouterLink> shows why.
      </p>
    </section>
  </PageLayout>
</template>

<style scoped>
.group {
  display: grid;
  gap: var(--space-2);
  max-width: 880px;
}

h2 {
  margin: 0;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
}

.card {
  display: grid;
  margin: 0;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  list-style: none;
}

.card > * + * {
  border-top: 1px solid var(--color-border);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin: 0;
  padding: var(--space-3) var(--space-4);
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.summary {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.value {
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
  text-align: right;
}

.path {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
  user-select: all;
}

.path-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.path-actions .btn {
  flex: none;
  white-space: nowrap;
}

.source-link {
  color: var(--color-text);
  text-decoration: none;
}

.source-link:hover {
  background: var(--color-surface-hover);
}

.chevron {
  color: var(--color-text-secondary);
  font-size: var(--font-size-body-large);
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}

.note {
  font-size: var(--font-size-caption);
}

/* Segmented control (Reka ToggleGroup), as the sidebar's anime/manga switch. */
.segmented {
  display: grid;
  flex: none;
  grid-template-columns: repeat(3, minmax(72px, 1fr));
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
}

.segment {
  min-height: calc(var(--control-height) - 6px);
  padding: 0 var(--space-3);
  border: 0;
  border-radius: calc(var(--radius-sm) - 1px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  cursor: pointer;
}

.segment:hover {
  background: var(--color-surface-hover);
}

.segment[data-state="on"] {
  background: var(--color-surface-raised);
  color: var(--color-text);
  box-shadow: 0 0 0 1px var(--color-border);
}
</style>
