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
}

/** `UpdateEpisodePatch` (`updateEpisode(s)`, Phase 4 Stage 3): what to change; a field left out stays. */
export interface EpisodePatch {
  /** False also clears the position (as Aniyomi does), unless `lastSecondSeen` is given. */
  isWatched?: boolean;
  isBookmarked?: boolean;
  /** Milliseconds (a LongString, as the field it sets), clamped to [0, the duration if known]. */
  lastSecondSeen?: LongString;
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
  pageCount: number;
}

/** A browse result card, the same for both kinds. */
export interface EntrySummary {
  kind: MediaKind;
  id: number;
  title: string;
  thumbnailUrl: string | null;
  inLibrary: boolean;
}

/** A library card: a summary with the time it was added (epoch seconds). */
export interface LibraryEntry extends EntrySummary {
  inLibraryAt: number;
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
