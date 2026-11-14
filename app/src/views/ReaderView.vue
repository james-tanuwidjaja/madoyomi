<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { fetchChapterPages, prefetchChapterPages } from "../api/operations";
import type { ChapterPages } from "../api/types";
import { useBackendStore } from "../stores/backend";
import { useReaderStore } from "../stores/reader";
import { queryKeys } from "../composables/queryKeys";
import { useChapters, useManga } from "../composables/useEntries";
import { useFullscreen } from "../composables/useFullscreen";
import { PAGES_AHEAD, usePageLoader } from "../composables/usePageLoader";
import { useReadingProgress } from "../composables/useReadingProgress";
import {
  adjacentChapter,
  BEFORE,
  clampPosition,
  createWheelGate,
  isPage,
  keyAction,
  nearEnd,
  NEXT_CHAPTER_PREFETCH_COUNT,
  parsePageQuery,
  prefetchRange,
  startPosition,
  step,
  wheelPixels,
  type KeyAction,
  type NavResult,
  type ZoneAction,
} from "../utils/pagedNav";
import {
  createEdgeWheel,
  PAGE_GAP,
  webtoonKeyAction,
  type WebtoonKeyAction,
} from "../utils/webtoonNav";
import {
  doubleStep,
  isWide,
  pairPages,
  progressPage,
  spreadOf,
  spreadPages,
  spreadPagesToLoad,
} from "../utils/doublePage";
import { panKeyDirection, zoomKeyAction } from "../utils/zoom";
import ErrorNotice from "../components/ErrorNotice.vue";
import PagedReader from "../components/reader/PagedReader.vue";
import ReaderStats from "../components/reader/ReaderStats.vue";
import ReaderToolbar from "../components/reader/ReaderToolbar.vue";
import WebtoonReader from "../components/reader/WebtoonReader.vue";

/*
 * The manga reader (Phase 5 Stage 2, steps 5, 6, 11–14): `/manga/:id/read/:chapterId?page=N`, outside the app shell.
 * It asks the source for the chapter's pages (upstream's `fetchChapterPages`, each time: image URLs may expire),
 * opens at `?page=`, else where reading stopped, and shows them in the paged mode. Clicks, keys and the wheel turn
 * the pages (utils/pagedNav.ts); past the last page is the next chapter, before the first the previous one. The page
 * shown is saved as the chapter's progress (useReadingProgress), and kept in the URL. The page loader fetches the
 * pages around the reader, at most 4 at a time, and the backend prefetches the next block and, near the end, the
 * next chapter's first pages.
 *
 * Stage 3: the webtoon mode (`WebtoonReader`), switched from the toolbar for the session. The strip reports the page
 * at its top as the position, and the pages to load around its viewport, which replace the paged ±N; the wheel
 * scrolls it natively, the keys scroll it by a line or a screen, and past its ends they open the next or previous
 * chapter. Switching modes keeps the page.
 *
 * Stage 4: the double-page mode (utils/doublePage.ts): the chapter in spreads of two pages (a wide page, and by
 * default the first, alone; "shift by one" pairs the first two); the position stays a page index and the spread that
 * holds it is shown, steps go spread by spread, progress counts the spread's last page. Zoom and pan in both paged
 * modes (PagedReader; Ctrl+wheel, Ctrl+=/−/0, and while zoomed the wheel and the arrows pan, Esc goes back to fit).
 * The seamless next chapter: near a chapter's end (and on its "Previous chapter" panel) the next (previous) chapter's
 * page list is fetched ahead, so one more step past the panel opens it at once, in place; the wheel at the strip's
 * bottom (top) does so in the webtoon mode.
 */
const props = defineProps<{ id: number; chapterId: number }>();

const route = useRoute();
const router = useRouter();
const backend = useBackendStore();
const reader = useReaderStore();
const fullscreen = useFullscreen();

const manga = useManga(() => props.id);
const chapters = useChapters(() => props.id);
const pagesQuery = useQuery({
  queryKey: computed(() => queryKeys.chapterPages(props.chapterId)),
  queryFn: () => fetchChapterPages(props.chapterId),
  enabled: computed(() => backend.ready && props.chapterId > 0),
  // Every open asks the source again (its image URLs may have expired), and the chapter's progress must be fresh.
  staleTime: 0,
  gcTime: 0,
});

