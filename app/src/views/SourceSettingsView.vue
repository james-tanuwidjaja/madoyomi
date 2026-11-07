<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { useOpenInExtensions } from "../composables/useOpenInExtensions";
import { useSourcePreferences, useUpdateSourcePreference } from "../composables/usePreferences";
import { useSources } from "../composables/useSources";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { formatLang } from "../utils/format";
import { canBrowse, sourceAccess } from "../utils/sources";
import ErrorNotice from "../components/ErrorNotice.vue";
import PageLayout from "../components/PageLayout.vue";
import PreferenceRenderer from "../components/PreferenceRenderer.vue";

/*
 * A source's own settings (Phase 3 step 17): the extension's preference screen through PreferenceRenderer, each
 * change saved at once with an optimistic update (composables/usePreferences.ts). The source is the current kind's,
 * as on the source page; one that can't be used is handled the same way there (Open in Extensions).
 */
const props = defineProps<{ id: string }>();

const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const openInExtensions = useOpenInExtensions();

const kind = computed(() => mediaKind.kind);
const sources = useSources(kind);
const source = computed(() => sources.data.value?.find((s) => s.id === props.id) ?? null);
const access = computed(() => (source.value ? sourceAccess(source.value) : null));
const usable = computed(() => access.value !== null && canBrowse(access.value));
const configurable = computed(() => usable.value && source.value?.isConfigurable === true);

const preferences = useSourcePreferences(kind, () => (configurable.value ? props.id : null));
const update = useUpdateSourcePreference(kind, () => props.id);

const kindLabel = computed(() => (kind.value === "anime" ? "Anime" : "Manga"));
const subtitle = computed(() => {
  const s = source.value;
  if (!s) return `${kindLabel.value} source`;
  return [`${kindLabel.value} source`, formatLang(s.lang), s.extension.name].join(" · ");
});
const visibleCount = computed(() => preferences.data.value?.filter((p) => p.visible).length ?? 0);
</script>

<template>
  <PageLayout :title="source?.name ?? 'Source settings'" :subtitle="subtitle">
    <template #actions>
      <RouterLink v-if="usable" :to="`/browse/${id}`" class="btn" data-action="browse-source">
        Browse this source
      </RouterLink>
      <RouterLink to="/settings" class="btn">All settings</RouterLink>
    </template>

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
      <RouterLink to="/settings">See the {{ kind }} sources with settings</RouterLink>
    </div>
    <div v-else-if="!usable" class="notice" data-testid="source-unusable">
      <p v-if="access === 'untrusted'">
        {{ source.extension.name }}'s signing certificate isn't trusted, so the backend doesn't load
        it, and its settings can't be read. Trust it in Extensions first.
      </p>
      <p v-else>
        {{ source.extension.name }} is installed, but the backend couldn't load it, so its settings
        can't be read. Extensions shows why.
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
    <p v-else-if="!configurable" class="muted" data-testid="source-not-configurable">
      {{ source.name }} has no settings of its own.
    </p>

    <ErrorNotice
      v-else-if="preferences.isError.value"
      :title="`Couldn't load ${source.name}'s settings`"
      :error="preferences.error.value"
      @retry="preferences.refetch()"
    />
    <p v-else-if="preferences.isPending.value" class="muted" data-testid="preferences-loading">
      Loading the settings…
    </p>
    <p v-else-if="visibleCount === 0" class="muted" data-testid="preferences-empty">
      {{ source.name }} shows no settings right now.
    </p>
    <template v-else>
      <p class="muted hint">
        Changes are saved as you make them. The source uses them from its next request.
      </p>
      <PreferenceRenderer
        :preferences="preferences.data.value!"
        :status="update.status"
        :owner="source.extension.name"
        @change="update.change"
      />
    </template>
  </PageLayout>
</template>

<style scoped>
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

.hint {
  font-size: var(--font-size-caption);
}
</style>
