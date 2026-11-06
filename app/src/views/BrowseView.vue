<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { api } from "../api/client";
import type { Source } from "../api/types";
import { useSources } from "../composables/useSources";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { formatLang } from "../utils/format";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";
import ErrorNotice from "../components/ErrorNotice.vue";

/*
 * Stage 2: the sources of the current kind, grouped by language. Stage 4 builds the full screen (pinned favourites,
 * the source page with Popular/Latest/Search and filters).
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const sources = useSources(() => mediaKind.kind);

const groups = computed(() => {
  const byLang = new Map<string, Source[]>();
  for (const s of sources.data.value ?? []) {
    byLang.set(s.lang, [...(byLang.get(s.lang) ?? []), s]);
  }
  const rank = (lang: string) => (lang === "all" ? 0 : lang === "other" ? 2 : 1);
  return [...byLang.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([lang, list]) => ({ lang, sources: list.sort((a, b) => a.name.localeCompare(b.name)) }));
});
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
    <p v-else-if="groups.length === 0" class="muted">
      No {{ mediaKind.kind }} sources yet. Install an extension in
      <RouterLink to="/extensions">Extensions</RouterLink>.
    </p>
    <template v-else>
      <section v-for="g in groups" :key="g.lang" class="group">
        <h2>{{ formatLang(g.lang) }}</h2>
        <ul class="list" data-testid="source-list">
          <li v-for="s in g.sources" :key="s.id">
            <RouterLink :to="`/browse/${s.id}`" class="row">
              <img
                v-if="api.assetUrl(s.iconUrl)"
                class="icon"
                :src="api.assetUrl(s.iconUrl) ?? undefined"
                alt=""
                width="32"
                height="32"
                loading="lazy"
              />
              <span class="row-main">
                <span class="row-title">{{ s.name }}</span>
                <span class="row-sub">{{ s.extension.name }}</span>
              </span>
              <span v-if="!s.extension.isTrusted" class="badge warn">Untrusted</span>
              <span v-if="s.contentWarning === 'NSFW'" class="badge">18+</span>
            </RouterLink>
          </li>
        </ul>
      </section>
    </template>

    <PagePlaceholder
      stage="Stage 4"
      summary="Pinned favourites, and each source's Popular, Latest and Search with filters."
    />
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
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
  color: var(--color-text);
  text-decoration: none;
}

.row:hover {
  background: var(--color-surface-hover);
}

.icon {
  flex: none;
  border-radius: var(--radius-sm);
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

.badge {
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.badge.warn {
  color: var(--color-warning);
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}
</style>
