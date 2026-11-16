/*
 * Types of the backend's GraphQL API (server/UPSTREAM.md "Startup contract"; Phase 3 §9 "Stage 2").
 *
 * Hand-written, not generated: each type has the fields the UI asks for, named as in the server's Kotlin types
 * (core: graphql/types/*.kt, upstream's; api: madoyomi/api/graphql/*.kt, Madoyomi's). The operation documents in
 * ./operations are checked against the live schema by src/api/__tests__/operations.live.spec.ts.
 *
 * GraphQL `LongString` (a Kotlin Long: source ids, version codes, epoch times) is a `string` here.
 */

import type { ApiError } from "./client";

/** A Kotlin `Long`, sent as a decimal string so it survives JavaScript numbers. */
export type LongString = string;

/** The two kinds of everything: extensions, repos, sources, entries. */
export type MediaKind = "anime" | "manga";

/** The server's `ExtensionKind`. */
export type ExtensionKind = "ANIME" | "MANGA";

export const extensionKindOf = (kind: MediaKind): ExtensionKind =>
  kind === "anime" ? "ANIME" : "MANGA";
export const mediaKindOf = (kind: ExtensionKind): MediaKind =>
  kind === "ANIME" ? "anime" : "manga";

// ---------------------------------------------------------------------------------------------------------------
// Errors

/** The server's `ErrorCode` (madoyomi.core.error), each with its HTTP status. */
export const SERVER_ERROR_CODES = [
  "REPO_FETCH_FAILED",
  "REPO_FORMAT_MISMATCH",
  "REPO_TYPE_MISMATCH",
  "NOT_FOUND",
  "SIGNATURE_INVALID",
  "SIGNATURE_CHANGED",
  "UNTRUSTED",
  "PACKAGE_MISMATCH",
  "INSTALL_FAILED",
  "SOURCE_FAILED",
  "LOAD_FAILED",
  "EXTENSION_INCOMPATIBLE",
  "PLAYER_NOT_FOUND",
  "PLAYER_FAILED",
  "BAD_REQUEST",
  "UNAUTHORIZED",
  "FORBIDDEN",
  "INTERNAL",
] as const;
export type ServerErrorCode = (typeof SERVER_ERROR_CODES)[number];

/**
 * Codes the client adds:
 * - `NETWORK`: the request didn't get an answer (the backend died, or is restarting);
 * - `BACKEND_UNAVAILABLE`: the request wasn't sent, because the backend isn't ready;
 * - `BAD_RESPONSE`: an answer that is neither GraphQL nor the REST error body.
 */
export type ClientErrorCode = "NETWORK" | "BACKEND_UNAVAILABLE" | "BAD_RESPONSE";

export type ErrorCode = ServerErrorCode | ClientErrorCode;

/** `errors[]` of a GraphQL response. The server puts `{code, details}` into `extensions`. */
export interface GraphQLErrorJson {
  message: string;
  path?: (string | number)[];
  extensions?: { code?: string; details?: unknown };
}

/** The body of every REST/binary error: `{code, message, details}` (the token filter's 401 included). */
export interface RestErrorBody {
  code: string;
  message: string;
  details: unknown;
}

// ---------------------------------------------------------------------------------------------------------------
// Repos and extensions

export type RepoIndexFormat = "AUTO" | "JSON" | "PROTOBUF";
export type ContentWarning = "SAFE" | "MIXED" | "NSFW";

/** A repo (`ExtensionStoreType`). */
export interface ExtensionStore {
  indexUrl: string;
  name: string;
  badgeLabel: string;
  type: ExtensionKind;
  format: RepoIndexFormat;
  signingKey: string;
  signingKeyTrusted: boolean;
  /** Epoch seconds, `"0"` if never refreshed. */
  lastRefreshedAt: LongString;
  lastError: string | null;
  contactWebsite: string;
  extensions: { totalCount: number };
}

export type CompatIssueKind =
  "MISSING_CLASS" | "MISSING_MEMBER" | "ABSTRACT_METHOD" | "STUB" | "CLASS_NOT_FOUND";

/** `ExtensionCompatIssueType`: one Android or extension API the extension needs and doesn't get. */
export interface ExtensionCompatIssue {
  kind: CompatIssueKind;
  /** The missing class or member, or the stubbed method. */
  target: string;
  /** `RUNTIME`: hit by its code; `STATIC`: found by the install-time link check. */
  origin: "RUNTIME" | "STATIC";
  /** Where it was first seen: a source operation, or (for `STATIC`) a jar class. */
  context: string | null;
  count: LongString;
}

