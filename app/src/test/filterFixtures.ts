import type { Filter, FilterChange } from "../api/types";

/*
 * Shared filter fixtures (Phase 3 §7: "keep backend + UI tests in sync with shared fixtures").
 *
 * `serverFixtureFilters` is the fake source of server/api/src/test/kotlin/madoyomi/api/AnimeBrowseApiTest.kt as the
 * GraphQL `filters` field returns it: Header "Options", Quality (Select), Studio (Text), Dubbed (CheckBox), and the
 * Genres group of two tri-states. `serverFixtureChanges` is the exact change list that test sends, which the backend
 * applies to give Quality = 2, Studio = "MAPPA", Dubbed = true, Genres = [IGNORE, EXCLUDE]. Change one side, change
 * the other.
 */
export const serverFixtureFilters: Filter[] = [
  { __typename: "HeaderFilter", name: "Options" },
  {
    __typename: "SelectFilter",
    name: "Quality",
    values: ["Any", "720p", "1080p"],
    selectDefault: 0,
  },
  { __typename: "TextFilter", name: "Studio", textDefault: "" },
  { __typename: "CheckBoxFilter", name: "Dubbed", checkBoxDefault: false },
  {
    __typename: "GroupFilter",
    name: "Genres",
    filters: [
      { __typename: "TriStateFilter", name: "Action", triStateDefault: "IGNORE" },
      { __typename: "TriStateFilter", name: "Drama", triStateDefault: "IGNORE" },
    ],
  },
];

export const serverFixtureChanges: FilterChange[] = [
  { position: 1, selectState: 2 },
  { position: 2, textState: "MAPPA" },
  { position: 3, checkBoxState: true },
  { position: 4, groupChange: { position: 1, triState: "EXCLUDE" } },
];

/**
 * Every filter kind, with the defaults a real source has: a select that doesn't start at 0, a text with a value, a
 * checkbox on, a tri-state that starts included, a sort with no default and one with a default, a separator, and a
 * group mixing kinds.
 */
export const allFilterTypes: Filter[] = [
  { __typename: "HeaderFilter", name: "Search options" },
  {
    __typename: "SelectFilter",
    name: "Type",
    values: ["All", "TV", "Movie", "OVA"],
    selectDefault: 1,
  },
  { __typename: "TextFilter", name: "Year", textDefault: "2024" },
  { __typename: "SeparatorFilter", name: "" },
  { __typename: "CheckBoxFilter", name: "Hide adult", checkBoxDefault: true },
  { __typename: "TriStateFilter", name: "Completed", triStateDefault: "INCLUDE" },
  {
    __typename: "SortFilter",
    name: "Order by",
    values: ["Title", "Score", "Date"],
    sortDefault: null,
  },
  {
    __typename: "SortFilter",
    name: "Sort",
    values: ["Popularity", "Rating"],
    sortDefault: { index: 0, ascending: false },
  },
  {
    __typename: "GroupFilter",
    name: "Genres",
    filters: [
      { __typename: "TriStateFilter", name: "Action", triStateDefault: "IGNORE" },
      { __typename: "CheckBoxFilter", name: "Comedy", checkBoxDefault: false },
      { __typename: "TriStateFilter", name: "Drama", triStateDefault: "IGNORE" },
    ],
  },
];
