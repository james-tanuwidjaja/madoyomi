import { api, ApiError } from "../client";
import type {
  Anime,
  AnimeRefresh,
  AnimeSeason,
  Chapter,
  Episode,
  EpisodePatch,
  LibraryEntry,
  Manga,
  MangaRefresh,
  MediaKind,
} from "../types";

/*
 * Stored entries and their children: Madoyomi's `anime`/`episodes` and upstream's `manga`/`chapters` (database reads),
 * the refreshes from the source (`fetchAnimeAndEpisodes`; upstream's `fetchManga` + `fetchChapters`), the library flag
 * (`updateAnime`, upstream's `updateManga`), the library lists, and the episodes' watched marks (`updateEpisodes`, upstream's
 * `updateChapters`; Phase 4 Stage 3).
 */

const ANIME_FIELDS = /* GraphQL */ `
  fragment AnimeFields on AnimeType {
    id
    sourceId
    url
    title
    thumbnailUrl
    initialized
    inLibrary
    inLibraryAt
    artist
    author
    description
    genre
    status
    fetchType
    seasonNumber
    parentId
  }
`;

const MANGA_FIELDS = /* GraphQL */ `
  fragment MangaFields on MangaType {
    id
    sourceId
    url
    title
    thumbnailUrl
    initialized
    inLibrary
    inLibraryAt
    artist
    author
    description
    genre
    status
  }
`;

const EPISODE_FIELDS = /* GraphQL */ `
  fragment EpisodeFields on EpisodeType {
    id
    animeId
    url
    name
    episodeNumber
    scanlator
    uploadDate
    sourceOrder
    isWatched
    isBookmarked
    fillermark
    lastSecondSeen
    totalSeconds
    summary
    previewUrl
  }
`;

export const CHAPTER_FIELDS = /* GraphQL */ `
  fragment ChapterFields on ChapterType {
    id
    mangaId
    url
    name
    chapterNumber
    scanlator
    uploadDate
    sourceOrder
    isRead
    isBookmarked
    lastPageRead
    pageCount
  }
`;

const SEASON_FIELDS = /* GraphQL */ `
  fragment SeasonFields on AnimeType {
    id
    title
    thumbnailUrl
    inLibrary
    seasonNumber
  }
`;

export const ANIME_QUERY = /* GraphQL */ `
  query Anime($id: Int!) {
    anime(id: $id) {
      ...AnimeFields
    }
  }
  ${ANIME_FIELDS}
`;

export const EPISODES_QUERY = /* GraphQL */ `
  query Episodes($animeId: Int!) {
    episodes(condition: { animeId: $animeId }) {
      nodes {
        ...EpisodeFields
      }
    }
  }
  ${EPISODE_FIELDS}
`;

export const ANIME_SEASONS_QUERY = /* GraphQL */ `
  query AnimeSeasons($id: Int!) {
    anime(id: $id) {
      id
      seasons {
        nodes {
          ...SeasonFields
        }
      }
    }
  }
  ${SEASON_FIELDS}
`;

export const MANGA_QUERY = /* GraphQL */ `
  query Manga($id: Int!) {
    manga(id: $id) {
      ...MangaFields
    }
  }
  ${MANGA_FIELDS}
`;

export const CHAPTERS_QUERY = /* GraphQL */ `
  query Chapters($mangaId: Int!) {
    chapters(condition: { mangaId: $mangaId }) {
      nodes {
        ...ChapterFields
      }
    }
  }
  ${CHAPTER_FIELDS}
`;

export const FETCH_ANIME_AND_EPISODES_MUTATION = /* GraphQL */ `
  mutation FetchAnimeAndEpisodes($id: Int!) {
    fetchAnimeAndEpisodes(input: { id: $id, fetchAnime: true, fetchEpisodes: true }) {
      anime {
        ...AnimeFields
      }
      episodes {
        ...EpisodeFields
      }
      seasons {
        ...SeasonFields
      }
    }
  }
  ${ANIME_FIELDS}
  ${EPISODE_FIELDS}
  ${SEASON_FIELDS}
`;

