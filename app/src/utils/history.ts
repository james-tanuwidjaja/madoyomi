import type { HistoryItem, HistoryTarget } from "../api/types";
import { progressText } from "./progress";

/*
 * The History view's pure parts (Phase 6 Stage 2): the items grouped by local day, a row's time and progress line,
 * and where Resume goes.
 */

export interface HistoryDay<T extends { at: number } = HistoryItem> {
  /** The local day, `YYYY-MM-DD` (a stable key). */
  key: string;
  /** "Today", "Yesterday", else the date ("Monday, November 9", with the year when it isn't this one). */
  label: string;
  items: T[];
}

function dayKey(d: Date): string {
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}

/** The label of `d`'s day, seen from `now`. */
export function dayLabel(d: Date, now: Date): string {
  if (dayKey(d) === dayKey(now)) return "Today";
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (dayKey(d) === dayKey(yesterday)) return "Yesterday";
  return d.toLocaleDateString("en-US", {
    weekday: "long",
    month: "long",
    day: "numeric",
    ...(d.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  });
}

/**
 * `items` (newest first, as the backend gives them) in groups by local day, in the same order. Phase 6 Stage 3: any
 * item with an `at` in epoch seconds (the Updates rows too).
 */
export function groupByDay<T extends { at: number }>(
  items: readonly T[],
  now: Date = new Date(),
): HistoryDay<T>[] {
  const days: HistoryDay<T>[] = [];
  for (const item of items) {
    const d = new Date(item.at * 1000);
    const key = dayKey(d);
    const last = days[days.length - 1];
    if (last && last.key === key) last.items.push(item);
    else days.push({ key, label: dayLabel(d, now), items: [item] });
  }
  return days;
}

/** The time of day an item was played or read ("9:05 PM"). */
export function historyTime(item: { at: number }): string {
  return new Date(item.at * 1000).toLocaleTimeString("en-US", {
    hour: "numeric",
    minute: "2-digit",
  });
}

/** A row's progress line: "Watched", "12:34 left", "Read", "Page 13 of 92", or "". */
export function targetProgress(kind: HistoryItem["kind"], t: HistoryTarget): string {
  if (t.done) return kind === "anime" ? "Watched" : "Read";
  if (t.progress) return progressText(t.progress);
  if (t.pages && t.pages.pageCount > 0) {
    return `Page ${Math.min(t.pages.lastPageRead + 1, t.pages.pageCount)} of ${t.pages.pageCount}`;
  }
  return "";
}

/**
 * Where Resume goes: a chapter opens in the reader (at its page, the reader's own rule), an episode opens its
 * details page with the picker for it (`?play=`), so the resume prompt, the next-episode toast and the live
 * progress work as they do there.
 */
export function resumeRoute(item: HistoryItem): string {
  return item.kind === "manga"
    ? `/manga/${item.entry.id}/read/${item.resume.id}`
    : `/anime/${item.entry.id}?play=${item.resume.id}`;
}

/** The Resume button's label: what it opens when it isn't the item itself ("Next: Episode 4"). */
export function resumeLabel(item: HistoryItem): string {
  if (item.resume.id !== item.item.id) return `Next: ${item.resume.name}`;
  if (item.item.done) return item.kind === "anime" ? "Watch again" : "Read again";
  return "Resume";
}