/**
 * The next and previous chapters' page lists, fetched ahead (near the end, on the "Previous chapter" panel), so that
 * going on into one shows it at once while its own query asks the source again. Kept only for this reader: a list
 * is dropped once its chapter has been read here, so its progress is never stale.
 */
const adjacent = shallowRef<ChapterPages[]>([]);
const adjacentAsked = new Set<number>();

function fetchAdjacent(id: number) {
  if (adjacentAsked.has(id)) return;
  adjacentAsked.add(id);
  fetchChapterPages(id).then(
    (d) => {
      if (adjacentAsked.has(id)) {
        adjacent.value = [...adjacent.value.filter((a) => a.chapter.id !== id), d].slice(-2);
      }
    },
    (e: unknown) => {
      adjacentAsked.delete(id);
      console.debug("couldn't fetch the page list ahead", id, e);
    },
  );
}

watch(
  () => props.chapterId,
  (_, left) => {
    adjacentAsked.delete(left);
    adjacent.value = adjacent.value.filter((a) => a.chapter.id !== left);
  },
);

/** The data for the chapter of the route (not the previous one while the next loads): its query's, else fetched ahead. */
const data = computed(() => {
  const d = pagesQuery.data.value;
  if (d && d.chapter.id === props.chapterId) return d;
  return adjacent.value.find((a) => a.chapter.id === props.chapterId) ?? null;
});
const pages = computed(() => data.value?.pages ?? []);
const pageCount = computed(() => pages.value.length);
const chapter = computed(() => data.value?.chapter ?? null);
const chapterName = computed(
  () =>
    chapter.value?.name ??
    chapters.data.value?.find((c) => c.id === props.chapterId)?.name ??
    "Chapter",
);
const mangaTitle = computed(() => manga.data.value?.title ?? "Manga");

const listChapter = computed(
  () => chapters.data.value?.find((c) => c.id === props.chapterId) ?? chapter.value,
);
const previousChapter = computed(() =>
  listChapter.value ? adjacentChapter(chapters.data.value ?? [], listChapter.value, -1) : null,
);
const nextChapter = computed(() =>
  listChapter.value ? adjacentChapter(chapters.data.value ?? [], listChapter.value, 1) : null,
);
const neighbours = computed(() => ({
  hasPrevious: previousChapter.value !== null,
  hasNext: nextChapter.value !== null,
}));

/** Null until the chapter's pages are in; then a page index or a transition panel. */
const position = ref<number | null>(null);
watch(
  () => props.chapterId,
  () => {
    position.value = null;
  },
);
watch(
  data,
  (d) => {
    if (!d || position.value !== null) return;
    position.value = startPosition(d.chapter, d.pages.length, parsePageQuery(route.query.page));
  },
  { immediate: true },
);

/** The webtoon strip's pages around its viewport, most urgent first (null in the paged mode, or before it reports). */
const webtoonWindow = ref<number[] | null>(null);
watch(
  () => reader.mode,
  () => {
    webtoonWindow.value = null;
  },
);
/** The double-page mode's pages to load (the spread, the pages after it and before it). */
const doubleWindow = ref<number[] | null>(null);
const loader = usePageLoader({
  pages,
  position: () => position.value ?? 0,
  wanted: () =>
    reader.mode === "webtoon"
      ? webtoonWindow.value
      : reader.mode === "double"
        ? doubleWindow.value
        : null,
});