export const FETCH_MANGA_MUTATION = /* GraphQL */ `
  mutation FetchManga($id: Int!) {
    fetchManga(input: { id: $id }) {
      manga {
        ...MangaFields
      }
    }
  }
  ${MANGA_FIELDS}
`;

export const FETCH_CHAPTERS_MUTATION = /* GraphQL */ `
  mutation FetchChapters($mangaId: Int!) {
    fetchChapters(input: { mangaId: $mangaId }) {
      chapters {
        ...ChapterFields
      }
    }
  }
  ${CHAPTER_FIELDS}
`;

export const UPDATE_ANIME_MUTATION = /* GraphQL */ `
  mutation UpdateAnime($id: Int!, $inLibrary: Boolean!) {
    updateAnime(input: { id: $id, patch: { inLibrary: $inLibrary } }) {
      anime {
        ...AnimeFields
      }
    }
  }
  ${ANIME_FIELDS}
`;

export const UPDATE_MANGA_MUTATION = /* GraphQL */ `
  mutation UpdateManga($id: Int!, $inLibrary: Boolean!) {
    updateManga(input: { id: $id, patch: { inLibrary: $inLibrary } }) {
      manga {
        ...MangaFields
      }
    }
  }
  ${MANGA_FIELDS}
`;

export const UPDATE_EPISODES_MUTATION = /* GraphQL */ `
  mutation UpdateEpisodes($ids: [Int!]!, $patch: UpdateEpisodePatchInput!) {
    updateEpisodes(input: { ids: $ids, patch: $patch }) {
      episodes {
        ...EpisodeFields
      }
    }
  }
  ${EPISODE_FIELDS}
`;

const LIBRARY_FIELDS = `
  id
  title
  thumbnailUrl
  inLibrary
  inLibraryAt
`;

export const LIBRARY_ANIME_QUERY = /* GraphQL */ `
  query LibraryAnime {
    animes(condition: { inLibrary: true }) {
      nodes {
        ${LIBRARY_FIELDS}
      }
    }
  }
`;

export const LIBRARY_MANGA_QUERY = /* GraphQL */ `
  query LibraryManga {
    mangas(condition: { inLibrary: true }) {
      nodes {
        ${LIBRARY_FIELDS}
      }
    }
  }
`;

export async function fetchAnime(id: number, signal?: AbortSignal): Promise<Anime> {
  return (await api.gql<{ anime: Anime }>(ANIME_QUERY, { id }, { signal })).anime;
}

export async function fetchEpisodes(animeId: number, signal?: AbortSignal): Promise<Episode[]> {
  return (
    await api.gql<{ episodes: { nodes: Episode[] } }>(EPISODES_QUERY, { animeId }, { signal })
  ).episodes.nodes;
}

export async function fetchSeasons(id: number, signal?: AbortSignal): Promise<AnimeSeason[]> {
  return (
    await api.gql<{ anime: { seasons: { nodes: AnimeSeason[] } } }>(
      ANIME_SEASONS_QUERY,
      { id },
      { signal },
    )
  ).anime.seasons.nodes;
}

export async function fetchManga(id: number, signal?: AbortSignal): Promise<Manga> {
  return (await api.gql<{ manga: Manga }>(MANGA_QUERY, { id }, { signal })).manga;
}

export async function fetchChapters(mangaId: number, signal?: AbortSignal): Promise<Chapter[]> {
  return (
    await api.gql<{ chapters: { nodes: Chapter[] } }>(CHAPTERS_QUERY, { mangaId }, { signal })
  ).chapters.nodes;
}

/**
 * Refreshes an anime's details and episodes (or seasons) from its source. A source failure still returns the stored
 * data, with the source's error as `error`; only a failure without data (the backend, an unknown id) throws.
 */
