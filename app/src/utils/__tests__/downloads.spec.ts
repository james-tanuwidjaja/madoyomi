import { describe, expect, it } from "vitest";
import {
  byItem,
  formatBytes,
  itemsOf,
  nextUnread,
  percentOf,
  queueCounts,
  stateText,
} from "../downloads";
import { makeChapters, makeDownloadItem, makeDownloadQueue } from "../../test/fixtures";

/* The download queue's pure helpers (Phase 6 Stage 4). */
describe("download helpers", () => {
  const queue = makeDownloadQueue(
    [
      makeDownloadItem({
        id: 1,
        itemId: 5001,
        state: "DOWNLOADING",
        progressDone: 5,
        progressTotal: 20,
      }),
      makeDownloadItem({ id: 2, itemId: 5002 }),
      makeDownloadItem({ id: 3, itemId: 7001, entryId: 70 }),
      makeDownloadItem({ id: 4, itemId: 9001, kind: "anime", entryId: 9 }),
      makeDownloadItem({ id: 5, itemId: 5003, state: "PAUSED" }),
      makeDownloadItem({ id: 6, itemId: 5004, state: "FAILED", tries: 3, error: "HTTP 403" }),
    ],
    { mangaDone: 12, animeDone: 1 },
  );

  it("picks a kind's items and an entry's by chapter", () => {
    expect(itemsOf(queue, "manga").map((i) => i.id)).toEqual([1, 2, 3, 5, 6]);
    expect(itemsOf(undefined, "manga")).toEqual([]);
    expect([...byItem(queue, "manga", 34).keys()]).toEqual([5001, 5002, 5003, 5004]);
    expect(byItem(queue, "anime", 34).size).toBe(0);
  });

  it("says how far an item got and where it is", () => {
    expect(percentOf(queue.items[0]!)).toBe(25);
    expect(percentOf(makeDownloadItem())).toBeNull();
    expect(percentOf(makeDownloadItem({ state: "DONE" }))).toBe(100);
    expect(stateText(queue.items[0]!)).toBe("Downloading 5/20");
    expect(stateText(makeDownloadItem({ state: "DOWNLOADING" }))).toBe("Downloading…");
    expect(stateText(makeDownloadItem())).toBe("Queued");
    expect(stateText(makeDownloadItem({ tries: 1 }))).toBe("Queued (try 2)");
    expect(stateText(makeDownloadItem({ state: "PAUSED" }))).toBe("Paused");
    expect(stateText(makeDownloadItem({ state: "FAILED" }))).toBe("Failed");
    expect(stateText(makeDownloadItem({ state: "DONE" }))).toBe("Downloaded");
  });

  it("formats sizes", () => {
    expect(formatBytes(0)).toBe("0 KB");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(5.25 * 1024 * 1024)).toBe("5.3 MB");
    expect(formatBytes(150 * 1024 * 1024)).toBe("150 MB");
    expect(formatBytes(2.5 * 1024 ** 3)).toBe("2.5 GB");
  });

  it("chooses the next unread chapters in reading order, without the downloaded or queued ones", () => {
    const chapters = makeChapters(8).map((c, i) => ({
      ...c,
      // the list comes newest first, as a source gives it
      sourceOrder: 8 - i,
      isRead: c.id === 5008,
      isDownloaded: c.id === 5006,
    }));
    const queued = byItem(queue, "manga", 34);
    // by sourceOrder: 5008 (read) 5007 5006 (downloaded) 5005 5004 (queued) 5003 (queued) 5002 (queued) 5001 (queued)
    expect(nextUnread(chapters, 1, queued)).toEqual([5007]);
    expect(nextUnread(chapters, 5, queued)).toEqual([5007, 5005]);
    expect(nextUnread(chapters, "all", new Map())).toEqual([5007, 5005, 5004, 5003, 5002, 5001]);
  });

  it("counts a kind's queue", () => {
    expect(queueCounts(queue, "manga")).toEqual({
      total: 5,
      active: 3,
      downloading: 1,
      paused: 1,
      failed: 1,
      done: 12,
    });
    expect(queueCounts(queue, "anime")).toMatchObject({ total: 1, active: 1, done: 1 });
    expect(queueCounts(undefined, "manga").total).toBe(0);
  });
});
