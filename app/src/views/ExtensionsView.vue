<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { SwitchRoot, SwitchThumb, TabsList, TabsRoot, TabsTrigger } from "reka-ui";
import type { Extension, ExtensionAction } from "../api/types";
import { LOCAL_SOURCE_PKG } from "../api/types";
import {
  useExtensionAction,
  useExtensionActionState,
  useExtensionStores,
  useExtensions,
} from "../composables/useExtensions";
import { useBackendStore } from "../stores/backend";
import { useEventsStore } from "../stores/events";
import { useExtensionFiltersStore, type ExtensionTab } from "../stores/extensionFilters";
import { useMediaKindStore } from "../stores/mediaKind";
import { formatExtensionLang } from "../utils/format";
import PageLayout from "../components/PageLayout.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import ExtensionRow from "../components/ExtensionRow.vue";
import TrustDialog from "../components/TrustDialog.vue";
import VirtualList from "../components/VirtualList.vue";

/*
 * Extensions (Phase 3 step 12): the current kind's extensions in three tabs (Available, Installed, Updates), with a
 * search, a language filter and "Show 18+". Install, update and uninstall show their live steps from the events
 * store (src/api/realtime.ts follows every install), and an installed extension whose certificate isn't trusted
 * offers the trust prompt (TrustDialog), which also opens by itself after such an install.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const filters = useExtensionFiltersStore();
const events = useEventsStore();

const extensions = useExtensions(() => mediaKind.kind);
const stores = useExtensionStores();
const action = useExtensionAction();
const actionState = useExtensionActionState();

/** The built-in Local source (manga, id 0) has a fake extension row without a signature: it isn't one to manage. */
const all = computed(() =>
  (extensions.data.value ?? []).filter((e) => e.pkgName !== LOCAL_SOURCE_PKG),
);

const repoNames = computed(() => {
  const names = new Map<string, string>();
  for (const s of stores.data.value ?? []) names.set(s.indexUrl, s.name || s.indexUrl);
  return names;
});
const hasRepoOfKind = computed(() =>
  (stores.data.value ?? []).some(
    (s) => s.type === (mediaKind.kind === "anime" ? "ANIME" : "MANGA"),
  ),
);

const languages = computed(() =>
  [...new Set(all.value.map((e) => e.lang))].sort((a, b) =>
    formatExtensionLang(a).localeCompare(formatExtensionLang(b)),
  ),
);
watch(languages, (langs) => {
  if (filters.lang && langs.length > 0 && !langs.includes(filters.lang)) filters.lang = "";
});
// A search is for one kind's names: switching the kind starts over.
watch(
  () => mediaKind.kind,
  () => {
    filters.search = "";
  },
);

/** Installed extensions that need the user first: untrusted, then updatable, then by name. */
function attentionRank(e: Extension): number {
  if (!e.isTrusted) return 0;
  if (e.hasUpdate) return 1;
  return 2;
}

const byName = (a: Extension, b: Extension) => a.name.localeCompare(b.name);

const tabs = computed(() => {
  const installed = all.value.filter((e) => e.isInstalled);
  return {
    // 18+ is hidden only from what you'd install: what's installed always shows
    available: all.value
      .filter((e) => !e.isInstalled && (filters.showNsfw || e.contentWarning !== "NSFW"))
      .sort(byName),
    installed: installed.sort((a, b) => attentionRank(a) - attentionRank(b) || byName(a, b)),
    updates: installed.filter((e) => e.hasUpdate).sort(byName),
  } satisfies Record<ExtensionTab, Extension[]>;
});

const matching = computed(() => {
  const q = filters.search.trim().toLowerCase();
  const matches = (e: Extension) =>
    (!q || e.name.toLowerCase().includes(q) || e.pkgName.toLowerCase().includes(q)) &&
    (!filters.lang || e.lang === filters.lang);
  return {
    available: tabs.value.available.filter(matches),
    installed: tabs.value.installed.filter(matches),
    updates: tabs.value.updates.filter(matches),
  } satisfies Record<ExtensionTab, Extension[]>;
});
const visible = computed(() => matching.value[filters.tab]);

/** Where the search finds something when this tab has nothing (an extension just installed leaves Available). */
const elsewhere = computed(() =>
  (Object.keys(matching.value) as ExtensionTab[])
    .filter((t) => t !== filters.tab && t !== "updates" && matching.value[t].length > 0)
    .map((t) => ({ tab: t, count: matching.value[t].length })),
);

const resetKey = computed(
  () => `${mediaKind.kind}|${filters.tab}|${filters.search}|${filters.lang}|${filters.showNsfw}`,
);

const tabLabels: Record<ExtensionTab, string> = {
  available: "Available",
  installed: "Installed",
  updates: "Updates",
};

// The trust prompt
const trustOpen = ref(false);
const trustPkg = ref<string | null>(null);
const trustFallback = ref<Extension | null>(null);
/** Read from the list, so the dialog shows the extension as it is now. */
const trustTarget = computed<Extension | null>(
  () => all.value.find((e) => e.pkgName === trustPkg.value) ?? trustFallback.value,
);

function askTrust(ext: Extension) {
  trustPkg.value = ext.pkgName;
  trustFallback.value = ext;
  trustOpen.value = true;
}

