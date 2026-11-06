import { api } from "../client";
import type { Anime, Chapter, Episode, Manga } from "../types";

/*
 * Stored entries and their children: Madoyomi's `anime`/`episodes` and upstream's `manga`/`chapters`. These read the
 * database only; refreshing from the source (`fetchAnimeAndEpisodes`, `fetchManga`/`fetchChapters`) comes with the
 * Details screen (Stage 5).
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
    artist
    author
    description
    genre
    status
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
    }
  }
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

export async function fetchManga(id: number, signal?: AbortSignal): Promise<Manga> {
  return (await api.gql<{ manga: Manga }>(MANGA_QUERY, { id }, { signal })).manga;
}

export async function fetchChapters(mangaId: number, signal?: AbortSignal): Promise<Chapter[]> {
  return (
    await api.gql<{ chapters: { nodes: Chapter[] } }>(CHAPTERS_QUERY, { mangaId }, { signal })
  ).chapters.nodes;
}
