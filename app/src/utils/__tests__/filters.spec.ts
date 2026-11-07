import { describe, expect, it } from "vitest";

import type { Filter, FilterChange } from "../../api/types";
import {
  allFilterTypes,
  serverFixtureChanges,
  serverFixtureFilters,
} from "../../test/filterFixtures";
import {
  activeFilterCount,
  defaultFilterState,
  filterChanges,
  filterSignature,
  filterStateFromChanges,
  withFilterValue,
} from "../filters";

describe("filter state", () => {
  it("starts at the source's defaults, which send nothing", () => {
    const state = defaultFilterState(allFilterTypes);

    expect(state).toEqual([
      null,
      1,
      "2024",
      null,
      true,
      "INCLUDE",
      null,
      { index: 0, ascending: false },
      ["IGNORE", false, "IGNORE"],
    ]);
    expect(filterChanges(allFilterTypes, state)).toEqual([]);
    expect(activeFilterCount(allFilterTypes, state)).toBe(0);
  });

  it("sends the change list of the server's own browse test, for the same filter values", () => {
    let state = defaultFilterState(serverFixtureFilters);
    state = withFilterValue(state, [1], 2);
    state = withFilterValue(state, [2], "MAPPA");
    state = withFilterValue(state, [3], true);
    state = withFilterValue(state, [4, 1], "EXCLUDE");

    expect(filterChanges(serverFixtureFilters, state)).toEqual(serverFixtureChanges);
  });

  it("sends each kind's state field, by position", () => {
    let state = defaultFilterState(allFilterTypes);
    state = withFilterValue(state, [1], 3);
    state = withFilterValue(state, [2], "");
    state = withFilterValue(state, [4], false);
    state = withFilterValue(state, [5], "EXCLUDE");
    state = withFilterValue(state, [6], { index: 2, ascending: true });
    state = withFilterValue(state, [7], { index: 0, ascending: true });

    expect(filterChanges(allFilterTypes, state)).toEqual([
      { position: 1, selectState: 3 },
      { position: 2, textState: "" },
      { position: 4, checkBoxState: false },
      { position: 5, triState: "EXCLUDE" },
      { position: 6, sortState: { index: 2, ascending: true } },
      { position: 7, sortState: { index: 0, ascending: true } },
    ]);
  });

  it("sends one groupChange per changed member, with the member's position in the group", () => {
    let state = defaultFilterState(allFilterTypes);
    state = withFilterValue(state, [8, 0], "INCLUDE");
    state = withFilterValue(state, [8, 2], "EXCLUDE");

    expect(filterChanges(allFilterTypes, state)).toEqual([
      { position: 8, groupChange: { position: 0, triState: "INCLUDE" } },
      { position: 8, groupChange: { position: 2, triState: "EXCLUDE" } },
    ]);
    expect(activeFilterCount(allFilterTypes, state)).toBe(2);
  });

  it("doesn't send a value set back to its default", () => {
    let state = defaultFilterState(allFilterTypes);
    state = withFilterValue(state, [1], 2);
    state = withFilterValue(state, [1], 1);
    state = withFilterValue(state, [7], { index: 0, ascending: false });
    state = withFilterValue(state, [8, 1], true);
    state = withFilterValue(state, [8, 1], false);

    expect(filterChanges(allFilterTypes, state)).toEqual([]);
  });

  it("never sends headers or separators", () => {
    const state = withFilterValue(defaultFilterState(allFilterTypes), [0], "x");
    expect(filterChanges(allFilterTypes, state)).toEqual([]);
  });

  it("round-trips: changes → state → the same changes", () => {
    const changes: FilterChange[] = [
      { position: 1, selectState: 0 },
      { position: 2, textState: "1999" },
      { position: 5, triState: "IGNORE" },
      { position: 6, sortState: { index: 1, ascending: false } },
      { position: 8, groupChange: { position: 1, checkBoxState: true } },
      { position: 8, groupChange: { position: 2, triState: "INCLUDE" } },
    ];
    const state = filterStateFromChanges(allFilterTypes, changes);

    expect(filterChanges(allFilterTypes, state)).toEqual(changes);
    expect(filterStateFromChanges(serverFixtureFilters, serverFixtureChanges)).toEqual([
      null,
      2,
      "MAPPA",
      true,
      ["IGNORE", "EXCLUDE"],
    ]);
  });

  it("resets to the defaults", () => {
    const state = filterStateFromChanges(serverFixtureFilters, serverFixtureChanges);
    expect(activeFilterCount(serverFixtureFilters, state)).toBe(4);

    const reset = defaultFilterState(serverFixtureFilters);
    expect(filterChanges(serverFixtureFilters, reset)).toEqual([]);
  });

  it("doesn't change the state it is given", () => {
    const state = defaultFilterState(allFilterTypes);
    const before = JSON.stringify(state);
    withFilterValue(state, [1], 0);
    withFilterValue(state, [8, 0], "INCLUDE");
    expect(JSON.stringify(state)).toBe(before);
  });

  it("has a signature that changes with the filters' kinds, names and options", () => {
    const sig = filterSignature(serverFixtureFilters);
    expect(filterSignature(structuredClone(serverFixtureFilters))).toBe(sig);

    const renamed = structuredClone(serverFixtureFilters) as Filter[];
    (renamed[2] as { name: string }).name = "Studios";
    const moreOptions = structuredClone(serverFixtureFilters) as Filter[];
    (moreOptions[1] as { values: string[] }).values.push("4K");
    const reordered = [...serverFixtureFilters].reverse();

    for (const other of [renamed, moreOptions, reordered])
      expect(filterSignature(other)).not.toBe(sig);
    // a new default alone doesn't move any position
    const newDefault = structuredClone(serverFixtureFilters) as Filter[];
    (newDefault[1] as { selectDefault: number }).selectDefault = 1;
    expect(filterSignature(newDefault)).toBe(sig);
  });
});
