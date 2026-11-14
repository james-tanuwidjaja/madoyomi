import { describe, expect, it } from "vitest";
import {
  anchorAt,
  atStripEnd,
  createEdgeWheel,
  DEFAULT_PAGE_RATIO,
  estimateRatio,
  itemHeight,
  itemsBetween,
  itemStart,
  maxScroll,
  pageHeights,
  positionAt,
  scrollAmount,
  scrollForAnchor,
  shownPosition,
  stripLayout,
  stripPagesToLoad,
  stripWidth,
  topAnchor,
  webtoonKeyAction,
  type PageSize,
} from "../webtoonNav";

/*
 * The webtoon strip's layout and navigation (Phase 5 Stage 3): page heights before and after a page's size is known,
 * scroll offset ↔ position, the page counted as shown, the anchor that keeps the view still, the pages to load around
 * the viewport, and the keys.
 */
const VH = 900;

/** A strip of `n` pages of `h` px each (800 px wide), with the previous panel when `previous`. */
function strip(heights: number[], previous = false, gap = 0) {
  return stripLayout({ pageHeights: heights, hasPrevious: previous, endHeight: VH, gap });
}

describe("page heights", () => {
  it("fits the strip to the window, at most 800 px", () => {
    expect(stripWidth(1920)).toBe(800);
    expect(stripWidth(640)).toBe(640);
    expect(stripWidth(-5)).toBe(0);
  });

  it("gives a page its own ratio once known, and the others the median of the known ones", () => {
    const sizes = new Map<number, PageSize>([
      [0, { width: 800, height: 1280 }],
      [2, { width: 800, height: 2000 }],
      [3, { width: 800, height: 1200 }],
    ]);
    expect(pageHeights(5, 800, (i) => sizes.get(i))).toEqual([1280, 1280, 2000, 1200, 1280]);
    // scaled to the strip's width
    expect(pageHeights(1, 400, (i) => sizes.get(i))).toEqual([640]);
  });

  it("uses a manga page's ratio before any size is known, and ignores sizes that can't be ratios", () => {
    expect(estimateRatio([])).toBe(DEFAULT_PAGE_RATIO);
    expect(estimateRatio([1, 3])).toBe(2);
    expect(pageHeights(2, 800, () => ({ width: 0, height: 100 }))).toEqual([1200, 1200]);
  });
});

describe("the layout", () => {
  it("stacks the previous panel, the pages and the end panel, with the gap between items", () => {
    const l = strip([1000, 500], true, 16);
    expect(l.first).toBe(-1);
    expect(l.starts).toEqual([0, 256, 1272, 1788]);
    expect(l.heights).toEqual([240, 1000, 500, VH]);
    expect(itemStart(l, 2)).toBe(1788); // the end panel
    expect(l.total).toBe(1788 + VH);
    expect(itemHeight(l, 0)).toBe(1000);
    // out of range clamps to the ends
    expect(itemStart(l, -5)).toBe(0);
    expect(itemStart(l, 99)).toBe(1788);
  });

  it("finds the item at an offset; a gap belongs to the item above it", () => {
    const l = strip([1000, 500], true, 16);
    expect(positionAt(l, 0)).toBe(-1);
    expect(positionAt(l, 245)).toBe(-1); // in the gap after the panel
    expect(positionAt(l, 256)).toBe(0);
    expect(positionAt(l, 1271)).toBe(0);
    expect(positionAt(l, 1272)).toBe(1);
    expect(positionAt(l, 99999)).toBe(2);
  });

  it("lists the items that meet a band", () => {
    const l = strip([1000, 1000, 1000]);
    expect(itemsBetween(l, 0, VH)).toEqual([0, 0]);
    expect(itemsBetween(l, 500, 1500)).toEqual([0, 1]);
    expect(itemsBetween(l, 1000, 1900)).toEqual([1, 1]);
    expect(itemsBetween(l, -400, 99999)).toEqual([0, 3]);
  });
});

