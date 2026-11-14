import { describe, expect, it } from "vitest";
import {
  doubleStep,
  isWide,
  pairPages,
  progressPage,
  spreadIndexOf,
  spreadLabel,
  spreadOf,
  spreadPages,
  spreadPagesToLoad,
  type Spread,
} from "../doublePage";
import { BEFORE } from "../pagedNav";

/* The double-page reader's pairing, spread detection and steps (Phase 5 Stage 4): pure, so every case is here. */
const both = { hasPrevious: true, hasNext: true };
const none = { hasPrevious: false, hasNext: false };
const portrait = () => false;
const wideAt =
  (...pages: number[]) =>
  (i: number) =>
    pages.includes(i);

describe("isWide", () => {
  it("is a page wider than tall, known", () => {
    expect(isWide({ width: 1600, height: 1200 })).toBe(true);
    expect(isWide({ width: 800, height: 1200 })).toBe(false);
    expect(isWide({ width: 1000, height: 1000 })).toBe(false);
    expect(isWide(undefined)).toBe(false);
    expect(isWide({ width: 0, height: 0 })).toBe(false);
  });
});

describe("pairPages", () => {
  it("puts the first page alone, then pairs the rest, the last alone if odd", () => {
    expect(pairPages(6, portrait)).toEqual([
      [0, 0],
      [1, 2],
      [3, 4],
      [5, 5],
    ]);
    expect(pairPages(5, portrait)).toEqual([
      [0, 0],
      [1, 2],
      [3, 4],
    ]);
  });

  it("pairs from the first page when shifted by one", () => {
    expect(pairPages(5, portrait, false)).toEqual([
      [0, 1],
      [2, 3],
      [4, 4],
    ]);
  });

  it("shows a wide page (a spread) alone and starts the pairs again after it, at an odd and an even index", () => {
    // 0 | 1-2 | 3 (wide) | 4-5 | 6 (wide) | 7-8 | 9
    expect(pairPages(10, wideAt(3, 6))).toEqual([
      [0, 0],
      [1, 2],
      [3, 3],
      [4, 5],
      [6, 6],
      [7, 8],
      [9, 9],
    ]);
    // the page before a wide one has no partner: alone
    expect(pairPages(6, wideAt(2))).toEqual([
      [0, 0],
      [1, 1],
      [2, 2],
      [3, 4],
      [5, 5],
    ]);
    expect(pairPages(6, wideAt(2), false)).toEqual([
      [0, 1],
      [2, 2],
      [3, 4],
      [5, 5],
    ]);
  });

  it("covers every page once, in order", () => {
    const wide = wideAt(0, 4, 5, 11);
    for (const firstAlone of [true, false]) {
      const pages = pairPages(13, wide, firstAlone).flatMap((s) => spreadPages(s));
      expect(pages).toEqual(Array.from({ length: 13 }, (_, i) => i));
    }
  });

  it("handles no page and one page", () => {
    expect(pairPages(0, portrait)).toEqual([]);
    expect(pairPages(1, portrait)).toEqual([[0, 0]]);
    expect(pairPages(1, portrait, false)).toEqual([[0, 0]]);
  });
});

describe("spreadOf", () => {
  const spreads = pairPages(6, portrait); // 0 | 1-2 | 3-4 | 5
  it("finds the spread that holds a page", () => {
    expect(spreadOf(spreads, 0)).toEqual([0, 0]);
    expect(spreadOf(spreads, 2)).toEqual([1, 2]);
    expect(spreadOf(spreads, 3)).toEqual([3, 4]);
    expect(spreadOf(spreads, 5)).toEqual([5, 5]);
    expect(spreadIndexOf(spreads, 4)).toBe(2);
    expect(spreadOf([], 0)).toBeNull();
  });

  it("keeps the page shown on screen when a size changes the pairs", () => {
    // page 4 shown in 3-4; page 3 turns out wide: 4 is now with 5, still on screen
    const after = pairPages(6, wideAt(3));
    expect(spreadOf(after, 4)).toEqual([4, 5]);
    expect(spreadPages(spreadOf(after, 4)!)).toContain(4);
  });
});

