<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { isApiError } from "../api/client";
import { useBackendStore } from "../stores/backend";
import { useDetailsPage } from "../composables/useDetailsPage";
import { useChapters, useManga, useRefreshManga } from "../composables/useEntries";
import { resumeChapter } from "../utils/pagedNav";
import ChapterList from "../components/ChapterList.vue";
import EntryHeader from "../components/EntryHeader.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import PageLayout from "../components/PageLayout.vue";

/*
 * A manga's details (Phase 3 step 15): the stored entry with its chapters, refreshed from the source (upstream's
 * `fetchManga`, then `fetchChapters`) by the button, and by itself the first time an entry that was never fetched is
 * opened. A source's failure is shown above the stored data, which stays.
 *
 * Phase 5 Stage 2: "Start reading" / "Continue reading" opens the first unread chapter in the source's order in the
 * reader (where it stopped, if it was begun), and the chapter rows open the reader too.
 */
const props = defineProps<{ id: number }>();
const backend = useBackendStore();

const manga = useManga(() => props.id);
const entry = computed(() => manga.data.value);
const chapters = useChapters(() => (entry.value ? props.id : null));

const page = useDetailsPage({
  kind: "manga",
  id: () => props.id,
  entry,
  refresh: useRefreshManga(),
});

const resume = computed(() => resumeChapter(chapters.data.value ?? []));

const notStored = computed(
  () => isApiError(manga.error.value) && manga.error.value.code === "NOT_FOUND",
);
</script>

<template>
  <PageLayout :title="entry ? undefined : 'Manga'" fill>
    <div v-if="notStored" class="notice" data-testid="not-stored">
      <p>This manga isn't stored. Open it from a source in Browse.</p>
      <RouterLink to="/browse">Browse your manga sources</RouterLink>
    </div>
    <ErrorNotice
      v-else-if="manga.isError.value"
      :error="manga.error.value"
      @retry="manga.refetch()"
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
        <template #actions>
          <RouterLink
            v-if="resume"
            class="btn btn-accent"
            :to="{ name: 'reader', params: { id, chapterId: resume.chapter.id } }"
            data-action="resume"
            :title="resume.chapter.name"
          >
            {{ resume.started ? `Continue: ${resume.chapter.name}` : "Start reading" }}
          </RouterLink>
          <button
            type="button"
            :class="entry.inLibrary || resume ? 'btn' : 'btn btn-accent'"
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

      <section class="children">
        <ErrorNotice
          v-if="chapters.isError.value"
          :error="chapters.error.value"
          @retry="chapters.refetch()"
        />
        <p v-else-if="chapters.isPending.value" class="muted">Loading the chapters…</p>
        <p
          v-else-if="page.refreshing.value && chapters.data.value?.length === 0"
          class="muted"
          data-testid="fetching"
        >
          Fetching the chapters from {{ page.sourceName.value }}…
        </p>
        <ChapterList v-else class="list" :manga-id="id" :chapters="chapters.data.value ?? []" />
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

.children {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-height: 0;
}

.list {
  flex: 1;
  min-height: 0;
}
</style>
