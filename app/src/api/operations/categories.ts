import { api } from "../client";
import type { CategoryPatch, EntryCategoriesPatch, LibraryCategory, MediaKind } from "../types";

/*
 * The library's categories (Phase 6 Stage 1), one list per kind: upstream's `categories` and category mutations for
 * manga, Madoyomi's `animeCategories` and `…AnimeCategory…` mutations for anime (the same shapes). Each call takes the
 * kind and picks the document.
 */

const CATEGORY_FIELDS = `
  id
  order
  name
  default
  isDefaultCategory
  includeInUpdate
  includeInDownload
  meta {
    key
    value
  }
`;

export const MANGA_CATEGORIES_QUERY = /* GraphQL */ `
  query MangaCategories {
    categories(order: [{ by: ORDER }]) {
      nodes {
        ${CATEGORY_FIELDS}
      }
    }
  }
`;

export const ANIME_CATEGORIES_QUERY = /* GraphQL */ `
  query AnimeCategories {
    animeCategories {
      ${CATEGORY_FIELDS}
    }
  }
`;

export const CREATE_MANGA_CATEGORY_MUTATION = /* GraphQL */ `
  mutation CreateMangaCategory($name: String!, $default: Boolean) {
    createCategory(input: { name: $name, default: $default }) {
      category {
        ${CATEGORY_FIELDS}
      }
    }
  }
`;

export const CREATE_ANIME_CATEGORY_MUTATION = /* GraphQL */ `
  mutation CreateAnimeCategory($name: String!, $default: Boolean) {
    createAnimeCategory(input: { name: $name, default: $default }) {
      category {
        ${CATEGORY_FIELDS}
      }
    }
  }
`;

export const UPDATE_MANGA_CATEGORY_MUTATION = /* GraphQL */ `
  mutation UpdateMangaCategory($id: Int!, $patch: UpdateCategoryPatchInput!) {
    updateCategory(input: { id: $id, patch: $patch }) {
      category {
        ${CATEGORY_FIELDS}
      }
    }
  }
`;

export const UPDATE_ANIME_CATEGORY_MUTATION = /* GraphQL */ `
  mutation UpdateAnimeCategory($id: Int!, $patch: UpdateAnimeCategoryPatchInput!) {
    updateAnimeCategory(input: { id: $id, patch: $patch }) {
      category {
        ${CATEGORY_FIELDS}
      }
    }
  }
`;

export const UPDATE_MANGA_CATEGORY_ORDER_MUTATION = /* GraphQL */ `
  mutation UpdateMangaCategoryOrder($id: Int!, $position: Int!) {
    updateCategoryOrder(input: { id: $id, position: $position }) {
      categories {
        id
      }
    }
  }
`;

export const UPDATE_ANIME_CATEGORY_ORDER_MUTATION = /* GraphQL */ `
  mutation UpdateAnimeCategoryOrder($id: Int!, $position: Int!) {
    updateAnimeCategoryOrder(input: { id: $id, position: $position }) {
      categories {
        id
      }
    }
  }
`;

export const DELETE_MANGA_CATEGORY_MUTATION = /* GraphQL */ `
  mutation DeleteMangaCategory($id: Int!) {
    deleteCategory(input: { categoryId: $id }) {
      category {
        id
      }
    }
  }
`;

export const DELETE_ANIME_CATEGORY_MUTATION = /* GraphQL */ `
  mutation DeleteAnimeCategory($id: Int!) {
    deleteAnimeCategory(input: { categoryId: $id }) {
      category {
        id
      }
    }
  }
`;

export const UPDATE_MANGAS_CATEGORIES_MUTATION = /* GraphQL */ `
  mutation UpdateMangasCategories($ids: [Int!]!, $patch: UpdateMangaCategoriesPatchInput!) {
    updateMangasCategories(input: { ids: $ids, patch: $patch }) {
      mangas {
        id
      }
    }
  }
`;

export const UPDATE_ANIMES_CATEGORIES_MUTATION = /* GraphQL */ `
  mutation UpdateAnimesCategories($ids: [Int!]!, $patch: UpdateAnimeCategoriesPatchInput!) {
    updateAnimesCategories(input: { ids: $ids, patch: $patch }) {
      animes {
        id
      }
    }
  }
`;