describe("doubleStep", () => {
  const spreads = pairPages(6, portrait); // 0 | 1-2 | 3-4 | 5
  const fwd = (p: number, n = both) => doubleStep(spreads, p, 6, 1, n);
  const back = (p: number, n = both) => doubleStep(spreads, p, 6, -1, n);

  it("goes forward spread by spread, then to the end panel, then to the next chapter", () => {
    expect(fwd(0)).toEqual({ kind: "position", position: 1 });
    expect(fwd(1)).toEqual({ kind: "position", position: 3 });
    // from the second page of a spread too
    expect(fwd(2)).toEqual({ kind: "position", position: 3 });
    expect(fwd(3)).toEqual({ kind: "position", position: 5 });
    expect(fwd(5)).toEqual({ kind: "position", position: 6 });
    expect(fwd(6)).toEqual({ kind: "chapter", to: "next" });
    expect(fwd(6, none)).toEqual({ kind: "none" });
  });

  it("goes back to the first page of the spread before, then to the previous panel and chapter", () => {
    expect(back(6)).toEqual({ kind: "position", position: 5 });
    expect(back(5)).toEqual({ kind: "position", position: 3 });
    expect(back(4)).toEqual({ kind: "position", position: 1 });
    expect(back(1)).toEqual({ kind: "position", position: 0 });
    expect(back(0)).toEqual({ kind: "position", position: BEFORE });
    expect(back(BEFORE)).toEqual({ kind: "chapter", to: "previous" });
    expect(back(0, none)).toEqual({ kind: "none" });
    expect(fwd(BEFORE)).toEqual({ kind: "position", position: 0 });
  });

  it("goes back to the last spread's first page from the end panel", () => {
    const even = pairPages(5, portrait); // 0 | 1-2 | 3-4
    expect(doubleStep(even, 5, 5, -1, both)).toEqual({ kind: "position", position: 3 });
  });

  it("treats a chapter without pages as the paged reader does", () => {
    expect(doubleStep([], 0, 0, 1, both)).toEqual({ kind: "chapter", to: "next" });
    expect(doubleStep([], 0, 0, -1, both)).toEqual({ kind: "position", position: BEFORE });
  });

  it("goes through wide pages alone, in both directions", () => {
    const s = pairPages(7, wideAt(3)); // 0 | 1-2 | 3 | 4-5 | 6
    const path: number[] = [];
    let p = 0;
    for (let k = 0; k < 6; k++) {
      path.push(p);
      const r = doubleStep(s, p, 7, 1, none);
      if (r.kind !== "position") break;
      p = r.position;
    }
    expect(path).toEqual([0, 1, 3, 4, 6, 7]);
    const backPath: number[] = [];
    for (let k = 0; k < 6; k++) {
      const r = doubleStep(s, p, 7, -1, none);
      if (r.kind !== "position") break;
      p = r.position;
      backPath.push(p);
    }
    expect(backPath).toEqual([6, 4, 3, 1, 0]);
  });
});

describe("progressPage", () => {
  const spreads = pairPages(6, portrait);
  it("counts the spread's last page, so the last spread marks the chapter read", () => {
    expect(progressPage(spreads, 1, 6)).toBe(2);
    expect(progressPage(spreads, 3, 6)).toBe(4);
    expect(progressPage(spreads, 5, 6)).toBe(5);
    const even = pairPages(5, portrait); // the last spread 3-4
    expect(progressPage(even, 3, 5)).toBe(4);
  });

  it("leaves the panels as they are", () => {
    expect(progressPage(spreads, 6, 6)).toBe(6);
    expect(progressPage(spreads, BEFORE, 6)).toBe(BEFORE);
  });

  it("reopens on the same spread at the page it saved", () => {
    const saved = progressPage(spreads, 3, 6);
    expect(spreadOf(spreads, saved)).toEqual(spreadOf(spreads, 3));
  });
});

describe("spreadPagesToLoad", () => {
  const spreads = pairPages(12, portrait); // 0 | 1-2 | 3-4 | 5-6 | 7-8 | 9-10 | 11
  it("loads the spread shown, then the pages after it, then the ones before", () => {
    expect(spreadPagesToLoad(spreads, 3, 12)).toEqual([3, 4, 5, 6, 7, 8, 2, 1]);
    expect(spreadPagesToLoad(spreads, 0, 12)).toEqual([0, 1, 2, 3, 4]);
    expect(spreadPagesToLoad(spreads, 11, 12)).toEqual([11, 10, 9]);
  });

  it("loads next to a panel", () => {
    expect(spreadPagesToLoad(spreads, 12, 12)).toEqual([11, 10, 9]);
    expect(spreadPagesToLoad(spreads, BEFORE, 12)).toEqual([0, 1, 2, 3, 4]);
    expect(spreadPagesToLoad([], 0, 0)).toEqual([]);
  });
});

describe("spreadLabel", () => {
  it("names one or two pages from 1", () => {
    expect(spreadLabel([3, 4] as Spread)).toBe("4–5");
    expect(spreadLabel([0, 0] as Spread)).toBe("1");
  });
});