/** The double-page mode's spreads (null in the other modes): they change as the pages' sizes arrive. */
const spreadList = computed(() =>
  reader.mode === "double"
    ? pairPages(pageCount.value, (i) => isWide(loader.size(i)), !reader.shift)
    : null,
);
const currentSpread = computed(() => {
  const p = position.value;
  return spreadList.value && p !== null && isPage(p, pageCount.value)
    ? spreadOf(spreadList.value, p)
    : null;
});
/** The pages on screen in the paged modes, in reading order (none on a panel). */
const viewPages = computed(() => {
  const p = position.value;
  if (p === null || !isPage(p, pageCount.value)) return [];
  return currentSpread.value ? spreadPages(currentSpread.value) : [p];
});
watch(
  [spreadList, position],
  ([list, p]) => {
    doubleWindow.value = list && p !== null ? spreadPagesToLoad(list, p, pageCount.value) : null;
  },
  { immediate: true },
);
/** The page progress counts: the double-page spread's last page, else the position. */
const progressPosition = computed(() => {
  const p = position.value;
  if (p === null || !spreadList.value) return p;
  return progressPage(spreadList.value, p, pageCount.value);
});
const pager = ref<InstanceType<typeof PagedReader> | null>(null);
const strip = ref<InstanceType<typeof WebtoonReader> | null>(null);
// Back after a restart: the pages that failed while it was away are fetched again by themselves.
watch(
  () => backend.ready,
  (ready) => {
    if (ready) loader.retryTransient();
  },
);
const progress = useReadingProgress({ chapter, pageCount, position: progressPosition });

const toolbar = ref(true);

// Time to the first page shown (the dev stats; phase-5 step 15).
const stats = ref(false);
let openedAt = performance.now();
const firstPageMs = ref<number | null>(null);
watch(
  () => props.chapterId,
  () => {
    openedAt = performance.now();
    firstPageMs.value = null;
  },
);
watch(
  () => {
    const p = position.value;
    return p !== null && isPage(p, pageCount.value) && loader.state(p)?.status === "loaded";
  },
  (shown) => {
    if (shown && firstPageMs.value === null) {
      firstPageMs.value = Math.round(performance.now() - openedAt);
    }
  },
);

// The page in the URL, so a reload or a copied link opens it.
watch(position, (p) => {
  if (p === null || !isPage(p, pageCount.value)) return;
  const page = String(p + 1);
  if (route.query.page !== page) void router.replace({ query: { ...route.query, page } });
});

// ---------------------------------------------------------------------------------------------------------------
// Navigation

function openChapter(to: "next" | "previous") {
  const target = to === "next" ? nextChapter.value : previousChapter.value;
  if (!target) return;
  void router.replace({
    name: "reader",
    params: { id: props.id, chapterId: target.id },
    query: to === "previous" ? { page: "last" } : {},
  });
}

function apply(result: NavResult) {
  if (result.kind === "position") position.value = result.position;
  else if (result.kind === "chapter") openChapter(result.to);
}

function turn(delta: 1 | -1) {
  if (position.value === null) return;
  toolbar.value = false;
  apply(
    spreadList.value
      ? doubleStep(spreadList.value, position.value, pageCount.value, delta, neighbours.value)
      : step(position.value, pageCount.value, delta, neighbours.value),
  );
}

function seek(index: number) {
  if (position.value === null) return;
  position.value = clampPosition(index, pageCount.value, neighbours.value.hasPrevious);
}

function back() {
  const state = window.history.state as { back?: unknown } | null;
  if (state?.back) router.back();
  else void router.replace({ name: "manga", params: { id: props.id } });
}

function onZone(action: ZoneAction) {
  if (action === "menu") toolbar.value = !toolbar.value;
  else turn(action === "next" ? 1 : -1);
}

/** In the strip: a line or a screen down or up, and past its end the next (or previous) chapter. */
function scrollStrip(kind: "line" | "screen", dir: 1 | -1) {
  if (strip.value?.scrollStep(kind, dir)) toolbar.value = false;
  else if (strip.value) openChapter(dir > 0 ? "next" : "previous");
}

function onKey(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null;
  const tag = target?.tagName;
  if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA" || target?.isContentEditable) {
    return;
  }
  if (tag === "BUTTON" && (event.key === " " || event.key === "Enter")) return;
  if (import.meta.env.DEV && event.key === "`") {
    stats.value = !stats.value;
    return;
  }
  if (reader.mode !== "webtoon" && zoomKey(event)) return;
  const action: KeyAction | WebtoonKeyAction | null =
    reader.mode === "webtoon" ? webtoonKeyAction(event) : keyAction(event, reader.direction);
  if (!action) return;
  event.preventDefault();
  switch (action) {
    case "next":
      return turn(1);
    case "previous":
      return turn(-1);
    case "lineDown":
      return scrollStrip("line", 1);
    case "lineUp":
      return scrollStrip("line", -1);
    case "screenDown":
      return scrollStrip("screen", 1);
    case "screenUp":
      return scrollStrip("screen", -1);
    case "first":
      return seek(0);
    case "last":
      return seek(pageCount.value - 1);
    case "nextChapter":
      return openChapter("next");
    case "previousChapter":
      return openChapter("previous");
    case "menu":
      toolbar.value = !toolbar.value;
      return;
    case "fullscreen":
      return void fullscreen.toggle();
    case "escape":
      if (fullscreen.active.value) void fullscreen.exit();
      else back();
  }
}

