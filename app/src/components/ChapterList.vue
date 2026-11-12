<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import type { Chapter } from "../api/types";
import { useUpdateChapters } from "../composables/useEntries";
import { fromChapter, type ListItem } from "../utils/entryItems";
import EntryItemList from "./EntryItemList.vue";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * A manga's chapters, sorted and filtered, virtualized (EntryItemList). Phase 5 Stage 2: a click on a row, or its
 * Read / Continue button, opens the reader (`/manga/:id/read/:chapterId`), which resumes where reading stopped; a
 * row read partway shows "Page 13 of 92". Rows can be selected for "Mark read", "Mark unread" (which also sends the
 * chapter back to its first page, as Mihon does) and, with one row, "Mark previous as read" (every earlier chapter in
 * the source's order), through `updateChapters`.
 */
defineOptions({ inheritAttrs: false });

const props = defineProps<{ mangaId: number; chapters: readonly Chapter[] }>();

const router = useRouter();
const items = computed(() => props.chapters.map(fromChapter));

function open(chapterId: number) {
  void router.push({ name: "reader", params: { id: props.mangaId, chapterId } });
}

function actionLabel(item: ListItem) {
  return !item.done && (item.pages?.lastPageRead ?? 0) > 0 ? "Continue" : "Read";
}

const selected = ref<number[]>([]);
const update = useUpdateChapters(() => props.mangaId);

const selectedOne = computed(() =>
  selected.value.length === 1 ? props.chapters.find((c) => c.id === selected.value[0]) : undefined,
);
/** The chapters before the selected one in the source's order that aren't read yet. */
const previousIds = computed(() => {
  const one = selectedOne.value;
  if (!one) return [];
  return props.chapters
    .filter((c) => c.sourceOrder < one.sourceOrder && !c.isRead)
    .map((c) => c.id);
});

async function mark(ids: number[], isRead: boolean) {
  if (ids.length === 0) return;
  try {
    await update.mutateAsync({ ids, patch: isRead ? { isRead } : { isRead, lastPageRead: 0 } });
    selected.value = [];
  } catch {
    // shown above the rows, from update.error
  }
}
</script>

<template>
  <EntryItemList
    v-bind="$attrs"
    v-model:selected="selected"
    kind="manga"
    :entry-id="mangaId"
    :items="items"
    :noun="{ one: 'chapter', many: 'chapters' }"
    :done-labels="{ done: 'Read', notDone: 'Unread' }"
    note="Open a chapter to read it; it resumes where you stopped."
    selectable
    openable
    @open="open"
  >
    <template #notice>
      <ErrorNotice
        v-if="update.error.value"
        title="Couldn't change the chapters"
        :error="update.error.value"
        data-testid="mark-error"
      />
    </template>
    <template #bulk-actions="{ ids }">
      <button
        type="button"
        class="btn"
        data-action="mark-read"
        :disabled="update.isPending.value"
        @click="mark(ids, true)"
      >
        Mark read
      </button>
      <button
        type="button"
        class="btn"
        data-action="mark-unread"
        :disabled="update.isPending.value"
        @click="mark(ids, false)"
      >
        Mark unread
      </button>
      <button
        v-if="selectedOne"
        type="button"
        class="btn"
        data-action="mark-previous"
        :disabled="update.isPending.value || previousIds.length === 0"
        :title="previousIds.length === 0 ? 'Every earlier chapter is read' : undefined"
        @click="mark(previousIds, true)"
      >
        Mark previous as read
      </button>
    </template>
    <template #actions="{ item }">
      <button
        type="button"
        class="btn"
        data-action="read"
        :aria-label="`${actionLabel(item)} ${item.name}`"
        @click="open(item.id)"
      >
        {{ actionLabel(item) }}
      </button>
    </template>
  </EntryItemList>
</template>
