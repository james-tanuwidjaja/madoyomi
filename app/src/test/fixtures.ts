import type {
  Anime,
  Chapter,
  Episode,
  Extension,
  ExtensionStore,
  Manga,
  MediaKind,
  PlaybackEvent,
  PlaybackSession,
  Source,
} from "../api/types";

/** A repo as `ExtensionStoreFields` returns it. */
export function makeStore(overrides: Partial<ExtensionStore> = {}): ExtensionStore {
  return {
    indexUrl: "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
    name: "Keiyoushi",
    badgeLabel: "",
    type: "MANGA",
    format: "AUTO",
    signingKey: "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
    signingKeyTrusted: true,
    lastRefreshedAt: "1762480000",
    lastError: null,
    contactWebsite: "",
    extensions: { totalCount: 1469 },
    ...overrides,
  };
}

export const YUZONO_STORE_URL = "https://github.com/yuzono/anime-repo/raw/repo/index.pb";

/** An extension as `ExtensionFields` returns it: an anime listing, not installed. */
export function makeExtension(overrides: Partial<Extension> = {}): Extension {
  return {
    pkgName: "eu.kanade.tachiyomi.animeextension.en.animegg",
    name: "AnimeGG",
    type: "ANIME",
    lang: "en",
    versionName: "14.3",
    versionCodeLong: "3",
    contentWarning: "SAFE",
    iconUrl: "/api/v1/extension/icon/animegg.apk",
    storeIndexUrl: YUZONO_STORE_URL,
    isInstalled: false,
    hasUpdate: false,
    isObsolete: false,
    isLoaded: false,
    isTorrent: false,
    signatureHash: null,
    isTrusted: false,
    compatIssues: [],
    ...overrides,
  };
}

/** The same extension installed and trusted (loaded). */
export function installed(e: Extension, overrides: Partial<Extension> = {}): Extension {
  return {
    ...e,
    isInstalled: true,
    isTrusted: true,
    isLoaded: true,
    signatureHash: "50ab1d722bf6ddac0a4d4b8a02e0c4a2c5d8b8ab3a37b6e0e20a3a9e1ee6aa51",
    ...overrides,
  };
}

/** A source as `SourceFields` returns it: AnimeGG, trusted and loaded. */
export function makeSource(overrides: Partial<Source> = {}): Source {
  return {
    kind: "anime",
    id: "2499283573021220255",
    name: "AnimeGG",
    lang: "en",
    contentWarning: "SAFE",
    iconUrl: "/api/v1/extension/icon/animegg.apk",
    baseUrl: "https://www.animegg.org",
    supportsLatest: true,
    isConfigurable: false,
    extension: {
      pkgName: "eu.kanade.tachiyomi.animeextension.en.animegg",
      name: "AnimeGG",
      isTrusted: true,
      isLoaded: true,
    },
    ...overrides,
  };
}

/** One browse page of `n` cards with ids from `from`. */
export function makePage(from: number, n: number, hasNextPage: boolean, kind: MediaKind = "anime") {
  return {
    items: Array.from({ length: n }, (_, i) => ({
      kind,
      id: from + i,
      title: `Entry ${from + i}`,
      thumbnailUrl: `/api/v1/${kind}/${from + i}/thumbnail`,
      inLibrary: false,
    })),
    hasNextPage,
  };
}

/** A stored anime of AnimeGG (see {@link makeSource}), fetched (`initialized`) and not in the library. */
export function makeAnime(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 12,
    sourceId: "2499283573021220255",
    url: "/series/one-piece",
    title: "One Piece",
    thumbnailUrl: "/api/v1/anime/12/thumbnail",
    initialized: true,
    inLibrary: false,
    inLibraryAt: "0",
    artist: null,
    author: "Toei Animation",
    description: "Pirates.",
    genre: ["Action", "Adventure"],
    status: "ONGOING",
    fetchType: "Episodes",
    seasonNumber: -1,
    parentId: null,
    ...overrides,
  };
}

/** A stored manga of MangaPill, fetched and not in the library. */
export function makeManga(overrides: Partial<Manga> = {}): Manga {
  return {
    id: 34,
    sourceId: "3436757651215440337",
    url: "/manga/2/one-piece",
    title: "One Piece",
    thumbnailUrl: "/api/v1/manga/34/thumbnail",
    initialized: true,
    inLibrary: false,
    inLibraryAt: "0",
    artist: "Eiichiro Oda",
    author: "Eiichiro Oda",
    description: "Pirates.",
    genre: ["Action"],
    status: "ONGOING",
    ...overrides,
  };
}

/** `n` episodes of `animeId`, episode i (from 1) uploaded on day i, in the source's order (oldest = 1). */
export function makeEpisodes(n: number, animeId = 12, from = 1): Episode[] {
  return Array.from({ length: n }, (_, k) => {
    const i = from + k;
    return {
      id: 1000 + i,
      animeId,
      url: `/ep/${i}`,
      name: `Episode ${i}`,
      episodeNumber: i,
      scanlator: null,
      uploadDate: String(Date.UTC(2024, 0, i)),
      sourceOrder: i,
      isWatched: false,
      isBookmarked: false,
      fillermark: false,
      lastSecondSeen: "0",
      totalSeconds: "0",
      summary: null,
      previewUrl: null,
    };
  });
}

/** `n` chapters of `mangaId`, like {@link makeEpisodes}. */
export function makeChapters(n: number, mangaId = 34): Chapter[] {
  return Array.from({ length: n }, (_, k) => {
    const i = k + 1;
    return {
      id: 5000 + i,
      mangaId,
      url: `/ch/${i}`,
      name: `Chapter ${i}`,
      chapterNumber: i,
      scanlator: null,
      uploadDate: String(Date.UTC(2024, 0, i)),
      sourceOrder: i,
      isRead: false,
      isBookmarked: false,
      lastPageRead: 0,
      lastReadAt: "0",
      pageCount: 0,
    };
  });
}

/** MangaPill as a manga source. */
export function makeMangaSource(overrides: Partial<Source> = {}): Source {
  return makeSource({
    kind: "manga",
    id: "3436757651215440337",
    name: "MangaPill",
    baseUrl: "https://mangapill.com",
    extension: {
      pkgName: "eu.kanade.tachiyomi.extension.en.mangapill",
      name: "MangaPill",
      isTrusted: true,
      isLoaded: true,
    },
    ...overrides,
  });
}

/** A running playback session of episode 1001 of anime 12 (as `makeEpisodes` numbers them). */
export function makePlaybackSession(overrides: Partial<PlaybackSession> = {}): PlaybackSession {
  return {
    id: "s1",
    episodeId: 1001,
    animeId: 12,
    title: "One Piece - Episode 1",
    videoTitle: "1080p",
    state: "RUNNING",
    startedAt: "1",
    endedAt: null,
    exitCode: null,
    positionSeconds: null,
    durationSeconds: null,
    paused: false,
    ...overrides,
  };
}

/** A `playbackEvents` event of `type` for `session` (default: {@link makePlaybackSession}). */
export function makePlaybackEvent(
  type: PlaybackEvent["type"],
  overrides: Partial<PlaybackEvent> = {},
): PlaybackEvent {
  return {
    type,
    session: makePlaybackSession(),
    nextEpisodeId: null,
    reason: null,
    message: null,
    httpStatus: null,
    retried: false,
    failedVideo: null,
    nextVideo: null,
    ...overrides,
  };
}