export async function refreshAnime(id: number): Promise<AnimeRefresh> {
  const { data, errors } = await api.gqlResult<{
    fetchAnimeAndEpisodes: Omit<AnimeRefresh, "error"> | null;
  }>(FETCH_ANIME_AND_EPISODES_MUTATION, { id });
  const payload = data?.fetchAnimeAndEpisodes;
  if (!payload) throw errors[0] ?? noData();
  return { ...payload, error: errors[0] ?? null };
}

/**
 * Refreshes a manga's details, then its chapters, from its source (upstream's `fetchManga` and `fetchChapters`).
 * A source failure stops there and is returned as `error`, with what is stored (read again), so the details page
 * keeps its data. Upstream's `fetchMangaAndChapters` would do both in one call, but its partial error reaches the
 * client without the error code (as `INTERNAL`), where these two get the source's `SOURCE_FAILED`.
 */
export async function refreshManga(id: number): Promise<MangaRefresh> {
  let error: ApiError | null = null;
  let manga: Manga | null = null;
  let chapters: Chapter[] | null = null;
  try {
    manga = (await api.gql<{ fetchManga: { manga: Manga } }>(FETCH_MANGA_MUTATION, { id }))
      .fetchManga.manga;
    chapters = (
      await api.gql<{ fetchChapters: { chapters: Chapter[] } }>(FETCH_CHAPTERS_MUTATION, {
        mangaId: id,
      })
    ).fetchChapters.chapters;
  } catch (e) {
    // The backend itself (down, restarting) or an unknown entry: nothing to keep from here.
    if (!(e instanceof ApiError) || e.isTransient || e.code === "NOT_FOUND") throw e;
    error = e;
  }
  return {
    manga: manga ?? (await fetchManga(id)),
    chapters: chapters ?? (await fetchChapters(id)),
    error,
  };
}

/** Adds an entry to the library or removes it; returns the entry as stored. */
export async function setInLibrary(
  kind: MediaKind,
  id: number,
  inLibrary: boolean,
): Promise<Anime | Manga> {
  if (kind === "anime") {
    return (
      await api.gql<{ updateAnime: { anime: Anime } }>(UPDATE_ANIME_MUTATION, { id, inLibrary })
    ).updateAnime.anime;
  }
  return (
    await api.gql<{ updateManga: { manga: Manga } }>(UPDATE_MANGA_MUTATION, { id, inLibrary })
  ).updateManga.manga;
}

/**
 * Changes episodes (watched, bookmarked, the position in milliseconds); returns them as stored, in the given order.
 * `isWatched: false` also clears the position, as in Aniyomi.
 */
export async function updateEpisodes(ids: number[], patch: EpisodePatch): Promise<Episode[]> {
  return (
    await api.gql<{ updateEpisodes: { episodes: Episode[] } }>(UPDATE_EPISODES_MUTATION, {
      ids,
      patch,
    })
  ).updateEpisodes.episodes;
}

type LibraryNode = Omit<LibraryEntry, "kind" | "inLibraryAt"> & { inLibraryAt: string };

/** The entries of `kind` in the library (unsorted; the view sorts them). */
export async function fetchLibrary(kind: MediaKind, signal?: AbortSignal): Promise<LibraryEntry[]> {
  const nodes =
    kind === "anime"
      ? (await api.gql<{ animes: { nodes: LibraryNode[] } }>(LIBRARY_ANIME_QUERY, {}, { signal }))
          .animes.nodes
      : (await api.gql<{ mangas: { nodes: LibraryNode[] } }>(LIBRARY_MANGA_QUERY, {}, { signal }))
          .mangas.nodes;
  return nodes.map((n) => ({ ...n, kind, inLibraryAt: Number(n.inLibraryAt) }));
}

function noData() {
  return new ApiError({
    kind: "graphql",
    code: "BAD_RESPONSE",
    message: "The backend returned no data",
  });
}
