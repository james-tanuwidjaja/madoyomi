import { describe, expect, it } from "vitest";
import {
  adjacentChapter,
  BEFORE,
  clampPosition,
  createWheelGate,
  keyAction,
  nearEnd,
  pagesToLoad,
  parsePageQuery,
  prefetchRange,
  resumeChapter,
  startPosition,
  step,
  wheelPixels,
  zoneAt,
} from "../pagedNav";
import { makeChapters } from "../../test/fixtures";

/* The paged reader's navigation (Phase 5 Stage 2): pure, so every boundary is checked here. */
const both = { hasPrevious: true, hasNext: true };
const none = { hasPrevious: false, hasNext: false };

describe("step", () => {
  it("goes through the pages, then to the end panel, then to the next chapter", () => {
    expect(step(0, 3, 1, both)).toEqual({ kind: "position", position: 1 });
    expect(step(2, 3, 1, both)).toEqual({ kind: "position", position: 3 });
    expect(step(3, 3, 1, both)).toEqual({ kind: "chapter", to: "next" });
  });

  it("shows the end panel of the last chapter, and stays there", () => {
    expect(step(2, 3, 1, none)).toEqual({ kind: "position", position: 3 });
    expect(step(3, 3, 1, none)).toEqual({ kind: "none" });
  });

  it("goes back to the previous chapter through its panel, and not past the first chapter", () => {
    expect(step(1, 3, -1, both)).toEqual({ kind: "position", position: 0 });
    expect(step(0, 3, -1, both)).toEqual({ kind: "position", position: BEFORE });
    expect(step(BEFORE, 3, -1, both)).toEqual({ kind: "chapter", to: "previous" });
    expect(step(0, 3, -1, none)).toEqual({ kind: "none" });
  });

  it("leaves the panels back into the pages", () => {
    expect(step(BEFORE, 3, 1, both)).toEqual({ kind: "position", position: 0 });
    expect(step(3, 3, -1, both)).toEqual({ kind: "position", position: 2 });
  });

  it("treats a chapter without pages as its end panel", () => {
    expect(step(0, 0, 1, both)).toEqual({ kind: "chapter", to: "next" });
    expect(step(0, 0, -1, both)).toEqual({ kind: "position", position: BEFORE });
  });

  it("keeps a position the slider or a key asked for inside the chapter", () => {
    expect(clampPosition(-5, 10, true)).toBe(BEFORE);
    expect(clampPosition(-5, 10, false)).toBe(0);
    expect(clampPosition(42, 10, true)).toBe(10);
    expect(clampPosition(4, 10, true)).toBe(4);
  });
});

describe("click zones", () => {
  it("left to right: the left third goes back, the right third forward, the middle toggles the toolbar", () => {
    expect(zoneAt(50, 900, "ltr")).toBe("previous");
    expect(zoneAt(450, 900, "ltr")).toBe("menu");
    expect(zoneAt(850, 900, "ltr")).toBe("next");
  });

  it("right to left: the left third goes forward", () => {
    expect(zoneAt(50, 900, "rtl")).toBe("next");
    expect(zoneAt(450, 900, "rtl")).toBe("menu");
    expect(zoneAt(850, 900, "rtl")).toBe("previous");
  });

  it("puts the edges of the middle third in the middle, and a zero width there too", () => {
    expect(zoneAt(300, 900, "ltr")).toBe("menu");
    expect(zoneAt(600, 900, "ltr")).toBe("menu");
    expect(zoneAt(299, 900, "ltr")).toBe("previous");
    expect(zoneAt(0, 0, "ltr")).toBe("menu");
  });
});

describe("keys", () => {
  it("follows the reading direction with the arrows", () => {
    expect(keyAction({ key: "ArrowRight" }, "ltr")).toBe("next");
    expect(keyAction({ key: "ArrowLeft" }, "ltr")).toBe("previous");
    expect(keyAction({ key: "ArrowLeft" }, "rtl")).toBe("next");
    expect(keyAction({ key: "ArrowRight" }, "rtl")).toBe("previous");
  });

  it.each(["ltr", "rtl"] as const)("keeps Space, the page keys and ↑↓ the same (%s)", (d) => {
    expect(keyAction({ key: " " }, d)).toBe("next");
    expect(keyAction({ key: " ", shiftKey: true }, d)).toBe("previous");
    expect(keyAction({ key: "PageDown" }, d)).toBe("next");
    expect(keyAction({ key: "PageUp" }, d)).toBe("previous");
    expect(keyAction({ key: "ArrowDown" }, d)).toBe("next");
    expect(keyAction({ key: "ArrowUp" }, d)).toBe("previous");
    expect(keyAction({ key: "Home" }, d)).toBe("first");
    expect(keyAction({ key: "End" }, d)).toBe("last");
  });

  it("has the chapter, toolbar, fullscreen and leave keys", () => {
    expect(keyAction({ key: "]" }, "rtl")).toBe("nextChapter");
    expect(keyAction({ key: "[" }, "rtl")).toBe("previousChapter");
    expect(keyAction({ key: "m" }, "rtl")).toBe("menu");
    expect(keyAction({ key: "F" }, "rtl")).toBe("fullscreen");
    expect(keyAction({ key: "Escape" }, "rtl")).toBe("escape");
  });

  it("leaves keys with a modifier and other keys alone", () => {
    expect(keyAction({ key: "ArrowRight", ctrlKey: true }, "ltr")).toBeNull();
    expect(keyAction({ key: "ArrowRight", altKey: true }, "ltr")).toBeNull();
    expect(keyAction({ key: "f", metaKey: true }, "ltr")).toBeNull();
    expect(keyAction({ key: "a" }, "ltr")).toBeNull();
    expect(keyAction({ key: "Tab" }, "ltr")).toBeNull();
  });
});

