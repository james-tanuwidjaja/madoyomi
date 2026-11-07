import type { Preference, SourcePreferenceChange } from "../api/types";

/*
 * A source's preferences (upstream's `Preference` union) as values: what each shows, the change that sets one, the
 * preference with a change applied (the optimistic cache), and whether the server's answer took it.
 */

/** A preference's value: a switch's or checkbox's state, a text, a list's entry value, a multi-select's values. */
export type PreferenceValue = boolean | string | string[];

/** The value shown: the stored one, or the default when nothing is stored. */
export function preferenceValue(p: Preference): PreferenceValue {
  switch (p.__typename) {
    case "SwitchPreference":
      return p.switchCurrentValue ?? p.switchDefault;
    case "CheckBoxPreference":
      return p.checkBoxCurrentValue ?? p.checkBoxDefault;
    case "EditTextPreference":
      return p.editTextCurrentValue ?? p.text ?? p.editTextDefault ?? "";
    case "ListPreference":
      return p.listCurrentValue ?? p.listDefault ?? "";
    case "MultiSelectListPreference":
      return p.multiSelectCurrentValue ?? p.multiSelectDefault ?? [];
  }
}

/** The change that sets `p` (at `position` in the source's list) to `value`: one field, for its kind. */
export function changeFor(
  position: number,
  p: Preference,
  value: PreferenceValue,
): SourcePreferenceChange {
  switch (p.__typename) {
    case "SwitchPreference":
      return { position, switchState: value as boolean };
    case "CheckBoxPreference":
      return { position, checkBoxState: value as boolean };
    case "EditTextPreference":
      return { position, editTextState: value as string };
    case "ListPreference":
      return { position, listState: value as string };
    case "MultiSelectListPreference":
      return { position, multiSelectState: [...(value as string[])] };
  }
}

/** The value a change sets, for `p`'s kind; undefined if the change has no field for it. */
export function changeValue(
  p: Preference,
  change: SourcePreferenceChange,
): PreferenceValue | undefined {
  switch (p.__typename) {
    case "SwitchPreference":
      return change.switchState;
    case "CheckBoxPreference":
      return change.checkBoxState;
    case "EditTextPreference":
      return change.editTextState;
    case "ListPreference":
      return change.listState;
    case "MultiSelectListPreference":
      return change.multiSelectState;
  }
}

/** `p` with `change` applied, as the server would store it if the extension agrees. */
export function withChange(p: Preference, change: SourcePreferenceChange): Preference {
  const v = changeValue(p, change);
  if (v === undefined) return p;
  switch (p.__typename) {
    case "SwitchPreference":
      return { ...p, switchCurrentValue: v as boolean };
    case "CheckBoxPreference":
      return { ...p, checkBoxCurrentValue: v as boolean };
    case "EditTextPreference":
      return { ...p, editTextCurrentValue: v as string, text: v as string };
    case "ListPreference":
      return { ...p, listCurrentValue: v as string };
    case "MultiSelectListPreference":
      return { ...p, multiSelectCurrentValue: [...(v as string[])] };
  }
}

export function sameValue(a: PreferenceValue, b: PreferenceValue): boolean {
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b)) return false;
    const set = new Set(a);
    return set.size === new Set(b).size && b.every((x) => set.has(x));
  }
  return a === b;
}

/**
 * Whether the server's preference (after the change) has the value the change sent. False means the extension's
 * change listener refused it: the server then answers with the value it kept, not with an error. A multi-select is
 * a set, so its order doesn't count.
 */
export function changeTaken(after: Preference, change: SourcePreferenceChange): boolean {
  const sent = changeValue(after, change);
  return sent === undefined || sameValue(preferenceValue(after), sent);
}

/** A list or multi-select value's label (its entry); the value itself when the list doesn't have it. */
export function entryLabel(p: { entries: string[]; entryValues: string[] }, value: string): string {
  const i = p.entryValues.indexOf(value);
  return i >= 0 ? (p.entries[i] ?? value) : value;
}

/** How the value reads in a sentence ("On", the entry's label, the text, the labels joined). */
export function valueLabel(p: Preference, value: PreferenceValue = preferenceValue(p)): string {
  switch (p.__typename) {
    case "SwitchPreference":
    case "CheckBoxPreference":
      return value ? "on" : "off";
    case "EditTextPreference":
      return (value as string) === "" ? "empty" : `"${value as string}"`;
    case "ListPreference":
      return entryLabel(p, value as string) || "none";
    case "MultiSelectListPreference": {
      const labels = (value as string[]).map((v) => entryLabel(p, v));
      return labels.length ? labels.join(", ") : "none";
    }
  }
}

/**
 * The line under a preference's title. A list's `%s` is its current entry, as Android's `ListPreference` formats its
 * summary (`%%` is a literal percent sign). An edit text without a summary shows its value.
 */
export function preferenceSummary(p: Preference): string | null {
  if (p.__typename === "ListPreference" && p.summary?.includes("%")) {
    const entry = entryLabel(p, preferenceValue(p) as string);
    return p.summary.replace(/%%|%s/g, (m) => (m === "%%" ? "%" : entry));
  }
  if (p.__typename === "EditTextPreference" && !p.summary) {
    const v = preferenceValue(p) as string;
    return v === "" ? null : v;
  }
  return p.summary;
}

/**
 * An edit text's value, shown under its summary when it has one (without one, the summary line is the value): the
 * row would otherwise not say what is stored. Null when empty.
 */
export function editTextValueLine(p: Preference): string | null {
  if (p.__typename !== "EditTextPreference" || !p.summary) return null;
  const v = preferenceValue(p) as string;
  return v === "" ? null : v;
}
