import type { KeyLike } from "./pagedNav";

/*
 * Zoom and pan in the paged and double-page modes (Phase 5 Stage 4, step 10), pure so it is unit-tested without the
 * view. The view is a box the size of the reader; the content (the page or the two pages, fitted to the box) is a
 * rect inside it at scale 1. A zoom is a scale from 1 (fit) to {@link MAX_ZOOM} and a translation of the box, applied
 * as `translate(x, y) scale(scale)` from its top-left corner. It is always clamped: the content's edges never come
 * inside the box, and along an axis where the scaled content is smaller than the box, it is centred.
 *
 * Stage 5: with a page fit other than "fit screen" (fit width, fit height, original size), the content can be bigger
 * than the box at scale 1. It then pans at scale 1 too (the same clamp), and a new page starts at its top, on the
 * reading direction's side ({@link startView}); the reader scrolls it before it turns the page ({@link edgesOf}).
 */

export const MIN_ZOOM = 1;
export const MAX_ZOOM = 4;
/** A double-click's zoom (in the middle third at fit). */
export const CLICK_ZOOM = 2;
/** How long the middle third's click waits for a second one (a double-click zooms) before it toggles the toolbar. */
export const CLICK_DELAY_MS = 250;
/** The zoom keys' factor (Ctrl+= and Ctrl+−). */
export const KEY_ZOOM_FACTOR = 1.25;

export interface Zoom {
  scale: number;
  x: number;
  y: number;
}

export interface Box {
  width: number;
  height: number;
}

export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export const FIT: Zoom = Object.freeze({ scale: 1, x: 0, y: 0 });

export function isZoomed(z: Zoom): boolean {
  return z.scale > MIN_ZOOM + 1e-3;
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), hi);
}

function clampAxis(t: number, scale: number, view: number, start: number, size: number): number {
  const scaled = size * scale;
  // `+ 0`: no -0 (it reads as 0 everywhere, but isn't equal to it in a deep comparison).
  if (scaled <= view) return (view - scaled) / 2 - start * scale + 0;
  return clamp(t, view - (start + size) * scale, -start * scale) + 0;
}

/** Whether the content at `scale` is bigger than the box along either axis (half a pixel of slack). */
export function overflows(scale: number, view: Box, content: Rect): boolean {
  return content.width * scale > view.width + 0.5 || content.height * scale > view.height + 0.5;
}

/** `z` kept inside the limits: the scale between fit and the maximum, and the content covering the box. */
export function clampZoom(z: Zoom, view: Box, content: Rect): Zoom {
  const scale = clamp(z.scale, MIN_ZOOM, MAX_ZOOM);
  if (scale <= MIN_ZOOM + 1e-3 && !overflows(MIN_ZOOM, view, content)) return FIT;
  return {
    scale,
    x: clampAxis(z.x, scale, view.width, content.left, content.width),
    y: clampAxis(z.y, scale, view.height, content.top, content.height),
  };
}

/** Zoomed to `scale` about `point` (in the box's coordinates): the content under the point stays under it. */
export function zoomAt(
  z: Zoom,
  scale: number,
  point: { x: number; y: number },
  view: Box,
  content: Rect,
): Zoom {
  const s = clamp(scale, MIN_ZOOM, MAX_ZOOM);
  const k = s / z.scale;
  return clampZoom(
    { scale: s, x: point.x - (point.x - z.x) * k, y: point.y - (point.y - z.y) * k },
    view,
    content,
  );
}

/** Moved by `dx`, `dy` px (the content moves with a drag), and clamped. No move while it fits the box. */
export function panBy(z: Zoom, dx: number, dy: number, view: Box, content: Rect): Zoom {
  if (!isZoomed(z) && !overflows(z.scale, view, content)) return z;
  return clampZoom({ scale: z.scale, x: z.x + dx, y: z.y + dy }, view, content);
}

/**
 * The view of a new page at scale 1: FIT when the content fits the box; else its top edge at the box's top, and
 * along a wider axis its start in the reading direction (the left edge left to right, the right edge right to left).
 */
export function startView(view: Box, content: Rect, direction: "ltr" | "rtl"): Zoom {
  if (!overflows(MIN_ZOOM, view, content)) return FIT;
  const x = direction === "rtl" ? view.width - (content.left + content.width) : -content.left;
  return clampZoom({ scale: MIN_ZOOM, x, y: -content.top }, view, content);
}

/** Whether the view is at the content's top, bottom, left and right edges (true along an axis that fits). */
export function edgesOf(
  z: Zoom,
  view: Box,
  content: Rect,
): { top: boolean; bottom: boolean; left: boolean; right: boolean } {
  const s = z.scale;
  const fitsY = content.height * s <= view.height + 0.5;
  const fitsX = content.width * s <= view.width + 0.5;
  return {
    top: fitsY || z.y >= -content.top * s - 0.5,
    bottom: fitsY || z.y <= view.height - (content.top + content.height) * s + 0.5,
    left: fitsX || z.x >= -content.left * s - 0.5,
    right: fitsX || z.x <= view.width - (content.left + content.width) * s + 0.5,
  };
}

/** Ctrl+wheel: the factor for a wheel delta in px (up zooms in; a 100 px notch is about 1.2×). */
export function wheelZoomFactor(deltaY: number): number {
  return Math.exp(-clamp(deltaY, -300, 300) * 0.002);
}

/** A double-click: fit when zoomed, else {@link CLICK_ZOOM} about the point. */
export function toggleZoomAt(
  z: Zoom,
  point: { x: number; y: number },
  view: Box,
  content: Rect,
): Zoom {
  return isZoomed(z) ? FIT : zoomAt(z, CLICK_ZOOM, point, view, content);
}

export type ZoomKeyAction = "zoomIn" | "zoomOut" | "zoomReset";

/** Ctrl+= (or Ctrl++) zooms in, Ctrl+− out, Ctrl+0 back to fit. */
export function zoomKeyAction(e: KeyLike): ZoomKeyAction | null {
  if (!e.ctrlKey || e.altKey || e.metaKey) return null;
  switch (e.key) {
    case "=":
    case "+":
      return "zoomIn";
    case "-":
    case "_":
      return "zoomOut";
    case "0":
      return "zoomReset";
    default:
      return null;
  }
}

/**
 * The arrows while zoomed: which way to look (the content moves the other way), as -1, 0 or 1 per axis. Null for
 * any other key, or with a modifier.
 */
export function panKeyDirection(e: KeyLike): { x: number; y: number } | null {
  if (e.ctrlKey || e.altKey || e.metaKey || e.shiftKey) return null;
  switch (e.key) {
    case "ArrowUp":
      return { x: 0, y: -1 };
    case "ArrowDown":
      return { x: 0, y: 1 };
    case "ArrowLeft":
      return { x: -1, y: 0 };
    case "ArrowRight":
      return { x: 1, y: 0 };
    default:
      return null;
  }
}

/** How far an arrow pans: 15 % of the box (at least 60 px), like the strip's line step. */
export function panStep(view: Box): { x: number; y: number } {
  return { x: Math.max(60, view.width * 0.15), y: Math.max(60, view.height * 0.15) };
}
