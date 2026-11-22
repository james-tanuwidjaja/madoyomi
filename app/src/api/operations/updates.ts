import { api } from "../client";
import type {
  LibraryCheck,
  LibraryUpdateSettings,
  LongString,
  MediaKind,
  UpdatesItem,
  UpdatesPage,
} from "../types";
import { extensionKindOf } from "../types";

/*
 * The library update check, Madoyomi's for both kinds: start it for a kind or one of its categories,
 * stop it, its status (`libraryCheck`; the `libraryUpdates` subscription is in ./subscriptions), its settings, the
 * Updates feed of each kind (`animeUpdates`/`mangaUpdates`, newest fetch first, paged by offset), and the "new" marks of
 * the entries a check found new items in (`updateMarks`, cleared when an entry is opened: `clearUpdateMarks`).
 */

export const LIBRARY_CHECK_FIELDS = /* GraphQL */ `
  fragment LibraryCheckFields on LibraryCheckType {
    running
    trigger
    startedAt
    finishedAt
    total
    done
    skipped
    checking {
      kind
      id
      title
    }
    failures {
      entry {
        kind
        id
        title
      }
      error
    }
    found {
      entry {
        kind
        id
        title
      }
      newCount
    }
    newEpisodes
    newChapters
    cancelled
    nextScheduledAt
  }
`;

export const LIBRARY_CHECK_QUERY = /* GraphQL */ `
  query LibraryCheck {
    libraryCheck {
      ...LibraryCheckFields
    }
  }
  ${LIBRARY_CHECK_FIELDS}
`;

export const START_LIBRARY_UPDATE_MUTATION = /* GraphQL */ `
  mutation StartLibraryUpdate($kind: ExtensionKind, $categoryId: Int) {
    startLibraryUpdate(input: { kind: $kind, categoryId: $categoryId }) {
      libraryCheck {
        ...LibraryCheckFields
      }
    }
  }
  ${LIBRARY_CHECK_FIELDS}
`;

export const STOP_LIBRARY_UPDATE_MUTATION = /* GraphQL */ `
  mutation StopLibraryUpdate {
    stopLibraryUpdate(input: {}) {
      libraryCheck {
        ...LibraryCheckFields
      }
    }
  }
  ${LIBRARY_CHECK_FIELDS}
`;

const SETTINGS_FIELDS = `
  intervalHours
  skipCompleted
  skipUnseen
  skipNotStarted
  notify
`;

export const LIBRARY_UPDATE_SETTINGS_QUERY = /* GraphQL */ `
  query LibraryUpdateSettings {
    libraryUpdateSettings {
      ${SETTINGS_FIELDS}
    }
  }
`;

export const SET_LIBRARY_UPDATE_SETTINGS_MUTATION = /* GraphQL */ `
  mutation SetLibraryUpdateSettings(
    $intervalHours: Int
    $skipCompleted: Boolean
    $skipUnseen: Boolean
    $skipNotStarted: Boolean
    $notify: Boolean
  ) {
    setLibraryUpdateSettings(
      input: {
        intervalHours: $intervalHours
        skipCompleted: $skipCompleted
        skipUnseen: $skipUnseen
        skipNotStarted: $skipNotStarted
        notify: $notify
      }
    ) {
      settings {
        ${SETTINGS_FIELDS}
      }
    }
  }
`;

const ENTRY_FIELDS = `
  id
  title
  thumbnailUrl
  inLibrary
`;

export const ANIME_UPDATES_QUERY = /* GraphQL */ `
  query AnimeUpdates($limit: Int, $offset: Int) {
    animeUpdates(limit: $limit, offset: $offset) {
      hasMore
      items {
        fetchedAt
        anime {
          ${ENTRY_FIELDS}
        }
        episode {
          id
          name
          isWatched
          lastSecondSeen
          totalSeconds
        }
      }
    }
  }
`;

export const MANGA_UPDATES_QUERY = /* GraphQL */ `
  query MangaUpdates($limit: Int, $offset: Int) {
    mangaUpdates(limit: $limit, offset: $offset) {
      hasMore
      items {
        fetchedAt
        manga {
          ${ENTRY_FIELDS}
        }
        chapter {
          id
          name
          isRead
          lastPageRead
          pageCount
        }
      }
    }
  }
`;

export const UPDATE_MARKS_QUERY = /* GraphQL */ `
  query UpdateMarks($kind: ExtensionKind!) {
    updateMarks(kind: $kind) {
      entryId
      newCount
      markedAt
    }
  }
`;

export const CLEAR_UPDATE_MARKS_MUTATION = /* GraphQL */ `
  mutation ClearUpdateMarks($kind: ExtensionKind!, $entryIds: [Int!]) {
    clearUpdateMarks(input: { kind: $kind, entryIds: $entryIds }) {
      cleared
    }
  }
`;

interface EntryNode {
  id: number;
  title: string;
  thumbnailUrl: string | null;
  inLibrary: boolean;
}