/** What `updateExtension`'s patch can ask for. */
export type ExtensionAction = "install" | "update" | "uninstall";

/** The package of upstream's fake "Local source" extension (manga, source id 0): built in, not from a repo. */
export const LOCAL_SOURCE_PKG = "eu.kanade.tachiyomi.source.local";

/** `ExtensionType`: an extension listed by a repo, installed or not. */
export interface Extension {
  pkgName: string;
  name: string;
  type: ExtensionKind;
  lang: string;
  versionName: string;
  versionCodeLong: LongString;
  contentWarning: ContentWarning;
  /** Server-relative (`/api/v1/extension/icon/…`): load it through `assetUrl`. */
  iconUrl: string;
  storeIndexUrl: string | null;
  isInstalled: boolean;
  hasUpdate: boolean;
  isObsolete: boolean;
  isLoaded: boolean;
  isTorrent: boolean;
  /** The verified signing-certificate hash; null until installed. */
  signatureHash: string | null;
  isTrusted: boolean;
  /** Empty for an extension that hasn't run (and after a backend restart, for runtime issues). */
  compatIssues: ExtensionCompatIssue[];
}

/** `TrustedSignatureType`, as `trustExtension` returns it. */
export interface TrustedSignature {
  hash: string;
  origin: "STORE" | "USER";
}

/** `InstallState` of `extensionInstallProgress`. */
export type InstallState = "DOWNLOADING" | "VERIFYING" | "CONVERTING" | "INSTALLED" | "FAILED";

/** An `extensionInstallProgress` event. */
export interface ExtensionInstallProgress {
  pkgName: string;
  type: ExtensionKind | null;
  state: InstallState;
  errorCode: ServerErrorCode | null;
  message: string | null;
}

/** An `extensionStoreRefreshed` event: one per repo per refresh. */
export interface ExtensionStoreRefreshed {
  indexUrl: string;
  type: ExtensionKind;
  ok: boolean;
  error: string | null;
  extensionCount: number;
}

// ---------------------------------------------------------------------------------------------------------------
// Sources

/** What anime and manga sources share (`AnimeSourceType`, `SourceType`). */
export interface SourceBase {
  id: LongString;
  name: string;
  lang: string;
  contentWarning: ContentWarning;
  /** Server-relative: load it through `assetUrl`. */
  iconUrl: string;
  /** The site the source reads, if it has one (the Local source has none). */
  baseUrl: string | null;
  /** Null for an anime source that can't be used (untrusted, not loadable). */
  supportsLatest: boolean | null;
  isConfigurable: boolean | null;
  extension: { pkgName: string; name: string; isTrusted: boolean; isLoaded: boolean };
}

/** Upstream's built-in Local source (manga): its fake extension has no signature, but it is always usable. */
export const LOCAL_SOURCE_ID = "0";

export interface Source extends SourceBase {
  kind: MediaKind;
}

export type BrowseType = "POPULAR" | "LATEST" | "SEARCH";

// Filters: upstream's GraphQL `Filter` union (graphql/types/SourceType.kt), for both kinds.

export interface HeaderFilter {
  __typename: "HeaderFilter";
  name: string;
}
export interface SeparatorFilter {
  __typename: "SeparatorFilter";
  name: string;
}
export interface SelectFilter {
  __typename: "SelectFilter";
  name: string;
  values: string[];
  /** Selected index. */
  selectDefault: number;
}
export interface TextFilter {
  __typename: "TextFilter";
  name: string;
  textDefault: string;
}
export interface CheckBoxFilter {
  __typename: "CheckBoxFilter";
  name: string;
  checkBoxDefault: boolean;
}
export type TriState = "IGNORE" | "INCLUDE" | "EXCLUDE";
export interface TriStateFilter {
  __typename: "TriStateFilter";
  name: string;
  triStateDefault: TriState;
}
export interface SortSelection {
  index: number;
  ascending: boolean;
}
export interface SortFilter {
  __typename: "SortFilter";
  name: string;
  values: string[];
  sortDefault: SortSelection | null;
}
/** A group holds one level of the other filters (never another group). */
export interface GroupFilter {
  __typename: "GroupFilter";
  name: string;
  filters: Exclude<Filter, GroupFilter>[];
}
export type Filter =
  | HeaderFilter
  | SeparatorFilter
  | SelectFilter
  | TextFilter
  | CheckBoxFilter
  | TriStateFilter
  | SortFilter
  | GroupFilter;

