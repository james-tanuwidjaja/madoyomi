import { api } from "../client";
import type { HistoryItem, HistoryPage, HistoryTarget, LongString, MediaKind } from "../types";

/*
 * History (Phase 6 Stage 2), one list per kind: Madoyomi's `animeHistory`/`mangaHistory` (one item per entry, its
 * episode or chapter played or read last, newest first, paged by offset), `deleteAnimeHistory`/`deleteMangaHistory`
 * (by entry) and `clearAnimeHistory`/`clearMangaHistory`. Deleting keeps the watched and read marks and positions.
 * Anime history is written by the player's saves, manga history by the reader's (upstream's `lastReadAt`).
 */

const ENTRY_FIELDS = `
  id
  title
  thumbnailUrl
  inLibrary
`;

const EPISODE_FIELDS = `
  id
  name
  isWatched
  lastSecondSeen
  totalSeconds
`;

const CHAPTER_FIELDS = `
  id
  name
  isRead
  lastPageRead
  pageCount
`;

export const ANIME_HISTORY_QUERY = /* GraphQL */ `
  query AnimeHistory($limit: Int, $offset: Int) {
    animeHistory(limit: $limit, offset: $offset) {
      hasMore
      items {
        lastSeenAt
        anime {
          ${ENTRY_FIELDS}
        }
        episode {
          ${EPISODE_FIELDS}
        }
        resume {
          ${EPISODE_FIELDS}
        }
      }
    }
  }
`;

export const MANGA_HISTORY_QUERY = /* GraphQL */ `
  query MangaHistory($limit: Int, $offset: Int) {
    mangaHistory(limit: $limit, offset: $offset) {
      hasMore
      items {
        lastReadAt
        manga {
          ${ENTRY_FIELDS}
        }
        chapter {
          ${CHAPTER_FIELDS}
        }
        resume {
          ${CHAPTER_FIELDS}
        }
      }
    }
  }
`;

export const DELETE_ANIME_HISTORY_MUTATION = /* GraphQL */ `
  mutation DeleteAnimeHistory($ids: [Int!]!) {
    deleteAnimeHistory(input: { animeIds: $ids }) {
      removed
    }
  }
`;

export const DELETE_MANGA_HISTORY_MUTATION = /* GraphQL */ `
  mutation DeleteMangaHistory($ids: [Int!]!) {
    deleteMangaHistory(input: { mangaIds: $ids }) {
      removed
    }
  }
`;

export const CLEAR_ANIME_HISTORY_MUTATION = /* GraphQL */ `
  mutation ClearAnimeHistory {
    clearAnimeHistory(input: {}) {
      removed
    }
  }
`;

export const CLEAR_MANGA_HISTORY_MUTATION = /* GraphQL */ `
  mutation ClearMangaHistory {
    clearMangaHistory(input: {}) {
      removed
    }
  }
`;

interface EntryNode {
  id: number;
  title: string;
  thumbnailUrl: string | null;
  inLibrary: boolean;
}

interface EpisodeNode {
  id: number;
  name: string;
  isWatched: boolean;
  lastSecondSeen: LongString;
  totalSeconds: LongString;
}

interface ChapterNode {
  id: number;
  name: string;
  isRead: boolean;
  lastPageRead: number;
  pageCount: number;
}

interface AnimeHistoryData {
  animeHistory: {
    hasMore: boolean;
    items: {
      lastSeenAt: LongString;
      anime: EntryNode;
      episode: EpisodeNode;
      resume: EpisodeNode;
    }[];
  };
}

interface MangaHistoryData {
  mangaHistory: {
    hasMore: boolean;
    items: {
      lastReadAt: LongString;
      manga: EntryNode;
      chapter: ChapterNode;
      resume: ChapterNode;
    }[];
  };
}

const episodeTarget = (e: EpisodeNode): HistoryTarget => ({
  id: e.id,
  name: e.name,
  done: e.isWatched,
  progress: { positionMs: Number(e.lastSecondSeen), durationMs: Number(e.totalSeconds) },
});

const chapterTarget = (c: ChapterNode): HistoryTarget => ({
  id: c.id,
  name: c.name,
  done: c.isRead,
  pages: { lastPageRead: c.lastPageRead, pageCount: c.pageCount },
});

/** The page of `kind`'s history that starts at `offset`, newest first. */
export async function fetchHistory(
  kind: MediaKind,
  offset: number,
  limit: number,
  signal?: AbortSignal,
): Promise<HistoryPage> {
  if (kind === "anime") {
    const { animeHistory: page } = await api.gql<AnimeHistoryData>(
      ANIME_HISTORY_QUERY,
      { limit, offset },
      { signal },
    );
    return {
      hasMore: page.hasMore,
      items: page.items.map((i): HistoryItem => ({
        kind,
        entry: { kind, ...i.anime },
        item: episodeTarget(i.episode),
        resume: episodeTarget(i.resume),
        at: Number(i.lastSeenAt),
      })),
    };
  }
  const { mangaHistory: page } = await api.gql<MangaHistoryData>(
    MANGA_HISTORY_QUERY,
    { limit, offset },
    { signal },
  );
  return {
    hasMore: page.hasMore,
    items: page.items.map((i): HistoryItem => ({
      kind,
      entry: { kind, ...i.manga },
      item: chapterTarget(i.chapter),
      resume: chapterTarget(i.resume),
      at: Number(i.lastReadAt),
    })),
  };
}

/** Removes entries of `kind` from its history; returns how many episodes or chapters lost theirs. */
export async function deleteHistory(kind: MediaKind, entryIds: number[]): Promise<number> {
  if (kind === "anime") {
    return (
      await api.gql<{ deleteAnimeHistory: { removed: number } }>(DELETE_ANIME_HISTORY_MUTATION, {
        ids: entryIds,
      })
    ).deleteAnimeHistory.removed;
  }
  return (
    await api.gql<{ deleteMangaHistory: { removed: number } }>(DELETE_MANGA_HISTORY_MUTATION, {
      ids: entryIds,
    })
  ).deleteMangaHistory.removed;
}

/** Clears `kind`'s history. */
export async function clearHistory(kind: MediaKind): Promise<number> {
  if (kind === "anime") {
    return (await api.gql<{ clearAnimeHistory: { removed: number } }>(CLEAR_ANIME_HISTORY_MUTATION))
      .clearAnimeHistory.removed;
  }
  return (await api.gql<{ clearMangaHistory: { removed: number } }>(CLEAR_MANGA_HISTORY_MUTATION))
    .clearMangaHistory.removed;
}
