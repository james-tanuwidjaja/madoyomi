import { isPage, step, type ChapterNeighbours, type NavResult } from "./pagedNav";

/*
 * The double-page reader's pairing and navigation (Phase 5 Stage 4, step 8), pure so it is unit-tested without the
 * view.
 *
 * A chapter's pages are cut into spreads, in order from its first page: two pages side by side, or one alone. A page
 * wider than tall (a spread already joined into one image) is always alone, and the pairing starts again after it.
 * By default the first page is alone too (the print convention: a chapter often opens on a single title or colour
 * page, then 2–3, 4–5); "shift by one" pairs it with the second (1–2, 3–4). A page whose size isn't known yet counts
 * as portrait, so the spreads can change when a size arrives: the reader keeps its position on a page index and
 * shows the spread that holds it, so the page shown stays on screen. The last page can be alone.
 *
 * Positions are the paged reader's: a page index, -1 for the "Previous: …" panel, `pageCount` for the end panel.
 */

/** A spread's first and last page index (the same for a page alone), in reading order. */
export type Spread = readonly [first: number, last: number];

export interface PageSize {
  width: number;
  height: number;
}

/** A page wider than tall: shown alone. */
export function isWide(size: PageSize | undefined): boolean {
  return !!size && size.width > 0 && size.height > 0 && size.width > size.height;
}

/**
 * The spreads of a chapter of `pageCount` pages. `wide(i)` is true for a page to show alone; `firstAlone` puts the
 * first page alone (false: "shift by one").
 */
export function pairPages(
  pageCount: number,
  wide: (index: number) => boolean,
  firstAlone = true,
): Spread[] {
  const out: Spread[] = [];
  let i = 0;
  if (firstAlone && pageCount > 0) {
    out.push([0, 0]);
    i = 1;
  }
  while (i < pageCount) {
    if (wide(i) || i + 1 >= pageCount || wide(i + 1)) {
      out.push([i, i]);
      i += 1;
    } else {
      out.push([i, i + 1]);
      i += 2;
    }
  }
  return out;
}

/** The index of the spread that holds page `page` (clamped to the chapter), or -1 without pages. */
export function spreadIndexOf(spreads: readonly Spread[], page: number): number {
  if (spreads.length === 0) return -1;
  let lo = 0;
  let hi = spreads.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (spreads[mid]![0] <= page) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

/** The spread that holds page `page`, or null without pages. */
export function spreadOf(spreads: readonly Spread[], page: number): Spread | null {
  const i = spreadIndexOf(spreads, page);
  return i < 0 ? null : spreads[i]!;
}

/** The pages of a spread, in reading order. */
export function spreadPages(spread: Spread): number[] {
  return spread[0] === spread[1] ? [spread[0]] : [spread[0], spread[1]];
}

/**
 * One step forward (+1) or back (-1) by spread: forward from a spread to the first page of the next one, or past the
 * last to the end panel; back to the first page of the spread before, or from the first one to the "Previous: …"
 * panel. From the end panel back is the last spread; the panels' other steps are the paged reader's.
 */
export function doubleStep(
  spreads: readonly Spread[],
  position: number,
  pageCount: number,
  delta: 1 | -1,
  n: ChapterNeighbours,
): NavResult {
  if (pageCount <= 0 || spreads.length === 0) return step(position, pageCount, delta, n);
  if (!isPage(position, pageCount)) {
    if (delta < 0 && position >= pageCount) {
      return { kind: "position", position: spreads[spreads.length - 1]![0] };
    }
    return step(position, pageCount, delta, n);
  }
  const at = spreadIndexOf(spreads, position);
  const [first, last] = spreads[at]!;
  if (delta > 0) return { kind: "position", position: last + 1 };
  if (first === 0 || at === 0) return step(0, pageCount, -1, n);
  return { kind: "position", position: spreads[at - 1]![0] };
}

/**
 * The page a position counts as for progress: the last page of its spread, so the page saved is the furthest one
 * seen (a reopened chapter shows the same spread), and showing the last spread marks the chapter read. A panel is
 * itself.
 */
export function progressPage(
  spreads: readonly Spread[],
  position: number,
  pageCount: number,
): number {
  if (!isPage(position, pageCount)) return position;
  return spreadOf(spreads, position)?.[1] ?? position;
}

/**
 * The pages to keep loaded, in the order to load them: the spread shown, then the `ahead` pages after it (nearest
 * first), then the `behind` pages before it. On a panel, the spread next to it.
 */
export function spreadPagesToLoad(
  spreads: readonly Spread[],
  position: number,
  pageCount: number,
  ahead = 4,
  behind = 2,
): number[] {
  if (pageCount <= 0 || spreads.length === 0) return [];
  const at = Math.min(Math.max(position, 0), pageCount - 1);
  const [first, last] = spreadOf(spreads, at)!;
  const out = spreadPages([first, last]);
  for (let i = 1; i <= ahead && last + i < pageCount; i++) out.push(last + i);
  for (let i = 1; i <= behind && first - i >= 0; i++) out.push(first - i);
  return out;
}

/** The toolbar's page label for a spread: "4–5", or "4" for a page alone (page numbers from 1). */
export function spreadLabel(spread: Spread): string {
  return spread[0] === spread[1] ? `${spread[0] + 1}` : `${spread[0] + 1}–${spread[1] + 1}`;
}