/** Input `FilterChange`: `position` in the list (or group), and the one state of that filter's kind. */
export interface FilterChange {
  position: number;
  selectState?: number;
  textState?: string;
  checkBoxState?: boolean;
  triState?: TriState;
  sortState?: SortSelection;
  groupChange?: FilterChange;
}

// Preferences: upstream's GraphQL `Preference` union, for both kinds.

interface PreferenceBase {
  key: string | null;
  title: string | null;
  summary: string | null;
  visible: boolean;
  enabled: boolean;
}
export interface SwitchPreference extends PreferenceBase {
  __typename: "SwitchPreference";
  switchCurrentValue: boolean | null;
  switchDefault: boolean;
}
export interface CheckBoxPreference extends PreferenceBase {
  __typename: "CheckBoxPreference";
  checkBoxCurrentValue: boolean | null;
  checkBoxDefault: boolean;
}
export interface EditTextPreference extends PreferenceBase {
  __typename: "EditTextPreference";
  editTextCurrentValue: string | null;
  editTextDefault: string | null;
  dialogTitle: string | null;
  dialogMessage: string | null;
  text: string | null;
}
export interface ListPreference extends PreferenceBase {
  __typename: "ListPreference";
  listCurrentValue: string | null;
  listDefault: string | null;
  entries: string[];
  entryValues: string[];
}
export interface MultiSelectListPreference extends PreferenceBase {
  __typename: "MultiSelectListPreference";
  multiSelectCurrentValue: string[] | null;
  multiSelectDefault: string[] | null;
  dialogTitle: string | null;
  dialogMessage: string | null;
  entries: string[];
  entryValues: string[];
}
export type Preference =
  | SwitchPreference
  | CheckBoxPreference
  | EditTextPreference
  | ListPreference
  | MultiSelectListPreference;

/** Input `SourcePreferenceChange`: `position` in the preference list, and the state for that preference's kind. */
export interface SourcePreferenceChange {
  position: number;
  switchState?: boolean;
  checkBoxState?: boolean;
  editTextState?: string;
  listState?: string;
  multiSelectState?: string[];
}

// ---------------------------------------------------------------------------------------------------------------
// Entries

/** `MangaStatus`, plus Aniyomi's `UPCOMING` for anime. */
export type EntryStatus =
  | "UNKNOWN"
  | "ONGOING"
  | "COMPLETED"
  | "LICENSED"
  | "PUBLISHING_FINISHED"
  | "CANCELLED"
  | "ON_HIATUS"
  | "UPCOMING";

interface EntryBase {
  id: number;
  sourceId: LongString;
  url: string;
  title: string;
  /** Server-relative thumbnail route (with the source's headers), null if the source gave none. */
  thumbnailUrl: string | null;
  initialized: boolean;
  inLibrary: boolean;
  /** Epoch seconds when it was last added to the library ("0" if never). Kept when it is removed. */
  inLibraryAt: LongString;
}

/** `AnimeType`. A season is an entry with a `parentId`. */
export interface Anime extends EntryBase {
  artist: string | null;
  author: string | null;
  description: string | null;
  genre: string[];
  status: EntryStatus;
  /** `Episodes`: has episodes; `Seasons`: has seasons (other entries). */
  fetchType: "Episodes" | "Seasons";
  seasonNumber: number;
  parentId: number | null;
}

/** `EpisodeType`. */
export interface Episode {
  id: number;
  animeId: number;
  url: string;
  name: string;
  episodeNumber: number;
  scanlator: string | null;
  /** Epoch milliseconds. */
  uploadDate: LongString;
  /** Oldest = 1. */
  sourceOrder: number;
  isWatched: boolean;
  isBookmarked: boolean;
  fillermark: boolean;
  /** Playback position in **milliseconds**, despite the name (as the Aniyomi app stores it; Phase 4 §9). */
  lastSecondSeen: LongString;
  /** Duration in milliseconds, "0" while unknown. */
  totalSeconds: LongString;
  summary: string | null;
  previewUrl: string | null;
  /** Phase 6 Stage 5: downloaded; Play plays its file (`playDownloadedEpisode`), offline too. */
  downloaded?: boolean;
}

/** `UpdateEpisodePatch` (`updateEpisode(s)`, Phase 4 Stage 3): what to change; a field left out stays. */
export interface EpisodePatch {
  /** False also clears the position (as Aniyomi does), unless `lastSecondSeen` is given. */
  isWatched?: boolean;
  isBookmarked?: boolean;
  /** Milliseconds (a LongString, as the field it sets), clamped to [0, the duration if known]. */
  lastSecondSeen?: LongString;
}