describe("the page shown", () => {
  it("is the one at the viewport's top edge", () => {
    const l = strip([1000, 1000, 1000], true);
    expect(shownPosition(l, 0, VH)).toBe(-1);
    expect(shownPosition(l, 240, VH)).toBe(0);
    expect(shownPosition(l, 1239, VH)).toBe(0);
    expect(shownPosition(l, 1240, VH)).toBe(1);
  });

  it("is the end panel once the strip is scrolled to the end; every page can reach the top first", () => {
    const l = strip([1000, 1000, 300]);
    const max = maxScroll(l, VH);
    expect(max).toBe(2300); // the end panel is a viewport tall
    expect(shownPosition(l, itemStart(l, 2), VH)).toBe(2); // the short last page at the top
    expect(shownPosition(l, max - 0.4, VH)).toBe(3);
    expect(atStripEnd(l, max - 1, VH)).toBe(true);
    expect(atStripEnd(l, max - 50, VH)).toBe(false);
  });

  it("is the page a seek put at the top, so a reopened chapter shows the page it saved", () => {
    const l = strip([1280, 400, 1280, 1280], true);
    for (const p of [0, 1, 2, 3]) {
      expect(shownPosition(l, scrollForAnchor(l, topAnchor(p), VH), VH)).toBe(p);
    }
  });
});

describe("the anchor", () => {
  it("keeps a page's top at the viewport's top when the pages above it get their real height", () => {
    const before = strip([1200, 1200, 1200, 1200]);
    const anchor = topAnchor(2);
    expect(scrollForAnchor(before, anchor, VH)).toBe(2400);

    const after = strip([2000, 600, 1200, 1200]); // pages 0 and 1 loaded
    expect(scrollForAnchor(after, anchor, VH)).toBe(2600);
    expect(shownPosition(after, 2600, VH)).toBe(2);
  });

  it("keeps the point at the viewport's centre still when a page above loads", () => {
    const before = strip([1200, 1200, 1200, 1200]);
    const scrollTop = 1500; // the centre (1950) is 750 px into page 1
    const anchor = anchorAt(before, scrollTop, VH);
    expect(anchor).toEqual({ position: 1, fraction: 0.625, line: 0.5 });

    const after = strip([1800, 1200, 1200, 1200]); // page 0 was 600 px taller
    expect(scrollForAnchor(after, anchor, VH)).toBe(2100);
  });

  it("keeps the same spot of a page when the window's width scales every page", () => {
    const narrow = strip([600, 600, 600]);
    const anchor = anchorAt(narrow, 700, VH); // centre at 1150: 550 px into page 1
    const wide = strip([1200, 1200, 1200]);
    const y = scrollForAnchor(wide, anchor, VH);
    expect(anchorAt(wide, y, VH).position).toBe(1);
    expect(anchorAt(wide, y, VH).fraction).toBeCloseTo(anchor.fraction, 6);
  });

  it("stays inside the strip", () => {
    const l = strip([500]);
    expect(scrollForAnchor(l, topAnchor(-1), VH)).toBe(0);
    expect(scrollForAnchor(l, topAnchor(1), VH)).toBe(maxScroll(l, VH));
  });
});

describe("the pages to load", () => {
  it("loads the visible pages first, then two screens ahead, then one behind", () => {
    const l = strip(Array<number>(30).fill(1000));
    // the viewport [5500, 6400) shows pages 5 and 6; ahead to 8200 → 7, 8; behind to 4600 → 4
    expect(stripPagesToLoad(l, 5500, VH)).toEqual([5, 6, 7, 8, 4]);
  });

  it("counts pixels, so very tall images load one or two at a time and short strips more", () => {
    const tall = strip(Array<number>(6).fill(20000));
    expect(stripPagesToLoad(tall, 25000, VH)).toEqual([1, 2, 3]); // at least 2 ahead, none behind in reach
    const short = strip(Array<number>(60).fill(100));
    const wanted = stripPagesToLoad(short, 2000, VH);
    expect(wanted).toHaveLength(16); // the cap
    expect(wanted.slice(0, 9)).toEqual([20, 21, 22, 23, 24, 25, 26, 27, 28]); // the visible ones first
  });

  it("covers the start and the end of the chapter", () => {
    const l = strip([1000, 1000, 1000], true);
    expect(stripPagesToLoad(l, 0, VH)).toEqual([0, 1, 2]); // the panel is at the top; pages below
    // the end panel fills the viewport: the last page stays loaded, for a scroll back up
    expect(stripPagesToLoad(l, maxScroll(l, VH), VH)).toEqual([2]);
    expect(stripPagesToLoad(strip([]), 0, VH)).toEqual([]);
  });
});