async function run(ext: Extension, what: ExtensionAction) {
  try {
    const after = await action.mutateAsync({
      pkgName: ext.pkgName,
      kind: mediaKind.kind,
      action: what,
    });
    // An install whose certificate isn't trusted is installed but not loaded: ask now, as Mihon does.
    if (what !== "uninstall" && after?.isInstalled && !after.isTrusted) askTrust(after);
  } catch {
    // shown on the row, from the mutation's state
  }
}
</script>

<template>
  <PageLayout
    title="Extensions"
    :subtitle="`Your ${mediaKind.kind} extensions and the ones your repos offer`"
    fill
  >
    <template #actions>
      <RouterLink to="/repos" class="btn">Repos</RouterLink>
    </template>

    <TrustDialog v-model:open="trustOpen" :extension="trustTarget" />

    <ErrorNotice
      v-if="extensions.isError.value"
      :error="extensions.error.value"
      @retry="extensions.refetch()"
    />
    <p v-else-if="extensions.isPending.value" class="muted">
      {{ backend.ready ? "Loading extensions…" : "Waiting for the backend…" }}
    </p>
    <div v-else-if="all.length === 0" class="empty" data-testid="extensions-empty">
      <p v-if="!hasRepoOfKind">
        No {{ mediaKind.kind }} repo yet. Madoyomi ships with none:
        <RouterLink to="/repos">add a repo</RouterLink> to see its extensions.
      </p>
      <p v-else>Your {{ mediaKind.kind }} repos list no extensions.</p>
    </div>

    <div v-else class="body">
      <TabsRoot v-model="filters.tab" activation-mode="manual" class="tabs">
        <TabsList class="tab-list" aria-label="Extension lists">
          <TabsTrigger
            v-for="(label, key) in tabLabels"
            :key="key"
            :value="key"
            class="tab"
            :data-tab="key"
          >
            {{ label }} <span class="count">{{ tabs[key].length }}</span>
          </TabsTrigger>
        </TabsList>
      </TabsRoot>

      <div class="toolbar">
        <input
          v-model="filters.search"
          class="field search"
          type="search"
          spellcheck="false"
          placeholder="Search by name or package"
          aria-label="Search extensions"
          data-testid="extension-search"
        />
        <select
          v-model="filters.lang"
          class="field"
          aria-label="Language"
          data-testid="extension-lang"
        >
          <option value="">Any language</option>
          <option v-for="l in languages" :key="l" :value="l">{{ formatExtensionLang(l) }}</option>
        </select>
        <label class="switch-label">
          <SwitchRoot v-model="filters.showNsfw" class="switch" data-testid="extension-nsfw">
            <SwitchThumb class="switch-thumb" />
          </SwitchRoot>
          Show 18+
        </label>
      </div>

      <p v-if="visible.length === 0" class="muted" data-testid="extensions-none">
        <template v-if="filters.tab === 'updates' && !filters.search && !filters.lang">
          Everything is up to date.
        </template>
        <template v-else-if="filters.tab === 'installed' && !filters.search && !filters.lang">
          No {{ mediaKind.kind }} extensions installed yet. Find one under Available.
        </template>
        <template v-else>
          Nothing matches here.
          <button
            v-for="e in elsewhere"
            :key="e.tab"
            type="button"
            class="link"
            :data-action="`show-${e.tab}`"
            @click="filters.tab = e.tab"
          >
            {{ e.count }} under {{ tabLabels[e.tab] }}
          </button>
        </template>
      </p>
      <VirtualList
        v-else
        class="list"
        :items="visible"
        :item-key="(e) => e.pkgName"
        :estimate-size="62"
        :reset-key="resetKey"
        :label="`${tabLabels[filters.tab]} ${mediaKind.kind} extensions`"
        data-testid="extension-list"
      >
        <template #default="{ item }">
          <ExtensionRow
            :extension="item"
            :repo-name="item.storeIndexUrl ? (repoNames.get(item.storeIndexUrl) ?? null) : null"
            :action-state="actionState[item.pkgName]"
            :progress="events.installProgress[item.pkgName]"
            @action="(a) => run(item, a)"
            @trust="askTrust(item)"
          />
        </template>
      </VirtualList>
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

.tab-list {
  display: flex;
  gap: var(--space-1);
  border-bottom: 1px solid var(--color-border);
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
  bottom: -1px;
  left: var(--space-3);
  height: 3px;
  border-radius: 2px;
  background: var(--color-accent);
  content: "";
}

.count {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-regular);
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
}

.search {
  flex: 1;
  min-width: 220px;
  max-width: 420px;
}

.switch-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
}

.switch {
  position: relative;
  width: 40px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--color-text-secondary);
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
}

.switch[data-state="checked"] {
  border-color: var(--color-accent);
  background: var(--color-accent);
}

.switch-thumb {
  display: block;
  width: 12px;
  height: 12px;
  margin-left: 3px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--easing-standard);
}

.switch[data-state="checked"] .switch-thumb {
  background: var(--color-on-accent);
  transform: translateX(20px);
}

.list {
  flex: 1;
  min-height: 0;
  padding-bottom: var(--space-4);
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--color-accent);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.empty p,
.muted {
  margin: 0;
}

.muted {
  color: var(--color-text-tertiary);
}
</style>
