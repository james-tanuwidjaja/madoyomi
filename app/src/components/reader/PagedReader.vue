<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import type { PageState } from "../../composables/usePageLoader";
import { BEFORE, zoneAt, type ReadingDirection, type ZoneAction } from "../../utils/pagedNav";
import type { PageFit } from "../../utils/readerSettings";
import { scrollAmount } from "../../utils/webtoonNav";
import {
  CLICK_DELAY_MS,
  edgesOf,
  FIT,
  KEY_ZOOM_FACTOR,
  isZoomed,
  overflows,
  panBy,
  panStep,
  startView,
  toggleZoomAt,
  wheelZoomFactor,
  zoomAt,
  type Box,
  type Rect,
  type Zoom,
} from "../../utils/zoom";
import ChapterPanel from "./ChapterPanel.vue";
import PageFailure from "./PageFailure.vue";

/**
 * The paged reader (Phase 5 Stage 2, step 6): one page at a time, fitted to the window, with the click zones (the
 * outer thirds turn the page, the middle toggles the toolbar; reading right to left, the left side goes forward). A
 * page still loading shows its number; a page that failed shows the error, with the source's HTTP status, and a click
 * on it fetches it again. Around the pages are the two transition panels: "Previous chapter: …" before the first page
 * and "Finished … Next chapter: …" after the last one (or that there is no next chapter).
 *
 * Stage 4: the view is one page or a spread of two (the double-page mode, `pages`), side by side in the reading
 * direction (right to left, the first page on the right). Zoom and pan (step 10, utils/zoom.ts): a double-click in the
 * middle third zooms 2× there (its single click, the toolbar, waits {@link CLICK_DELAY_MS} for a second one; the side
 * thirds still turn at once); zoomed in, a drag pans, a click does nothing and a double-click anywhere goes back to
 * fit. The reader drives the rest through the exposed methods (Ctrl+wheel, the zoom keys, the wheel and the arrows
 * panning). A new view (a page turn, a mode switch) or a resize goes back to fit.
 *
 * Stage 5: the page fit (`fit`): the whole page (`screen`, Stage 2's), the window's width (each page of a spread half
 * of it), the window's height, or the page's own size. A page bigger than the window starts at its top, on the
 * reading direction's side, and pans at 1× (`scrollable`): a drag, and the reader's wheel and keys through
 * `scrollPage` and `pan`, before they turn the page. Zoom scales the fitted page; "back to fit" is that start view.
 */
