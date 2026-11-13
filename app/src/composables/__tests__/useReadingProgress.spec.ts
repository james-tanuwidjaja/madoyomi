import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick, ref } from "vue";
import { createPinia } from "pinia";
import { QueryClient } from "@tanstack/vue-query";
import { flushPromises } from "@vue/test-utils";

import type { Chapter, ChapterPatch } from "../../api/types";
import { queryKeys } from "../queryKeys";
import { PROGRESS_DEBOUNCE_MS, PROGRESS_RETRY_MS, useReadingProgress } from "../useReadingProgress";
import { makeChapters } from "../../test/fixtures";
import { withSetup } from "../../test/withSetup";

/* Reading progress (Phase 5 Stage 2): debounced saves, the read mark on the last page, flushes, the cached list. */
let queryClient: QueryClient;
let save: ReturnType<typeof vi.fn<(id: number, patch: ChapterPatch) => Promise<Chapter>>>;
let unmount: () => void;
let list: Chapter[];

function setup(chapter: Chapter, pageCount = 10, start: number | null = null) {
  const current = ref<Chapter | null>(chapter);
  const position = ref<number | null>(start);
  const s = withSetup(
    () => useReadingProgress({ chapter: current, pageCount: () => pageCount, position, save }),
    { pinia: createPinia(), queryClient },
  );
  unmount = s.unmount;
  return { progress: s.result, current, position };
}

async function go(position: { value: number | null }, p: number) {
  position.value = p;
  await nextTick();
}

beforeEach(() => {
  vi.useFakeTimers();
  queryClient = new QueryClient();
  list = makeChapters(3).map((c) => ({ ...c, pageCount: 10 }));
  queryClient.setQueryData(queryKeys.chapters(34), list);
  save = vi.fn(async (id: number, patch: ChapterPatch) => ({
    ...list.find((c) => c.id === id)!,
    ...(patch.lastPageRead !== undefined ? { lastPageRead: patch.lastPageRead } : {}),
    ...(patch.isRead !== undefined ? { isRead: patch.isRead } : {}),
  }));
});

afterEach(() => {
  unmount();
  vi.useRealTimers();
});

describe("useReadingProgress", () => {
  it("saves the page once paging stops, not on every page", async () => {
    const { position } = setup(list[0]!);
    for (const p of [0, 1, 2, 3]) {
      await go(position, p);
      vi.advanceTimersByTime(300);
    }
    expect(save).not.toHaveBeenCalled();

    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);
    await flushPromises();

    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 3 });
    // the details page's list has it without a refetch
    expect(queryClient.getQueryData<Chapter[]>(queryKeys.chapters(34))![0]!.lastPageRead).toBe(3);
  });

  it("marks the chapter read on its last page at once, and only once", async () => {
    const { position } = setup(list[0]!);
    await go(position, 9);
    await flushPromises();

    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 9, isRead: true });
    expect(queryClient.getQueryData<Chapter[]>(queryKeys.chapters(34))![0]!.isRead).toBe(true);

    await go(position, 8);
    await go(position, 9);
    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);
    expect(save).toHaveBeenCalledTimes(2);
    expect(save).toHaveBeenLastCalledWith(5001, { lastPageRead: 9 });
  });

  it("counts the position a chapter opens at when its pages were in already", async () => {
    setup(list[0]!, 10, 9);
    await flushPromises();
    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 9, isRead: true });
  });

  it("doesn't mark a chapter that is read again, and saves no panel", async () => {
    const { position } = setup({ ...list[0]!, isRead: true });
    await go(position, 9);
    await go(position, 10); // the end panel
    await go(position, -1); // the previous chapter's panel
    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);

    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 9 });
  });

  it("marks the chapter read on reaching the end panel past its last page (a webtoon fling)", async () => {
    const { position } = setup(list[0]!, 10, 6);
    await go(position, 10);
    await flushPromises();

    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 9, isRead: true });
    await go(position, 9);
    await go(position, 10);
    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);
    // the last page again is saved as a page; the mark isn't sent twice
    expect(save).toHaveBeenCalledTimes(2);
    expect(save).toHaveBeenLastCalledWith(5001, { lastPageRead: 9 });
  });

  it("writes what is pending when the chapter changes or the reader closes", async () => {
    const { position, current } = setup(list[0]!);
    await go(position, 4);
    current.value = list[1]!;
    await nextTick();
    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 4 });

    await go(position, 2);
    unmount();
    unmount = () => {};
    expect(save).toHaveBeenLastCalledWith(5002, { lastPageRead: 2 });
  });

  it("writes what is pending when the window goes away", async () => {
    const { position } = setup(list[0]!);
    await go(position, 6);
    window.dispatchEvent(new Event("pagehide"));
    expect(save).toHaveBeenCalledWith(5001, { lastPageRead: 6 });
  });

  it("tries a read mark that failed again, and keeps it through later pages", async () => {
    const { progress, position } = setup(list[0]!);
    save.mockRejectedValueOnce(new Error("backend restarting"));
    await go(position, 9);
    await flushPromises();
    expect(progress.error.value).toBeInstanceOf(Error);

    vi.advanceTimersByTime(PROGRESS_RETRY_MS);
    await flushPromises();
    expect(save).toHaveBeenLastCalledWith(5001, { lastPageRead: 9, isRead: true });
    expect(progress.error.value).toBeNull();
    expect(queryClient.getQueryData<Chapter[]>(queryKeys.chapters(34))![0]!.isRead).toBe(true);

    // failed again, then the reader went back a page before the retry: the page and the mark go together
    save.mockClear();
    unmount();
    const { position: p2 } = setup(list[1]!);
    save.mockRejectedValueOnce(new Error("backend restarting"));
    await go(p2, 9);
    await flushPromises();
    await go(p2, 7);
    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);
    await flushPromises();
    expect(save).toHaveBeenLastCalledWith(5002, { lastPageRead: 7, isRead: true });
  });

  it("reports a save that failed, until one works", async () => {
    const { progress, position } = setup(list[0]!);
    save.mockRejectedValueOnce(new Error("backend down"));
    await go(position, 2);
    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);
    await flushPromises();
    expect(progress.error.value).toBeInstanceOf(Error);

    await go(position, 3);
    vi.advanceTimersByTime(PROGRESS_DEBOUNCE_MS);
    await flushPromises();
    expect(progress.error.value).toBeNull();
  });
});
