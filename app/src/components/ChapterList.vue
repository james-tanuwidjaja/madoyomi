<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import type { Chapter, DownloadItem } from "../api/types";
import { useUpdateChapters } from "../composables/useEntries";
import {
  useChangeDownloads,
  useDeleteDownloads,
  useEnqueueDownloads,
  useEntryDownloads,
} from "../composables/useDownloads";
import { NEXT_UNREAD_COUNTS, nextUnread, percentOf, stateText } from "../utils/downloads";
import { fromChapter, type ListItem } from "../utils/entryItems";
import EntryItemList from "./EntryItemList.vue";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * A manga's chapters, sorted and filtered, virtualized (EntryItemList). Phase 5 Stage 2: a click on a row, or its
 * Read / Continue button, opens the reader (`/manga/:id/read/:chapterId`), which resumes where reading stopped; a
 * row read partway shows "Page 13 of 92". Rows can be selected for "Mark read", "Mark unread" (which also sends the
 * chapter back to its first page, as Mihon does) and, with one row, "Mark previous as read" (every earlier chapter in
 * the source's order), through `updateChapters`.
 *
 * Phase 6 Stage 4, downloads: a row's Download button queues it, and while it is queued or downloading the row says how
 * far it got with Cancel (Retry once it failed); a downloaded one has a badge and opens from its CBZ, offline too. The
 * selection (Shift+click for a range) has Download and Delete downloads, and the toolbar's "Download…" queues the next
 * 1, 5, 10 or 25 unread chapters in reading order, or all of them. The queue itself is the Downloads screen.
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

// ---------------------------------------------------------------------------------------------------------------
// Downloads

const downloads = useEntryDownloads("manga", () => props.mangaId);
const enqueue = useEnqueueDownloads();
const change = useChangeDownloads();
const remove = useDeleteDownloads();
const downloadError = computed(
  () => enqueue.error.value ?? change.error.value ?? remove.error.value ?? null,
);

const byId = computed(() => new Map(props.chapters.map((c) => [c.id, c])));

function downloadOf(id: number): DownloadItem | undefined {
  return downloads.value.get(id);
}

function queue(ids: number[]) {
  const fresh = ids.filter((id) => !byId.value.get(id)?.isDownloaded && !downloads.value.has(id));
  if (fresh.length === 0) return;
  enqueue.mutate({ kind: "manga", entryId: props.mangaId, itemIds: fresh });
}

/** The selected chapters that are downloaded or in the queue. */
const selectedDownloads = computed(() =>
  selected.value.filter((id) => byId.value.get(id)?.isDownloaded || downloads.value.has(id)),
);
const selectedToDownload = computed(() =>
  selected.value.filter((id) => !byId.value.get(id)?.isDownloaded && !downloads.value.has(id)),
);

async function deleteSelected() {
  const ids = selectedDownloads.value;
  if (ids.length === 0) return;
  try {
    await remove.mutateAsync({ kind: "manga", entryIds: [props.mangaId], itemIds: ids });
    selected.value = [];
  } catch {
    // shown above the rows
  }
}

function queueSelected() {
  queue(selectedToDownload.value);
  selected.value = [];
}

function cancel(item: DownloadItem) {
  change.mutate({ action: "cancel", ids: [item.id] });
}

function retry(item: DownloadItem) {
  change.mutate({ action: "retry", ids: [item.id] });
}

/** "Download…": the select resets to its title after each pick. */
const nextChoice = ref("");
const nextOptions = computed(() => [
  ...NEXT_UNREAD_COUNTS.map((n) => ({
    value: String(n),
    label: n === 1 ? "Next unread chapter" : `Next ${n} unread`,
    count: nextUnread(props.chapters, n, downloads.value).length,
  })),
  {
    value: "all",
    label: "All unread",
    count: nextUnread(props.chapters, "all", downloads.value).length,
  },
]);
function onNext() {
  const choice = nextChoice.value;
  nextChoice.value = "";
  if (!choice) return;
  queue(nextUnread(props.chapters, choice === "all" ? "all" : Number(choice), downloads.value));
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
    note="Open a chapter to read it; it resumes where you stopped. Downloaded chapters open offline."
    selectable
    openable
    @open="open"
  >
    <template #tools>
      <label class="control">
        <span class="sr-only">Download unread chapters</span>
        <select
          v-model="nextChoice"
          class="field"
          data-testid="download-next"
          :disabled="enqueue.isPending.value"
          @change="onNext"
        >
          <option value="" disabled>Download…</option>
          <option
            v-for="o in nextOptions"
            :key="o.value"
            :value="o.value"
            :disabled="o.count === 0"
          >
            {{ o.label }}{{ o.count === 0 ? " (none left)" : "" }}
          </option>
        </select>
      </label>
    </template>
    <template #notice>
      <ErrorNotice
        v-if="update.error.value"
        title="Couldn't change the chapters"
        :error="update.error.value"
        data-testid="mark-error"
      />
      <ErrorNotice
        v-if="downloadError"
        title="Couldn't change the downloads"
        :error="downloadError"
        data-testid="download-error"
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
      <button
        type="button"
        class="btn"
        data-action="download-selected"
        :disabled="enqueue.isPending.value || selectedToDownload.length === 0"
        :title="
          selectedToDownload.length === 0
            ? 'Every selected chapter is downloaded or queued'
            : undefined
        "
        @click="queueSelected"
      >
        Download
      </button>
      <button
        type="button"
        class="btn"
        data-action="delete-downloads"
        :disabled="remove.isPending.value || selectedDownloads.length === 0"
        :title="
          selectedDownloads.length === 0 ? 'None of the selected chapters is downloaded' : undefined
        "
        @click="deleteSelected"
      >
        Delete downloads
      </button>
    </template>
    <template #actions="{ item }">
      <template v-if="downloadOf(item.id)">
        <span
          class="download-state"
          :class="{ failed: downloadOf(item.id)!.state === 'FAILED' }"
          data-testid="download-state"
          :title="downloadOf(item.id)!.error ?? undefined"
        >
          {{ stateText(downloadOf(item.id)!)
          }}<template
            v-if="
              downloadOf(item.id)!.state === 'DOWNLOADING' &&
              percentOf(downloadOf(item.id)!) !== null
            "
          >
            · {{ percentOf(downloadOf(item.id)!) }}%</template
          >
        </span>
        <button
          v-if="downloadOf(item.id)!.state === 'FAILED'"
          type="button"
          class="btn"
          data-action="retry-download"
          :aria-label="`Retry downloading ${item.name}`"
          @click="retry(downloadOf(item.id)!)"
        >
          Retry
        </button>
        <button
          type="button"
          class="btn"
          data-action="cancel-download"
          :aria-label="`Cancel downloading ${item.name}`"
          @click="cancel(downloadOf(item.id)!)"
        >
          Cancel
        </button>
      </template>
      <button
        v-else-if="!item.downloaded"
        type="button"
        class="btn"
        data-action="download"
        :aria-label="`Download ${item.name}`"
        :disabled="enqueue.isPending.value"
        @click="queue([item.id])"
      >
        Download
      </button>
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

<style scoped>
.control {
  display: flex;
  align-items: center;
}

.download-state {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  white-space: nowrap;
}

.download-state.failed {
  color: var(--color-danger);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
