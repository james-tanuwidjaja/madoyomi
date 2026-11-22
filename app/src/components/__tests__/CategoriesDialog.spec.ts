import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import type { CategoryPatch, LibraryCategory } from "../../api/types";
import { makeCategory, makeDefaultCategory, makeLibraryEntry } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchCategories: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  moveCategory: vi.fn(),
  deleteCategory: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import CategoriesDialog from "../CategoriesDialog.vue";

/* The Library's categories dialog against mocked category calls that keep a list. */
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper | null = null;
let list: LibraryCategory[];

const q = <T extends Element = HTMLElement>(selector: string) =>
  document.body.querySelector<T>(selector);
const qa = <T extends Element = HTMLElement>(selector: string) => [
  ...document.body.querySelectorAll<T>(selector),
];
const names = () => qa('[data-testid="category-name"]').map((n) => n.textContent);
const rowButton = (row: number, action: string) =>
  qa('[data-testid="category-row"]')[row]!.querySelector<HTMLButtonElement>(
    `[data-action="${action}"]`,
  )!;

async function type(selector: string, value: string) {
  const input = q<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event("input"));
  await flushPromises();
}

async function open() {
  wrapper = mountView(
    CategoriesDialog,
    { pinia, queryClient },
    {
      open: true,
      kind: "anime",
      entries: [
        makeLibraryEntry({ id: 10, categoryIds: [1] }),
        makeLibraryEntry({ id: 11, categoryIds: [1, 2] }),
      ],
    },
  );
  await flushPromises();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  list = [
    makeDefaultCategory(),
    makeCategory({ id: 1, order: 1, name: "Watching" }),
    makeCategory({ id: 2, order: 2, name: "Later", default: true }),
  ];
  ops.fetchCategories.mockImplementation(async () => list.map((c) => ({ ...c })));
  ops.createCategory.mockImplementation(async (_k: string, name: string) => {
    const c = makeCategory({ id: 9, order: list.length, name });
    list.push(c);
    return c;
  });
  ops.updateCategory.mockImplementation(async (_k: string, id: number, patch: CategoryPatch) => {
    const i = list.findIndex((c) => c.id === id);
    list[i] = { ...list[i]!, ...patch };
    return list[i];
  });
  ops.moveCategory.mockImplementation(async (_k: string, id: number, position: number) => {
    const others = list.filter((c) => !c.isDefaultCategory && c.id !== id);
    others.splice(
      position - 1,
      0,
      list.find((c) => c.id === id)!,
    );
    list = [list[0]!, ...others.map((c, i) => ({ ...c, order: i + 1 }))];
  });
  ops.deleteCategory.mockImplementation(async (_k: string, id: number) => {
    list = list.filter((c) => c.id !== id);
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  queryClient.clear();
  vi.clearAllMocks();
});

describe("CategoriesDialog", () => {
  it("lists the categories without Default, with their counts and the new-entries flag", async () => {
    await open();

    expect(names()).toEqual(["Watching", "Later"]);
    expect(qa('[data-testid="category-row"] .count').map((c) => c.textContent)).toEqual(["2", "1"]);
    expect(qa<HTMLInputElement>('[data-testid="category-default"]').map((c) => c.checked)).toEqual([
      false,
      true,
    ]);
  });

  it("adds a category, refusing a name that is taken or Default", async () => {
    await open();

    await type('[data-testid="new-category-name"]', " later ");
    q<HTMLFormElement>(".new")!.dispatchEvent(new Event("submit"));
    await flushPromises();
    expect(q('[data-testid="new-category-error"]')!.textContent).toContain("already");
    await type('[data-testid="new-category-name"]', "default");
    expect(q('[data-testid="new-category-error"]')!.textContent).toContain("built-in");
    expect(ops.createCategory).not.toHaveBeenCalled();

    await type('[data-testid="new-category-name"]', "  Plan to watch ");
    q<HTMLFormElement>(".new")!.dispatchEvent(new Event("submit"));
    await flushPromises();
    expect(ops.createCategory).toHaveBeenCalledWith("anime", "Plan to watch", undefined);
    expect(names()).toEqual(["Watching", "Later", "Plan to watch"]);
    expect(q<HTMLInputElement>('[data-testid="new-category-name"]')!.value).toBe("");
  });

  it("renames inline, and keeps the field open on a taken name", async () => {
    await open();

    rowButton(0, "rename").click();
    await flushPromises();
    await type('[data-testid="rename-field"]', "Later");
    expect(q('[data-testid="rename-error"]')!.textContent).toContain("already");
    expect(rowButton(0, "save-rename").disabled).toBe(true);

    await type('[data-testid="rename-field"]', "Now watching");
    q<HTMLFormElement>(".rename")!.dispatchEvent(new Event("submit"));
    await flushPromises();
    expect(ops.updateCategory).toHaveBeenCalledWith("anime", 1, { name: "Now watching" });
    expect(names()).toEqual(["Now watching", "Later"]);
  });

  it("moves categories up and down, and flags one for new entries", async () => {
    await open();

    expect(rowButton(0, "move-up").disabled).toBe(true);
    expect(rowButton(1, "move-down").disabled).toBe(true);
    rowButton(1, "move-up").click();
    await flushPromises();
    expect(ops.moveCategory).toHaveBeenCalledWith("anime", 2, 1);
    expect(names()).toEqual(["Later", "Watching"]);

    qa<HTMLInputElement>('[data-testid="category-default"]')[1]!.click();
    await flushPromises();
    expect(ops.updateCategory).toHaveBeenCalledWith("anime", 1, { default: true });
  });

  it("deletes a category after a confirm that says where its entries go", async () => {
    await open();

    rowButton(0, "delete").click();
    await flushPromises();
    expect(q('[data-testid="delete-question"]')!.textContent).toContain(
      "Its 2 entries go to Default unless in another category.",
    );
    rowButton(0, "keep").click();
    await flushPromises();
    expect(names()).toEqual(["Watching", "Later"]);

    rowButton(1, "delete").click();
    await flushPromises();
    rowButton(1, "confirm-delete").click();
    await flushPromises();
    expect(ops.deleteCategory).toHaveBeenCalledWith("anime", 2);
    expect(names()).toEqual(["Watching"]);
  });

  it("has the Updates and Downloads flags, Default's too, off as EXCLUDE and on as UNSET", async () => {
    list[2] = { ...list[2]!, includeInUpdate: "EXCLUDE", includeInDownload: "INCLUDE" };
    await open();

    const checked = (id: string) =>
      qa<HTMLInputElement>(`[data-testid="${id}"]`).map((c) => c.checked);
    expect(checked("category-updates")).toEqual([true, false]);
    expect(checked("category-downloads")).toEqual([true, true]);
    expect(q('[data-testid="default-category-row"]')!.textContent).toContain(
      "entries in no category",
    );
    expect(checked("default-updates")).toEqual([true]);

    qa<HTMLInputElement>('[data-testid="category-updates"]')[0]!.click();
    await flushPromises();
    expect(ops.updateCategory).toHaveBeenLastCalledWith("anime", 1, { includeInUpdate: "EXCLUDE" });
    qa<HTMLInputElement>('[data-testid="category-updates"]')[1]!.click();
    await flushPromises();
    expect(ops.updateCategory).toHaveBeenLastCalledWith("anime", 2, { includeInUpdate: "UNSET" });
    q<HTMLInputElement>('[data-testid="default-downloads"]')!.click();
    await flushPromises();
    expect(ops.updateCategory).toHaveBeenLastCalledWith("anime", 0, {
      includeInDownload: "EXCLUDE",
    });
    expect(checked("category-updates")).toEqual([false, true]);
    expect(checked("default-downloads")).toEqual([false]);
  });
});
