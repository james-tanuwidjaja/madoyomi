<script setup lang="ts">
import { computed } from "vue";
import type { PageState } from "../../composables/usePageLoader";
import { BEFORE, zoneAt, type ReadingDirection, type ZoneAction } from "../../utils/pagedNav";
import ChapterPanel from "./ChapterPanel.vue";
import PageFailure from "./PageFailure.vue";

/**
 * The paged reader (Phase 5 Stage 2, step 6): one page at a time, fitted to the window, with the click zones (the
 * outer thirds turn the page, the middle toggles the toolbar; reading right to left, the left side goes forward). A
 * page still loading shows its number; a page that failed shows the error, with the source's HTTP status, and a click
 * on it fetches it again. Around the pages are the two transition panels: "Previous: …" before the first page and
 * "Finished … Next: …" after the last one (or that there is no next chapter).
 */
const props = defineProps<{
  position: number;
  pageCount: number;
  direction: ReadingDirection;
  pageState: (index: number) => PageState | undefined;
  chapterName: string;
  mangaTitle: string;
  previousName: string | null;
  nextName: string | null;
}>();

const emit = defineEmits<{
  zone: [action: ZoneAction];
  retry: [index: number];
  nextChapter: [];
  previousChapter: [];
  back: [];
}>();

const state = computed(() =>
  props.position >= 0 && props.position < props.pageCount
    ? props.pageState(props.position)
    : undefined,
);

function onClick(event: MouseEvent) {
  const el = event.currentTarget as HTMLElement;
  const rect = el.getBoundingClientRect();
  emit("zone", zoneAt(event.clientX - rect.left, rect.width, props.direction));
}
</script>

<template>
  <div class="paged" data-testid="paged-reader" :data-direction="direction" @click="onClick">
    <template v-if="position >= 0 && position < pageCount">
      <img
        v-if="state?.status === 'loaded'"
        class="page"
        :src="state.url"
        :alt="`Page ${position + 1}`"
        draggable="false"
        data-testid="page-image"
      />
      <PageFailure
        v-else-if="state?.status === 'error'"
        :index="position"
        :error="state.error"
        @retry="emit('retry', $event)"
      />
      <p v-else class="placeholder" data-testid="page-loading">Loading page {{ position + 1 }}…</p>
    </template>

    <ChapterPanel
      v-else
      :kind="position === BEFORE ? 'previous' : 'end'"
      :chapter-name="chapterName"
      :manga-title="mangaTitle"
      :page-count="pageCount"
      :previous-name="previousName"
      :next-name="nextName"
      @next-chapter="emit('nextChapter')"
      @previous-chapter="emit('previousChapter')"
      @back="emit('back')"
    />
  </div>
</template>

<style scoped>
.paged {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  user-select: none;
}

.page {
  display: block;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.placeholder {
  margin: 0;
  padding: var(--space-5);
  color: rgb(255 255 255 / 70%);
  text-align: center;
}
</style>
