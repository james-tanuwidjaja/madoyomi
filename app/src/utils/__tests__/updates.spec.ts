import { describe, expect, it } from "vitest";
import type { LibraryCheck, UpdatesItem } from "../../api/types";
import { makeLibraryCheck } from "../../test/fixtures";
import {
  checkSummary,
  failuresOf,
  foundSignature,
  relativeTime,
  toastText,
  updatesRoute,
} from "../updates";

/* The library update check's pure parts (Phase 6 Stage 3). */

const NOW = Date.UTC(2026, 10, 15, 12, 0, 0);
const entry = (kind: "ANIME" | "MANGA", id: number, title: string) => ({ kind, id, title });

function finished(over: Partial<LibraryCheck> = {}): LibraryCheck {
  return makeLibraryCheck({
    trigger: "SCHEDULED",
    startedAt: String(NOW - 10 * 60_000),
    finishedAt: String(NOW - 5 * 60_000),
    total: 4,
    done: 4,
    ...over,
  });
}

describe("relativeTime", () => {
  it("says how long ago, in rough units", () => {
    expect(relativeTime(NOW - 20_000, NOW)).toBe("just now");
    expect(relativeTime(NOW - 5 * 60_000, NOW)).toBe("5 min ago");
    expect(relativeTime(NOW - 3 * 3_600_000, NOW)).toBe("3 h ago");
    expect(relativeTime(NOW - 4 * 86_400_000, NOW)).toBe("4 days ago");
    expect(relativeTime(NOW + 60_000, NOW)).toBe("just now");
  });
});

describe("checkSummary", () => {
  it("says nothing before the first check, and how far a running one got", () => {
    expect(checkSummary(undefined, "anime", NOW)).toBeNull();
    expect(checkSummary(makeLibraryCheck(), "anime", NOW)).toBeNull();
    expect(
      checkSummary(
        makeLibraryCheck({ running: true, startedAt: String(NOW), total: 12, done: 3 }),
        "anime",
        NOW,
      ),
    ).toBe("Checking 3 of 12…");
  });

  it("says what the last check found for the kind, and which entries it couldn't reach", () => {
    const check = finished({
      newEpisodes: 3,
      newChapters: 1,
      failures: [
        { entry: entry("MANGA", 4, "Theme World"), error: "No chapters found" },
        { entry: entry("MANGA", 5, "Other"), error: "HTTP 503" },
      ],
    });
    expect(checkSummary(check, "anime", NOW)).toBe("Checked 5 min ago: 3 new episodes");
    expect(checkSummary(check, "manga", NOW)).toBe(
      "Checked 5 min ago: 1 new chapter · 2 entries couldn't be checked",
    );
    expect(checkSummary(finished({ cancelled: true }), "manga", NOW)).toBe(
      "Stopped 5 min ago: nothing new",
    );
    expect(failuresOf(check, "manga").map((f) => f.entry.title)).toEqual(["Theme World", "Other"]);
    expect(failuresOf(check, "anime")).toEqual([]);
  });
});

describe("toastText", () => {
  it("is null for a check that found nothing", () => {
    expect(toastText(finished())).toBeNull();
  });

  it("counts the new items of each kind and names the entries", () => {
    expect(
      toastText(
        finished({
          newEpisodes: 1,
          found: [{ entry: entry("ANIME", 1, "Oshi no Ko"), newCount: 1 }],
        }),
      ),
    ).toEqual({ title: "1 new episode", body: "Oshi no Ko" });
    expect(
      toastText(
        finished({
          newEpisodes: 2,
          newChapters: 3,
          found: [
            { entry: entry("ANIME", 1, "Oshi no Ko"), newCount: 2 },
            { entry: entry("MANGA", 7, "Kingdom"), newCount: 3 },
          ],
        }),
      ),
    ).toEqual({ title: "2 new episodes, 3 new chapters", body: "Oshi no Ko and Kingdom" });
    expect(
      toastText(
        finished({
          newChapters: 4,
          found: ["A", "B", "C", "D"].map((t, i) => ({ entry: entry("MANGA", i, t), newCount: 1 })),
        }),
      ),
    ).toEqual({ title: "4 new chapters", body: "A, B and 2 more" });
  });
});

describe("updatesRoute and foundSignature", () => {
  it("opens a chapter in the reader and an episode's picker on its details page", () => {
    const item = (kind: "anime" | "manga"): UpdatesItem => ({
      kind,
      entry: { kind, id: 5, title: "T", thumbnailUrl: null, inLibrary: true },
      item: { id: 42, name: "N", done: false },
      at: 0,
    });
    expect(updatesRoute(item("manga"))).toBe("/manga/5/read/42");
    expect(updatesRoute(item("anime"))).toBe("/anime/5?play=42");
  });

  it("changes when a check finds more", () => {
    const one = finished({ found: [{ entry: entry("ANIME", 1, "A"), newCount: 1 }] });
    const more = finished({ found: [{ entry: entry("ANIME", 1, "A"), newCount: 2 }] });
    expect(foundSignature(finished())).toBe("");
    expect(foundSignature(one)).not.toBe(foundSignature(more));
  });
});
