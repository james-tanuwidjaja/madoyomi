import { api } from "../client";
import type {
  ChapterPages,
  PageCache,
  PrefetchResult,
  ReaderSettings,
  ReaderSettingsPatch,
} from "../types";
import { CHAPTER_FIELDS } from "./entries";

/*
 * The manga reader's backend (Phase 5): a chapter's pages (upstream's `fetchChapterPages`, which fetches the page list
 * from the source each time), the prefetch that warms the page cache, and the reader settings the backend applies
 * (the page cache's cap, Settings → Reader), with what the cache holds and Clear.
 */

export const FETCH_CHAPTER_PAGES_MUTATION = /* GraphQL */ `
  mutation FetchChapterPages($chapterId: Int!) {
    fetchChapterPages(input: { chapterId: $chapterId }) {
      pages
      chapter {
        ...ChapterFields
      }
    }
  }
  ${CHAPTER_FIELDS}
`;

export const PREFETCH_CHAPTER_PAGES_MUTATION = /* GraphQL */ `
  mutation PrefetchChapterPages($chapterId: Int!, $from: Int!, $count: Int!) {
    prefetchChapterPages(input: { chapterId: $chapterId, from: $from, count: $count }) {
      chapterId
      pageCount
      queued
      cached
    }
  }
`;

export const READER_SETTINGS_QUERY = /* GraphQL */ `
  query ReaderSettings {
    readerSettings {
      pageCacheLimitMb
    }
  }
`;

export const SET_READER_SETTINGS_MUTATION = /* GraphQL */ `
  mutation SetReaderSettings($patch: ReaderSettingsPatchInput!) {
    setReaderSettings(input: { patch: $patch }) {
      settings {
        pageCacheLimitMb
      }
    }
  }
`;

const PAGE_CACHE_FIELDS = /* GraphQL */ `
  fragment PageCacheFields on PageCacheType {
    bytes
    files
    limitBytes
    path
  }
`;

export const PAGE_CACHE_QUERY = /* GraphQL */ `
  query PageCache {
    pageCache {
      ...PageCacheFields
    }
  }
  ${PAGE_CACHE_FIELDS}
`;

export const CLEAR_PAGE_CACHE_MUTATION = /* GraphQL */ `
  mutation ClearPageCache {
    clearPageCache(input: {}) {
      cache {
        ...PageCacheFields
      }
    }
  }
  ${PAGE_CACHE_FIELDS}
`;

/** A chapter's page routes (fetched from its source now) and the chapter as stored. */
export async function fetchChapterPages(
  chapterId: number,
  signal?: AbortSignal,
): Promise<ChapterPages> {
  return (
    await api.gql<{ fetchChapterPages: ChapterPages }>(
      FETCH_CHAPTER_PAGES_MUTATION,
      { chapterId },
      { signal },
    )
  ).fetchChapterPages;
}

/** Warms the cache with pages `from` … `from + count - 1` (count 1 to 20) of a chapter, in the background. */
export async function prefetchChapterPages(
  chapterId: number,
  from: number,
  count: number,
): Promise<PrefetchResult> {
  return (
    await api.gql<{ prefetchChapterPages: PrefetchResult }>(PREFETCH_CHAPTER_PAGES_MUTATION, {
      chapterId,
      from,
      count,
    })
  ).prefetchChapterPages;
}

export async function fetchReaderSettings(signal?: AbortSignal): Promise<ReaderSettings> {
  return (
    await api.gql<{ readerSettings: ReaderSettings }>(READER_SETTINGS_QUERY, undefined, { signal })
  ).readerSettings;
}

/** Changes the given fields and returns all of them as the backend kept them. A lower cap trims the cache first. */
export async function setReaderSettings(patch: ReaderSettingsPatch): Promise<ReaderSettings> {
  return (
    await api.gql<{ setReaderSettings: { settings: ReaderSettings } }>(
      SET_READER_SETTINGS_MUTATION,
      { patch },
    )
  ).setReaderSettings.settings;
}

export async function fetchPageCache(signal?: AbortSignal): Promise<PageCache> {
  return (await api.gql<{ pageCache: PageCache }>(PAGE_CACHE_QUERY, undefined, { signal }))
    .pageCache;
}

/** Removes every cached page (not downloads, not covers) and returns what is left. */
export async function clearPageCache(): Promise<PageCache> {
  return (await api.gql<{ clearPageCache: { cache: PageCache } }>(CLEAR_PAGE_CACHE_MUTATION))
    .clearPageCache.cache;
}
