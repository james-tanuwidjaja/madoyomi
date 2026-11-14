import { keyAction, type KeyAction, type KeyLike } from "./pagedNav";

/*
 * The webtoon reader's layout and navigation (Phase 5 Stage 3, step 7), pure so it is unit-tested without the view.
 *
 * The strip is a column of items: the "Previous: …" panel (only when there is a previous chapter), the pages, and the
 * end panel. Items are addressed by their position, as in the paged reader: -1 the previous panel, 0 to
 * pageCount - 1 the pages, pageCount the end panel. Every page is as wide as the strip and as tall as its image's
 * ratio makes it; a page whose image hasn't loaded yet is given the median ratio of the pages known so far, so the
 * strip's height is computed, never measured. When a page's real size arrives, the view keeps an anchor still (a
 * point of one item at a line of the viewport), so what the reader looks at doesn't move.
 *
 * The page "shown" (saved as progress, in the slider and the URL) is the one at the viewport's top edge: a page
 * opened at its top shows that page, so a chapter reopened where it was saved shows the same page again. The end
 * panel is at least a viewport tall, so every page can reach the top; at the bottom of the strip the end panel is
 * shown.
 */

/** The ratio (height / width) of a page before any page's size is known: about a manga page's. */
export const DEFAULT_PAGE_RATIO = 1.5;
/** The strip's widest (Webtoons' own images are 800 px wide); narrower windows fit it to their width. */
export const MAX_STRIP_WIDTH = 800;
export const PREVIOUS_PANEL_HEIGHT = 240;
/** The page gap when it is on (the toolbar's toggle). */
export const PAGE_GAP = 16;

export interface PageSize {
  width: number;
  height: number;
}

export interface StripLayout {
  /** The first position: -1 with the previous panel, else 0. */
  first: number;
  pageCount: number;
  /** Per item (`position - first`): its top and height, px. */
  starts: number[];
  heights: number[];
  total: number;
}

export function stripWidth(containerWidth: number, max = MAX_STRIP_WIDTH): number {
  return Math.max(0, Math.min(containerWidth, max));
}

/** The median ratio of the pages whose size is known, else {@link DEFAULT_PAGE_RATIO}. */
export function estimateRatio(ratios: readonly number[]): number {
  const known = ratios.filter((r) => Number.isFinite(r) && r > 0).sort((a, b) => a - b);
  if (known.length === 0) return DEFAULT_PAGE_RATIO;
  const mid = known.length >> 1;
  return known.length % 2 ? known[mid]! : (known[mid - 1]! + known[mid]!) / 2;
}

function ratioOf(size: PageSize | undefined): number | null {
  return size && size.width > 0 && size.height > 0 ? size.height / size.width : null;
}

/** Each page's height at `width`: by its own ratio when its size is known, else by the estimate. */
export function pageHeights(
  pageCount: number,
  width: number,
  sizeOf: (index: number) => PageSize | undefined,
): number[] {
  const ratios = Array.from({ length: pageCount }, (_, i) => ratioOf(sizeOf(i)));
  const estimate = estimateRatio(ratios.filter((r): r is number => r !== null));
  return ratios.map((r) => Math.max(1, Math.round(width * (r ?? estimate))));
}

export function stripLayout(opts: {
  pageHeights: readonly number[];
  hasPrevious: boolean;
  /** The end panel's height; give it at least the viewport's, so the last page can reach the top. */
  endHeight: number;
  gap?: number;
  previousHeight?: number;
}): StripLayout {
  const gap = opts.gap ?? 0;
  const heights = [
    ...(opts.hasPrevious ? [opts.previousHeight ?? PREVIOUS_PANEL_HEIGHT] : []),
    ...opts.pageHeights,
    Math.max(0, opts.endHeight),
  ];
  const starts: number[] = [];
  let y = 0;
  heights.forEach((h, i) => {
    if (i > 0) y += gap;
    starts.push(y);
    y += h;
  });
  return {
    first: opts.hasPrevious ? -1 : 0,
    pageCount: opts.pageHeights.length,
    starts,
    heights,
    total: y,
  };
}

/** The last position (the end panel). */
export function lastPosition(layout: StripLayout): number {
  return layout.pageCount;
}

