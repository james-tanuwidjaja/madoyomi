import { describe, expect, it } from "vitest";
import {
  applyAssignment,
  assignmentPatch,
  CATEGORY_SORT_KEY,
  categoryNameError,
  categorySort,
  entriesIn,
  formatSort,
  libraryTabs,
  membership,
  nextMembership,
  parseSort,
  pickTab,
  withSort,
  type Membership,
} from "../categories";
import { makeCategory, makeDefaultCategory, makeLibraryEntry } from "../../test/fixtures";

/* The Library's categories: tabs, sorts, names and the assign dialog's tri-state. */
const def = makeDefaultCategory();
const watching = makeCategory({ id: 1, order: 1, name: "Watching" });
const later = makeCategory({ id: 2, order: 2, name: "Later" });
const a = makeLibraryEntry({ id: 10, categoryIds: [1] });
const b = makeLibraryEntry({ id: 11, categoryIds: [1, 2] });
const c = makeLibraryEntry({ id: 12, categoryIds: [] });

describe("libraryTabs", () => {
  it("has no tabs while Default is the only category", () => {
    expect(libraryTabs([def], [a, b, c])).toEqual([]);
    expect(libraryTabs([], [a])).toEqual([]);
  });

  it("lists every category in order with its count, Default only while it holds entries", () => {
    const tabs = libraryTabs([def, watching, later], [a, b, c]);
    expect(tabs.map((t) => [t.category.name, t.count])).toEqual([
      ["Default", 1],
      ["Watching", 2],
      ["Later", 1],
    ]);
    expect(libraryTabs([def, watching, later], [a, b]).map((t) => t.category.name)).toEqual([
      "Watching",
      "Later",
    ]);
    // an empty category still has its tab
    expect(libraryTabs([def, watching, later], [c]).map((t) => t.count)).toEqual([1, 0, 0]);
  });

  it("puts Default's entries in no other category", () => {
    expect(entriesIn([a, b, c], def)).toEqual([c]);
    expect(entriesIn([a, b, c], later)).toEqual([b]);
  });

  it("opens the remembered tab, else the first", () => {
    const tabs = libraryTabs([def, watching, later], [a, b]);
    expect(pickTab(tabs, 2)?.category.id).toBe(2);
    expect(pickTab(tabs, 0)?.category.id).toBe(1); // Default is hidden
    expect(pickTab(tabs, null)?.category.id).toBe(1);
    expect(pickTab([], 1)).toBeNull();
  });
});

describe("a category's sort", () => {
  it("is read from and written to its meta", () => {
    expect(parseSort("title:asc")).toEqual({ by: "title", desc: false });
    expect(parseSort("added:desc")).toEqual({ by: "added", desc: true });
    expect(parseSort("unread:desc")).toEqual({ by: "unread", desc: true });
    expect(parseSort("lastRead:asc")).toEqual({ by: "lastRead", desc: false });
    for (const bad of [null, undefined, "", "title", "unseen:asc", "title:up"]) {
      expect(parseSort(bad)).toBeNull();
    }
    expect(formatSort({ by: "added", desc: false })).toBe("added:asc");

    expect(categorySort(watching)).toBeNull();
    const sorted = withSort(
      {
        ...watching,
        meta: [
          { key: "other", value: "1" },
          { key: CATEGORY_SORT_KEY, value: "x" },
        ],
      },
      { by: "title", desc: true },
    );
    expect(sorted.meta).toEqual([
      { key: "other", value: "1" },
      { key: CATEGORY_SORT_KEY, value: "title:desc" },
    ]);
    expect(categorySort(sorted)).toEqual({ by: "title", desc: true });
  });
});

describe("categoryNameError", () => {
  it("wants a trimmed, unique name of at most 64 characters that isn't Default", () => {
    const list = [def, watching, later];
    expect(categoryNameError("  Plan  ", list)).toBeNull();
    expect(categoryNameError("   ", list)).toBe("Enter a name.");
    expect(categoryNameError("x".repeat(65), list)).toBe("Use at most 64 characters.");
    expect(categoryNameError("default", list)).toContain("built-in");
    expect(categoryNameError(" WATCHING ", list)).toBe(
      'There is a category named "WATCHING" already.',
    );
    // a rename may keep its own name, in another case
    expect(categoryNameError("watching", list, 1)).toBeNull();
    expect(categoryNameError("Later", list, 1)).not.toBeNull();
  });
});

describe("the assign dialog's states", () => {
  it("are all, some or none of the selected entries", () => {
    expect(membership([a, b], 1)).toBe("all");
    expect(membership([a, b], 2)).toBe("some");
    expect(membership([a, c], 2)).toBe("none");
    expect(nextMembership("some")).toBe("all");
    expect(nextMembership("all")).toBe("none");
    expect(nextMembership("none")).toBe("all");
  });

  it("send only what changed; a box left mixed changes nothing", () => {
    const before = new Map<number, Membership>([
      [1, "all"],
      [2, "some"],
      [3, "none"],
      [4, "some"],
    ]);
    const after = new Map<number, Membership>([
      [1, "none"],
      [2, "some"],
      [3, "all"],
      [4, "all"],
      [5, "all"],
    ]);
    expect(assignmentPatch(before, after)).toEqual({ add: [3, 4, 5], remove: [1] });
    expect(assignmentPatch(before, before)).toEqual({ add: [], remove: [] });
  });

  it("are applied to the cached entries", () => {
    expect(applyAssignment([a, b, c], [11, 12], { add: [3], remove: [1] })).toEqual([
      a,
      { ...b, categoryIds: [2, 3] },
      { ...c, categoryIds: [3] },
    ]);
  });
});
