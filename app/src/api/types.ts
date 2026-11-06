/*
 * Types of the backend's GraphQL API (server/UPSTREAM.md "Startup contract"; Phase 3 §9 "Stage 2").
 *
 * Hand-written, not generated: each type has the fields the UI asks for, named as in the server's Kotlin types
 * (core: graphql/types/*.kt, upstream's; api: madoyomi/api/graphql/*.kt, Madoyomi's). The operation documents in
 * ./operations are checked against the live schema by src/api/__tests__/operations.live.spec.ts.
 *
 * GraphQL `LongString` (a Kotlin Long: source ids, version codes, epoch times) is a `string` here.
 */

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
  /** Null for an anime source that can't be used (untrusted, not loadable). */
  supportsLatest: boolean | null;
  isConfigurable: boolean | null;
  extension: { pkgName: string; name: string; isTrusted: boolean };
}

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
  /** Seconds. */
  lastSecondSeen: LongString;
  totalSeconds: LongString;
  summary: string | null;
  previewUrl: string | null;
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

/** One page of `fetchSourceAnime`/`fetchSourceManga`. */
export interface SourcePage {
  items: EntrySummary[];
  hasNextPage: boolean;
}

// ---------------------------------------------------------------------------------------------------------------
// Videos (Phase 4 plays them; typed now so the client is complete)

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
  name: string;
  lazy: boolean;
  state: HosterState;
  videos: Video[];
  errorCode: ServerErrorCode | null;
  errorMessage: string | null;
}
