import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn(), gqlResult: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql, gqlResult: client.gqlResult } };
});

import {
  ANIME_CATEGORIES_QUERY,
  CREATE_ANIME_CATEGORY_MUTATION,
  CREATE_MANGA_CATEGORY_MUTATION,
  createCategory,
  DELETE_ANIME_CATEGORY_MUTATION,
  DELETE_MANGA_CATEGORY_MUTATION,
  deleteCategory,
  fetchCategories,
  MANGA_CATEGORIES_QUERY,
  moveCategory,
  SET_ANIME_CATEGORY_META_MUTATION,
  SET_MANGA_CATEGORY_META_MUTATION,
  setCategoryMeta,
  setEntriesCategories,
  UPDATE_ANIME_CATEGORY_MUTATION,
  UPDATE_ANIME_CATEGORY_ORDER_MUTATION,
  UPDATE_ANIMES_CATEGORIES_MUTATION,
  UPDATE_MANGA_CATEGORY_MUTATION,
  UPDATE_MANGA_CATEGORY_ORDER_MUTATION,
  UPDATE_MANGAS_CATEGORIES_MUTATION,
  updateCategory,
} from "../operations/categories";
import { makeCategory } from "../../test/fixtures";

/* The category calls over a mocked client: each kind gets its own document. */
afterEach(() => vi.clearAllMocks());

describe("fetchCategories", () => {
  it("reads upstream's node list for manga and the plain list for anime, Default first", async () => {
    const watching = makeCategory({ id: 3, order: 1, name: "Watching" });
    const later = makeCategory({ id: 2, order: 2, name: "Later" });
    // upstream's rows can share an order value with Default
    const def = makeCategory({ id: 0, order: 1, name: "Default", isDefaultCategory: true });
    client.gql.mockImplementation(async (doc: string) =>
      doc === MANGA_CATEGORIES_QUERY
        ? { categories: { nodes: [watching, def, later] } }
        : { animeCategories: [later, def] },
    );

    expect((await fetchCategories("manga")).map((c) => c.id)).toEqual([0, 3, 2]);
    expect((await fetchCategories("anime")).map((c) => c.id)).toEqual([0, 2]);
    expect(client.gql.mock.calls.map((c) => c[0])).toEqual([
      MANGA_CATEGORIES_QUERY,
      ANIME_CATEGORIES_QUERY,
    ]);
  });
});

describe("the category changes", () => {
  it("create, update, move and delete through each kind's document", async () => {
    const created = makeCategory({ id: 7, name: "New" });
    client.gql.mockImplementation(async (doc: string) => {
      if (doc === CREATE_ANIME_CATEGORY_MUTATION)
        return { createAnimeCategory: { category: created } };
      if (doc === CREATE_MANGA_CATEGORY_MUTATION) return { createCategory: { category: created } };
      if (doc === UPDATE_ANIME_CATEGORY_MUTATION)
        return { updateAnimeCategory: { category: created } };
      if (doc === UPDATE_MANGA_CATEGORY_MUTATION) return { updateCategory: { category: created } };
      return {};
    });

    expect(await createCategory("anime", "New", true)).toEqual(created);
    expect(await createCategory("manga", "New")).toEqual(created);
    expect(await updateCategory("anime", 7, { name: "Renamed" })).toEqual(created);
    expect(await updateCategory("manga", 7, { default: true })).toEqual(created);
    await moveCategory("anime", 7, 2);
    await moveCategory("manga", 7, 1);
    await deleteCategory("anime", 7);
    await deleteCategory("manga", 7);

    expect(client.gql.mock.calls).toEqual([
      [CREATE_ANIME_CATEGORY_MUTATION, { name: "New", default: true }],
      [CREATE_MANGA_CATEGORY_MUTATION, { name: "New", default: null }],
      [UPDATE_ANIME_CATEGORY_MUTATION, { id: 7, patch: { name: "Renamed" } }],
      [UPDATE_MANGA_CATEGORY_MUTATION, { id: 7, patch: { default: true } }],
      [UPDATE_ANIME_CATEGORY_ORDER_MUTATION, { id: 7, position: 2 }],
      [UPDATE_MANGA_CATEGORY_ORDER_MUTATION, { id: 7, position: 1 }],
      [DELETE_ANIME_CATEGORY_MUTATION, { id: 7 }],
      [DELETE_MANGA_CATEGORY_MUTATION, { id: 7 }],
    ]);
  });

  it("assigns entries and saves a category's meta", async () => {
    client.gql.mockResolvedValue({});

    await setEntriesCategories("anime", [1, 2], { addToCategories: [3] });
    await setEntriesCategories("manga", [4], { removeFromCategories: [5] });
    await setCategoryMeta("anime", 3, "madoyomi.library.sort", "title:asc");
    await setCategoryMeta("manga", 5, "madoyomi.library.sort", "added:desc");

    expect(client.gql.mock.calls).toEqual([
      [UPDATE_ANIMES_CATEGORIES_MUTATION, { ids: [1, 2], patch: { addToCategories: [3] } }],
      [UPDATE_MANGAS_CATEGORIES_MUTATION, { ids: [4], patch: { removeFromCategories: [5] } }],
      [
        SET_ANIME_CATEGORY_META_MUTATION,
        { categoryId: 3, key: "madoyomi.library.sort", value: "title:asc" },
      ],
      [
        SET_MANGA_CATEGORY_META_MUTATION,
        { categoryId: 5, key: "madoyomi.library.sort", value: "added:desc" },
      ],
    ]);
  });
});