describe("the wheel", () => {
  it("turns one page per notch, forward down and back up", () => {
    const gate = createWheelGate();
    expect(gate.push(100, 0)).toBe(1);
    expect(gate.push(-100, 1000)).toBe(-1);
  });

  it("turns once for a burst of deltas, then again after the cooldown", () => {
    const gate = createWheelGate({ threshold: 50, cooldownMs: 350 });
    const turns = [0, 16, 32, 48, 64, 80, 96].map((t) => gate.push(30, t));
    expect(turns.filter((t) => t !== 0)).toEqual([1]);
    // past the cooldown, a new sum starts
    expect(gate.push(30, 400)).toBe(0);
    expect(gate.push(30, 416)).toBe(1);
  });

  it("forgets small deltas after a pause", () => {
    const gate = createWheelGate({ threshold: 50, gapMs: 200 });
    expect(gate.push(30, 0)).toBe(0);
    expect(gate.push(30, 500)).toBe(0);
    expect(gate.push(30, 520)).toBe(1);
  });

  it("counts lines and pages in pixels", () => {
    expect(wheelPixels(3, 1)).toBe(48);
    expect(wheelPixels(1, 2, 700)).toBe(700);
    expect(wheelPixels(-120, 0)).toBe(-120);
  });
});

describe("loading and prefetching", () => {
  it("loads the current page first, then the ones ahead, then behind", () => {
    expect(pagesToLoad(5, 92)).toEqual([5, 6, 7, 8, 9, 4]);
    expect(pagesToLoad(0, 92)).toEqual([0, 1, 2, 3, 4]);
    expect(pagesToLoad(91, 92)).toEqual([91, 90]);
    expect(pagesToLoad(2, 3, 2, 2)).toEqual([2, 1, 0]);
  });

  it("loads the pages next to a panel, and nothing for an empty chapter", () => {
    expect(pagesToLoad(BEFORE, 10)).toEqual([0, 1, 2, 3, 4]);
    expect(pagesToLoad(10, 10)).toEqual([9, 8]);
    expect(pagesToLoad(0, 0)).toEqual([]);
  });

  it("asks the backend for the block after the reader's own pages, once per block", () => {
    expect(prefetchRange(0, 103)).toEqual({ block: 0, from: 5, count: 10 });
    expect(prefetchRange(9, 103)).toEqual({ block: 0, from: 5, count: 10 });
    expect(prefetchRange(10, 103)).toEqual({ block: 1, from: 15, count: 10 });
    expect(prefetchRange(95, 103)).toEqual({ block: 9, from: 95, count: 8 });
    expect(prefetchRange(100, 103)).toBeNull();
    expect(prefetchRange(0, 5)).toBeNull();
    expect(prefetchRange(BEFORE, 30)).toEqual({ block: 0, from: 5, count: 10 });
  });

  it("knows when the next chapter's first pages are due", () => {
    expect(nearEnd(86, 92)).toBe(false);
    expect(nearEnd(87, 92)).toBe(true);
    expect(nearEnd(92, 92)).toBe(true);
    expect(nearEnd(0, 0)).toBe(false);
  });
});

describe("where a chapter opens", () => {
  const chapter = { isRead: false, lastPageRead: 0 };

  it("reads ?page= as a page number from 1, or last", () => {
    expect(parsePageQuery("12")).toBe(12);
    expect(parsePageQuery(["3", "4"])).toBe(3);
    expect(parsePageQuery("last")).toBe("last");
    for (const bad of ["0", "-2", "1.5", "x", "", undefined, null]) {
      expect(parsePageQuery(bad)).toBeNull();
    }
  });

  it("resumes where reading stopped", () => {
    expect(startPosition({ ...chapter, lastPageRead: 12 }, 92, null)).toBe(12);
    expect(startPosition(chapter, 92, null)).toBe(0);
    // upstream clamps lastPageRead to the page count, one past the last index
    expect(startPosition({ ...chapter, lastPageRead: 92 }, 92, null)).toBe(91);
  });

  it("starts a read chapter over", () => {
    expect(startPosition({ isRead: true, lastPageRead: 91 }, 92, null)).toBe(0);
  });

  it("opens the page asked for, or the last one, inside the chapter", () => {
    expect(startPosition({ ...chapter, lastPageRead: 12 }, 92, 3)).toBe(2);
    expect(startPosition(chapter, 92, 500)).toBe(91);
    expect(startPosition(chapter, 92, "last")).toBe(91);
    expect(startPosition(chapter, 0, "last")).toBe(0);
  });
});