export const SET_MANGA_CATEGORY_META_MUTATION = /* GraphQL */ `
  mutation SetMangaCategoryMeta($categoryId: Int!, $key: String!, $value: String!) {
    setCategoryMeta(input: { meta: { categoryId: $categoryId, key: $key, value: $value } }) {
      meta {
        key
        value
      }
    }
  }
`;

export const SET_ANIME_CATEGORY_META_MUTATION = /* GraphQL */ `
  mutation SetAnimeCategoryMeta($categoryId: Int!, $key: String!, $value: String!) {
    setAnimeCategoryMeta(input: { meta: { categoryId: $categoryId, key: $key, value: $value } }) {
      meta {
        key
        value
      }
    }
  }
`;

/** A kind's categories, Default first, then by position. */
export async function fetchCategories(
  kind: MediaKind,
  signal?: AbortSignal,
): Promise<LibraryCategory[]> {
  const list =
    kind === "anime"
      ? (
          await api.gql<{ animeCategories: LibraryCategory[] }>(
            ANIME_CATEGORIES_QUERY,
            {},
            { signal },
          )
        ).animeCategories
      : (
          await api.gql<{ categories: { nodes: LibraryCategory[] } }>(
            MANGA_CATEGORIES_QUERY,
            {},
            { signal },
          )
        ).categories.nodes;
  // Default first even if a stored order says otherwise (upstream's rows can collide), then by position and id
  return [...list].sort(
    (a, b) =>
      Number(b.isDefaultCategory) - Number(a.isDefaultCategory) || a.order - b.order || a.id - b.id,
  );
}

/** Creates a category at the end; the category as stored. */
export async function createCategory(
  kind: MediaKind,
  name: string,
  isDefault?: boolean,
): Promise<LibraryCategory> {
  const variables = { name, default: isDefault ?? null };
  return kind === "anime"
    ? (
        await api.gql<{ createAnimeCategory: { category: LibraryCategory } }>(
          CREATE_ANIME_CATEGORY_MUTATION,
          variables,
        )
      ).createAnimeCategory.category
    : (
        await api.gql<{ createCategory: { category: LibraryCategory } }>(
          CREATE_MANGA_CATEGORY_MUTATION,
          variables,
        )
      ).createCategory.category;
}

/** Renames a category or flags it for new entries; the category as stored. */
export async function updateCategory(
  kind: MediaKind,
  id: number,
  patch: CategoryPatch,
): Promise<LibraryCategory> {
  return kind === "anime"
    ? (
        await api.gql<{ updateAnimeCategory: { category: LibraryCategory } }>(
          UPDATE_ANIME_CATEGORY_MUTATION,
          { id, patch },
        )
      ).updateAnimeCategory.category
    : (
        await api.gql<{ updateCategory: { category: LibraryCategory } }>(
          UPDATE_MANGA_CATEGORY_MUTATION,
          { id, patch },
        )
      ).updateCategory.category;
}

/** Moves a category to a 1-based position among the categories after Default. */
export async function moveCategory(kind: MediaKind, id: number, position: number): Promise<void> {
  await api.gql(
    kind === "anime" ? UPDATE_ANIME_CATEGORY_ORDER_MUTATION : UPDATE_MANGA_CATEGORY_ORDER_MUTATION,
    { id, position },
  );
}

/** Deletes a category; its entries are in Default again unless they are in another. */
export async function deleteCategory(kind: MediaKind, id: number): Promise<void> {
  await api.gql(
    kind === "anime" ? DELETE_ANIME_CATEGORY_MUTATION : DELETE_MANGA_CATEGORY_MUTATION,
    { id },
  );
}

/** Changes the categories of entries of one kind (one or many at once). */
export async function setEntriesCategories(
  kind: MediaKind,
  ids: number[],
  patch: EntryCategoriesPatch,
): Promise<void> {
  await api.gql(
    kind === "anime" ? UPDATE_ANIMES_CATEGORIES_MUTATION : UPDATE_MANGAS_CATEGORIES_MUTATION,
    { ids, patch },
  );
}

/** Sets one meta value of a category (the Library keeps each category's sort there). */
export async function setCategoryMeta(
  kind: MediaKind,
  categoryId: number,
  key: string,
  value: string,
): Promise<void> {
  await api.gql(
    kind === "anime" ? SET_ANIME_CATEGORY_META_MUTATION : SET_MANGA_CATEGORY_META_MUTATION,
    { categoryId, key, value },
  );
}
