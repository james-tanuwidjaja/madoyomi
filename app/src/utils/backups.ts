import type { BackupFile, BackupReport, RestoreStatus, RestoreStep } from "../api/types";
import { formatBytes } from "./downloads";
import { clockTime } from "./updates";

/* Settings → Backup's wording and the restore dialog's defaults (Phase 6 Stage 6). */

/** The schedule's choices: off (the default), daily, weekly. */
export const BACKUP_INTERVALS = [
  { value: 0, label: "Off" },
  { value: 24, label: "Every day" },
  { value: 168, label: "Every week" },
] as const;

/** How many scheduled backups to keep. */
export const BACKUP_KEEP = [1, 3, 5, 7, 10, 14, 30] as const;

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** A backup file's line under its name: "Nov 16, 9:05 PM · 84 KB · Scheduled". */
export function backupFileText(file: BackupFile, now: Date = new Date()): string {
  const parts = [clockTime(file.modifiedAt, now), formatBytes(file.size)];
  // a .tachibk is neither (Suwayomi's own schedule made the ones in a data dir; the live check)
  if (file.format === "TACHIYOMI") parts.push("Mihon/Aniyomi backup (manga only)");
  else parts.push(file.automatic ? "Scheduled" : "Manual");
  return parts.join(" · ");
}

/** The anime line of a report: "5 entries (1 season), 120 episodes (40 watched), 12 in history, 4 categories". */
export function animeSummary(r: BackupReport): string {
  const entries =
    plural(r.anime, "entry", "entries") +
    (r.seasons > 0 ? ` (${plural(r.seasons, "season")})` : "");
  return [
    entries,
    `${plural(r.episodes, "episode")} (${r.watchedEpisodes} watched)`,
    `${r.animeHistory} in history`,
    plural(r.animeCategories, "category", "categories"),
  ].join(", ");
}

/** The manga line of a report: "7 entries, 930 chapters (12 read), 9 in history, 2 categories". */
export function mangaSummary(r: BackupReport): string {
  return [
    plural(r.manga, "entry", "entries"),
    `${plural(r.chapters, "chapter")} (${r.readChapters} read)`,
    `${r.mangaHistory} in history`,
    plural(r.mangaCategories, "category", "categories"),
  ].join(", ");
}

/** What the trust prompt starts with: the repos not added, the certificates not trusted, the extensions to reinstall. */
export function restoreDefaults(r: BackupReport): {
  repos: string[];
  certificates: string[];
  extensions: string[];
} {
  return {
    repos: r.repos.filter((x) => !x.added).map((x) => x.indexUrl),
    certificates: r.certificates.filter((x) => !x.trusted).map((x) => x.hash),
    extensions: r.extensions.filter((x) => !x.installed && x.inRepo).map((x) => x.pkgName),
  };
}

const STEP_TEXT: Record<RestoreStep, string> = {
  SETTINGS: "Restoring the settings",
  REPOS: "Adding the repos",
  CERTIFICATES: "Trusting the certificates",
  EXTENSIONS: "Installing the extensions",
  SOURCE_PREFERENCES: "Restoring the source settings",
  CATEGORIES: "Restoring the categories",
  ANIME: "Restoring the anime",
  MANGA: "Restoring the manga",
};

/** The status line: "Restoring the anime: 2 of 5, Oshi no Ko", "Restored", "The restore failed". */
export function restoreStatusText(s: RestoreStatus): string {
  if (s.state === "SUCCESS") {
    return s.problems.length > 0
      ? `Restored, with ${plural(s.problems.length, "problem")}`
      : "Restored";
  }
  if (s.state === "FAILURE") return "The restore failed";
  if (s.state !== "RUNNING") return "";
  if (!s.step) return "Starting the restore…";
  const count = s.total > 0 ? `: ${s.current} of ${s.total}` : "";
  const title = s.title ? `, ${s.title}` : "";
  return `${STEP_TEXT[s.step]}${count}${title}`;
}

/** A hash shortened for a line ("ab12cd34…"); the full one is in the title. */
export const shortHash = (hash: string) => (hash.length > 16 ? `${hash.slice(0, 16)}…` : hash);
