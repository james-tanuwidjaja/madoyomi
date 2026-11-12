import type { Chapter } from "../api/types";

/*
 * The paged reader's navigation (Phase 5 Stage 2), pure so it is unit-tested without the view: where a click, a key
 * or the wheel goes in left-to-right and right-to-left reading, the chapter boundaries, which pages to load first,
 * where a chapter opens, and what to ask the backend to prefetch.
 *
 * A position is a page index (0-based, as upstream's `lastPageRead` and page routes), or one of the two transition
 * panels around the pages: -1, "Previous: …" (only when there is a previous chapter), and `pageCount`, "Finished …
 * Next: …" (always, so the end of the last chapter says so).
 */

export type ReadingDirection = "ltr" | "rtl";

/** Where a step goes: a position in this chapter, the next or previous chapter, or nowhere. */
export type NavResult =
  | { kind: "position"; position: number }
  | { kind: "chapter"; to: "next" | "previous" }
  | { kind: "none" };

export interface ChapterNeighbours {
  hasPrevious: boolean;
  hasNext: boolean;
}

/** The position before the first page: the "Previous: …" panel. */
export const BEFORE = -1;

/** One step forward (+1) or back (-1) from `position` in a chapter of `pageCount` pages. */
export function step(
  position: number,
  pageCount: number,
  delta: 1 | -1,
  n: ChapterNeighbours,
): NavResult {
  if (delta > 0) {
    if (position >= pageCount)
      return n.hasNext ? { kind: "chapter", to: "next" } : { kind: "none" };
    return { kind: "position", position: Math.max(position + 1, 0) };
  }
  if (position <= BEFORE)
    return n.hasPrevious ? { kind: "chapter", to: "previous" } : { kind: "none" };
  if (position === 0)
    return n.hasPrevious ? { kind: "position", position: BEFORE } : { kind: "none" };
  return { kind: "position", position: Math.min(position - 1, pageCount) };
}

/** A position kept inside what exists: a page, or the panels (-1 only with a previous chapter). */
export function clampPosition(position: number, pageCount: number, hasPrevious: boolean): number {
  return Math.min(Math.max(position, hasPrevious ? BEFORE : 0), pageCount);
}

export function isPage(position: number, pageCount: number): boolean {
  return position >= 0 && position < pageCount;
}

// ---------------------------------------------------------------------------------------------------------------
// Input

export type ZoneAction = "previous" | "next" | "menu";

/**
 * The click zones: the left and right thirds turn the page, the middle one toggles the toolbar. Reading right to
 * left, the left side goes forward (the next page lies to the left), as in Mihon.
 */
export function zoneAt(x: number, width: number, direction: ReadingDirection): ZoneAction {
  if (width <= 0) return "menu";
  const third = x / width;
  if (third >= 1 / 3 && third <= 2 / 3) return "menu";
  const left = third < 1 / 3;
  return left === (direction === "rtl") ? "next" : "previous";
}

export type KeyAction =
  | "next"
  | "previous"
  | "first"
  | "last"
  | "menu"
  | "fullscreen"
  | "escape"
  | "nextChapter"
  | "previousChapter";

export interface KeyLike {
  key: string;
  shiftKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
  metaKey?: boolean;
}

/**
 * The keys: the arrows follow the reading direction (→ is forward left to right, ← right to left); ↓, Page Down and
 * Space always go forward, ↑, Page Up and Shift+Space back; Home and End the first and last page; [ and ] the
 * previous and next chapter; M the toolbar, F fullscreen, Esc out. With Ctrl, Alt or the Windows key: nothing (the
 * webview's own shortcuts, and zoom later).
 */
