<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import type { PageSize, PageState } from "../../composables/usePageLoader";
import {
  anchorAt,
  atStripEnd,
  itemHeight,
  itemsBetween,
  itemStart,
  pageHeights,
  scrollAmount,
  scrollForAnchor,
  shownPosition,
  stripLayout,
  stripPagesToLoad,
  stripWidth,
  stripWindow,
  topAnchor,
  type Anchor,
} from "../../utils/webtoonNav";
import ChapterPanel from "./ChapterPanel.vue";
import PageFailure from "./PageFailure.vue";

/**
 * The webtoon reader (Phase 5 Stage 3, step 7): the chapter as one vertical strip, scrolled natively (the wheel, the
 * scrollbar, a trackpad), as wide as the window up to 800 px. Only the items near the viewport are in the DOM; each
 * page is as tall as its image's ratio makes it (utils/webtoonNav.ts), a page not loaded yet as tall as the median of
 * the known ones. When a page's real size arrives, or the window or the gap changes, the strip keeps its anchor still:
 * after a seek the page's top stays at the viewport's top, after a scroll the point at the viewport's centre.
 *
 * It reports the page at the viewport's top (`position`) and the pages to load around the viewport (`window`), and
 * follows a `position` that it didn't report (the slider, Home/End, a mode switch) by scrolling that page's top to
 * the viewport's top. Above the first page is the "Previous: …" panel (when there is a previous chapter), below the
 * last one the end panel, a viewport tall. A click toggles the toolbar; a failed page's click fetches it again.
 *
 * Stage 4: `edges()` says whether it is at its top or bottom, for the reader's wheel past the ends (the seamless
 * next chapter); the reader keys the strip by chapter, so each chapter gets a new one.
 */
const props = defineProps<{
  position: number;
  pageCount: number;
  pageState: (index: number) => PageState | undefined;
  pageSize: (index: number) => PageSize | undefined;
  /** Between items, px. */
  gap: number;
  chapterName: string;
  mangaTitle: string;
  previousName: string | null;
  nextName: string | null;
}>();

const emit = defineEmits<{
  position: [position: number];
  window: [pages: number[]];
  menu: [];
  /** The user scrolled (not the strip keeping its anchor). */
  scrolled: [];
  retry: [index: number];
  nextChapter: [];
  previousChapter: [];
  back: [];
}>();

const scroller = ref<HTMLElement | null>(null);
const viewport = reactive({ width: 0, height: 0 });
const scrollTop = ref(0);
let anchor: Anchor = topAnchor(props.position);
/** The scrollTop the strip set itself, until its scroll event arrives. */
let programmatic: number | null = null;

const width = computed(() => stripWidth(viewport.width));
/** Set once the strip is measured and scrolled to its first position: nothing is reported before. */
const started = ref(false);
const ready = computed(() => started.value && viewport.height > 0 && width.value > 0);
const layout = computed(() =>
  stripLayout({
    pageHeights: pageHeights(props.pageCount, width.value, props.pageSize),
    hasPrevious: props.previousName !== null,
    endHeight: viewport.height,
    gap: props.gap,
  }),
);

const shown = computed(() =>
  ready.value ? shownPosition(layout.value, scrollTop.value, viewport.height) : props.position,
);
const wanted = computed(() =>
  ready.value ? stripPagesToLoad(layout.value, scrollTop.value, viewport.height) : [],
);
const wantedKey = computed(() => wanted.value.join(","));

/** The items in the DOM: the band of the load window, and every page being loaded. */
const band = computed((): [number, number] => {
  if (!ready.value) return [0, -1];
  const w = stripWindow(viewport.height);
  const [a, b] = itemsBetween(
    layout.value,
    scrollTop.value - w.behind,
    scrollTop.value + viewport.height + w.ahead,
  );
  const ws = wanted.value;
  return [Math.min(a, ...ws), Math.max(b, ...ws)];
});
const renderFrom = computed(() => band.value[0]);
const renderTo = computed(() => band.value[1]);
const items = computed(() => {
  const out: { position: number; top: number; height: number }[] = [];
  for (let p = renderFrom.value; p <= renderTo.value; p++) {
    out.push({ position: p, top: itemStart(layout.value, p), height: itemHeight(layout.value, p) });
  }
  return out;
});

/** Pages in the viewport that aren't shown yet (the dev stats count the frames with one). */
const blank = computed(() => {
  if (!ready.value) return 0;
  const [a, b] = itemsBetween(layout.value, scrollTop.value, scrollTop.value + viewport.height);
  let n = 0;
  for (let p = Math.max(a, 0); p <= Math.min(b, props.pageCount - 1); p++) {
    if (props.pageState(p)?.status !== "loaded") n++;
  }
  return n;
});

const isPage = (p: number) => p >= 0 && p < props.pageCount;

function setScroll(y: number) {
  const el = scroller.value;
  if (!el) return;
  // Already there: no scroll event would come, and a smooth key scroll under way isn't cut short.
  if (Math.abs(el.scrollTop - y) >= 0.5) {
    el.scrollTop = y;
    // What the browser took (it rounds to device pixels, and clamps), so its scroll event is known as the strip's.
    programmatic = el.scrollTop;
  }
  scrollTop.value = el.scrollTop;
}

function applyAnchor() {
  if (ready.value) setScroll(scrollForAnchor(layout.value, anchor, viewport.height));
}

