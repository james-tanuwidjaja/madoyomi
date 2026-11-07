import { ApiError, api } from "../client";
import type {
  Anime,
  BrowseType,
  Filter,
  FilterChange,
  Manga,
  MediaKind,
  Source,
  SourceBase,
  SourcePage,
} from "../types";

/*
 * Sources of both kinds: anime through Madoyomi's `animeSources`/`fetchSourceAnime`, manga through upstream's
 * `sources`/`source`/`fetchSourceManga`. Both answer in the same shapes, normalised here to `Source` and `SourcePage`.
 */

export const SOURCE_FIELDS = (on: string) => /* GraphQL */ `
  fragment SourceFields on ${on} {
    id
    name
    lang
    contentWarning
    iconUrl
    baseUrl
    supportsLatest
    isConfigurable
    extension {
      pkgName
      name
      isTrusted
      isLoaded
    }
  }
`;

/**
 * The `Filter` union. Fields of the same name with different types (`default`) need aliases in a union selection,
 * so each one is aliased by its kind (`selectDefault`, …), as in types.ts.
 */
export const FILTER_FIELDS = /* GraphQL */ `
  fragment FilterLeafFields on Filter {
    __typename
    ... on HeaderFilter {
      name
    }
    ... on SeparatorFilter {
      name
    }
    ... on SelectFilter {
      name
      values
      selectDefault: default
    }
    ... on TextFilter {
      name
      textDefault: default
    }
    ... on CheckBoxFilter {
      name
      checkBoxDefault: default
    }
    ... on TriStateFilter {
      name
      triStateDefault: default
    }
    ... on SortFilter {
      name
      values
      sortDefault: default {
        index
        ascending
      }
    }
  }
  fragment FilterFields on Filter {
    ...FilterLeafFields
    ... on GroupFilter {
      name
      filters {
        ...FilterLeafFields
      }
    }
  }
`;

/** The browse card fields; both entry types have them. */
const ENTRY_CARD_FIELDS = `
  id
  title
  thumbnailUrl
  inLibrary
`;

export const ANIME_SOURCES_QUERY = /* GraphQL */ `
  query AnimeSources {
    animeSources {
      nodes {
        ...SourceFields
      }
    }
  }
  ${SOURCE_FIELDS("AnimeSourceType")}
`;

export const MANGA_SOURCES_QUERY = /* GraphQL */ `
  query MangaSources {
    sources {
      nodes {
        ...SourceFields
      }
    }
  }
  ${SOURCE_FIELDS("SourceType")}
`;

export const ANIME_SOURCE_FILTERS_QUERY = /* GraphQL */ `
  query AnimeSourceFilters($id: LongString!) {
    animeSources(condition: { id: $id }) {
      nodes {
        id
        filters {
          ...FilterFields
        }
      }
    }
  }
  ${FILTER_FIELDS}
`;

export const MANGA_SOURCE_FILTERS_QUERY = /* GraphQL */ `
  query MangaSourceFilters($id: LongString!) {
    source(id: $id) {
      id
      filters {
        ...FilterFields
      }
    }
  }
  ${FILTER_FIELDS}
`;

export const BROWSE_ANIME_MUTATION = /* GraphQL */ `
  mutation BrowseAnime($input: FetchSourceAnimeInput!) {
    fetchSourceAnime(input: $input) {
      hasNextPage
      animes {
        ${ENTRY_CARD_FIELDS}
      }
    }
  }
`;

export const BROWSE_MANGA_MUTATION = /* GraphQL */ `
  mutation BrowseManga($input: FetchSourceMangaInput!) {
    fetchSourceManga(input: $input) {
      hasNextPage
      mangas {
        ${ENTRY_CARD_FIELDS}
      }
    }
  }
`;

export async function fetchSources(kind: MediaKind, signal?: AbortSignal): Promise<Source[]> {
  const nodes =
    kind === "anime"
      ? (
          await api.gql<{ animeSources: { nodes: SourceBase[] } }>(
            ANIME_SOURCES_QUERY,
            {},
            { signal },
          )
        ).animeSources.nodes
      : (await api.gql<{ sources: { nodes: SourceBase[] } }>(MANGA_SOURCES_QUERY, {}, { signal }))
          .sources.nodes;
  return nodes.map((s) => ({ ...s, kind }));
}

export async function fetchSourceFilters(
  kind: MediaKind,
  sourceId: string,
  signal?: AbortSignal,
): Promise<Filter[]> {
  if (kind === "anime") {
    const data = await api.gql<{ animeSources: { nodes: { filters: Filter[] }[] } }>(
      ANIME_SOURCE_FILTERS_QUERY,
      { id: sourceId },
      { signal },
    );
    const source = data.animeSources.nodes[0];
    if (!source) throw notFound(sourceId);
    return source.filters;
  }
  const data = await api.gql<{ source: { filters: Filter[] } }>(
    MANGA_SOURCE_FILTERS_QUERY,
    { id: sourceId },
    { signal },
  );
  return data.source.filters;
}

export interface BrowseParams {
  kind: MediaKind;
  sourceId: string;
  type: BrowseType;
  page: number;
  /** For `SEARCH`. */
  query?: string;
  filters?: FilterChange[];
}

type CardFields = Pick<Anime & Manga, "id" | "title" | "thumbnailUrl" | "inLibrary">;

/**
 * One page of a source's popular, latest or search results. The server stores the entries (stable ids), so this
 * is a mutation; useSourceBrowse wraps it in an infinite query.
 */
export async function browseSource(p: BrowseParams, signal?: AbortSignal): Promise<SourcePage> {
  const input = {
    source: p.sourceId,
    type: p.type,
    page: p.page,
    query: p.type === "SEARCH" ? (p.query ?? "") : null,
    filters: p.filters?.length ? p.filters : null,
  };
  const toPage = (items: CardFields[], hasNextPage: boolean): SourcePage => ({
    items: items.map((e) => ({ kind: p.kind, ...e })),
    hasNextPage,
  });
  if (p.kind === "anime") {
    const data = await api.gql<{
      fetchSourceAnime: { animes: CardFields[]; hasNextPage: boolean };
    }>(BROWSE_ANIME_MUTATION, { input }, { signal });
    return toPage(data.fetchSourceAnime.animes, data.fetchSourceAnime.hasNextPage);
  }
  const data = await api.gql<{
    fetchSourceManga: { mangas: CardFields[]; hasNextPage: boolean };
  }>(BROWSE_MANGA_MUTATION, { input }, { signal });
  return toPage(data.fetchSourceManga.mangas, data.fetchSourceManga.hasNextPage);
}

export function notFound(sourceId: string) {
  // The server's NOT_FOUND, for an id that isn't listed (animeSources filters, it doesn't fail).
  return new ApiError({
    kind: "graphql",
    code: "NOT_FOUND",
    message: `No anime source with id ${sourceId}`,
    details: { sourceId },
  });
}
