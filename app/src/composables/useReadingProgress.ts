import { onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter } from "vue";
import { useQueryClient } from "@tanstack/vue-query";
import { updateChapter } from "../api/operations";
import type { Chapter, ChapterPatch } from "../api/types";
import { writeChapters } from "./useEntries";

/*
 * Reading progress (Phase 5 Stage 2, steps 12–13): the page shown is saved into upstream's `CHAPTER_USER` through
 * `updateChapter` (`lastPageRead`, which also stamps `lastReadAt`), debounced, so paging quickly through a chapter
 * writes once. Showing the last page marks the chapter read at once (Mihon's rule). A pending save is written when
 * the reader moves to another chapter, when it closes, and when the window goes away. Each saved chapter replaces
 * its row in the manga's cached chapter list, so the details page shows it without a refetch. A save that fails (the
 * backend restarting) is tried again after `RETRY_MS`, a read mark included, until one works.
 *
 * Stage 3: reaching the end panel marks the chapter read too, with its last page, if showing the last page didn't
 * (a fling down the webtoon strip, or End, can pass the last page between two scroll events).
 */

export const PROGRESS_DEBOUNCE_MS = 1000;
export const PROGRESS_RETRY_MS = 5000;

export interface ReadingProgressOptions {
  /** The chapter shown (as `fetchChapterPages` returned it), null while it loads. */
  chapter: MaybeRefOrGetter<Chapter | null>;
  pageCount: MaybeRefOrGetter<number>;
  /** The reader's position: a page index, a transition panel (not saved), or null before the chapter opens. */
  position: MaybeRefOrGetter<number | null>;
  save?: (id: number, patch: ChapterPatch) => Promise<Chapter>;
  debounceMs?: number;
  retryMs?: number;
}

export function useReadingProgress(opts: ReadingProgressOptions) {
  const queryClient = useQueryClient();
  const save = opts.save ?? updateChapter;
  const delay = opts.debounceMs ?? PROGRESS_DEBOUNCE_MS;
  const retryDelay = opts.retryMs ?? PROGRESS_RETRY_MS;

  /** The last save that failed (shown by the reader); cleared by the next one that works. */
  const error = ref<unknown>(null);
  /** The save to write next; `isRead` when it also marks the chapter read (a read mark that failed). */
  let pending: { id: number; page: number; isRead?: boolean } | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  /** Chapters this reader marked read (so the last page doesn't mark them twice). */
  const markedRead = new Set<number>();

  function schedule(ms: number) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(flush, ms);
  }

  function send(id: number, patch: ChapterPatch) {
    save(id, patch).then(
      (chapter) => {
        error.value = null;
        writeChapters(queryClient, chapter.mangaId, [chapter]);
      },
      (e: unknown) => {
        error.value = e;
        if (patch.isRead) markedRead.delete(id);
        // Try again later, unless the reader has moved on: a newer page of this chapter keeps the read mark.
        if (!pending) {
          pending = {
            id,
            page: patch.lastPageRead ?? 0,
            ...(patch.isRead ? { isRead: true } : {}),
          };
          schedule(retryDelay);
        } else if (pending.id === id && patch.isRead) {
          pending.isRead = true;
        }
      },
    );
  }

  function flush() {
    if (timer) clearTimeout(timer);
    timer = null;
    if (!pending) return;
    const { id, page, isRead } = pending;
    pending = null;
    send(id, isRead ? { lastPageRead: page, isRead: true } : { lastPageRead: page });
  }

  watch(
    () => toValue(opts.position),
    (position) => {
      const chapter = toValue(opts.chapter);
      const count = toValue(opts.pageCount);
      if (!chapter || position === null || position < 0 || count <= 0 || position > count) return;
      if (pending && pending.id !== chapter.id) flush();
      if (position >= count - 1 && !chapter.isRead && !markedRead.has(chapter.id)) {
        markedRead.add(chapter.id);
        if (timer) clearTimeout(timer);
        timer = null;
        pending = null;
        send(chapter.id, { lastPageRead: count - 1, isRead: true });
        return;
      }
      // The end panel itself isn't a page to save.
      if (position === count) return;
      const keepRead = pending?.id === chapter.id && pending.isRead;
      pending = { id: chapter.id, page: position, ...(keepRead ? { isRead: true } : {}) };
      schedule(delay);
    },
    // A chapter whose pages were in already opens with its position set: that counts too (its last page marks it).
    { immediate: true },
  );

  // Another chapter: what was pending for the last one goes now.
  watch(
    () => toValue(opts.chapter)?.id,
    (id) => {
      if (pending && pending.id !== id) flush();
    },
  );

  const onHide = () => flush();
  onMounted(() => {
    window.addEventListener("pagehide", onHide);
    window.addEventListener("beforeunload", onHide);
  });
  onBeforeUnmount(() => {
    window.removeEventListener("pagehide", onHide);
    window.removeEventListener("beforeunload", onHide);
    flush();
  });

  return { error, flush };
}