/**
 * `UpdateChapterPatch` (upstream's `updateChapter(s)`, Phase 5 Stage 2): what to change; a field left out stays.
 * Upstream stamps `lastReadAt` whenever `lastPageRead` is given.
 */
export interface ChapterPatch {
  isRead?: boolean;
  isBookmarked?: boolean;
  /** The page index (0-based), clamped by the backend to [0, the chapter's page count]. */
  lastPageRead?: number;
}

/** `MangaType`. */
export interface Manga extends EntryBase {
  artist: string | null;
  author: string | null;
  description: string | null;
  genre: string[];
  status: EntryStatus;
}

/** `ChapterType`. */
export interface Chapter {
  id: number;
  mangaId: number;
  url: string;
  name: string;
  chapterNumber: number;
  scanlator: string | null;
  uploadDate: LongString;
  sourceOrder: number;
  isRead: boolean;
  isBookmarked: boolean;
  lastPageRead: number;
  /** When `lastPageRead` was last saved, epoch seconds ("0": never). Phase 5: "Continue reading" follows it. */
  lastReadAt: LongString;
  pageCount: number;
  /** Phase 6 Stage 4: downloaded as a CBZ (upstream's `isDownloaded`, the user's); the reader reads it offline. */
  isDownloaded?: boolean;
}

/** A browse result card, the same for both kinds. */
export interface EntrySummary {
  kind: MediaKind;
  id: number;
  title: string;
  thumbnailUrl: string | null;
  inLibrary: boolean;
}

/**
 * A library card: a summary with the time it was added (epoch seconds) and the categories it is in (Phase 6: their ids,
 * never the Default category's; none means it is in Default).
 */
export interface LibraryEntry extends EntrySummary {
  inLibraryAt: number;
  categoryIds: number[];
  /**
   * Phase 6 Stage 2, the card's badge: episodes not watched (`AnimeType.unwatchedCount`, a Seasons entry's seasons
   * included) or chapters not read (upstream's `MangaType.unreadCount`).
   */
  unreadCount: number;
  /** When an episode was last played or a chapter last read, epoch seconds (0: never); the "Last watched/read" sort. */
  lastReadAt: number;
  /**
   * Phase 6 Stages 4–5: chapters downloaded (upstream's `MangaType.downloadCount`) or episodes downloaded
   * (`AnimeType.downloadCount`, a Seasons entry's seasons included).
   */
  downloadCount?: number;
}

/**
 * A History row (Phase 6 Stage 2): an entry with its episode or chapter played or read last. `item` is that episode
 * or chapter, `resume` what Resume opens: the item itself, or when it is finished the first unfinished one after it.
 */
export interface HistoryItem {
  kind: MediaKind;
  entry: EntrySummary;
  item: HistoryTarget;
  resume: HistoryTarget;
  /** Epoch seconds. */
  at: number;
}

/** An episode or chapter as a History row shows it. */
export interface HistoryTarget {
  id: number;
  name: string;
  done: boolean;
  /** Manga: the page reading stopped at (from 0) and the chapter's page count (-1 or 0 while unknown). */
  pages?: { lastPageRead: number; pageCount: number };
  /** Anime: the position and duration in milliseconds (0 while unknown). */
  progress?: { positionMs: number; durationMs: number };
}

export interface HistoryPage {
  items: HistoryItem[];
  hasMore: boolean;
}

/** Upstream's `IncludeOrExclude` (a category's update and download flags; UNSET follows the global setting). */
export type IncludeOrExclude = "INCLUDE" | "EXCLUDE" | "UNSET";

/**
 * A library category (Phase 6 Stage 1): upstream's `CategoryType` for manga, Madoyomi's `AnimeCategoryType` for anime,
 * the same fields. One list per kind. The Default category (`isDefaultCategory`) holds the entries in no other one.
 */
export interface LibraryCategory {
  id: number;
  /** 0 for Default, then 1, 2, … */
  order: number;
  name: string;
  /** New library entries go into it. */
  default: boolean;
  isDefaultCategory: boolean;
  includeInUpdate: IncludeOrExclude;
  includeInDownload: IncludeOrExclude;
  meta: { key: string; value: string }[];
}

/** A change to a category: a field left out stays. */
export interface CategoryPatch {
  name?: string;
  default?: boolean;
  /** Phase 6 Stage 3: the Categories dialog's "Updates" and "Downloads" flags (UNSET when on, EXCLUDE when off). */
  includeInUpdate?: IncludeOrExclude;
  includeInDownload?: IncludeOrExclude;
}