/** The zoom keys in the paged modes, and while zoomed the arrows (pan) and Esc (back to fit). True when handled. */
function zoomKey(event: KeyboardEvent): boolean {
  const p = pager.value;
  if (!p) return false;
  const z = zoomKeyAction(event);
  if (z) {
    event.preventDefault();
    if (z === "zoomReset") p.resetZoom();
    else p.zoomStep(z === "zoomIn" ? 1 : -1);
    return true;
  }
  if (!p.zoomed) return false;
  const dir = panKeyDirection(event);
  if (dir) {
    event.preventDefault();
    p.panStep(dir);
    return true;
  }
  if (event.key === "Escape") {
    event.preventDefault();
    p.resetZoom();
    return true;
  }
  return false;
}

const wheel = createWheelGate();
const edgeWheel = createEdgeWheel();
function onWheel(event: WheelEvent) {
  const dy = wheelPixels(event.deltaY, event.deltaMode, window.innerHeight);
  if (reader.mode === "webtoon") {
    // The strip scrolls natively; at its bottom (top) a new gesture goes on into the next (previous) chapter.
    if (event.ctrlKey) {
      event.preventDefault();
      return;
    }
    const at = strip.value?.edges();
    if (!at) return;
    const edge = dy > 0 ? (at.bottom ? 1 : 0) : dy < 0 ? (at.top ? -1 : 0) : 0;
    const dir = edgeWheel.push(dy, performance.now(), edge);
    if (dir) {
      // That notch opens the chapter; it doesn't also scroll the new strip.
      event.preventDefault();
      openChapter(dir > 0 ? "next" : "previous");
    }
    return;
  }
  event.preventDefault();
  const p = pager.value;
  if (event.ctrlKey) {
    p?.zoomWheel(dy, event.clientX, event.clientY);
    return;
  }
  if (p?.zoomed) {
    const dx = wheelPixels(event.deltaX, event.deltaMode, window.innerWidth);
    if (event.shiftKey) p.pan(dx + dy, 0);
    else p.pan(dx, dy);
    return;
  }
  const dir = wheel.push(dy, performance.now());
  if (dir) turn(dir);
}

const surface = ref<HTMLElement | null>(null);
onMounted(() => {
  window.addEventListener("keydown", onKey);
  surface.value?.addEventListener("wheel", onWheel, { passive: false });
  surface.value?.focus();
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKey);
  surface.value?.removeEventListener("wheel", onWheel);
});

// ---------------------------------------------------------------------------------------------------------------
// The backend prefetch

const prefetchedBlock = new Map<number, number>();
const prefetchedNext = new Set<number>();

function prefetch(chapterId: number, from: number, count: number) {
  prefetchChapterPages(chapterId, from, count).catch((e: unknown) =>
    console.debug("prefetch failed", chapterId, e),
  );
}

watch([position, data, nextChapter], ([p, d, next]) => {
  if (p === null || !d) return;
  const count = d.pages.length;
  const range = prefetchRange(p, count, PAGES_AHEAD);
  if (range && prefetchedBlock.get(d.chapter.id) !== range.block) {
    prefetchedBlock.set(d.chapter.id, range.block);
    prefetch(d.chapter.id, range.from, range.count);
  }
  // The neighbours' page lists ahead, for the seamless transition.
  if (next && nearEnd(p, count)) fetchAdjacent(next.id);
  if (p === BEFORE && previousChapter.value) fetchAdjacent(previousChapter.value.id);
  if (next && nearEnd(p, count) && !prefetchedNext.has(d.chapter.id)) {
    prefetchedNext.add(d.chapter.id);
    prefetch(
      next.id,
      next.isRead ? 0 : Math.max(next.lastPageRead, 0),
      NEXT_CHAPTER_PREFETCH_COUNT,
    );
  }
});

