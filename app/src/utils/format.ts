/** Epoch seconds (a LongString, `"0"` for never) as local date and time. */
export function formatEpochSeconds(seconds: string | number | null | undefined): string {
  const n = Number(seconds ?? 0);
  if (!Number.isFinite(n) || n <= 0) return "never";
  return new Date(n * 1000).toLocaleString();
}

/** Epoch milliseconds as a local time of day. */
export function formatTime(ms: number): string {
  return new Date(ms).toLocaleTimeString();
}

/** A language code as a heading: "all" and "other" first-letter capitalised, codes upper-cased ("EN", "PT-BR"). */
export function formatLang(lang: string): string {
  if (lang === "all") return "All languages";
  if (lang === "other" || lang === "localsourcelang") return "Other";
  return lang.toUpperCase();
}

/** An extension's language as a badge or filter option: like {@link formatLang}, but `all` is "Multi-language". */
export function formatExtensionLang(lang: string): string {
  return lang === "all" ? "Multi-language" : formatLang(lang);
}

/** Epoch milliseconds (a LongString, `"0"` when the source gave none) as a local date, or "" without one. */
export function formatDate(ms: string | number | null | undefined): string {
  const n = Number(ms ?? 0);
  if (!Number.isFinite(n) || n <= 0) return "";
  return new Date(n).toLocaleDateString();
}

const STATUS_LABELS: Record<string, string> = {
  UNKNOWN: "Unknown status",
  ONGOING: "Ongoing",
  COMPLETED: "Completed",
  LICENSED: "Licensed",
  PUBLISHING_FINISHED: "Publishing finished",
  CANCELLED: "Cancelled",
  ON_HIATUS: "On hiatus",
  UPCOMING: "Upcoming",
};

/** An entry's status (`MangaStatus`, plus anime's `UPCOMING`) as a label. */
export function formatStatus(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

/** A number of things with its noun ("1 episode", "1,234 episodes"). */
export function formatCount(n: number, one: string, many: string): string {
  return `${n.toLocaleString("en-US")} ${n === 1 ? one : many}`;
}
