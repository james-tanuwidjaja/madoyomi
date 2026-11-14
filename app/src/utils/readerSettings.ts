import type { ReadingDirection } from "./pagedNav";

/*
 * The reader's settings (Phase 5 Stage 5, §3 and step 11), pure so they are unit-tested without a store or the API:
 * - global ones, kept on this device (`stores/reader.ts`, one JSON value in `localStorage`): the default mode and
 *   direction, the page fit of the paged modes, the strip's width, the page gap, the background and the preload count;
 * - per-manga ones, kept in the manga's meta (upstream's `setMangaMeta`, strings only; they go into backups): the mode
 *   and the direction, as in Mihon.
 *
 * A manga's mode (§8, the user's): its own, else webtoon when its genres or tags say Long Strip, Webtoon, Manhwa or
 * Manhua, else webtoon when its source's name says Webtoon (Stage 5, the user's), else the global default. Values
 * that aren't known (a hand edit, a later version's) read as not set.
 */

/** The reader's modes: one page at a time, two side by side (Stage 4), or the vertical strip (Stage 3). */
export type ReaderMode = "paged" | "double" | "webtoon";
export const READER_MODES: readonly ReaderMode[] = ["paged", "double", "webtoon"];
export const READING_DIRECTIONS: readonly ReadingDirection[] = ["rtl", "ltr"];

/**
 * How a page fits the window in the paged modes: whole (`screen`, the default), as wide as the window (or half of it
 * for each page of a spread), as tall as the window, or at its own size. A page bigger than the window scrolls inside
 * it (utils/zoom.ts).
 */
export type PageFit = "screen" | "width" | "height" | "original";
export const PAGE_FITS: readonly PageFit[] = ["screen", "width", "height", "original"];

/** The webtoon strip's widest, px; 0 is the window's width. */
export const STRIP_WIDTHS: readonly number[] = [600, 800, 1000, 1200, 0];

export type ReaderBackground = "dark" | "black" | "gray" | "white";
export const READER_BACKGROUNDS: readonly ReaderBackground[] = ["dark", "black", "gray", "white"];

/** The background's colour, and whether the text on it is dark. */
export const BACKGROUND_COLORS: Record<ReaderBackground, { color: string; light: boolean }> = {
  dark: { color: "#111111", light: false },
  black: { color: "#000000", light: false },
  gray: { color: "#3a3a3a", light: false },
  white: { color: "#ffffff", light: true },
};

/** Pages loaded ahead of the reader. */
export const PRELOAD_COUNTS: readonly number[] = [2, 4, 6, 8, 12];

/** The settings kept on this device. */
export interface ReaderPrefs {
  mode: ReaderMode;
  direction: ReadingDirection;
  fit: PageFit;
  /** The strip's widest, px (0: the window's width). */
  stripWidth: number;
  pageGap: boolean;
  background: ReaderBackground;
  preload: number;
}

export const DEFAULT_READER_PREFS: Readonly<ReaderPrefs> = Object.freeze({
  mode: "paged",
  direction: "rtl",
  fit: "screen",
  stripWidth: 800,
  pageGap: false,
  background: "dark",
  preload: 4,
});

function oneOf<T>(values: readonly T[], v: unknown): v is T {
  return values.includes(v as T);
}

export function isReaderMode(v: unknown): v is ReaderMode {
  return oneOf(READER_MODES, v);
}

export function isReadingDirection(v: unknown): v is ReadingDirection {
  return oneOf(READING_DIRECTIONS, v);
}

