import type { Chapter, DownloadItem, DownloadQueue, MediaKind } from "../api/types";

/*
 * The download queue as the app shows it (Phase 6 Stage 4): pure helpers for the Downloads screen and the chapter
 * list.
 */

/** The unfinished items of `kind`, in queue order. */
export function itemsOf(queue: DownloadQueue | undefined, kind: MediaKind): DownloadItem[] {
  return (queue?.items ?? []).filter((i) => i.kind === kind);
}

/** The unfinished downloads of one entry by chapter (or episode) id. */
export function byItem(
  queue: DownloadQueue | undefined,
  kind: MediaKind,
  entryId: number,
): Map<number, DownloadItem> {
  return new Map(
    itemsOf(queue, kind)
      .filter((i) => i.entryId === entryId)
      .map((i) => [i.itemId, i]),
  );
}

/** How far an item got, 0 to 100; null while its page count isn't known. */
export function percentOf(item: DownloadItem): number | null {
  if (item.state === "DONE") return 100;
  if (item.progressTotal <= 0) return null;
  return Math.min(100, Math.round((item.progressDone / item.progressTotal) * 100));
}

/** What a queue row or a chapter row says about a download. */
export function stateText(item: DownloadItem): string {
  switch (item.state) {
    case "QUEUED":
      return item.tries > 0 ? `Queued (try ${item.tries + 1})` : "Queued";
    case "DOWNLOADING":
      return item.progressTotal > 0
        ? `Downloading ${item.progressDone}/${item.progressTotal}`
        : "Downloading…";
    case "PAUSED":
      return "Paused";
    case "FAILED":
      return "Failed";
    case "DONE":
      return "Downloaded";
  }
}

/** A size for people: "820 KB", "12.4 MB", "1.2 GB". */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 KB";
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

/** The choices of "Download next…", with "all" for every unread one. */
export const NEXT_UNREAD_COUNTS = [1, 5, 10, 25] as const;

/**
 * The chapters to queue for "Download next N unread": the unread ones in reading order (the source's order, oldest
 * first), leaving out those downloaded or in the queue already, the first `n` of them (`"all"`: every one).
 */
export function nextUnread(
  chapters: readonly Chapter[],
  n: number | "all",
  queued: ReadonlyMap<number, DownloadItem>,
): number[] {
  const candidates = [...chapters]
    .filter((c) => !c.isRead && !c.isDownloaded && !queued.has(c.id))
    .sort((a, b) => a.sourceOrder - b.sourceOrder)
    .map((c) => c.id);
  return n === "all" ? candidates : candidates.slice(0, n);
}

/** The counts the Downloads screen's header and the sidebar show for a kind. */
export function queueCounts(queue: DownloadQueue | undefined, kind: MediaKind) {
  const items = itemsOf(queue, kind);
  const count = (s: DownloadItem["state"]) => items.filter((i) => i.state === s).length;
  return {
    total: items.length,
    active: count("QUEUED") + count("DOWNLOADING"),
    downloading: count("DOWNLOADING"),
    paused: count("PAUSED"),
    failed: count("FAILED"),
    done: kind === "anime" ? (queue?.animeDone ?? 0) : (queue?.mangaDone ?? 0),
  };
}