/** A change to entries' categories, as upstream's `UpdateMangaCategoriesPatch`. */
export interface EntryCategoriesPatch {
  clearCategories?: boolean;
  addToCategories?: number[];
  removeFromCategories?: number[];
}

/** A season of a `Seasons` anime: an entry of its own, shown as a card. */
export interface AnimeSeason {
  id: number;
  title: string;
  thumbnailUrl: string | null;
  inLibrary: boolean;
  seasonNumber: number;
}

/**
 * A refresh from the source, with the data as stored afterwards. `error` is the source's failure, if any: the data
 * is then what was stored before.
 */
export interface AnimeRefresh {
  anime: Anime;
  episodes: Episode[];
  seasons: AnimeSeason[];
  error: ApiError | null;
}
export interface MangaRefresh {
  manga: Manga;
  chapters: Chapter[];
  error: ApiError | null;
}

/** One page of `fetchSourceAnime`/`fetchSourceManga`. */
export interface SourcePage {
  items: EntrySummary[];
  hasNextPage: boolean;
}

// ---------------------------------------------------------------------------------------------------------------
// Videos and playback (Phase 4)

export interface VideoHeader {
  name: string;
  value: string;
}
export interface VideoTrack {
  url: string;
  lang: string;
}
export interface VideoTimestamp {
  start: number;
  end: number;
  name: string;
  type: string;
}

/** `EpisodeVideoType`. */
export interface Video {
  index: number;
  videoTitle: string;
  videoUrl: string;
  resolution: number | null;
  bitrate: number | null;
  headers: VideoHeader[];
  preferred: boolean;
  /** Its position in the source's own order (`index` is its position in the list, sorted by the player settings). */
  sourceIndex: number;
  /** It is in the front group of its hoster by the preferred server or quality (Settings → Player); false without one. */
  matchesPreference: boolean;
  /** False: call `resolveEpisodeVideo` before playing it. */
  initialized: boolean;
  subtitleTracks: VideoTrack[];
  audioTracks: VideoTrack[];
  timestamps: VideoTimestamp[];
  mpvArgs: VideoHeader[];
}

export type HosterState = "READY" | "IDLE" | "ERROR";

/** `EpisodeHosterType`. */
export interface Hoster {
  index: number;
  /** Its position in the source's own order (`index` is its position in the list, sorted by the preferred server). */
  sourceIndex: number;
  name: string;
  lazy: boolean;
  state: HosterState;
  videos: Video[];
  errorCode: ServerErrorCode | null;
  errorMessage: string | null;
}

/** What the UI chooses a video by (`EPISODE_VIDEOS_MUTATION`): the backend keeps the URL and headers. */
export type VideoChoice = Pick<
  Video,
  | "index"
  | "videoTitle"
  | "resolution"
  | "bitrate"
  | "preferred"
  | "sourceIndex"
  | "matchesPreference"
  | "initialized"
> & {
  subtitleTracks: Pick<VideoTrack, "lang">[];
  audioTracks: Pick<VideoTrack, "lang">[];
};

/** A hoster with its videos as {@link VideoChoice}s. */
export type HosterChoice = Omit<Hoster, "videos"> & { videos: VideoChoice[] };

/** `PlaybackSessionType.state`: mpv is running, exited by itself (closed, ended, failed), or was stopped. */
export type PlaybackState = "RUNNING" | "EXITED" | "STOPPED";

/** `PlaybackSessionType`: the backend's mpv session (one at a time). */
export interface PlaybackSession {
  id: string;
  episodeId: number;
  animeId: number;
  /** "<anime> - <episode>", mpv's window title. */
  title: string;
  videoTitle: string;
  state: PlaybackState;
  /** Epoch milliseconds. */
  startedAt: LongString;
  endedAt: LongString | null;
  exitCode: number | null;
  /** mpv's position in seconds; null until mpv reports it over IPC. */
  positionSeconds: number | null;
  durationSeconds: number | null;
  paused: boolean;
}

/**
 * `PlaybackEventType.type` (Phase 4 Stage 2). CLOSED is always a session's last event. RETRYING (Stage 4): mpv
 * couldn't open the video, and the backend fetches the episode's videos again once; the session's CLOSED follows,
 * then the new session's events, or ERROR.
 */
export type PlaybackEventKind =
  "STARTED" | "PROGRESS" | "WATCHED" | "ENDED" | "RETRYING" | "ERROR" | "CLOSED";

