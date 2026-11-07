import type {
  CheckBoxPreference,
  EditTextPreference,
  ListPreference,
  MultiSelectListPreference,
  Preference,
  SwitchPreference,
} from "../api/types";

/*
 * Preference screens as the server returns them (upstream's `Preference` union, aliased as in types.ts), modelled on
 * AnimePahe's 7 preferences (Phase 2 §10: lists, switches, an edit text) and MangaDex's content-rating multi-select.
 */

const base = { visible: true, enabled: true };

export const qualityList: ListPreference = {
  ...base,
  __typename: "ListPreference",
  key: "preferred_quality",
  title: "Preferred quality",
  summary: "%s",
  listCurrentValue: null,
  listDefault: "1080",
  entries: ["1080p", "720p", "360p"],
  entryValues: ["1080", "720", "360"],
};

export const siteNumberSwitch: SwitchPreference = {
  ...base,
  __typename: "SwitchPreference",
  key: "pref_show_site_number",
  title: "Use the site's episode numbers",
  summary: "Instead of counting from 1",
  switchCurrentValue: false,
  switchDefault: false,
};

export const nsfwCheckBox: CheckBoxPreference = {
  ...base,
  __typename: "CheckBoxPreference",
  key: "show_nsfw",
  title: "Show 18+ results",
  summary: null,
  checkBoxCurrentValue: true,
  checkBoxDefault: false,
};

export const userAgentText: EditTextPreference = {
  ...base,
  __typename: "EditTextPreference",
  key: "cf_bypass_ua",
  title: "User agent",
  summary: null,
  editTextCurrentValue: "Mozilla/5.0",
  editTextDefault: "",
  dialogTitle: "Custom user agent",
  dialogMessage: "Leave empty for the default.",
  text: "Mozilla/5.0",
};

export const contentRatingMulti: MultiSelectListPreference = {
  ...base,
  __typename: "MultiSelectListPreference",
  key: "content_rating",
  title: "Content rating",
  summary: "Which ratings to show",
  multiSelectCurrentValue: ["safe", "suggestive"],
  multiSelectDefault: ["safe"],
  dialogTitle: "Default content rating",
  dialogMessage: null,
  entries: ["Safe", "Suggestive", "Erotica"],
  entryValues: ["safe", "suggestive", "erotica"],
};

export const disabledSwitch: SwitchPreference = {
  ...siteNumberSwitch,
  key: "preferred_av1",
  title: "Prefer AV1",
  summary: "Not available on this site",
  enabled: false,
};

export const hiddenList: ListPreference = {
  ...qualityList,
  key: "hidden_mirror",
  title: "Hidden mirror",
  visible: false,
};

/**
 * One of each kind, plus a disabled and a hidden one, in this order:
 * 0 list, 1 switch, 2 checkbox, 3 edit text, 4 multi-select, 5 disabled switch, 6 hidden list.
 */
export const allPreferenceTypes: Preference[] = [
  qualityList,
  siteNumberSwitch,
  nsfwCheckBox,
  userAgentText,
  contentRatingMulti,
  disabledSwitch,
  hiddenList,
];