// The window's title names the chapter.
watch(
  [chapterName, mangaTitle],
  ([c, m]) => {
    document.title = `${c} · ${m} · Madoyomi`;
  },
  { immediate: true },
);
</script>

<template>
  <div
    ref="surface"
    class="reader"
    tabindex="-1"
    data-testid="reader"
    :data-position="position ?? ''"
  >
    <h1 class="visually-hidden">{{ chapterName }} · {{ mangaTitle }}</h1>

    <div v-if="pagesQuery.isError.value && !data" class="state">
      <ErrorNotice
        :title="`Couldn't open ${chapterName}`"
        :error="pagesQuery.error.value"
        @retry="pagesQuery.refetch()"
      />
      <button type="button" class="btn" @click="back">Back to {{ mangaTitle }}</button>
    </div>
    <p v-else-if="position === null" class="state muted" data-testid="reader-loading">
      {{ backend.ready ? `Loading ${chapterName}…` : "Waiting for the backend…" }}
    </p>
    <WebtoonReader
      v-else-if="reader.mode === 'webtoon'"
      :key="`strip-${chapterId}`"
      ref="strip"
      :position="position"
      :page-count="pageCount"
      :page-state="loader.state"
      :page-size="loader.size"
      :gap="reader.pageGap ? PAGE_GAP : 0"
      :chapter-name="chapterName"
      :manga-title="mangaTitle"
      :previous-name="previousChapter?.name ?? null"
      :next-name="nextChapter?.name ?? null"
      @position="position = $event"
      @window="webtoonWindow = $event"
      @menu="toolbar = !toolbar"
      @scrolled="toolbar = false"
      @retry="loader.retry"
      @next-chapter="openChapter('next')"
      @previous-chapter="openChapter('previous')"
      @back="back"
    />
    <PagedReader
      v-else
      :key="`paged-${chapterId}`"
      ref="pager"
      :position="position"
      :pages="viewPages"
      :page-count="pageCount"
      :direction="reader.direction"
      :page-state="loader.state"
      :chapter-name="chapterName"
      :manga-title="mangaTitle"
      :previous-name="previousChapter?.name ?? null"
      :next-name="nextChapter?.name ?? null"
      @zone="onZone"
      @retry="loader.retry"
      @next-chapter="openChapter('next')"
      @previous-chapter="openChapter('previous')"
      @back="back"
    />

    <ReaderToolbar
      v-if="toolbar || position === null"
      :manga-title="mangaTitle"
      :chapter-name="chapterName"
      :position="position ?? 0"
      :page-count="pageCount"
      :direction="reader.direction"
      :mode="reader.mode"
      :page-gap="reader.pageGap"
      :shift="reader.shift"
      :spread="currentSpread"
      :has-previous="neighbours.hasPrevious"
      :has-next="neighbours.hasNext"
      :fullscreen="fullscreen.active.value"
      @back="back"
      @seek="seek"
      @previous-chapter="openChapter('previous')"
      @next-chapter="openChapter('next')"
      @toggle-direction="reader.toggleDirection()"
      @set-mode="reader.setMode($event)"
      @toggle-page-gap="reader.togglePageGap()"
      @toggle-shift="reader.toggleShift()"
      @toggle-fullscreen="fullscreen.toggle()"
    />

    <ReaderStats v-if="stats" :first-page-ms="firstPageMs" @close="stats = false" />

    <p v-if="progress.error.value" class="save-error" role="status" data-testid="save-error">
      Couldn't save your place in this chapter.
    </p>
  </div>
</template>

<style scoped>
.reader {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #111;
  color: #fff;
  outline: none;
}

.state {
  display: grid;
  justify-items: start;
  gap: var(--space-3);
  max-width: 560px;
  padding: var(--space-5);
}

.muted {
  color: rgb(255 255 255 / 60%);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

.save-error {
  position: absolute;
  top: 56px;
  right: var(--space-4);
  margin: 0;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--color-danger-surface);
  color: var(--color-danger);
}
</style>