/** Puts `position`'s top at the viewport's top, and keeps it there while pages above it load. */
function seek(position: number) {
  anchor = topAnchor(position);
  applyAnchor();
}

function onScroll() {
  const el = scroller.value;
  if (!el) return;
  const y = el.scrollTop;
  const own = programmatic !== null && Math.abs(y - programmatic) < 1;
  programmatic = null;
  scrollTop.value = y;
  if (own) return;
  anchor = anchorAt(layout.value, y, viewport.height, 0.5);
  emit("scrolled");
}

function measure() {
  const el = scroller.value;
  if (!el) return;
  viewport.width = el.clientWidth;
  viewport.height = el.clientHeight;
}

/**
 * Scrolls a line or a screen down (1) or up (-1), smoothly. False at the strip's end that way (the reader then opens
 * the next or previous chapter).
 */
function scrollStep(kind: "line" | "screen", dir: 1 | -1): boolean {
  const el = scroller.value;
  if (!el || !ready.value) return false;
  if (dir > 0 && atStripEnd(layout.value, scrollTop.value, viewport.height)) return false;
  if (dir < 0 && scrollTop.value <= 1) return false;
  const top = dir * scrollAmount(kind, viewport.height);
  if (typeof el.scrollBy === "function") el.scrollBy({ top, behavior: "smooth" });
  else {
    el.scrollTop = el.scrollTop + top;
    el.dispatchEvent(new Event("scroll"));
  }
  return true;
}

/** Whether the strip is scrolled to its top or to its bottom (the reader's wheel then goes on into a chapter). */
function edges(): { top: boolean; bottom: boolean } {
  if (!ready.value) return { top: false, bottom: false };
  return {
    top: scrollTop.value <= 1,
    bottom: atStripEnd(layout.value, scrollTop.value, viewport.height),
  };
}

defineExpose({ scrollStep, edges });

// The pages' sizes, the window or the gap changed: the anchor goes back on its line. The strip's own scrollTop moves
// at once (so the page shown is never read from the old offset in the new layout), the element's once the DOM has
// the new heights (before, the browser would clamp it to the old ones).
let pendingScroll: number | null = null;
watch(
  layout,
  (l) => {
    if (!ready.value) return;
    pendingScroll = scrollForAnchor(l, anchor, viewport.height);
    scrollTop.value = pendingScroll;
  },
  { flush: "sync" },
);
watch(
  layout,
  () => {
    if (pendingScroll === null) return;
    const y = pendingScroll;
    pendingScroll = null;
    setScroll(y);
  },
  { flush: "post" },
);

watch(shown, (p) => emit("position", p));
watch(wantedKey, () => emit("window", wanted.value));
// After the DOM update, so the sizer already has the height of any layout change in the same tick (else the browser
// would clamp the seek to the old height).
watch(
  () => props.position,
  (p) => {
    if (ready.value && p !== shown.value) seek(p);
  },
  { flush: "post" },
);

let observer: ResizeObserver | null = null;
onMounted(async () => {
  measure();
  if (typeof ResizeObserver !== "undefined" && scroller.value) {
    observer = new ResizeObserver(measure);
    observer.observe(scroller.value);
  }
  // The sizer has its height in the DOM once rendered; then the first position is scrolled to.
  await nextTick();
  started.value = true;
  seek(props.position);
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div
    ref="scroller"
    class="strip"
    data-testid="webtoon-reader"
    :data-shown="shown"
    :data-blank="blank"
    @scroll.passive="onScroll"
    @click="emit('menu')"
  >
    <div class="sizer" :style="{ width: `${width}px`, height: `${layout.total}px` }">
      <div
        v-for="item in items"
        :key="item.position"
        class="item"
        :class="{ page: isPage(item.position) }"
        :data-position="item.position"
        :style="{ top: `${item.top}px`, height: `${item.height}px` }"
      >
        <template v-if="isPage(item.position)">
          <img
            v-if="pageState(item.position)?.status === 'loaded'"
            class="image"
            :src="(pageState(item.position) as { url: string }).url"
            :alt="`Page ${item.position + 1}`"
            draggable="false"
            data-testid="page-image"
          />
          <PageFailure
            v-else-if="pageState(item.position)?.status === 'error'"
            :index="item.position"
            :error="(pageState(item.position) as { error: unknown }).error"
            @retry="emit('retry', $event)"
          />
          <p v-else class="loading" data-testid="page-loading">
            Loading page {{ item.position + 1 }}…
          </p>
        </template>
        <ChapterPanel
          v-else
          :kind="item.position < 0 ? 'previous' : 'end'"
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
    </div>
  </div>
</template>

<style scoped>
.strip {
  position: absolute;
  inset: 0;
  overflow: hidden auto;
  overflow-anchor: none;
  overscroll-behavior: contain;
  outline: none;
  user-select: none;
}

.sizer {
  position: relative;
  max-width: 100%;
  margin: 0 auto;
}

.item {
  position: absolute;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  contain: strict;
}

.image {
  display: block;
  width: 100%;
  height: 100%;
}

.loading {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  width: 100%;
  height: 100%;
  margin: 0;
  padding-top: var(--space-6, 48px);
  background: rgb(255 255 255 / 4%);
  color: rgb(255 255 255 / 60%);
}
</style>
