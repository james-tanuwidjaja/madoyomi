import { describe, expect, it } from "vitest";
import type { HistoryItem } from "../../api/types";
import { dayLabel, groupByDay, resumeLabel, resumeRoute, targetProgress } from "../history";

/* The History view's pure parts. */
const at = (y: number, m: number, d: number, h = 12) => new Date(y, m - 1, d, h).getTime() / 1000;

function item(id: number, seconds: number, over: Partial<HistoryItem> = {}): HistoryItem {
  return {
    kind: "anime",
    entry: { kind: "anime", id, title: `Entry ${id}`, thumbnailUrl: null, inLibrary: true },
    item: {
      id: id * 10,
      name: "Episode 1",
      done: false,
      progress: { positionMs: 0, durationMs: 0 },
    },
    resume: {
      id: id * 10,
      name: "Episode 1",
      done: false,
      progress: { positionMs: 0, durationMs: 0 },
    },
    at: seconds,
    ...over,
  };
}

const now = new Date(2026, 10, 15, 18);

describe("groupByDay", () => {
  it("groups by local day in the given order, with Today, Yesterday and dates", () => {
    const items = [
      item(1, at(2026, 11, 15, 17)),
      item(2, at(2026, 11, 15, 0)),
      item(3, at(2026, 11, 14, 23)),
      item(4, at(2026, 11, 9)),
      item(5, at(2025, 12, 31)),
    ];

    const days = groupByDay(items, now);

    expect(days.map((d) => [d.label, d.items.map((i) => i.entry.id)])).toEqual([
      ["Today", [1, 2]],
      ["Yesterday", [3]],
      ["Monday, November 9", [4]],
      ["Wednesday, December 31, 2025", [5]],
    ]);
    expect(days[0]!.key).toBe("2026-11-15");
  });

  it("knows yesterday across a month's start", () => {
    expect(dayLabel(new Date(2026, 9, 31, 22), new Date(2026, 10, 1, 8))).toBe("Yesterday");
  });

  it("gives nothing for no items", () => {
    expect(groupByDay([], now)).toEqual([]);
  });
});

describe("a row's progress and resume", () => {
  it("says how far an episode or chapter got", () => {
    expect(targetProgress("anime", { id: 1, name: "", done: true })).toBe("Watched");
    expect(targetProgress("manga", { id: 1, name: "", done: true })).toBe("Read");
    expect(
      targetProgress("anime", {
        id: 1,
        name: "",
        done: false,
        progress: { positionMs: 600_000, durationMs: 1_440_000 },
      }),
    ).toBe("14:00 left");
    expect(
      targetProgress("manga", {
        id: 1,
        name: "",
        done: false,
        pages: { lastPageRead: 12, pageCount: 92 },
      }),
    ).toBe("Page 13 of 92");
    expect(
      targetProgress("manga", {
        id: 1,
        name: "",
        done: false,
        pages: { lastPageRead: 0, pageCount: -1 },
      }),
    ).toBe("");
  });

  it("resumes a chapter in the reader and an episode in its details page's picker", () => {
    const manga = item(7, 1, {
      kind: "manga",
      entry: { kind: "manga", id: 7, title: "M", thumbnailUrl: null, inLibrary: false },
      resume: { id: 31, name: "Chapter 6", done: false },
    });
    expect(resumeRoute(manga)).toBe("/manga/7/read/31");
    expect(resumeRoute(item(4, 1, { resume: { id: 42, name: "Episode 2", done: false } }))).toBe(
      "/anime/4?play=42",
    );
  });

  it("names the next one when Resume moves on, and says again for a finished last one", () => {
    expect(resumeLabel(item(1, 1))).toBe("Resume");
    expect(
      resumeLabel(
        item(1, 1, {
          item: { id: 10, name: "Episode 1", done: true },
          resume: { id: 11, name: "Episode 2", done: false },
        }),
      ),
    ).toBe("Next: Episode 2");
    const last = { id: 10, name: "Episode 1", done: true };
    expect(resumeLabel(item(1, 1, { item: last, resume: last }))).toBe("Watch again");
    expect(resumeLabel(item(1, 1, { kind: "manga", item: last, resume: last }))).toBe("Read again");
  });
});