/** `PlaybackVideoRefType`: a video by its hoster's name and its title and resolution (indexes change per fetch). */
export interface PlaybackVideoRef {
  hosterName: string;
  videoTitle: string;
  resolution: number | null;
}

/** `playbackEvents`: something that happened in a playback session. */
export interface PlaybackEvent {
  type: PlaybackEventKind;
  session: PlaybackSession;
  /** ENDED: the next episode in the source's order, if any. */
  nextEpisodeId: number | null;
  /** ENDED: mpv's end-file reason ("eof", or "quit" past the watched threshold). */
  reason: string | null;
  /** RETRYING, ERROR: what went wrong. */
  message: string | null;
  /** RETRYING, ERROR: the last HTTP error status in mpv's log, if any. */
  httpStatus: number | null;
  /** ERROR: the videos had been fetched again before it (the failure is final). */
  retried: boolean;
  /** RETRYING, ERROR: the video that failed. */
  failedVideo: PlaybackVideoRef | null;
  /** ERROR: the video to offer instead, the next in the source's order, if there is one. */
  nextVideo: PlaybackVideoRef | null;
}

/** The schema's `QualityPreference`: the source's own order, the highest quality, or a resolution (Phase 4 Stage 5). */
export type VideoQualityPreference =
  "SOURCE" | "HIGHEST" | "P2160" | "P1080" | "P720" | "P480" | "P360";

/**
 * `PlayerSettingsType`: the backend's player settings (Settings → Player), kept in its data dir. A language is a
 * `PlayerLanguage.code`, "" for the source's choice, or "off" (subtitles only); `mpvPath` "" uses `MADOYOMI_MPV` or PATH.
 */
export interface PlayerSettings {
  /** The share of the duration at which an episode is marked watched: 0.5 to 0.95, in steps of 0.05. */
  watchedThreshold: number;
  preferredQuality: VideoQualityPreference;
  preferredServer: string;
  subtitleLanguage: string;
  audioLanguage: string;
  mpvPath: string;
}

/** `PlayerSettingsPatch`: only the fields given change. */
export type PlayerSettingsPatch = Partial<PlayerSettings>;

/** `PlayerLanguageType`: a language the track preferences can match. */
export interface PlayerLanguage {
  code: string;
  name: string;
}

/** `MpvOrigin`: where the mpv in use comes from. */
export type MpvOrigin = "SETTINGS" | "SYSTEM_PROPERTY" | "ENV" | "PATH";

/** `MpvCheckType`: whether an mpv path (or the one in use) runs `mpv --version`. */
export interface MpvCheck {
  ok: boolean;
  path: string | null;
  origin: MpvOrigin | null;
  /** The first line of `mpv --version` ("mpv v0.40.0 …"). */
  version: string | null;
  errorCode: ServerErrorCode | null;
  message: string | null;
}

// ---------------------------------------------------------------------------------------------------------------
// Manga reader (Phase 5)

/**
 * `fetchChapterPages` (upstream's): a chapter's page routes and the chapter (with its `pageCount`, `lastPageRead`
 * and `isRead`). Each route is `/api/v1/manga/{mangaId}/chapter/{sourceOrder}/page/{index}`: the backend fetches the
 * image through the source and caches it, so `assetUrl` adds only the token.
 */
export interface ChapterPages {
  pages: string[];
  chapter: Chapter;
}

/** `PrefetchChapterPagesPayload`: the pages being fetched in the background, and those cached already. */
export interface PrefetchResult {
  chapterId: number;
  pageCount: number;
  queued: number[];
  cached: number[];
}

/** `ReaderSettingsType`: the reader settings the backend applies (Settings → Reader), kept in its data dir. */
export interface ReaderSettings {
  /** The chapter-page cache's cap in MiB: 256 to 10240 (default 1024). */
  pageCacheLimitMb: number;
}

/** A manga's meta and its source's name (`MANGA_READER_META_QUERY`), for its own reader settings (Phase 5 Stage 5). */
export interface MangaReaderMeta {
  mangaId: number;
  meta: { key: string; value: string }[];
  sourceName: string | null;
}

/** `ReaderSettingsPatch`: only the fields given change. */
export type ReaderSettingsPatch = Partial<ReaderSettings>;

/** `PageCacheType`: what the chapter-page cache holds. */
export interface PageCache {
  bytes: LongString;
  files: number;
  limitBytes: LongString;
  /** `<data dir>/cache/manga`. */
  path: string;
}