export function itemStart(layout: StripLayout, position: number): number {
  return layout.starts[clampItem(layout, position) - layout.first]!;
}

export function itemHeight(layout: StripLayout, position: number): number {
  return layout.heights[clampItem(layout, position) - layout.first]!;
}

function clampItem(layout: StripLayout, position: number): number {
  return Math.min(Math.max(Math.round(position), layout.first), layout.pageCount);
}

export function maxScroll(layout: StripLayout, viewportHeight: number): number {
  return Math.max(0, layout.total - viewportHeight);
}

/** The position of the item at `y` px from the strip's top (a gap belongs to the item above it). */
export function positionAt(layout: StripLayout, y: number): number {
  const { starts } = layout;
  let lo = 0;
  let hi = starts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (starts[mid]! <= y) lo = mid;
    else hi = mid - 1;
  }
  return lo + layout.first;
}

/** Within this many px of the bottom, the strip counts as scrolled to the end (scrollTop may be fractional). */
const END_SLACK = 2;

/** The position shown: the item at the viewport's top edge, the end panel once the strip is scrolled to the end. */
export function shownPosition(
  layout: StripLayout,
  scrollTop: number,
  viewportHeight: number,
): number {
  const max = maxScroll(layout, viewportHeight);
  if (max > 0 && scrollTop >= max - END_SLACK) return lastPosition(layout);
  return positionAt(layout, Math.max(scrollTop, 0) + 0.5);
}

export function atStripEnd(
  layout: StripLayout,
  scrollTop: number,
  viewportHeight: number,
): boolean {
  return scrollTop >= maxScroll(layout, viewportHeight) - END_SLACK;
}

/** A point of one item (`fraction` of its height) held at a line of the viewport (`line`, 0 top to 1 bottom). */
export interface Anchor {
  position: number;
  fraction: number;
  line: number;
}

/** The item's top at the viewport's top: where a seek, an open or the slider puts a page. */
export function topAnchor(position: number): Anchor {
  return { position, fraction: 0, line: 0 };
}

/** The point of the strip at `line` of the viewport now (the view keeps the centre still while the user scrolls). */
export function anchorAt(
  layout: StripLayout,
  scrollTop: number,
  viewportHeight: number,
  line = 0.5,
): Anchor {
  const y = Math.max(scrollTop, 0) + line * viewportHeight;
  const position = positionAt(layout, y);
  const start = itemStart(layout, position);
  const h = itemHeight(layout, position);
  return { position, fraction: h > 0 ? Math.min(Math.max((y - start) / h, 0), 1) : 0, line };
}

/** The scrollTop that puts `anchor` back on its line in `layout`, kept inside the strip. */
export function scrollForAnchor(
  layout: StripLayout,
  anchor: Anchor,
  viewportHeight: number,
): number {
  const y =
    itemStart(layout, anchor.position) +
    anchor.fraction * itemHeight(layout, anchor.position) -
    anchor.line * viewportHeight;
  return Math.min(Math.max(y, 0), maxScroll(layout, viewportHeight));
}

/** The first and last positions of the items that meet the band [from, to) px. */
export function itemsBetween(layout: StripLayout, from: number, to: number): [number, number] {
  const a = positionAt(layout, Math.max(from, 0));
  const b = positionAt(layout, Math.max(Math.min(to, layout.total) - 0.5, 0));
  return [a, Math.max(a, b)];
}

export interface StripWindow {
  /** The viewport's height, px, behind and ahead of it to keep loaded. */
  behind: number;
  ahead: number;
  /** At least this many pages past the viewport, however tall the band. */
  minAhead: number;
  /** At most this many pages in all (the visible ones always). */
  max: number;
}

/**
 * The strip's load window: a screen behind, two ahead, and at least `preload − 2` pages past the viewport (the
 * preload count, Stage 5, counts from the page shown; the viewport already holds one or two): 2 with the default 4,
 * as in Stage 3.
 */
export function stripWindow(viewportHeight: number, preload = 4): StripWindow {
  const minAhead = Math.max(2, preload - 2);
  return {
    behind: viewportHeight,
    ahead: 2 * viewportHeight,
    minAhead,
    max: Math.max(16, minAhead + 4),
  };
}