/** The stored prefs (anything), field by field: what isn't known takes its default. */
export function parseReaderPrefs(raw: unknown): ReaderPrefs {
  const r = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const d = DEFAULT_READER_PREFS;
  return {
    mode: isReaderMode(r.mode) ? r.mode : d.mode,
    direction: isReadingDirection(r.direction) ? r.direction : d.direction,
    fit: oneOf(PAGE_FITS, r.fit) ? r.fit : d.fit,
    stripWidth: oneOf(STRIP_WIDTHS, r.stripWidth) ? r.stripWidth : d.stripWidth,
    pageGap: typeof r.pageGap === "boolean" ? r.pageGap : d.pageGap,
    background: oneOf(READER_BACKGROUNDS, r.background) ? r.background : d.background,
    preload: oneOf(PRELOAD_COUNTS, r.preload) ? r.preload : d.preload,
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Per manga

/** The manga meta keys. */
export const MANGA_MODE_KEY = "madoyomi.reader.mode";
export const MANGA_DIRECTION_KEY = "madoyomi.reader.direction";

/** A manga's own settings; null where it has none (it follows the defaults). */
export interface MangaReaderPrefs {
  mode: ReaderMode | null;
  direction: ReadingDirection | null;
}

export const NO_MANGA_PREFS: Readonly<MangaReaderPrefs> = Object.freeze({
  mode: null,
  direction: null,
});

/** A manga's own settings from its meta (`{ key, value }` pairs); an unknown value reads as not set. */
export function parseMangaMeta(meta: readonly { key: string; value: string }[]): MangaReaderPrefs {
  const get = (k: string) => meta.find((m) => m.key === k)?.value;
  const mode = get(MANGA_MODE_KEY);
  const direction = get(MANGA_DIRECTION_KEY);
  return {
    mode: isReaderMode(mode) ? mode : null,
    direction: isReadingDirection(direction) ? direction : null,
  };
}

// ---------------------------------------------------------------------------------------------------------------
// The mode by content (§8)

/** Genres and tags that mean a vertical strip, written as {@link genreToken} makes them. */
const WEBTOON_GENRES = new Set(["longstrip", "webtoon", "webtoons", "manhwa", "manhua"]);

/** A genre without case, anything in parentheses, spaces, hyphens or other non-letters: "Manhwa (Korean)" → "manhwa". */
export function genreToken(genre: string): string {
  return genre
    .toLowerCase()
    .replace(/\([^)]*\)/g, "")
    .replace(/[^\p{L}]/gu, "");
}

/** The first of `genres` that means a vertical strip, or null. */
export function webtoonGenre(genres: readonly string[]): string | null {
  return genres.find((g) => WEBTOON_GENRES.has(genreToken(g))) ?? null;
}

/** Whether a source's name says it is a webtoon site ("Webtoons.com", "Webtoon XYZ"). */
export function isWebtoonSource(name: string | null | undefined): boolean {
  return !!name && /webtoon/i.test(name);
}

export type ModeOrigin =
  | { kind: "manga" }
  | { kind: "genre"; genre: string }
  | { kind: "source"; source: string }
  | { kind: "default" };

/** A manga's mode and where it came from: its own, its genres, its source's name, or the global default. */
export function resolveReaderMode(opts: {
  manga: ReaderMode | null;
  genres: readonly string[];
  sourceName?: string | null;
  global: ReaderMode;
}): { mode: ReaderMode; origin: ModeOrigin } {
  if (opts.manga) return { mode: opts.manga, origin: { kind: "manga" } };
  const genre = webtoonGenre(opts.genres);
  if (genre) return { mode: "webtoon", origin: { kind: "genre", genre } };
  if (isWebtoonSource(opts.sourceName)) {
    return { mode: "webtoon", origin: { kind: "source", source: opts.sourceName ?? "" } };
  }
  return { mode: opts.global, origin: { kind: "default" } };
}

/** A manga's direction: its own, else the global default. */
export function resolveDirection(
  manga: ReadingDirection | null,
  global: ReadingDirection,
): ReadingDirection {
  return manga ?? global;
}

// ---------------------------------------------------------------------------------------------------------------
// Labels (the settings sheet and Settings → Reader)

export const MODE_LABELS: Record<ReaderMode, string> = {
  paged: "Single",
  double: "Double",
  webtoon: "Webtoon",
};

export const DIRECTION_LABELS: Record<ReadingDirection, string> = {
  rtl: "Right to left",
  ltr: "Left to right",
};

export const FIT_LABELS: Record<PageFit, string> = {
  screen: "Fit screen",
  width: "Fit width",
  height: "Fit height",
  original: "Original size",
};

export const BACKGROUND_LABELS: Record<ReaderBackground, string> = {
  dark: "Dark",
  black: "Black",
  gray: "Gray",
  white: "White",
};

export function stripWidthLabel(px: number): string {
  return px === 0 ? "Window width" : `${px} px`;
}

/** "This manga", "Genre: Long Strip", "Source: Webtoons.com", "Default". */
export function modeOriginLabel(origin: ModeOrigin): string {
  switch (origin.kind) {
    case "manga":
      return "This manga's";
    case "genre":
      return `From the genre “${origin.genre}”`;
    case "source":
      return `From the source (${origin.source})`;
    case "default":
      return "The default";
  }
}
