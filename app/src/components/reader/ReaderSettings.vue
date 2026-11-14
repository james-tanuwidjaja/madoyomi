<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { ReadingDirection } from "../../utils/pagedNav";
import {
  DIRECTION_LABELS,
  MODE_LABELS,
  modeOriginLabel,
  READER_MODES,
  READING_DIRECTIONS,
  type MangaReaderPrefs,
  type ModeOrigin,
  type ReaderMode,
} from "../../utils/readerSettings";
import ErrorNotice from "../ErrorNotice.vue";
import ReaderPrefsFields from "./ReaderPrefsFields.vue";

/**
 * The reader's settings sheet (Phase 5 Stage 5, step 11), from the toolbar's Settings button: "This manga" (its own
 * mode and direction, kept in its meta, each with "Use default", and a reset) and "All manga" (the global settings on
 * this device, as in Settings → Reader). It says where the mode shown comes from: the manga's own, its genres, its
 * source's name or the default. Esc or Close closes it; the reader's keys and wheel leave it alone meanwhile.
 */
const props = defineProps<{
  mangaTitle: string;
  own: MangaReaderPrefs;
  mode: ReaderMode;
  origin: ModeOrigin;
  direction: ReadingDirection;
  /** What "Use default" gives this manga: the mode by genre, source or the global default, and the global direction. */
  fallbackMode: ReaderMode;
  fallbackDirection: ReadingDirection;
  /** A manga setting that couldn't be saved. */
  error: unknown;
}>();

const emit = defineEmits<{
  setMode: [mode: ReaderMode | null];
  setDirection: [direction: ReadingDirection | null];
  resetManga: [];
  close: [];
}>();

const value = (e: Event) => (e.target as HTMLSelectElement).value;
const hasOwn = computed(() => props.own.mode !== null || props.own.direction !== null);

const closeButton = ref<HTMLButtonElement | null>(null);
onMounted(() => closeButton.value?.focus());
</script>

<template>
  <aside
    class="sheet"
    role="dialog"
    aria-labelledby="reader-settings-title"
    data-reader-sheet
    data-testid="reader-settings-sheet"
    @click.stop
  >
    <header class="head">
      <h2 id="reader-settings-title">Reader settings</h2>
      <button
        ref="closeButton"
        type="button"
        class="btn"
        data-action="close-settings"
        @click="emit('close')"
      >
        Close
      </button>
    </header>

    <section class="group" aria-labelledby="reader-settings-manga">
      <h3 id="reader-settings-manga">This manga</h3>
      <p class="for">{{ mangaTitle }}</p>
      <div class="card">
        <div class="row">
          <label class="text" for="reader-manga-mode">
            <span>Mode</span>
            <span class="summary" data-testid="mode-origin">
              {{ MODE_LABELS[mode] }} · {{ modeOriginLabel(origin) }}
            </span>
          </label>
          <select
            id="reader-manga-mode"
            class="field"
            data-manga-pref="mode"
            :value="own.mode ?? ''"
            @change="emit('setMode', (value($event) || null) as ReaderMode | null)"
          >
            <option value="">Default ({{ MODE_LABELS[fallbackMode] }})</option>
            <option v-for="m in READER_MODES" :key="m" :value="m">{{ MODE_LABELS[m] }}</option>
          </select>
        </div>
        <div class="row">
          <label class="text" for="reader-manga-direction">
            <span>Direction</span>
            <span class="summary">
              {{ DIRECTION_LABELS[direction] }} ·
              {{ own.direction ? "This manga's" : "The default" }}
            </span>
          </label>
          <select
            id="reader-manga-direction"
            class="field"
            data-manga-pref="direction"
            :value="own.direction ?? ''"
            @change="emit('setDirection', (value($event) || null) as ReadingDirection | null)"
          >
            <option value="">Default ({{ DIRECTION_LABELS[fallbackDirection] }})</option>
            <option v-for="d in READING_DIRECTIONS" :key="d" :value="d">
              {{ DIRECTION_LABELS[d] }}
            </option>
          </select>
        </div>
        <div class="row">
          <div class="text">
            <span>Reset this manga</span>
            <span class="summary"
              >Back to the defaults. The toolbar's switches set this manga's own.</span
            >
          </div>
          <button
            type="button"
            class="btn"
            data-action="reset-manga-prefs"
            :disabled="!hasOwn"
            @click="emit('resetManga')"
          >
            Reset
          </button>
        </div>
      </div>
      <ErrorNotice
        v-if="error"
        title="Couldn't save this manga's reader settings"
        :error="error"
        data-testid="manga-prefs-error"
      />
    </section>

    <section class="group" aria-labelledby="reader-settings-all">
      <h3 id="reader-settings-all">All manga</h3>
      <div class="card">
        <ReaderPrefsFields id-prefix="reader-sheet" />
      </div>
    </section>
  </aside>
</template>

<style scoped>
.sheet {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  display: grid;
  align-content: start;
  gap: var(--space-4);
  width: min(460px, 100%);
  padding: var(--space-4);
  overflow-y: auto;
  border-left: 1px solid var(--color-border);
  background: var(--color-surface-sunken, var(--color-surface));
  color: var(--color-text);
  box-shadow: -8px 0 24px rgb(0 0 0 / 35%);
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

h2,
h3 {
  margin: 0;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
}

h2 {
  font-size: var(--font-size-body-large);
}

.group {
  display: grid;
  gap: var(--space-2);
}

.for {
  margin: 0;
  overflow: hidden;
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
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
</style>
