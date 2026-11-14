<script setup lang="ts">
import { useReaderStore } from "../../stores/reader";
import type { ReadingDirection } from "../../utils/pagedNav";
import {
  BACKGROUND_LABELS,
  DIRECTION_LABELS,
  FIT_LABELS,
  MODE_LABELS,
  PAGE_FITS,
  PRELOAD_COUNTS,
  READER_BACKGROUNDS,
  READER_MODES,
  READING_DIRECTIONS,
  STRIP_WIDTHS,
  stripWidthLabel,
  type PageFit,
  type ReaderBackground,
  type ReaderMode,
} from "../../utils/readerSettings";

/**
 * The reader's global settings (Phase 5 Stage 5), kept on this device (`stores/reader.ts`): in Settings → Reader and
 * in the reader's settings sheet ("All manga"). A manga's own mode and direction come first (its meta), and for its
 * mode, its genres and its source's name.
 */
const props = defineProps<{ idPrefix: string }>();
const reader = useReaderStore();

const id = (name: string) => `${props.idPrefix}-${name}`;
const value = (e: Event) => (e.target as HTMLSelectElement).value;
</script>

<template>
  <div class="prefs" data-testid="reader-prefs">
    <div class="row">
      <label class="text" :for="id('mode')">
        <span>Default mode</span>
        <span class="summary">
          For a manga with no mode of its own. Long Strip, Webtoon, Manhwa and Manhua titles, and
          webtoon sites, open in Webtoon.
        </span>
      </label>
      <select
        :id="id('mode')"
        class="field"
        data-pref="mode"
        :value="reader.prefs.mode"
        @change="reader.set({ mode: value($event) as ReaderMode })"
      >
        <option v-for="m in READER_MODES" :key="m" :value="m">{{ MODE_LABELS[m] }}</option>
      </select>
    </div>
    <div class="row">
      <label class="text" :for="id('direction')">
        <span>Default direction</span>
        <span class="summary"
          >Single and Double pages, for a manga with no direction of its own.</span
        >
      </label>
      <select
        :id="id('direction')"
        class="field"
        data-pref="direction"
        :value="reader.prefs.direction"
        @change="reader.set({ direction: value($event) as ReadingDirection })"
      >
        <option v-for="d in READING_DIRECTIONS" :key="d" :value="d">
          {{ DIRECTION_LABELS[d] }}
        </option>
      </select>
    </div>
    <div class="row">
      <label class="text" :for="id('fit')">
        <span>Page fit</span>
        <span class="summary">
          Single and Double pages. A page bigger than the window scrolls before the next one.
        </span>
      </label>
      <select
        :id="id('fit')"
        class="field"
        data-pref="fit"
        :value="reader.prefs.fit"
        @change="reader.set({ fit: value($event) as PageFit })"
      >
        <option v-for="f in PAGE_FITS" :key="f" :value="f">{{ FIT_LABELS[f] }}</option>
      </select>
    </div>
    <div class="row">
      <label class="text" :for="id('strip-width')">
        <span>Strip width</span>
        <span class="summary">Webtoon: the widest the pages get; narrower windows fit them.</span>
      </label>
      <select
        :id="id('strip-width')"
        class="field"
        data-pref="stripWidth"
        :value="reader.prefs.stripWidth"
        @change="reader.set({ stripWidth: Number(value($event)) })"
      >
        <option v-for="w in STRIP_WIDTHS" :key="w" :value="w">{{ stripWidthLabel(w) }}</option>
      </select>
    </div>
    <div class="row">
      <label class="text" :for="id('page-gap')">
        <span>Page gap</span>
        <span class="summary">Webtoon: a space between pages.</span>
      </label>
      <input
        :id="id('page-gap')"
        type="checkbox"
        class="check"
        data-pref="pageGap"
        :checked="reader.prefs.pageGap"
        @change="reader.set({ pageGap: ($event.target as HTMLInputElement).checked })"
      />
    </div>
    <div class="row">
      <label class="text" :for="id('background')">
        <span>Background</span>
        <span class="summary">Around and between the pages.</span>
      </label>
      <select
        :id="id('background')"
        class="field"
        data-pref="background"
        :value="reader.prefs.background"
        @change="reader.set({ background: value($event) as ReaderBackground })"
      >
        <option v-for="b in READER_BACKGROUNDS" :key="b" :value="b">
          {{ BACKGROUND_LABELS[b] }}
        </option>
      </select>
    </div>
    <div class="row">
      <label class="text" :for="id('preload')">
        <span>Pages to preload</span>
        <span class="summary"
          >Loaded ahead of the page you're on; the backend fetches more after them.</span
        >
      </label>
      <select
        :id="id('preload')"
        class="field"
        data-pref="preload"
        :value="reader.prefs.preload"
        @change="reader.set({ preload: Number(value($event)) })"
      >
        <option v-for="n in PRELOAD_COUNTS" :key="n" :value="n">{{ n }} pages</option>
      </select>
    </div>
    <div class="row">
      <div class="text">
        <span>Reset</span>
        <span class="summary">These settings back to their defaults. Each manga's own stay.</span>
      </div>
      <button type="button" class="btn" data-action="reset-reader-prefs" @click="reader.reset()">
        Reset to defaults
      </button>
    </div>
  </div>
</template>

<style scoped>
.prefs {
  display: grid;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
}

.row + .row {
  border-top: 1px solid var(--color-border);
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.summary {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.field {
  flex: none;
}

.check {
  width: 18px;
  height: 18px;
  accent-color: var(--color-accent);
}
</style>