// ---------------------------------------------------------------------------------------------------------------
// Library updates (Phase 6 Stage 3)

/** An entry a library update check looked at. */
export interface LibraryCheckEntry {
  kind: ExtensionKind;
  id: number;
  title: string;
}

/**
 * The library update check that runs, else the last one (Madoyomi's `LibraryCheckType`): the `libraryCheck` query and
 * the `libraryUpdates` subscription (the WS "library.updates" event). Times are epoch milliseconds ("0": never).
 */
export interface LibraryCheck {
  running: boolean;
  trigger: "MANUAL" | "SCHEDULED" | null;
  startedAt: LongString;
  finishedAt: LongString;
  total: number;
  /** Entries checked, failed ones included. */
  done: number;
  /** Entries the settings or their update strategy left out. */
  skipped: number;
  checking: LibraryCheckEntry[];
  failures: { entry: LibraryCheckEntry; error: string }[];
  /** The entries the check found new items in. */
  found: { entry: LibraryCheckEntry; newCount: number }[];
  newEpisodes: number;
  newChapters: number;
  cancelled: boolean;
  /** The next scheduled check, or null while the schedule is off. */
  nextScheduledAt: LongString | null;
}

/** Settings → Updates, kept by the backend for both kinds. */
export interface LibraryUpdateSettings {
  /** Hours between scheduled checks; 0: off. */
  intervalHours: 0 | 6 | 12 | 24;
  skipCompleted: boolean;
  /** Skip entries with unwatched episodes or unread chapters. */
  skipUnseen: boolean;
  /** Skip entries with nothing watched or read yet. */
  skipNotStarted: boolean;
  /** A Windows toast when a check finds new items (the app shows it). */
  notify: boolean;
}

/** An Updates row: a new episode or chapter of a library entry, with when it was fetched (epoch seconds). */
export interface UpdatesItem {
  kind: MediaKind;
  /** The episode's or chapter's entry (for an anime season, the season). */
  entry: EntrySummary;
  item: HistoryTarget;
  at: number;
}

export interface UpdatesPage {
  items: UpdatesItem[];
  hasMore: boolean;
}

/**
 * Where a download is (Phase 6 Stage 4): waiting its turn, being downloaded, paused, failed after its retries, or done
 * (in the completed list until it is cleared or its file deleted).
 */
export type DownloadItemState = "QUEUED" | "DOWNLOADING" | "PAUSED" | "FAILED" | "DONE";

/**
 * What a download's `progressDone`/`progressTotal` count (Stage 5): a chapter's pages; a file's bytes (as thousandths,
 * with `totalBytes`); the seconds ffmpeg wrote of the episode's duration (HLS, DASH); yt-dlp's segments.
 */
export type DownloadProgressUnit = "PAGES" | "BYTES" | "SECONDS" | "SEGMENTS";

/** An episode or chapter in the download queue or the completed list. */
export interface DownloadItem {
  id: number;
  kind: MediaKind;
  state: DownloadItemState;
  /** The manga or anime. */
  entryId: number;
  /** The chapter or episode. */
  itemId: number;
  entryTitle: string;
  itemName: string;
  /** How far it got of `progressTotal` (0 before that is known), in `progressUnit`. */
  progressDone: number;
  progressTotal: number;
  progressUnit: DownloadProgressUnit;
  /** Bytes written so far; the file's size once done. */
  bytes: number;
  /** The size it will have, when known (0: unknown). */
  totalBytes: number;
  /** Failed attempts since it was queued or retried. */
  tries: number;
  error: string | null;
  /** Epoch milliseconds. */
  addedAt: number;
  /** Epoch milliseconds; 0 until done. */
  finishedAt: number;
}

/** The queue: every unfinished item of both kinds in order, and how many of each kind are in the completed list. */
export interface DownloadQueue {
  items: DownloadItem[];
  mangaDone: number;
  animeDone: number;
  /** Epoch milliseconds of the last item done (0: none in the completed list): the completed list is fetched again. */
  lastFinishedAt: number;
}

export interface CompletedDownloadsPage {
  items: DownloadItem[];
  hasMore: boolean;
}