export function keyAction(e: KeyLike, direction: ReadingDirection): KeyAction | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null;
  const forward = direction === "ltr" ? "ArrowRight" : "ArrowLeft";
  const backward = direction === "ltr" ? "ArrowLeft" : "ArrowRight";
  switch (e.key) {
    case forward:
    case "ArrowDown":
    case "PageDown":
      return "next";
    case backward:
    case "ArrowUp":
    case "PageUp":
      return "previous";
    case " ":
    case "Spacebar":
      return e.shiftKey ? "previous" : "next";
    case "Home":
      return "first";
    case "End":
      return "last";
    case "]":
      return "nextChapter";
    case "[":
      return "previousChapter";
    case "m":
    case "M":
      return "menu";
    case "f":
    case "F":
      return "fullscreen";
    case "Escape":
      return "escape";
    default:
      return null;
  }
}

/**
 * The mouse wheel turns one page per gesture: deltas are summed until they pass `threshold` pixels, then nothing
 * counts for `cooldownMs`, so a trackpad's stream of small deltas or a free-spinning wheel doesn't race through the
 * chapter. A pause longer than `gapMs` starts a new sum.
 */
export function createWheelGate({ threshold = 50, cooldownMs = 350, gapMs = 200 } = {}) {
  let sum = 0;
  let lastEvent = -Infinity;
  let lastTurn = -Infinity;
  return {
    /** `deltaY` in pixels; returns +1 (forward), -1 (back) or 0. */
    push(deltaY: number, now: number): 1 | -1 | 0 {
      if (now - lastEvent > gapMs) sum = 0;
      lastEvent = now;
      if (now - lastTurn < cooldownMs) {
        sum = 0;
        return 0;
      }
      sum += deltaY;
      if (Math.abs(sum) < threshold) return 0;
      const dir = sum > 0 ? 1 : -1;
      sum = 0;
      lastTurn = now;
      return dir;
    },
  };
}

/** A WheelEvent's vertical delta in pixels (`deltaMode` 1 is lines, 2 pages). */
export function wheelPixels(deltaY: number, deltaMode: number, pageHeight = 800): number {
  return deltaMode === 1 ? deltaY * 16 : deltaMode === 2 ? deltaY * pageHeight : deltaY;
}

// ---------------------------------------------------------------------------------------------------------------
// Loading

/**
 * The pages to keep loaded around `position`, in the order to load them: the current page, then the ones ahead
 * (nearest first), then the ones behind. On a panel, the pages next to it.
 */
export function pagesToLoad(position: number, pageCount: number, behind = 1, ahead = 4): number[] {
  if (pageCount <= 0) return [];
  const at = Math.min(Math.max(position, 0), pageCount - 1);
  const out = [at];
  for (let i = 1; i <= ahead; i++) if (at + i < pageCount) out.push(at + i);
  for (let i = 1; i <= behind; i++) if (at - i >= 0) out.push(at - i);
  return out;
}

/**
 * The backend prefetch for `position`: the block of `size` pages that follows the reader's own window (`ahead`).
 * Blocks are `size` pages apart, so the reader asks once per block, not on every page turn, and the ranges of
 * consecutive blocks join up. Null when the block is past the end.
 */
export function prefetchRange(
  position: number,
  pageCount: number,
  ahead = 4,
  size = 10,
): { block: number; from: number; count: number } | null {
  const block = Math.floor(Math.max(position, 0) / size);
  const from = block * size + ahead + 1;
  if (from >= pageCount) return null;
  return { block, from, count: Math.min(size, pageCount - from, 20) };
}

/** The next chapter's first pages are prefetched once the reader is this close to the end. */
export const NEXT_CHAPTER_PREFETCH_AT = 5;
export const NEXT_CHAPTER_PREFETCH_COUNT = 5;

export function nearEnd(position: number, pageCount: number): boolean {
  return pageCount > 0 && pageCount - position <= NEXT_CHAPTER_PREFETCH_AT;
}

// ---------------------------------------------------------------------------------------------------------------
// Where a chapter opens, and which chapter comes next

/** `?page=` as asked for: a page number from 1, or `last`. */
export type PageRequest = number | "last" | null;