describe("the next and previous chapter", () => {
  const list = makeChapters(5);
  const at = (i: number) => list[i - 1]!;

  it("goes by the source's order, whatever order the list is in", () => {
    const shuffled = [at(4), at(1), at(5), at(3), at(2)];
    expect(adjacentChapter(shuffled, at(3), 1)?.name).toBe("Chapter 4");
    expect(adjacentChapter(shuffled, at(3), -1)?.name).toBe("Chapter 2");
    expect(adjacentChapter(shuffled, at(5), 1)).toBeNull();
    expect(adjacentChapter(shuffled, at(1), -1)).toBeNull();
  });

  it("skips copies of the current chapter and prefers the same scanlator among the next one's", () => {
    const c = (id: number, order: number, number: number, scanlator: string | null) => ({
      id,
      sourceOrder: order,
      chapterNumber: number,
      scanlator,
      name: `${number} ${scanlator}`,
    });
    const chapters = [
      c(1, 1, 1, "A"),
      c(2, 2, 1, "B"),
      c(3, 3, 2, "B"),
      c(4, 4, 2, "A"),
      c(5, 5, 3, "B"),
    ];
    expect(adjacentChapter(chapters, chapters[0]!, 1)?.id).toBe(4);
    expect(adjacentChapter(chapters, chapters[1]!, 1)?.id).toBe(3);
    // a group the next chapter lacks: the nearest copy
    expect(adjacentChapter(chapters, chapters[3]!, 1)?.id).toBe(5);
    expect(adjacentChapter(chapters, chapters[3]!, -1)?.id).toBe(1);
    expect(adjacentChapter(chapters, chapters[2]!, -1)?.id).toBe(2);
  });

  it("goes chapter by chapter when a source's numbers are wrong (MangaPill's 'Group 2 Chapter 385' is 2)", () => {
    const chapters = makeChapters(5).map((ch) => ({ ...ch, chapterNumber: 2 }));
    expect(adjacentChapter(chapters, chapters[2]!, 1)?.id).toBe(chapters[3]!.id);
    expect(adjacentChapter(chapters, chapters[2]!, -1)?.id).toBe(chapters[1]!.id);
    expect(adjacentChapter(chapters, chapters[4]!, 1)).toBeNull();
  });

  it("never treats chapters without a number as copies", () => {
    const chapters = makeChapters(3).map((ch) => ({ ...ch, chapterNumber: -1 }));
    expect(adjacentChapter(chapters, chapters[0]!, 1)?.id).toBe(chapters[1]!.id);
  });
});

describe("resumeChapter", () => {
  it("starts a manga nobody read at its first chapter", () => {
    const r = resumeChapter(makeChapters(3).reverse());
    expect(r?.chapter.name).toBe("Chapter 1");
    expect(r?.started).toBe(false);
  });

  it("continues at the first unread chapter, begun or not", () => {
    const chapters = makeChapters(4).map((c) => (c.sourceOrder <= 2 ? { ...c, isRead: true } : c));
    expect(resumeChapter(chapters)).toMatchObject({
      chapter: { name: "Chapter 3" },
      started: true,
    });
    const begun = makeChapters(2).map((c, i) => (i === 0 ? { ...c, lastPageRead: 5 } : c));
    expect(resumeChapter(begun)).toMatchObject({ chapter: { name: "Chapter 1" }, started: true });
  });

  it("continues the chapter read last, or the one after it once it is finished", () => {
    const chapters = makeChapters(400).map((c) =>
      c.sourceOrder === 385 ? { ...c, lastPageRead: 4, lastReadAt: "1794486276" } : c,
    );
    expect(resumeChapter(chapters)).toMatchObject({
      chapter: { name: "Chapter 385" },
      started: true,
    });

    const finished = chapters.map((c) =>
      c.sourceOrder === 385
        ? { ...c, isRead: true }
        : c.sourceOrder === 10
          ? { ...c, lastPageRead: 3, lastReadAt: "1700000000" }
          : c,
    );
    expect(resumeChapter(finished)?.chapter.name).toBe("Chapter 386");
    // a chapter marked unread (back to page 0) isn't one to continue
    const unmarked = makeChapters(3).map((c) =>
      c.sourceOrder === 3 ? { ...c, lastReadAt: "1794486276" } : c,
    );
    expect(resumeChapter(unmarked)?.chapter.name).toBe("Chapter 1");
  });

  it("has nothing when every chapter is read, or there are none", () => {
    expect(resumeChapter(makeChapters(2).map((c) => ({ ...c, isRead: true })))).toBeNull();
    expect(resumeChapter([])).toBeNull();
  });
});