/** Settings → Downloads, kept by the backend for both kinds. */
export interface DownloadSettings {
  /** Downloads at once, all sources together (1 to 10). */
  maxParallel: number;
  /** Downloads at once from one source (1 to 3). */
  perSource: number;
  /** Queue the new chapters a library update finds in categories with Downloads on (off on a fresh install). */
  autoDownload: boolean;
  /** The folder downloads are saved in (read only). */
  path: string;
  /** Stage 5: the ffmpeg for HLS episodes; "" looks for one (`MADOYOMI_FFMPEG`, then PATH). */
  ffmpegPath: string;
  /** Stage 5: the yt-dlp to fall back on; "" looks for one (`MADOYOMI_YTDLP`, then PATH). */
  ytDlpPath: string;
}

/** Stage 5: ffmpeg or yt-dlp as the backend finds it now (Settings → Downloads). */
export interface DownloadTool {
  tool: "FFMPEG" | "YT_DLP";
  ok: boolean;
  path: string | null;
  origin: "SETTINGS" | "SYSTEM_PROPERTY" | "ENV" | "PATH" | null;
  version: string | null;
  message: string | null;
}

/* --- Phase 6 Stage 6: backups (`.madoyomi-backup`) --- */

/** Madoyomi's own backup, or a Mihon/Suwayomi/Aniyomi `.tachibk` (its manga part only). */
export type BackupFormat = "MADOYOMI" | "TACHIYOMI";

/** A file in the backups folder. */
export interface BackupFile {
  name: string;
  /** Its full path, for Show in Explorer. */
  path: string;
  /** Bytes. */
  size: number;
  /** Epoch milliseconds. */
  modifiedAt: number;
  /** Made by the schedule (`Madoyomi_auto_…`); only those are pruned. */
  automatic: boolean;
  format: BackupFormat;
}

/** Settings → Backup's schedule. */
export interface BackupSettings {
  /** 0 (off, the default), 24 (daily) or 168 (weekly). */
  intervalHours: number;
  /** How many scheduled backups are kept (1 to 50). */
  keep: number;
  /** Epoch milliseconds of the next scheduled backup, null while off. */
  nextScheduledAt: number | null;
  folder: string;
  lastError: string | null;
}

export interface BackupRepoReport {
  indexUrl: string;
  kind: ExtensionKind;
  name: string;
  signingKey: string;
  /** Already added here. */
  added: boolean;
}

export interface BackupCertificateReport {
  hash: string;
  trusted: boolean;
}

export interface BackupExtensionReport {
  pkgName: string;
  kind: ExtensionKind;
  name: string;
  versionName: string;
  repoUrl: string | null;
  signatureHash: string | null;
  installed: boolean;
  /** A repo here or in the backup lists it: it can be reinstalled. */
  inRepo: boolean;
  /** Its certificate is trusted here, or will be by the backup's repos or certificates. */
  trustedAfterRestore: boolean;
}

export interface BackupSourceReport {
  id: LongString;
  kind: ExtensionKind;
  name: string;
  entries: number;
  /** The backup's extension that provides it, if any. */
  extension: string | null;
}

/** What a backup file holds, checked against this install (`validateMadoyomiBackup`). */
export interface BackupReport {
  name: string;
  format: BackupFormat;
  createdAt: number | null;
  appVersion: string | null;
  automatic: boolean;
  anime: number;
  seasons: number;
  episodes: number;
  watchedEpisodes: number;
  animeHistory: number;
  animeCategories: number;
  manga: number;
  chapters: number;
  readChapters: number;
  mangaHistory: number;
  mangaCategories: number;
  repos: BackupRepoReport[];
  certificates: BackupCertificateReport[];
  extensions: BackupExtensionReport[];
  missingSources: BackupSourceReport[];
  settingsFiles: string[];
  sourcePreferences: number;
}

export type RestoreMode = "MERGE" | "REPLACE";
export type RestoreState = "IDLE" | "RUNNING" | "SUCCESS" | "FAILURE";
export type RestoreStep =
  | "SETTINGS"
  | "REPOS"
  | "CERTIFICATES"
  | "EXTENSIONS"
  | "SOURCE_PREFERENCES"
  | "CATEGORIES"
  | "ANIME"
  | "MANGA";

/** The restore that runs, else the last one (`madoyomiRestoreStatus`, and its subscription). */
export interface RestoreStatus {
  state: RestoreState;
  name: string | null;
  mode: RestoreMode | null;
  step: RestoreStep | null;
  current: number;
  total: number;
  title: string | null;
  problems: string[];
  startedAt: number;
  finishedAt: number;
}

/** What to restore; only the repos, certificates and extensions listed are added, trusted or installed. */
export interface RestoreRequest {
  name: string;
  mode: RestoreMode;
  library: boolean;
  settings: boolean;
  repos: string[];
  certificates: string[];
  extensions: string[];
}