export function parsePageQuery(q: unknown): PageRequest {
  const v = Array.isArray(q) ? q[0] : q;
  if (v === "last") return "last";
  const n = Number(v);
  return typeof v === "string" && v !== "" && Number.isInteger(n) && n >= 1 ? n : null;
}

/**
 * The page a chapter opens at: the one asked for (`?page=`), else where reading stopped (upstream's `lastPageRead`)
 * unless the chapter is read (a read chapter starts over, as in Mihon), else the first.
 */
export function startPosition(
  chapter: Pick<Chapter, "isRead" | "lastPageRead">,
  pageCount: number,
  requested: PageRequest,
): number {
  if (pageCount <= 0) return 0;
  const last = pageCount - 1;
  if (requested === "last") return last;
  if (requested !== null) return Math.min(requested - 1, last);
  if (!chapter.isRead && chapter.lastPageRead > 0) return Math.min(chapter.lastPageRead, last);
  return 0;
}

type OrderedChapter = Pick<Chapter, "id" | "sourceOrder" | "chapterNumber" | "scanlator">;

/**
 * The chapter after (`dir` 1) or before (-1) `current` in the source's order (upstream's `sourceOrder`, 1 for the
 * oldest). Another group's copy of the current chapter (the same number, another scanlator) is skipped, and among
 * the copies of the chapter that follows (a run of the same number), the current one's scanlator is preferred,
 * else the nearest. A chapter of the same group is never skipped, so a source whose numbers are wrong (MangaPill's
 * "Group 2 Chapter 385" is number 2) still goes chapter by chapter, as Mihon does by default. A chapter without a
 * number (-1) is never a copy.
 */
export function adjacentChapter<C extends OrderedChapter>(
  chapters: readonly C[],
  current: OrderedChapter,
  dir: 1 | -1,
): C | null {
  const known = (n: number) => n >= 0;
  const group = (c: OrderedChapter) => c.scanlator ?? null;
  const candidates = chapters
    .filter((c) => c.id !== current.id && dir * (c.sourceOrder - current.sourceOrder) > 0)
    .sort((a, b) => dir * (a.sourceOrder - b.sourceOrder))
    .filter(
      (c) =>
        !(
          known(current.chapterNumber) &&
          c.chapterNumber === current.chapterNumber &&
          group(c) !== group(current)
        ),
    );
  const first = candidates[0];
  if (!first) return null;
  if (!known(first.chapterNumber)) return first;
  const run: C[] = [];
  for (const c of candidates) {
    if (c.chapterNumber !== first.chapterNumber) break;
    run.push(c);
  }
  return run.find((c) => group(c) === group(current)) ?? first;
}

/**
 * The chapter a manga's "Start reading" / "Continue reading" opens: the chapter read last (upstream's `lastReadAt`)
 * if it isn't finished, else the first unread one after it in the source's order; without either, the first unread
 * chapter from the oldest (Mihon's resume button). `started` is true once any chapter was read or begun.
 */
export function resumeChapter<
  C extends Pick<Chapter, "sourceOrder" | "isRead" | "lastPageRead" | "lastReadAt">,
>(chapters: readonly C[]): { chapter: C; started: boolean } | null {
  const ordered = [...chapters].sort((a, b) => a.sourceOrder - b.sourceOrder);
  // Read or begun: a chapter marked unread (page 0) has a fresh lastReadAt too, but nothing to continue.
  const last = ordered
    .filter((c) => Number(c.lastReadAt) > 0 && (c.isRead || c.lastPageRead > 0))
    .reduce<C | null>((a, c) => (!a || Number(c.lastReadAt) > Number(a.lastReadAt) ? c : a), null);
  if (last && !last.isRead) return { chapter: last, started: true };
  if (last) {
    const after = ordered.find((c) => c.sourceOrder > last.sourceOrder && !c.isRead);
    if (after) return { chapter: after, started: true };
  }
  const next = ordered.find((c) => !c.isRead);
  if (!next) return null;
  return { chapter: next, started: ordered.some((c) => c.isRead || c.lastPageRead > 0) };
}