interface AnimeUpdatesData {
  animeUpdates: {
    hasMore: boolean;
    items: {
      fetchedAt: LongString;
      anime: EntryNode;
      episode: {
        id: number;
        name: string;
        isWatched: boolean;
        lastSecondSeen: LongString;
        totalSeconds: LongString;
      };
    }[];
  };
}

interface MangaUpdatesData {
  mangaUpdates: {
    hasMore: boolean;
    items: {
      fetchedAt: LongString;
      manga: EntryNode;
      chapter: {
        id: number;
        name: string;
        isRead: boolean;
        lastPageRead: number;
        pageCount: number;
      };
    }[];
  };
}

export async function fetchLibraryCheck(signal?: AbortSignal): Promise<LibraryCheck> {
  return (await api.gql<{ libraryCheck: LibraryCheck }>(LIBRARY_CHECK_QUERY, undefined, { signal }))
    .libraryCheck;
}

/** Starts a check of `kind`'s library, or of one of its categories (whatever its update flag). */
export async function startLibraryUpdate(
  kind: MediaKind,
  categoryId?: number,
): Promise<LibraryCheck> {
  return (
    await api.gql<{ startLibraryUpdate: { libraryCheck: LibraryCheck } }>(
      START_LIBRARY_UPDATE_MUTATION,
      { kind: extensionKindOf(kind), categoryId: categoryId ?? null },
    )
  ).startLibraryUpdate.libraryCheck;
}

export async function stopLibraryUpdate(): Promise<LibraryCheck> {
  return (
    await api.gql<{ stopLibraryUpdate: { libraryCheck: LibraryCheck } }>(
      STOP_LIBRARY_UPDATE_MUTATION,
    )
  ).stopLibraryUpdate.libraryCheck;
}

export async function fetchLibraryUpdateSettings(
  signal?: AbortSignal,
): Promise<LibraryUpdateSettings> {
  return (
    await api.gql<{ libraryUpdateSettings: LibraryUpdateSettings }>(
      LIBRARY_UPDATE_SETTINGS_QUERY,
      undefined,
      { signal },
    )
  ).libraryUpdateSettings;
}

/** Changes the fields given; a new interval reschedules at once. */
export async function setLibraryUpdateSettings(
  patch: Partial<LibraryUpdateSettings>,
): Promise<LibraryUpdateSettings> {
  return (
    await api.gql<{ setLibraryUpdateSettings: { settings: LibraryUpdateSettings } }>(
      SET_LIBRARY_UPDATE_SETTINGS_MUTATION,
      patch,
    )
  ).setLibraryUpdateSettings.settings;
}

/** The page of `kind`'s Updates that starts at `offset`, newest fetch first. */
export async function fetchUpdates(
  kind: MediaKind,
  offset: number,
  limit: number,
  signal?: AbortSignal,
): Promise<UpdatesPage> {
  if (kind === "anime") {
    const { animeUpdates: page } = await api.gql<AnimeUpdatesData>(
      ANIME_UPDATES_QUERY,
      { limit, offset },
      { signal },
    );
    return {
      hasMore: page.hasMore,
      items: page.items.map((i): UpdatesItem => ({
        kind,
        entry: { kind, ...i.anime },
        item: {
          id: i.episode.id,
          name: i.episode.name,
          done: i.episode.isWatched,
          progress: {
            positionMs: Number(i.episode.lastSecondSeen),
            durationMs: Number(i.episode.totalSeconds),
          },
        },
        at: Number(i.fetchedAt),
      })),
    };
  }
  const { mangaUpdates: page } = await api.gql<MangaUpdatesData>(
    MANGA_UPDATES_QUERY,
    { limit, offset },
    { signal },
  );
  return {
    hasMore: page.hasMore,
    items: page.items.map((i): UpdatesItem => ({
      kind,
      entry: { kind, ...i.manga },
      item: {
        id: i.chapter.id,
        name: i.chapter.name,
        done: i.chapter.isRead,
        pages: { lastPageRead: i.chapter.lastPageRead, pageCount: i.chapter.pageCount },
      },
      at: Number(i.fetchedAt),
    })),
  };
}

/** The new item count of each marked library entry of `kind`. */
export async function fetchUpdateMarks(
  kind: MediaKind,
  signal?: AbortSignal,
): Promise<Record<number, number>> {
  const { updateMarks } = await api.gql<{ updateMarks: { entryId: number; newCount: number }[] }>(
    UPDATE_MARKS_QUERY,
    { kind: extensionKindOf(kind) },
    { signal },
  );
  return Object.fromEntries(updateMarks.map((m) => [m.entryId, m.newCount]));
}

/** Clears the marks of `entryIds` (opened), or all of `kind`'s when it is left out. */
export async function clearUpdateMarks(kind: MediaKind, entryIds?: number[]): Promise<number> {
  return (
    await api.gql<{ clearUpdateMarks: { cleared: number } }>(CLEAR_UPDATE_MARKS_MUTATION, {
      kind: extensionKindOf(kind),
      entryIds: entryIds ?? null,
    })
  ).clearUpdateMarks.cleared;
}
