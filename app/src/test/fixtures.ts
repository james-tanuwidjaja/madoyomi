import type { Extension, ExtensionStore, MediaKind, Source } from "../api/types";

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
