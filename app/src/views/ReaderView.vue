<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { fetchChapterPages, prefetchChapterPages } from "../api/operations";
import { useBackendStore } from "../stores/backend";
import { useReaderStore } from "../stores/reader";
import { queryKeys } from "../composables/queryKeys";
import { useChapters, useManga } from "../composables/useEntries";
import { useFullscreen } from "../composables/useFullscreen";
import { PAGES_AHEAD, usePageLoader } from "../composables/usePageLoader";
import { useReadingProgress } from "../composables/useReadingProgress";
import {
  adjacentChapter,
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
  type NavResult,
  type ZoneAction,
} from "../utils/pagedNav";
import ErrorNotice from "../components/ErrorNotice.vue";
import PagedReader from "../components/reader/PagedReader.vue";
import ReaderToolbar from "../components/reader/ReaderToolbar.vue";

/*
 * The manga reader (Phase 5 Stage 2, steps 5, 6, 11–14): `/manga/:id/read/:chapterId?page=N`, outside the app shell.
 * It asks the source for the chapter's pages (upstream's `fetchChapterPages`, each time: image URLs may expire),
 * opens at `?page=`, else where reading stopped, and shows them in the paged mode. Clicks, keys and the wheel turn
 * the pages (utils/pagedNav.ts); past the last page is the next chapter, before the first the previous one. The page
 * shown is saved as the chapter's progress (useReadingProgress), and kept in the URL. The page loader fetches the
 * pages around the reader, at most 4 at a time, and the backend prefetches the next block and, near the end, the
 * next chapter's first pages.
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

/** The data for the chapter of the route (not the previous one while the next loads). */
const data = computed(() => {
  const d = pagesQuery.data.value;
  return d && d.chapter.id === props.chapterId ? d : null;
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

const loader = usePageLoader({ pages, position: () => position.value ?? 0 });
// Back after a restart: the pages that failed while it was away are fetched again by themselves.
watch(
  () => backend.ready,
  (ready) => {
    if (ready) loader.retryTransient();
  },
);
const progress = useReadingProgress({ chapter, pageCount, position });

const toolbar = ref(true);

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
  apply(step(position.value, pageCount.value, delta, neighbours.value));
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

function onKey(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null;
  const tag = target?.tagName;
  if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA" || target?.isContentEditable) {
    return;
  }
  if (tag === "BUTTON" && (event.key === " " || event.key === "Enter")) return;
  const action = keyAction(event, reader.direction);
  if (!action) return;
  event.preventDefault();
  switch (action) {
    case "next":
      return turn(1);
    case "previous":
      return turn(-1);
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

const wheel = createWheelGate();
function onWheel(event: WheelEvent) {
  if (event.ctrlKey) return;
  event.preventDefault();
  const dir = wheel.push(
    wheelPixels(event.deltaY, event.deltaMode, window.innerHeight),
    performance.now(),
  );
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
    <PagedReader
      v-else
      :position="position"
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
      :has-previous="neighbours.hasPrevious"
      :has-next="neighbours.hasNext"
      :fullscreen="fullscreen.active.value"
      @back="back"
      @seek="seek"
      @previous-chapter="openChapter('previous')"
      @next-chapter="openChapter('next')"
      @toggle-direction="reader.toggleDirection()"
      @toggle-fullscreen="fullscreen.toggle()"
    />

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
