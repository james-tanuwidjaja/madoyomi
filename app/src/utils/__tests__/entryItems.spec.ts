import { describe, expect, it } from "vitest";

import {
  activeFilterCount,
  DEFAULT_FILTERS,
  filterItems,
  fromChapter,
  fromEpisode,
  scanlators,
  sortItems,
  type ListItem,
} from "../entryItems";
import { makeChapters, makeEpisodes } from "../../test/fixtures";

const item = (o: Partial<ListItem> & { id: number }): ListItem => ({
  name: `Item ${o.id}`,
  number: o.id,
  date: 0,
  sourceOrder: o.id,
  scanlator: null,
  done: false,
  bookmarked: false,
  filler: false,
  ...o,
});

const ids = (items: ListItem[]) => items.map((i) => i.id);

describe("entry items", () => {
  it("maps episodes and chapters to rows", () => {
    const [e] = makeEpisodes(1);
    expect(fromEpisode({ ...e, fillermark: true, isBookmarked: true, scanlator: "" })).toEqual({
      id: e.id,
      name: "Episode 1",
      number: 1,
      date: Date.UTC(2024, 0, 1),
      sourceOrder: 1,
      scanlator: null,
      done: false,
      bookmarked: true,
      filler: true,
      progress: { positionMs: 0, durationMs: 0 },
    });
    // the stored position, or the live session's for the episode mpv plays
    const stopped = { ...e, lastSecondSeen: "752043", totalSeconds: "1500125" };
    expect(fromEpisode(stopped)).toMatchObject({
      progress: { positionMs: 752043, durationMs: 1500125 },
    });
    expect(fromEpisode(stopped).playing).toBeUndefined();
    const live = {
      id: "s1",
      episodeId: e.id,
      animeId: e.animeId,
      title: "t",
      videoTitle: "v",
      state: "RUNNING" as const,
      startedAt: "1",
      endedAt: null,
      exitCode: null,
      positionSeconds: 800,
      durationSeconds: 1500.125,
      paused: true,
    };
    expect(fromEpisode(stopped, live)).toMatchObject({
      progress: { positionMs: 800000, durationMs: 1500125 },
      playing: "paused",
    });
    expect(fromEpisode(stopped, { ...live, paused: false }).playing).toBe("playing");
    expect(fromEpisode(stopped, { ...live, episodeId: 1 }).playing).toBeUndefined();
    const [c] = makeChapters(1);
    expect(fromChapter({ ...c, isRead: true, scanlator: "TCB", uploadDate: "0" })).toMatchObject({
      number: 1,
      date: 0,
      scanlator: "TCB",
      done: true,
      filler: false,
    });
  });

  it("sorts by the source's order, the number and the date, either way", () => {
    const items = [
      item({ id: 1, sourceOrder: 1, number: 3, date: 200 }),
      item({ id: 2, sourceOrder: 2, number: 1, date: 300 }),
      item({ id: 3, sourceOrder: 3, number: 2, date: 100 }),
    ];
    expect(ids(sortItems(items, { by: "source", desc: true }))).toEqual([3, 2, 1]);
    expect(ids(sortItems(items, { by: "source", desc: false }))).toEqual([1, 2, 3]);
    expect(ids(sortItems(items, { by: "number", desc: false }))).toEqual([2, 3, 1]);
    expect(ids(sortItems(items, { by: "date", desc: true }))).toEqual([2, 1, 3]);
    // a copy: the input keeps its order
    expect(ids(items)).toEqual([1, 2, 3]);
  });

  it("breaks ties by the source's order, in the same direction", () => {
    const items = [
      item({ id: 1, sourceOrder: 1, number: -1 }),
      item({ id: 2, sourceOrder: 2, number: -1 }),
      item({ id: 3, sourceOrder: 3, number: 5 }),
    ];
    expect(ids(sortItems(items, { by: "number", desc: true }))).toEqual([3, 2, 1]);
    expect(ids(sortItems(items, { by: "number", desc: false }))).toEqual([1, 2, 3]);
  });

  it("filters by progress, bookmark and scanlator together", () => {
    const items = [
      item({ id: 1, done: true, scanlator: "A" }),
      item({ id: 2, bookmarked: true, scanlator: "B" }),
      item({ id: 3, bookmarked: true, done: true, scanlator: "A" }),
      item({ id: 4 }),
    ];
    expect(ids(filterItems(items, DEFAULT_FILTERS))).toEqual([1, 2, 3, 4]);
    expect(ids(filterItems(items, { ...DEFAULT_FILTERS, progress: "done" }))).toEqual([1, 3]);
    expect(ids(filterItems(items, { ...DEFAULT_FILTERS, progress: "notDone" }))).toEqual([2, 4]);
    expect(ids(filterItems(items, { ...DEFAULT_FILTERS, bookmarked: true }))).toEqual([2, 3]);
    expect(ids(filterItems(items, { ...DEFAULT_FILTERS, scanlator: "A" }))).toEqual([1, 3]);
    expect(ids(filterItems(items, { progress: "done", bookmarked: true, scanlator: "A" }))).toEqual(
      [3],
    );
  });

  it("lists the scanlators once each, by name, and counts the active filters", () => {
    const items = [
      item({ id: 1, scanlator: "TCB" }),
      item({ id: 2, scanlator: "Asura" }),
      item({ id: 3, scanlator: "TCB" }),
      item({ id: 4 }),
    ];
    expect(scanlators(items)).toEqual(["Asura", "TCB"]);
    expect(activeFilterCount(DEFAULT_FILTERS)).toBe(0);
    expect(activeFilterCount({ progress: "notDone", bookmarked: true, scanlator: "TCB" })).toBe(3);
  });
});
