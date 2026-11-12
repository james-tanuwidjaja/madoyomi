<script setup lang="ts">
import { computed } from "vue";
import type { ReadingDirection } from "../../utils/pagedNav";

/**
 * The reader's toolbar (Phase 5 Stage 2, a first step 11): back to the manga and the chapter's name at the top; the
 * previous and next chapter, the page slider with "12 / 92", the reading direction and fullscreen at the bottom.
 * Reading right to left, the slider fills from the right and the chapter buttons swap sides, as in Mihon. The mode
 * switch and the settings come with the other modes (Stages 3–5).
 */
const props = defineProps<{
  mangaTitle: string;
  chapterName: string;
  /** The page index shown; a transition panel shows as the nearest page. */
  position: number;
  pageCount: number;
  direction: ReadingDirection;
  hasPrevious: boolean;
  hasNext: boolean;
  fullscreen: boolean;
}>();

const emit = defineEmits<{
  back: [];
  seek: [index: number];
  previousChapter: [];
  nextChapter: [];
  toggleDirection: [];
  toggleFullscreen: [];
}>();

const page = computed(() =>
  props.pageCount === 0 ? 0 : Math.min(Math.max(props.position, 0), props.pageCount - 1) + 1,
);
const label = computed(() =>
  props.position >= props.pageCount && props.pageCount > 0
    ? `End · ${props.pageCount} pages`
    : `${page.value} / ${props.pageCount}`,
);

function onSlider(event: Event) {
  emit("seek", Number((event.target as HTMLInputElement).value) - 1);
}
</script>

<template>
  <header class="bar top" data-testid="reader-top">
    <button type="button" class="btn" data-action="reader-back" @click="emit('back')">
      ← {{ mangaTitle }}
    </button>
    <span class="chapter" data-testid="reader-chapter">{{ chapterName }}</span>
  </header>
  <footer class="bar bottom" :class="direction" data-testid="reader-bottom">
    <button
      type="button"
      class="btn"
      data-action="previous-chapter"
      :disabled="!hasPrevious"
      @click="emit('previousChapter')"
    >
      Previous chapter
    </button>
    <input
      class="slider"
      type="range"
      min="1"
      :max="Math.max(pageCount, 1)"
      :value="page"
      :dir="direction"
      :disabled="pageCount <= 1"
      aria-label="Page"
      data-testid="page-slider"
      @input="onSlider"
    />
    <span class="count" data-testid="page-count">{{ label }}</span>
    <button
      type="button"
      class="btn"
      data-action="next-chapter"
      :disabled="!hasNext"
      @click="emit('nextChapter')"
    >
      Next chapter
    </button>
    <button
      type="button"
      class="btn"
      data-action="direction"
      :aria-label="
        direction === 'rtl'
          ? 'Right to left, switch to left to right'
          : 'Left to right, switch to right to left'
      "
      @click="emit('toggleDirection')"
    >
      {{ direction === "rtl" ? "Right to left" : "Left to right" }}
    </button>
    <button type="button" class="btn" data-action="fullscreen" @click="emit('toggleFullscreen')">
      {{ fullscreen ? "Exit fullscreen" : "Fullscreen" }}
    </button>
  </footer>
</template>

<style scoped>
.bar {
  position: absolute;
  right: 0;
  left: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  padding: var(--space-2) var(--space-4);
  background: rgb(32 32 32 / 92%);
  color: #fff;
}

.top {
  top: 0;
}

.bottom {
  bottom: 0;
  flex-wrap: wrap;
}

/* Right to left, the next chapter's button sits on the left, where the reading goes. */
.bottom.rtl [data-action="previous-chapter"] {
  order: 4;
}

.bottom.rtl [data-action="next-chapter"] {
  order: 0;
}

.bottom.rtl .slider {
  order: 1;
}

.bottom.rtl .count {
  order: 2;
}

.bottom.rtl [data-action="direction"],
.bottom.rtl [data-action="fullscreen"] {
  order: 5;
}

.chapter {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slider {
  flex: 1;
  min-width: 160px;
  accent-color: var(--color-accent);
}

.count {
  min-width: 7ch;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
</style>
