<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { isApiError } from "../api/client";
import { useBackendStore } from "../stores/backend";
import type { EntrySummary } from "../api/types";
import { useDetailsPage } from "../composables/useDetailsPage";
import { useAnime, useEpisodes, useRefreshAnime, useSeasons } from "../composables/useEntries";
import EntryHeader from "../components/EntryHeader.vue";
import EpisodeList from "../components/EpisodeList.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import MediaCard from "../components/MediaCard.vue";
import PageLayout from "../components/PageLayout.vue";
import VirtualGrid from "../components/VirtualGrid.vue";

/*
 * An anime's details (Phase 3 step 15): the stored entry with its episodes, or, for a `Seasons` entry, its seasons
 * (entries of their own, linking to their details). Refreshed from the source by the button, and by itself the first
 * time an entry that was never fetched is opened; a source's failure is shown above the stored data, which stays.
 */
const props = defineProps<{ id: number }>();
const backend = useBackendStore();

const anime = useAnime(() => props.id);
const entry = computed(() => anime.data.value);
const isSeasons = computed(() => entry.value?.fetchType === "Seasons");
const episodes = useEpisodes(() => (entry.value && !isSeasons.value ? props.id : null));
const seasons = useSeasons(() => (isSeasons.value ? props.id : null));
const parent = useAnime(() => entry.value?.parentId ?? null);

const page = useDetailsPage({
  kind: "anime",
  id: () => props.id,
  entry,
  refresh: useRefreshAnime(),
});

const notStored = computed(
  () => isApiError(anime.error.value) && anime.error.value.code === "NOT_FOUND",
);

const seasonCards = computed<EntrySummary[]>(() =>
  (seasons.data.value ?? []).map((s) => ({
    kind: "anime",
    id: s.id,
    title: s.title,
    thumbnailUrl: s.thumbnailUrl,
    inLibrary: s.inLibrary,
  })),
);
</script>

<template>
  <PageLayout :title="entry ? undefined : 'Anime'" fill>
    <div v-if="notStored" class="notice" data-testid="not-stored">
      <p>This anime isn't stored. Open it from a source in Browse.</p>
      <RouterLink to="/browse">Browse your anime sources</RouterLink>
    </div>
    <ErrorNotice
      v-else-if="anime.isError.value"
      :error="anime.error.value"
      @retry="anime.refetch()"
    />
    <p v-else-if="!entry" class="muted">
      {{ backend.ready ? "Loading…" : "Waiting for the backend…" }}
    </p>

    <template v-else>
      <EntryHeader
        :entry="entry"
        :source-name="page.sourceName.value"
        :source-link="page.sourceLink.value"
      >
        <template #context>
          <p v-if="entry.parentId" class="muted context" data-testid="season-of">
            <span v-if="entry.seasonNumber >= 0">Season {{ entry.seasonNumber }} of </span>
            <span v-else>A season of </span>
            <RouterLink :to="`/anime/${entry.parentId}`">{{
              parent.data.value?.title ?? "its series"
            }}</RouterLink>
          </p>
        </template>
        <template #actions>
          <button
            type="button"
            :class="entry.inLibrary ? 'btn' : 'btn btn-accent'"
            :disabled="page.libraryPending.value"
            data-action="library"
            @click="page.toggleLibrary()"
          >
            {{ entry.inLibrary ? "Remove from library" : "Add to library" }}
          </button>
          <button
            type="button"
            class="btn"
            :disabled="!page.usable.value || page.refreshing.value"
            data-action="refresh"
            @click="page.refreshNow()"
          >
            {{ page.refreshing.value ? "Refreshing…" : "Refresh" }}
          </button>
          <span v-if="entry.inLibrary" class="badge ok" data-testid="in-library">In library</span>
        </template>
      </EntryHeader>

      <div class="notices">
        <ErrorNotice
          v-if="page.refreshError.value"
          :title="`Couldn't refresh from ${page.sourceName.value}`"
          :error="page.refreshError.value"
          data-testid="refresh-error"
          @retry="page.refreshNow()"
        />
        <ErrorNotice
          v-if="page.libraryError.value"
          title="Couldn't change the library"
          :error="page.libraryError.value"
        />
        <div
          v-if="page.sourceKnown.value && !page.usable.value"
          class="notice"
          data-testid="source-unusable"
        >
          <template v-if="!page.source.value">
            <p>Its source isn't installed, so it can't be refreshed. This is what is stored.</p>
          </template>
          <template v-else>
            <p v-if="page.access.value === 'untrusted'">
              {{ page.source.value.extension.name }}'s signing certificate isn't trusted, so it
              can't be refreshed. Trust it in Extensions.
            </p>
            <p v-else>
              {{ page.source.value.extension.name }} couldn't be loaded. Extensions shows why.
            </p>
            <button
              type="button"
              class="btn"
              data-action="open-extensions"
              @click="page.openExtensions()"
            >
              Open in Extensions
            </button>
          </template>
        </div>
      </div>

      <section v-if="isSeasons" class="children" aria-label="Seasons">
        <ErrorNotice
          v-if="seasons.isError.value"
          :error="seasons.error.value"
          @retry="seasons.refetch()"
        />
        <p v-else-if="seasons.isPending.value" class="muted">Loading the seasons…</p>
        <p v-else-if="seasonCards.length === 0" class="muted" data-testid="seasons-none">
          {{
            page.refreshing.value
              ? `Fetching the seasons from ${page.sourceName.value}…`
              : "No seasons yet."
          }}
        </p>
        <template v-else>
          <p class="muted">
            {{ seasonCards.length }} {{ seasonCards.length === 1 ? "season" : "seasons" }}
          </p>
          <VirtualGrid
            class="grid"
            :items="seasonCards"
            :item-key="(s) => s.id"
            label="Seasons"
            data-testid="seasons"
          >
            <template #default="{ item }">
              <MediaCard :entry="item" />
            </template>
          </VirtualGrid>
        </template>
      </section>
      <section v-else class="children">
        <ErrorNotice
          v-if="episodes.isError.value"
          :error="episodes.error.value"
          @retry="episodes.refetch()"
        />
        <p v-else-if="episodes.isPending.value" class="muted">Loading the episodes…</p>
        <p
          v-else-if="page.refreshing.value && episodes.data.value?.length === 0"
          class="muted"
          data-testid="fetching"
        >
          Fetching the episodes from {{ page.sourceName.value }}…
        </p>
        <EpisodeList v-else class="list" :anime-id="id" :episodes="episodes.data.value ?? []" />
      </section>
    </template>
  </PageLayout>
</template>

<style scoped>
.notices {
  display: grid;
  gap: var(--space-2);
}

.notices:empty {
  display: none;
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

.context {
  color: var(--color-text-secondary);
}

.children {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-height: 0;
}

.list,
.grid {
  flex: 1;
  min-height: 0;
}
</style>