/**
 * The pages to keep loaded, in the order to load them: the visible ones from the top, then the ones ahead (nearest
 * first) within `ahead` px of the viewport's bottom and at least `minAhead` of them, then the ones behind (nearest
 * first) within `behind` px of its top. The band is in pixels, so a chapter of a few very tall images holds one or
 * two, and one of short strips holds more.
 */
export function stripPagesToLoad(
  layout: StripLayout,
  scrollTop: number,
  viewportHeight: number,
  w: StripWindow = stripWindow(viewportHeight),
): number[] {
  const count = layout.pageCount;
  if (count <= 0 || viewportHeight <= 0) return [];
  const clampPage = (p: number) => Math.min(Math.max(p, 0), count - 1);
  const top = Math.max(scrollTop, 0);
  const bottom = top + viewportHeight;
  const [v0, v1] = itemsBetween(layout, top, bottom);
  const first = clampPage(v0);
  const last = clampPage(v1);
  const out: number[] = [];
  for (let p = first; p <= last; p++) out.push(p);
  const room = () => out.length < w.max;
  let ahead = 0;
  for (let p = last + 1; p < count && room(); p++) {
    if (itemStart(layout, p) >= bottom + w.ahead && ahead >= w.minAhead) break;
    out.push(p);
    ahead++;
  }
  for (let p = first - 1; p >= 0 && room(); p--) {
    if (itemStart(layout, p) + itemHeight(layout, p) <= top - w.behind) break;
    out.push(p);
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Keys

export type WebtoonKeyAction =
  Exclude<KeyAction, "next" | "previous"> | "lineDown" | "lineUp" | "screenDown" | "screenUp";

/**
 * The keys in the strip: ↓ and ↑ scroll a little, Page Down, Page Up and Space (Shift+Space back) a screen; Home and
 * End go to the first and last page, [ and ] to the previous and next chapter, M toggles the toolbar, F fullscreen,
 * Esc out (the paged reader's). ← and → do nothing here: the strip has no reading direction.
 */
export function webtoonKeyAction(e: KeyLike): WebtoonKeyAction | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null;
  switch (e.key) {
    case "ArrowDown":
      return "lineDown";
    case "ArrowUp":
      return "lineUp";
    case "PageDown":
      return "screenDown";
    case "PageUp":
      return "screenUp";
    case " ":
    case "Spacebar":
      return e.shiftKey ? "screenUp" : "screenDown";
    case "ArrowLeft":
    case "ArrowRight":
      return null;
  }
  const a = keyAction(e, "ltr");
  return a === "next" || a === "previous" ? null : a;
}

/** How far a key scrolls: a line is 15 % of the viewport (at least 60 px), a screen 90 % of it. */
export function scrollAmount(kind: "line" | "screen", viewportHeight: number): number {
  return kind === "line" ? Math.max(60, viewportHeight * 0.15) : viewportHeight * 0.9;
}

// ---------------------------------------------------------------------------------------------------------------
// The wheel past the strip's ends (Stage 4, the seamless next chapter)

/**
 * The wheel at the strip's ends: the strip scrolls natively, so at its bottom (the end panel fully shown) a further
 * wheel gesture down opens the next chapter, and at its top one up the previous chapter. Only a gesture that starts
 * at that end counts (after a pause of `gapMs`), so a fling that reaches the end doesn't carry on into the next
 * chapter; its deltas then add up to `threshold` px (one notch).
 */
export function createEdgeWheel({ threshold = 50, gapMs = 200 } = {}) {
  let lastEvent = -Infinity;
  let armed: 1 | -1 | 0 = 0;
  let sum = 0;
  return {
    /** `edge`: 1 at the strip's bottom, -1 at its top, 0 between; returns the chapter to open (1, -1) or 0. */
    push(deltaY: number, now: number, edge: 1 | -1 | 0): 1 | -1 | 0 {
      if (now - lastEvent > gapMs) {
        armed = edge;
        sum = 0;
      }
      lastEvent = now;
      if (armed === 0 || edge !== armed || Math.sign(deltaY) !== armed) {
        if (edge !== armed) armed = 0;
        return 0;
      }
      sum += Math.abs(deltaY);
      if (sum < threshold) return 0;
      const dir = armed;
      armed = 0;
      sum = 0;
      return dir;
    },
  };
}
