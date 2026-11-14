<script setup lang="ts">
import { computed } from "vue";
import type { ReadingDirection } from "../../utils/pagedNav";
import { READER_MODES, type ReaderMode } from "../../stores/reader";

/**
 * The reader's toolbar (Phase 5 Stage 2, a first step 11): back to the manga and the chapter's name at the top; the
 * previous and next chapter, the page slider with "12 / 92", the reading direction and fullscreen at the bottom.
 * Reading right to left, the slider fills from the right and the chapter buttons swap sides, as in Mihon. Stage 3
 * adds the mode switch (paged / webtoon) and, in the webtoon mode, the page gap; the direction is the paged mode's
 * only, and the webtoon slider fills from the left. The settings come in Stage 5.
 *
 * Stage 4: three modes in a segmented control (Single, Double, Webtoon); the direction in both paged modes, and in
 * the double-page one "Shift" and a spread's label ("4–5 / 92"). A mouse click on a button leaves the focus where it
 * was (the reader), so Space then turns the page instead of pressing the button again; Tab still reaches every
 * button, and Space or Enter presses the focused one. The slider gives the focus back once a pointer lets it go.
 */
const props = defineProps<{
  mangaTitle: string;
  chapterName: string;
  /** The page index shown; a transition panel shows as the nearest page. */
  position: number;
  pageCount: number;
  direction: ReadingDirection;
  mode: ReaderMode;
  pageGap: boolean;
  /** The double-page "shift by one". */
  shift: boolean;
  /** The spread shown (its first and last page), in the double-page mode. */
  spread?: readonly [number, number] | null;
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
  setMode: [mode: ReaderMode];
  togglePageGap: [];
  toggleShift: [];
  toggleFullscreen: [];
}>();

const MODE_LABELS: Record<ReaderMode, string> = {
  paged: "Single",
  double: "Double",
  webtoon: "Webtoon",
};

const paged = computed(() => props.mode !== "webtoon");
/** The slider's and the buttons' order: the reading direction in the paged modes, top to bottom (left) in the strip. */
const flow = computed(() => (paged.value ? props.direction : "ltr"));

const page = computed(() =>
  props.pageCount === 0 ? 0 : Math.min(Math.max(props.position, 0), props.pageCount - 1) + 1,
);
const label = computed(() => {
  if (props.position >= props.pageCount && props.pageCount > 0) {
    return `End · ${props.pageCount} pages`;
  }
  const s = props.spread;
  const shown = s && s[1] > s[0] ? `${s[0] + 1}–${s[1] + 1}` : `${page.value}`;
  return `${shown} / ${props.pageCount}`;
});

function onSlider(event: Event) {
  emit("seek", Number((event.target as HTMLInputElement).value) - 1);
}

/** A mouse click on a button doesn't take the focus from the reader (keyboard focus still reaches it). */
function keepFocus(event: MouseEvent) {
  if ((event.target as Element | null)?.closest("button")) event.preventDefault();
}

/** Once a pointer lets the slider go, the keys are the reader's again (Tab still focuses the slider). */
function releaseSlider(event: Event) {
  (event.target as HTMLElement).blur();
}
</script>

<template>
  <header class="bar top" data-testid="reader-top" @mousedown="keepFocus">
    <button type="button" class="btn" data-action="reader-back" @click="emit('back')">
      ← {{ mangaTitle }}
    </button>
    <span class="chapter" data-testid="reader-chapter">{{ chapterName }}</span>
  </header>
  <footer class="bar bottom" :class="flow" data-testid="reader-bottom" @mousedown="keepFocus">
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
      :dir="flow"
      :disabled="pageCount <= 1"
      aria-label="Page"
      data-testid="page-slider"
      @input="onSlider"
      @pointerup="releaseSlider"
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
    <div class="modes" role="group" aria-label="Reading mode" data-testid="reader-modes">
      <button
        v-for="m in READER_MODES"
        :key="m"
        type="button"
        class="btn seg"
        :data-action="`mode-${m}`"
        :aria-pressed="mode === m"
        @click="emit('setMode', m)"
      >
        {{ MODE_LABELS[m] }}
      </button>
    </div>
    <button
      v-if="mode === 'webtoon'"
      type="button"
      class="btn"
      data-action="page-gap"
      :aria-pressed="pageGap"
      @click="emit('togglePageGap')"
    >
      {{ pageGap ? "Page gap: on" : "Page gap: off" }}
    </button>
    <button
      v-if="mode === 'double'"
      type="button"
      class="btn"
      data-action="shift"
      :aria-pressed="shift"
      title="Shift the pairs by one page"
      @click="emit('toggleShift')"
    >
      {{ shift ? "Shift: on" : "Shift: off" }}
    </button>
    <button
      v-if="paged"
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

.bottom.rtl .modes,
.bottom.rtl [data-action="shift"],
.bottom.rtl [data-action="direction"],
.bottom.rtl [data-action="fullscreen"] {
  order: 5;
}

.modes {
  display: inline-flex;
}

.seg {
  border-radius: 0;
}

.seg:first-child {
  border-radius: var(--radius-sm) 0 0 var(--radius-sm);
}

.seg:last-child {
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

.seg + .seg {
  margin-left: -1px;
}

.seg[aria-pressed="true"] {
  background: var(--color-accent);
  color: var(--color-on-accent);
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
