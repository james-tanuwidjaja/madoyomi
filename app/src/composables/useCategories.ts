import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/vue-query";
import {
  createCategory,
  deleteCategory,
  fetchCategories,
  moveCategory,
  setCategoryMeta,
  setEntriesCategories,
  setManyInLibrary,
  updateCategory,
} from "../api/operations";
import type {
  CategoryPatch,
  LibraryCategory,
  LibraryEntry,
  MediaKind,
  SourcePage,
} from "../api/types";
import { useBackendStore } from "../stores/backend";
import type { LibrarySort } from "../stores/librarySort";
import { applyAssignment, CATEGORY_SORT_KEY, formatSort, withSort } from "../utils/categories";
import { queryKeys, type BrowseKey } from "./queryKeys";

/*
 * The library's categories (Phase 6 Stage 1): the list per kind, and the changes, each written into the cache so the
 * Library and the dialogs follow at once. Changes that the backend may have widened (a deletion, entries added to the
 * library) refetch.
 */

const STALE = 30_000;

/** A kind's categories, Default first, then by position. */
export function useCategories(kind: MaybeRefOrGetter<MediaKind>) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.categories(toValue(kind))),
    queryFn: ({ signal }) => fetchCategories(toValue(kind), signal),
    enabled: computed(() => backend.ready),
    staleTime: STALE,
  });
}

export interface CreateCategoryRequest {
  kind: MediaKind;
  name: string;
  isDefault?: boolean;
}

export function useCreateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: CreateCategoryRequest) => createCategory(r.kind, r.name.trim(), r.isDefault),
    onSuccess: (category, r) => {
      queryClient.setQueryData<LibraryCategory[]>(queryKeys.categories(r.kind), (list) =>
        list ? [...list, category] : list,
      );
      void queryClient.invalidateQueries({ queryKey: queryKeys.categories(r.kind) });
    },
  });
}

export interface UpdateCategoryRequest {
  kind: MediaKind;
  id: number;
  patch: CategoryPatch;
}

export function useUpdateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: UpdateCategoryRequest) =>
      updateCategory(
        r.kind,
        r.id,
        r.patch.name ? { ...r.patch, name: r.patch.name.trim() } : r.patch,
      ),
    onSuccess: (category, r) => {
      queryClient.setQueryData<LibraryCategory[]>(queryKeys.categories(r.kind), (list) =>
        list?.map((c) => (c.id === category.id ? category : c)),
      );
    },
  });
}

export interface MoveCategoryRequest {
  kind: MediaKind;
  id: number;
  /** 1-based, among the categories after Default. */
  position: number;
}

/** Moves a category, shown at once; the list is read again afterwards either way. */
export function useMoveCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: MoveCategoryRequest) => moveCategory(r.kind, r.id, r.position),
    onMutate: (r) => {
      queryClient.setQueryData<LibraryCategory[]>(queryKeys.categories(r.kind), (list) => {
        if (!list) return list;
        const defaults = list.filter((c) => c.isDefaultCategory);
        const others = list.filter((c) => !c.isDefaultCategory);
        const from = others.findIndex((c) => c.id === r.id);
        if (from < 0) return list;
        const [moved] = others.splice(from, 1);
        others.splice(Math.min(r.position - 1, others.length), 0, moved!);
        return [...defaults, ...others.map((c, i) => ({ ...c, order: i + 1 }))];
      });
    },
    onSettled: (_d, _e, r) =>
      queryClient.invalidateQueries({ queryKey: queryKeys.categories(r.kind) }),
  });
}

export interface DeleteCategoryRequest {
  kind: MediaKind;
  id: number;
}