const props = defineProps<{
  position: number;
  /** The pages shown, in reading order: one, two (a spread), or none on a panel. */
  pages: readonly number[];
  pageCount: number;
  direction: ReadingDirection;
  /** How a page fits the window (Stage 5); `screen` when not given. */
  fit?: PageFit;
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

/** A pointer that moves further than this between down and up is a drag (a pan), not a click. */
const DRAG_SLOP = 4;

const root = ref<HTMLElement | null>(null);
const content = ref<HTMLElement | null>(null);
const zoom = ref<Zoom>(FIT);
const zoomed = computed(() => isZoomed(zoom.value));
const panning = ref(false);
const fitClass = computed(() => `fit-${props.fit ?? "screen"}`);
/** No zoom or pan by the user since the view was (re)set: the view follows the layout to its start. */
let fresh = true;
/** The box and the pages' rect as last measured (after each layout change), for `scrollable`. */
const measured = shallowRef<{ box: Box; rect: Rect }>({
  box: { width: 0, height: 0 },
  rect: { left: 0, top: 0, width: 0, height: 0 },
});
/** At 1×, the fitted pages are bigger than the window: they pan before the reader turns the page. */
const scrollable = computed(
  () => !zoomed.value && overflows(1, measured.value.box, measured.value.rect),
);

const two = computed(() => props.pages.length > 1);
const stateOf = (i: number) => props.pageState(i);

function box(): Box {
  const el = root.value;
  return { width: el?.clientWidth ?? 0, height: el?.clientHeight ?? 0 };
}

/** The pages' rect in the box at scale 1 (layout offsets: the transform doesn't change them). */
function contentRect(): Rect {
  const items = content.value ? [...content.value.children] : [];
  let left = Infinity;
  let top = Infinity;
  let right = -Infinity;
  let bottom = -Infinity;
  for (const node of items) {
    const el = node as HTMLElement;
    left = Math.min(left, el.offsetLeft);
    top = Math.min(top, el.offsetTop);
    right = Math.max(right, el.offsetLeft + el.offsetWidth);
    bottom = Math.max(bottom, el.offsetTop + el.offsetHeight);
  }
  if (!Number.isFinite(left) || right <= left || bottom <= top) {
    const b = box();
    return { left: 0, top: 0, width: b.width, height: b.height };
  }
  return { left, top, width: right - left, height: bottom - top };
}

function pointIn(event: { clientX: number; clientY: number }) {
  const rect = root.value?.getBoundingClientRect();
  return { x: event.clientX - (rect?.left ?? 0), y: event.clientY - (rect?.top ?? 0) };
}

function measure() {
  measured.value = { box: box(), rect: contentRect() };
}

/** Back to the start view: the page whole, or its top on the reading direction's side when it is bigger. */
function resetZoom() {
  fresh = true;
  measure();
  zoom.value = startView(measured.value.box, measured.value.rect, props.direction);
}

function setZoom(z: Zoom) {
  fresh = false;
  zoom.value = z;
}

/** Zooms by `factor` about a point of the box (its centre when none is given). */
function zoomBy(factor: number, point?: { x: number; y: number }) {
  const b = box();
  const p = point ?? { x: b.width / 2, y: b.height / 2 };
  const z = zoomAt(zoom.value, zoom.value.scale * factor, p, b, contentRect());
  if (isZoomed(z)) setZoom(z);
  else resetZoom();
}

/** Moves the view by `dx`, `dy` px (positive: look further right or down); false when it was at that edge. */
function pan(dx: number, dy: number): boolean {
  const before = zoom.value;
  const z = panBy(before, -dx, -dy, box(), contentRect());
  if (Math.abs(z.x - before.x) < 0.5 && Math.abs(z.y - before.y) < 0.5) return false;
  setZoom(z);
  return true;
}

defineExpose({
  /** True while zoomed in (the reader then pans with the wheel and the arrows). */
  get zoomed() {
    return zoomed.value;
  },
  /** True while the fitted pages are bigger than the window at 1× (they scroll before a page turn). */
  get scrollable() {
    return scrollable.value;
  },
  /** Whether the view is at the pages' top and bottom (the wheel then turns the page). */
  edges() {
    return edgesOf(zoom.value, box(), contentRect());
  },
  /** A line or a screen down (1) or up (-1) through a page bigger than the window; false at its edge. */
  scrollPage(kind: "line" | "screen", dir: 1 | -1): boolean {
    return pan(0, dir * scrollAmount(kind, box().height));
  },
  resetZoom,
  /** Ctrl+wheel: zooms about the pointer. */
  zoomWheel(deltaY: number, clientX: number, clientY: number) {
    zoomBy(wheelZoomFactor(deltaY), pointIn({ clientX, clientY }));
  },
  /** Ctrl+= / Ctrl+−: one step in (1) or out (-1) about the centre. */
  zoomStep(dir: 1 | -1) {
    zoomBy(dir > 0 ? KEY_ZOOM_FACTOR : 1 / KEY_ZOOM_FACTOR);
  },
  pan,
  /** An arrow while zoomed: a step that way (-1, 0, 1 per axis). */
  panStep(dir: { x: number; y: number }) {
    const s = panStep(box());
    pan(dir.x * s.x, dir.y * s.y);
  },
});

// ---------------------------------------------------------------------------------------------------------------
// Clicks, double-clicks and drags

let menuTimer: ReturnType<typeof setTimeout> | null = null;
let drag: { id: number; x: number; y: number; moved: boolean } | null = null;
/** The click that ends a drag isn't a click. */
let swallowClick = false;

function cancelMenu() {
  if (menuTimer) clearTimeout(menuTimer);
  menuTimer = null;
}

function onClick(event: MouseEvent) {
  if (swallowClick) {
    swallowClick = false;
    return;
  }
  const el = event.currentTarget as HTMLElement;
  const rect = el.getBoundingClientRect();
  const point = { x: event.clientX - rect.left, y: event.clientY - rect.top };
  const second = event.detail >= 2;
  if (zoomed.value) {
    // Zoomed in: a click does nothing (a drag pans), a double-click goes back to fit.
    if (second) resetZoom();
    return;
  }
  const action = zoneAt(point.x, rect.width, props.direction);
  if (action !== "menu") {
    cancelMenu();
    emit("zone", action);
    return;
  }
  if (second || menuTimer) {
    // The middle third's double-click: zoom there instead of toggling the toolbar.
    cancelMenu();
    if (props.pages.length > 0) {
      const z = toggleZoomAt(zoom.value, point, box(), contentRect());
      if (isZoomed(z)) setZoom(z);
      else resetZoom();
    }
    return;
  }
  menuTimer = setTimeout(() => {
    menuTimer = null;
    emit("zone", "menu");
  }, CLICK_DELAY_MS);
}

function onPointerDown(event: PointerEvent) {
  if ((!zoomed.value && !scrollable.value) || event.button !== 0) return;
  drag = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false };
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
}

