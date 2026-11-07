<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { api } from "../api/client";
import type { Source } from "../api/types";
import { useOpenInExtensions } from "../composables/useOpenInExtensions";
import { useSources } from "../composables/useSources";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { usePinnedSourcesStore } from "../stores/pinnedSources";
import { formatLang } from "../utils/format";
import { canBrowse, sourceAccess, type SourceAccess } from "../utils/sources";
import PageLayout from "../components/PageLayout.vue";
import ErrorNotice from "../components/ErrorNotice.vue";

/*
 * Browse (Phase 3 step 13): the current kind's installed sources, the pinned ones first, then by language, and
 * upstream's built-in Local source last. A source opens its page (SourceView) only when the backend can use it;
 * an untrusted or unloadable one points to Extensions, where the trust prompt and the install error are.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const pinned = usePinnedSourcesStore();
const openInExtensions = useOpenInExtensions();
const sources = useSources(() => mediaKind.kind);

interface Section {
  key: string;
  title: string;
  sources: Source[];
}

const byName = (a: Source, b: Source) => a.name.localeCompare(b.name);

const sections = computed<Section[]>(() => {
  const all = sources.data.value ?? [];
  const kind = mediaKind.kind;
  const pinnedIds = pinned.pins[kind];
  const isPinned = (s: Source) => pinnedIds.includes(s.id);
  const builtIn = (s: Source) => sourceAccess(s) === "builtIn";

  const out: Section[] = [];
  const pins = all
    .filter(isPinned)
    .sort((a, b) => pinnedIds.indexOf(a.id) - pinnedIds.indexOf(b.id));
  if (pins.length) out.push({ key: "pinned", title: "Pinned", sources: pins });

  const byLang = new Map<string, Source[]>();
  for (const s of all) {
    if (isPinned(s) || builtIn(s)) continue;
    byLang.set(s.lang, [...(byLang.get(s.lang) ?? []), s]);
  }
  const rank = (lang: string) => (lang === "all" ? 0 : lang === "other" ? 2 : 1);
  [...byLang.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .forEach(([lang, list]) =>
      out.push({ key: `lang:${lang}`, title: formatLang(lang), sources: list.sort(byName) }),
    );

  const local = all.filter((s) => builtIn(s) && !isPinned(s));
  if (local.length) out.push({ key: "builtIn", title: "Built in", sources: local });
  return out;
});

const ACCESS_BADGE: Partial<Record<SourceAccess, { text: string; title: string }>> = {
  untrusted: {
    text: "Untrusted",
    title: "Its extension's certificate isn't trusted, so the backend doesn't load it.",
  },
  notLoaded: {
    text: "Not loaded",
    title: "Its extension is installed but the backend couldn't load it.",
  },
};
</script>

<template>
  <PageLayout title="Browse" :subtitle="`Your ${mediaKind.kind} sources`">
    <ErrorNotice
      v-if="sources.isError.value"
      :error="sources.error.value"
      @retry="sources.refetch()"
    />
    <p v-else-if="sources.isPending.value" class="muted">
      {{ backend.ready ? "Loading sources…" : "Waiting for the backend…" }}
    </p>
    <p v-else-if="sections.length === 0" class="muted" data-testid="sources-empty">
      No {{ mediaKind.kind }} sources yet. Install an extension in
      <RouterLink to="/extensions">Extensions</RouterLink>.
    </p>
    <template v-else>
      <section
        v-for="g in sections"
        :key="g.key"
        class="group"
        data-testid="source-group"
        :data-group="g.key"
      >
        <h2>{{ g.title }}</h2>
        <ul class="list" data-testid="source-list">
          <li
            v-for="s in g.sources"
            :key="s.id"
            class="row"
            data-testid="source-row"
            :data-id="s.id"
            :data-access="sourceAccess(s)"
          >
            <component
              :is="canBrowse(sourceAccess(s)) ? RouterLink : 'div'"
              :to="canBrowse(sourceAccess(s)) ? `/browse/${s.id}` : undefined"
              class="row-link"
              :class="{ disabled: !canBrowse(sourceAccess(s)) }"
            >
              <img
                v-if="api.assetUrl(s.iconUrl)"
                class="icon"
                :src="api.assetUrl(s.iconUrl) ?? undefined"
                alt=""
                width="32"
                height="32"
                loading="lazy"
              />
              <span v-else class="icon icon-fallback" aria-hidden="true">
                {{ s.name.charAt(0) }}
              </span>
              <span class="row-main">
                <span class="row-title">{{ s.name }}</span>
                <span class="row-sub">
                  {{
                    sourceAccess(s) === "builtIn" ? "Files in your data folder" : s.extension.name
                  }}
                  <template v-if="g.key === 'pinned'"> · {{ formatLang(s.lang) }}</template>
                </span>
              </span>
              <span v-if="sourceAccess(s) === 'builtIn'" class="badge">Built in</span>
              <span
                v-else-if="ACCESS_BADGE[sourceAccess(s)]"
                class="badge warn"
                :title="ACCESS_BADGE[sourceAccess(s)]!.title"
              >
                {{ ACCESS_BADGE[sourceAccess(s)]!.text }}
              </span>
              <span v-if="s.contentWarning === 'NSFW'" class="badge">18+</span>
            </component>
            <button
              v-if="!canBrowse(sourceAccess(s))"
              type="button"
              class="btn btn-small"
              data-action="open-extensions"
              @click="openInExtensions(s.extension.pkgName)"
            >
              Open in Extensions
            </button>
            <button
              type="button"
              class="pin"
              data-action="pin"
              :aria-pressed="pinned.isPinned(mediaKind.kind, s.id)"
              :aria-label="`${pinned.isPinned(mediaKind.kind, s.id) ? 'Unpin' : 'Pin'} ${s.name}`"
              :title="pinned.isPinned(mediaKind.kind, s.id) ? 'Unpin' : 'Pin to the top'"
              @click="pinned.toggle(mediaKind.kind, s.id)"
            >
              {{ pinned.isPinned(mediaKind.kind, s.id) ? "★" : "☆" }}
            </button>
          </li>
        </ul>
      </section>
    </template>
  </PageLayout>
</template>

<style scoped>
.group h2 {
  margin: 0 0 var(--space-2);
  color: var(--color-text-secondary);
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
}

.list {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding-right: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.row:hover {
  background: var(--color-surface-hover);
}

.row-link {
  display: flex;
  flex: 1;
  align-items: center;
  gap: var(--space-3);
  min-width: 0;
  padding: var(--space-2) var(--space-3);
  color: var(--color-text);
  text-decoration: none;
}

.row-link.disabled .row-title,
.row-link.disabled .icon {
  opacity: 0.6;
}

.icon {
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
}

.icon-fallback {
  display: grid;
  place-items: center;
  background: var(--color-surface-sunken);
  color: var(--color-text-secondary);
}

.row-main {
  display: grid;
  flex: 1;
  min-width: 0;
}

.row-sub {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.pin {
  flex: none;
  width: var(--control-height);
  height: var(--control-height);
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--color-text-secondary);
  font-size: 18px;
  cursor: pointer;
}

.pin:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.pin[aria-pressed="true"] {
  color: var(--color-accent);
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}
</style>