/** Deletes a category; its entries go back to Default unless they are in another (the library is read again). */
export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: DeleteCategoryRequest) => deleteCategory(r.kind, r.id),
    onSuccess: (_d, r) => {
      queryClient.setQueryData<LibraryCategory[]>(queryKeys.categories(r.kind), (list) =>
        list?.filter((c) => c.id !== r.id),
      );
      queryClient.setQueryData<LibraryEntry[]>(queryKeys.library(r.kind), (entries) =>
        entries?.map((e) =>
          e.categoryIds.includes(r.id)
            ? { ...e, categoryIds: e.categoryIds.filter((c) => c !== r.id) }
            : e,
        ),
      );
      void queryClient.invalidateQueries({ queryKey: queryKeys.categories(r.kind) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.library(r.kind) });
    },
  });
}

export interface SetCategorySortRequest {
  kind: MediaKind;
  categoryId: number;
  sort: LibrarySort;
}

/** Saves a category's own sort in its meta, shown at once and put back on an error. */
export function useSetCategorySort() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: SetCategorySortRequest) =>
      setCategoryMeta(r.kind, r.categoryId, CATEGORY_SORT_KEY, formatSort(r.sort)),
    onMutate: (r) => {
      const key = queryKeys.categories(r.kind);
      const before = queryClient.getQueryData<LibraryCategory[]>(key);
      queryClient.setQueryData<LibraryCategory[]>(key, (list) =>
        list?.map((c) => (c.id === r.categoryId ? withSort(c, r.sort) : c)),
      );
      return { before };
    },
    onError: (_e, r, context) => {
      if (context?.before) queryClient.setQueryData(queryKeys.categories(r.kind), context.before);
    },
  });
}

export interface SetEntriesCategoriesRequest {
  kind: MediaKind;
  ids: number[];
  add: number[];
  remove: number[];
}

/** Changes entries' categories (one or many), shown at once in the library; the library is read again afterwards. */
export function useSetEntriesCategories() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: SetEntriesCategoriesRequest) =>
      setEntriesCategories(r.kind, r.ids, {
        addToCategories: r.add,
        removeFromCategories: r.remove,
      }),
    onMutate: (r) => {
      const key = queryKeys.library(r.kind);
      const before = queryClient.getQueryData<LibraryEntry[]>(key);
      queryClient.setQueryData<LibraryEntry[]>(
        key,
        (entries) => entries && applyAssignment(entries, r.ids, r),
      );
      return { before };
    },
    onError: (_e, r, context) => {
      if (context?.before) queryClient.setQueryData(queryKeys.library(r.kind), context.before);
    },
    onSettled: (_d, _e, r) =>
      queryClient.invalidateQueries({ queryKey: queryKeys.library(r.kind) }),
  });
}

export interface RemoveFromLibraryRequest {
  kind: MediaKind;
  ids: number[];
}

/**
 * Takes several entries out of the library (the Library's selection). They keep their categories, as upstream's
 * manga do. The cached entries and browse cards lose their mark, as `useSetInLibrary` does for one.
 */
export function useRemoveFromLibrary() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: RemoveFromLibraryRequest) => setManyInLibrary(r.kind, r.ids, false),
    onSuccess: (_d, r) => {
      const removed = new Set(r.ids);
      queryClient.setQueryData<LibraryEntry[]>(queryKeys.library(r.kind), (entries) =>
        entries?.filter((e) => !removed.has(e.id)),
      );
      queryClient.setQueriesData<InfiniteData<SourcePage>>(
        {
          queryKey: ["api", "browse"],
          predicate: (q) => (q.queryKey[2] as BrowseKey | null)?.kind === r.kind,
        },
        (data) =>
          data && {
            ...data,
            pages: data.pages.map((page) => ({
              ...page,
              items: page.items.map((e) => (removed.has(e.id) ? { ...e, inLibrary: false } : e)),
            })),
          },
      );
      for (const id of r.ids) {
        void queryClient.invalidateQueries({
          queryKey: r.kind === "anime" ? queryKeys.anime(id) : queryKeys.manga(id),
          exact: true,
        });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.library(r.kind) });
    },
  });
}