function onPointerMove(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.id) return;
  const dx = event.clientX - drag.x;
  const dy = event.clientY - drag.y;
  if (!drag.moved && Math.hypot(dx, dy) < DRAG_SLOP) return;
  drag.moved = true;
  panning.value = true;
  drag.x = event.clientX;
  drag.y = event.clientY;
  setZoom(panBy(zoom.value, dx, dy, box(), contentRect()));
}

function onPointerUp(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.id) return;
  if (drag.moved) swallowClick = true;
  drag = null;
  panning.value = false;
}

// A new view, or a window of another size: back to fit.
watch(
  () => `${props.position}:${props.pages.join(",")}`,
  () => {
    cancelMenu();
    fresh = true;
    zoom.value = FIT;
  },
);
// Once the DOM has the new view, a page loaded or the fit changed: a view not touched since goes to its start.
watch(
  () =>
    `${props.position}:${props.pages.map((i) => props.pageState(i)?.status ?? "").join(",")}:${props.fit}:${props.direction}`,
  () => {
    if (fresh) resetZoom();
    else measure();
  },
  { flush: "post" },
);

function onImageLoad() {
  if (fresh) resetZoom();
  else measure();
}
let observer: ResizeObserver | null = null;
onMounted(() => {
  // The pages may be in already (a mode switch, a chapter fetched ahead): start from their layout.
  resetZoom();
  if (typeof ResizeObserver !== "undefined" && root.value) {
    let first = true;
    observer = new ResizeObserver(() => {
      // The observer reports the first size at once: that isn't a resize.
      if (first) first = false;
      else resetZoom();
    });
    observer.observe(root.value);
  }
});
onBeforeUnmount(() => {
  cancelMenu();
  observer?.disconnect();
});

const layerStyle = computed(() =>
  zoom.value === FIT
    ? undefined
    : { transform: `translate(${zoom.value.x}px, ${zoom.value.y}px) scale(${zoom.value.scale})` },
);
</script>

<template>
  <div
    ref="root"
    class="paged"
    :class="{ zoomed, scrollable, panning }"
    data-testid="paged-reader"
    :data-direction="direction"
    :data-zoom="zoom.scale.toFixed(2)"
    :data-fit="fit ?? 'screen'"
    @click="onClick"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <div v-if="pages.length > 0" class="layer" :style="layerStyle" data-testid="zoom-layer">
      <div
        ref="content"
        class="spread"
        :class="[direction, fitClass, { two }]"
        :data-pages="pages.join(',')"
        data-testid="spread"
      >
        <template v-for="i in pages" :key="i">
          <img
            v-if="stateOf(i)?.status === 'loaded'"
            class="page"
            :src="(stateOf(i) as { url: string }).url"
            :alt="`Page ${i + 1}`"
            :data-page="i"
            draggable="false"
            data-testid="page-image"
            @load="onImageLoad"
          />
          <div v-else-if="stateOf(i)?.status === 'error'" class="slot" :data-page="i">
            <PageFailure
              :index="i"
              :error="(stateOf(i) as { error: unknown }).error"
              @retry="emit('retry', $event)"
            />
          </div>
          <p v-else class="slot placeholder" :data-page="i" data-testid="page-loading">
            Loading page {{ i + 1 }}…
          </p>
        </template>
      </div>
    </div>

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
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  user-select: none;
  touch-action: none;
}

.paged.zoomed,
.paged.scrollable {
  cursor: grab;
}

.paged.panning {
  cursor: grabbing;
}

.layer {
  position: absolute;
  inset: 0;
  transform-origin: 0 0;
}

.spread {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}

.spread.rtl {
  flex-direction: row-reverse;
}

.page {
  display: block;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.spread.fit-screen.two > * {
  max-width: 50%;
}

/* The other fits can be bigger than the window: the layer pans them (utils/zoom.ts). */
.fit-width .page,
.fit-height .page,
.fit-original .page {
  flex: none;
  max-width: none;
  max-height: none;
}

.fit-width .page {
  width: 100%;
  height: auto;
}

.fit-width.two .page {
  width: 50%;
}

.fit-height .page {
  width: auto;
  height: 100%;
}

.slot {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
}

.spread.two > .slot {
  flex: 0 1 50%;
  align-self: stretch;
}

.placeholder {
  margin: 0;
  padding: var(--space-5);
  color: var(--reader-muted, rgb(255 255 255 / 70%));
  text-align: center;
}
</style>
