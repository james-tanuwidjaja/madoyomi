import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { makeCategory, makeDefaultCategory, makeLibraryEntry } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchLibrary: vi.fn(),
  fetchCategories: vi.fn(),
  createCategory: vi.fn(),
  setEntriesCategories: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import EntryCategoriesButton from "../EntryCategoriesButton.vue";

/* A details page's Categories button (Phase 6 Stage 1): one entry's categories through the picker. */
let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;

const q = <T extends Element = HTMLElement>(selector: string) =>
  document.body.querySelector<T>(selector);

beforeEach(async () => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  ops.fetchLibrary.mockResolvedValue([
    makeLibraryEntry({ kind: "manga", id: 34, title: "Kingdom", categoryIds: [2] }),
  ]);
  const categories = [
    makeDefaultCategory(),
    makeCategory({ id: 1, order: 1, name: "Reading" }),
    makeCategory({ id: 2, order: 2, name: "Later" }),
  ];
  ops.fetchCategories.mockImplementation(async () => [...categories]);
  ops.createCategory.mockImplementation(async (_kind: string, name: string) => {
    const created = makeCategory({ id: 3, order: 3, name });
    categories.push(created);
    return created;
  });
  ops.setEntriesCategories.mockResolvedValue(undefined);
  wrapper = mountView(EntryCategoriesButton, { pinia, queryClient }, { kind: "manga", id: 34 });
  await flushPromises();
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
});

describe("EntryCategoriesButton", () => {
  it("names the entry's categories and changes them through the picker", async () => {
    const button = wrapper.find('[data-action="categories"]');
    expect(button.text()).toBe("Categories: Later");
    expect(ops.fetchLibrary).toHaveBeenCalledWith("manga", expect.anything());

    await button.trigger("click");
    await flushPromises();
    expect(q('[data-testid="category-picker"]')!.textContent).toContain("“Kingdom”");
    const boxes = () => [
      ...document.body.querySelectorAll<HTMLInputElement>('[data-testid="picker-choice"] input'),
    ];
    expect(boxes().map((b) => b.dataset.state)).toEqual(["none", "all"]);
    boxes()[0]!.click();

    // a new category is made and checked at once
    const field = q<HTMLInputElement>('[data-testid="picker-new-name"]')!;
    field.value = "Finished";
    field.dispatchEvent(new Event("input"));
    await flushPromises();
    q<HTMLButtonElement>('[data-action="picker-add"]')!.click();
    await flushPromises();
    expect(ops.createCategory).toHaveBeenCalledWith("manga", "Finished", undefined);
    expect(boxes().map((b) => b.dataset.state)).toEqual(["all", "all", "all"]);

    q<HTMLButtonElement>('[data-action="picker-save"]')!.click();
    await flushPromises();
    expect(ops.setEntriesCategories).toHaveBeenCalledWith("manga", [34], {
      addToCategories: [1, 3],
      removeFromCategories: [],
    });
    expect(q('[data-testid="category-picker"]')).toBeNull();
  });

  it("closes without a call when nothing changed", async () => {
    await wrapper.find('[data-action="categories"]').trigger("click");
    await flushPromises();
    q<HTMLButtonElement>('[data-action="picker-save"]')!.click();
    await flushPromises();

    expect(ops.setEntriesCategories).not.toHaveBeenCalled();
    expect(q('[data-testid="category-picker"]')).toBeNull();
  });
});
