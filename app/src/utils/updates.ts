import type { LibraryCheck, MediaKind, UpdatesItem } from "../api/types";
import { extensionKindOf } from "../api/types";
import { formatCount } from "./format";

/*
 * The library update check's pure parts: what the Updates view and the Library say about the check,
 * where an Updates row opens, the toast's text, and the subscription's changes the app acts on.
 */

/** "just now", "5 min ago", "2 h ago", "3 days ago", seen from `now` (epoch milliseconds both). */
export function relativeTime(at: number, now: number = Date.now()): string {
  const s = Math.max(0, Math.round((now - at) / 1000));
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} days ago`;
}

/** The time of day of an epoch milliseconds value ("9:05 PM"), with the date when it isn't today. */
export function clockTime(at: number, now: Date = new Date()): string {
  const d = new Date(at);
  const time = d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  if (d.toDateString() === now.toDateString()) return time;
  return `${d.toLocaleDateString("en-US", { month: "short", day: "numeric" })}, ${time}`;
}

/** The new items a check found in `kind`'s entries. */
export function newCountOf(check: LibraryCheck, kind: MediaKind): number {
  return kind === "anime" ? check.newEpisodes : check.newChapters;
}

const itemWord = (kind: MediaKind, n: number) =>
  kind === "anime"
    ? formatCount(n, "new episode", "new episodes")
    : formatCount(n, "new chapter", "new chapters");

/**
 * What a check is doing or did, for the Updates view and the Library's toolbar: "Checking 3 of 12…", or after one
 * "Checked 2 min ago: 3 new episodes" (with "Stopped" or the failures). Null before the first check.
 */
export function checkSummary(
  check: LibraryCheck | undefined,
  kind: MediaKind,
  now = Date.now(),
): string | null {
  if (!check || Number(check.startedAt) === 0) return null;
  if (check.running)
    return `Checking ${check.done.toLocaleString("en-US")} of ${check.total.toLocaleString("en-US")}…`;
  const when = relativeTime(Number(check.finishedAt) || Number(check.startedAt), now);
  const n = newCountOf(check, kind);
  const found = n > 0 ? itemWord(kind, n) : `nothing new`;
  const parts = [`${check.cancelled ? "Stopped" : "Checked"} ${when}: ${found}`];
  const failed = check.failures.filter((f) => f.entry.kind === extensionKindOf(kind)).length;
  if (failed) parts.push(`${formatCount(failed, "entry", "entries")} couldn't be checked`);
  return parts.join(" · ");
}

/** The failures of `kind`'s entries in the last check. */
export function failuresOf(check: LibraryCheck | undefined, kind: MediaKind) {
  return (check?.failures ?? []).filter((f) => f.entry.kind === extensionKindOf(kind));
}

/** Where an Updates row opens: a chapter in the reader, an episode's picker on its details page (as History's Resume). */
export function updatesRoute(item: UpdatesItem): string {
  return item.kind === "manga"
    ? `/manga/${item.entry.id}/read/${item.item.id}`
    : `/anime/${item.entry.id}?play=${item.item.id}`;
}

/**
 * The toast after a check that found something: "3 new episodes, 1 new chapter" and the entries ("Oshi no Ko,
 * Kingdom and 2 more"). Null when it found nothing.
 */
export function toastText(check: LibraryCheck): { title: string; body: string } | null {
  if (check.newEpisodes + check.newChapters === 0) return null;
  const counts = [
    check.newEpisodes ? itemWord("anime", check.newEpisodes) : null,
    check.newChapters ? itemWord("manga", check.newChapters) : null,
  ].filter((x): x is string => x !== null);
  const titles = [...new Set(check.found.map((f) => f.entry.title))];
  const shown = titles.slice(0, 2);
  const rest = titles.length - shown.length;
  const body =
    rest > 0
      ? `${shown.join(", ")} and ${rest.toLocaleString("en-US")} more`
      : shown.length === 2
        ? `${shown[0]} and ${shown[1]}`
        : (shown[0] ?? "");
  return { title: counts.join(", "), body };
}

/** A signature of what a check found so far: when it changes, the found entries' lists are fetched again. */
export function foundSignature(check: LibraryCheck): string {
  return check.found.map((f) => `${f.entry.kind}:${f.entry.id}:${f.newCount}`).join(",");
}
