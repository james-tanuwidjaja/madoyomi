import type { Filter, FilterChange, GroupFilter, SortSelection, TriState } from "../api/types";

/*
 * A source's filter values, and the `FilterChange[]` the backend takes (Phase 3 step 18, §7 "filter state must
 * round-trip exactly").
 *
 * The backend applies the changes to a fresh `getFilterList()` on every request (upstream's `updateFilterList`), so
 * only what differs from a filter's default is sent: a filter left at its default sends nothing, and the empty list is
 * the source's own defaults. `position` is the index in the list; a group's member is sent as
 * `{position: <group's index>, groupChange: {position: <member's index>, …}}`, one change per member.
 */

/** One filter's value: select index, text, checkbox, tri-state, sort, or null for a header or separator. */
export type LeafValue = number | string | boolean | TriState | SortSelection | null;
/** A group's value is its members' values, in order. */
export type FilterValue = LeafValue | LeafValue[];
/** The values of a whole filter list, parallel to it. */
export type FilterState = FilterValue[];

type LeafFilter = Exclude<Filter, GroupFilter>;

function defaultLeaf(f: LeafFilter): LeafValue {
  switch (f.__typename) {
    case "SelectFilter":
      return f.selectDefault;
    case "TextFilter":
      return f.textDefault;
    case "CheckBoxFilter":
      return f.checkBoxDefault;
    case "TriStateFilter":
      return f.triStateDefault;
    case "SortFilter":
      return f.sortDefault ? { ...f.sortDefault } : null;
    default:
      return null;
  }
}

/** The source's defaults: what an empty change list means. */
export function defaultFilterState(filters: readonly Filter[]): FilterState {
  return filters.map((f) =>
    f.__typename === "GroupFilter" ? f.filters.map(defaultLeaf) : defaultLeaf(f),
  );
}

function sameLeaf(a: LeafValue, b: LeafValue): boolean {
  if (a !== null && b !== null && typeof a === "object" && typeof b === "object") {
    return a.index === b.index && a.ascending === b.ascending;
  }
  return a === b;
}

/** The change for one filter at `position`, or null when it can't carry one (a header, a separator, no sort). */
function leafChange(f: LeafFilter, position: number, v: LeafValue): FilterChange | null {
  switch (f.__typename) {
    case "SelectFilter":
      return typeof v === "number" ? { position, selectState: v } : null;
    case "TextFilter":
      return typeof v === "string" ? { position, textState: v } : null;
    case "CheckBoxFilter":
      return typeof v === "boolean" ? { position, checkBoxState: v } : null;
    case "TriStateFilter":
      return typeof v === "string" ? { position, triState: v as TriState } : null;
    case "SortFilter":
      return v !== null && typeof v === "object"
        ? { position, sortState: { index: v.index, ascending: v.ascending } }
        : null;
    default:
      return null;
  }
}

/** What to send for `state`: the filters that differ from their defaults, by position. */
export function filterChanges(filters: readonly Filter[], state: FilterState): FilterChange[] {
  const changes: FilterChange[] = [];
  filters.forEach((f, i) => {
    if (f.__typename === "GroupFilter") {
      const members = Array.isArray(state[i]) ? (state[i] as LeafValue[]) : [];
      f.filters.forEach((child, j) => {
        const v = j < members.length ? members[j] : defaultLeaf(child);
        if (sameLeaf(v, defaultLeaf(child))) return;
        const change = leafChange(child, j, v);
        if (change) changes.push({ position: i, groupChange: change });
      });
      return;
    }
    const v = state[i] as LeafValue;
    if (v === undefined || sameLeaf(v, defaultLeaf(f))) return;
    const change = leafChange(f, i, v);
    if (change) changes.push(change);
  });
  return changes;
}

function leafValueOf(f: LeafFilter, c: FilterChange): LeafValue | undefined {
  switch (f.__typename) {
    case "SelectFilter":
      return c.selectState;
    case "TextFilter":
      return c.textState;
    case "CheckBoxFilter":
      return c.checkBoxState;
    case "TriStateFilter":
      return c.triState;
    case "SortFilter":
      return c.sortState ? { ...c.sortState } : undefined;
    default:
      return undefined;
  }
}

/** The inverse of {@link filterChanges}: the state a change list stands for. Changes that don't fit are ignored. */
export function filterStateFromChanges(
  filters: readonly Filter[],
  changes: readonly FilterChange[],
): FilterState {
  const state = defaultFilterState(filters);
  for (const c of changes) {
    const f = filters[c.position];
    if (!f) continue;
    if (f.__typename === "GroupFilter") {
      const g = c.groupChange;
      const child = g ? f.filters[g.position] : undefined;
      const v = g && child ? leafValueOf(child, g) : undefined;
      if (g && v !== undefined) (state[c.position] as LeafValue[])[g.position] = v;
      continue;
    }
    const v = leafValueOf(f, c);
    if (v !== undefined) state[c.position] = v;
  }
  return state;
}

/** How many filters differ from their defaults (each group member counts). */
export function activeFilterCount(filters: readonly Filter[], state: FilterState): number {
  return filterChanges(filters, state).length;
}

/** `state` with the value at `[index]` or, inside a group, `[index, member]` replaced. */
export function withFilterValue(
  state: FilterState,
  path: readonly [number] | readonly [number, number],
  value: LeafValue,
): FilterState {
  const next = state.slice();
  if (path.length === 1) {
    next[path[0]] = value;
  } else {
    const members = (Array.isArray(next[path[0]]) ? (next[path[0]] as LeafValue[]) : []).slice();
    members[path[1]] = value;
    next[path[0]] = members;
  }
  return next;
}

/**
 * What a stored state depends on: each filter's kind, name and options. A state kept for a source is dropped when an
 * extension update changes this (the positions would point elsewhere).
 */
export function filterSignature(filters: readonly Filter[]): string {
  const leaf = (f: LeafFilter) =>
    f.__typename === "SelectFilter" || f.__typename === "SortFilter"
      ? [f.__typename, f.name, f.values]
      : [f.__typename, f.name];
  return JSON.stringify(
    filters.map((f) =>
      f.__typename === "GroupFilter" ? [f.__typename, f.name, f.filters.map(leaf)] : leaf(f),
    ),
  );
}