describe("the keys", () => {
  it("scrolls with the arrows, Page Up/Down and Space; ← and → do nothing", () => {
    expect(webtoonKeyAction({ key: "ArrowDown" })).toBe("lineDown");
    expect(webtoonKeyAction({ key: "ArrowUp" })).toBe("lineUp");
    expect(webtoonKeyAction({ key: "PageDown" })).toBe("screenDown");
    expect(webtoonKeyAction({ key: "PageUp" })).toBe("screenUp");
    expect(webtoonKeyAction({ key: " " })).toBe("screenDown");
    expect(webtoonKeyAction({ key: " ", shiftKey: true })).toBe("screenUp");
    expect(webtoonKeyAction({ key: "ArrowLeft" })).toBeNull();
    expect(webtoonKeyAction({ key: "ArrowRight" })).toBeNull();
  });

  it("keeps the paged reader's other keys, and nothing with Ctrl", () => {
    expect(webtoonKeyAction({ key: "Home" })).toBe("first");
    expect(webtoonKeyAction({ key: "End" })).toBe("last");
    expect(webtoonKeyAction({ key: "]" })).toBe("nextChapter");
    expect(webtoonKeyAction({ key: "m" })).toBe("menu");
    expect(webtoonKeyAction({ key: "Escape" })).toBe("escape");
    expect(webtoonKeyAction({ key: "ArrowDown", ctrlKey: true })).toBeNull();
  });

  it("scrolls a line of 15 % (at least 60 px) and a screen of 90 %", () => {
    expect(scrollAmount("line", 900)).toBe(135);
    expect(scrollAmount("line", 200)).toBe(60);
    expect(scrollAmount("screen", 900)).toBe(810);
  });
});

describe("createEdgeWheel (the wheel past the strip's ends, Stage 4)", () => {
  it("opens the next chapter on a new gesture down at the bottom", () => {
    const w = createEdgeWheel();
    expect(w.push(100, 0, 1)).toBe(1);
  });

  it("doesn't carry a fling that reaches the bottom into the next chapter", () => {
    const w = createEdgeWheel();
    expect(w.push(100, 0, 0)).toBe(0);
    expect(w.push(100, 50, 1)).toBe(0);
    expect(w.push(100, 100, 1)).toBe(0);
    // a pause, then a notch down: on into the next chapter
    expect(w.push(100, 400, 1)).toBe(1);
    // the rest of that gesture doesn't open another one
    expect(w.push(100, 450, 1)).toBe(0);
  });

  it("opens the previous chapter on a gesture up at the top, not on one down", () => {
    const w = createEdgeWheel();
    expect(w.push(100, 0, 0)).toBe(0);
    expect(w.push(-100, 500, -1)).toBe(-1);
    expect(w.push(100, 1000, 0)).toBe(0);
  });

  it("adds a trackpad's small deltas up to a notch", () => {
    const w = createEdgeWheel();
    expect(w.push(20, 0, 1)).toBe(0);
    expect(w.push(20, 16, 1)).toBe(0);
    expect(w.push(20, 32, 1)).toBe(1);
  });

  it("starts again when the strip leaves the edge", () => {
    const w = createEdgeWheel();
    expect(w.push(-20, 0, 0)).toBe(0);
    expect(w.push(30, 10, 1)).toBe(0);
    expect(w.push(30, 20, 1)).toBe(0);
  });
});
