<script setup lang="ts">
import { computed } from "vue";
import { describeError } from "../../api/errorText";
import { isApiError } from "../../api/client";
import type { PageState } from "../../composables/usePageLoader";
import { BEFORE, zoneAt, type ReadingDirection, type ZoneAction } from "../../utils/pagedNav";

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

const failure = computed(() => {
  const s = state.value;
  if (s?.status !== "error") return null;
  const d = describeError(s.error);
  const details = isApiError(s.error) ? (s.error.details as { httpCode?: number } | null) : null;
  return { ...d, httpCode: details?.httpCode ?? null };
});

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
      <button
        v-else-if="failure"
        type="button"
        class="placeholder failed"
        data-testid="page-failed"
        @click.stop="emit('retry', position)"
      >
        <span class="title">Couldn't load page {{ position + 1 }}</span>
        <span v-if="failure.hint" class="hint">{{ failure.hint }}</span>
        <span class="message">
          <code v-if="failure.code">{{ failure.code }}</code>
          <template v-if="failure.httpCode"> HTTP {{ failure.httpCode }}:</template>
          {{ failure.message }}
        </span>
        <span class="retry">Click to retry</span>
      </button>
      <p v-else class="placeholder" data-testid="page-loading">Loading page {{ position + 1 }}…</p>
    </template>

    <div v-else-if="position === BEFORE" class="panel" data-testid="panel-previous">
      <p class="muted">Start of {{ chapterName }}</p>
      <p class="title">Previous: {{ previousName }}</p>
      <div class="actions">
        <button type="button" class="btn btn-accent" @click.stop="emit('previousChapter')">
          Read {{ previousName }}
        </button>
      </div>
    </div>

    <div v-else class="panel" data-testid="panel-end">
      <p class="muted">
        {{ pageCount === 0 ? `${chapterName} has no pages.` : `Finished: ${chapterName}` }}
      </p>
      <template v-if="nextName">
        <p class="title">Next: {{ nextName }}</p>
        <div class="actions">
          <button
            type="button"
            class="btn btn-accent"
            data-action="panel-next"
            @click.stop="emit('nextChapter')"
          >
            Read {{ nextName }}
          </button>
          <button type="button" class="btn" @click.stop="emit('back')">
            Back to {{ mangaTitle }}
          </button>
        </div>
      </template>
      <template v-else>
        <p class="title">There's no next chapter.</p>
        <div class="actions">
          <button
            type="button"
            class="btn btn-accent"
            data-action="panel-back"
            @click.stop="emit('back')"
          >
            Back to {{ mangaTitle }}
          </button>
        </div>
      </template>
    </div>
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
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  max-width: 480px;
  margin: 0;
  padding: var(--space-5);
  border: 0;
  border-radius: var(--radius-md);
  background: none;
  color: rgb(255 255 255 / 70%);
  font: inherit;
  text-align: center;
}

.placeholder.failed {
  border: 1px solid rgb(255 255 255 / 16%);
  background: rgb(255 255 255 / 6%);
  cursor: pointer;
}

.placeholder .title,
.panel .title {
  color: #fff;
  font-size: var(--font-size-body-large);
  font-weight: var(--font-weight-semibold);
}

.placeholder .retry {
  color: var(--color-accent);
}

.panel {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  padding: var(--space-5);
  color: #fff;
  text-align: center;
}

.panel p {
  margin: 0;
}

.muted {
  color: rgb(255 255 255 / 60%);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
}
</style>
