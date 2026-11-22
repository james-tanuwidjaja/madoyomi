<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";
import type { DownloadItem } from "../api/types";
import {
  useChangeDownloads,
  useClearCompletedDownloads,
  useCompletedDownloads,
  useDeleteDownloads,
  useDownloadQueue,
  useReorderDownload,
} from "../composables/useDownloads";
import { usePlayDownloadedEpisode } from "../composables/usePlayback";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import {
  formatBytes,
  itemsOf,
  percentOf,
  queueCounts,
  sizeText,
  stateText,
} from "../utils/downloads";
import ErrorNotice from "../components/ErrorNotice.vue";
import PageLayout from "../components/PageLayout.vue";
import VirtualList from "../components/VirtualList.vue";

/*
 * Downloads of the kind the switch shows, as History and Updates (the user's decision): the queue,
 * live over the `downloadsChanged` subscription (each row's state and pages, Pause or Resume, Retry once it failed,
 * moves up, down and to the top, Cancel), with Pause all / Resume all / Retry failed / Cancel all for the kind, and the
 * completed list (the last finished first, Read, Delete for the file, Clear list for the rows). One queue serves both
 * Kinds with shared limits (Settings → Downloads). Episodes too, their rows in bytes, the episode's time or
 * segments, and the completed list's Play plays the file in mpv from where it stopped (offline too).
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const kind = computed(() => mediaKind.kind);

const queue = useDownloadQueue();
const completed = useCompletedDownloads(kind);
const change = useChangeDownloads();
const reorder = useReorderDownload();
const remove = useDeleteDownloads();
const clear = useClearCompletedDownloads();
const playLocal = usePlayDownloadedEpisode();

const items = computed(() => itemsOf(queue.data.value, kind.value));
const counts = computed(() => queueCounts(queue.data.value, kind.value));
const error = computed(
  () =>
    change.error.value ??
    reorder.error.value ??
    remove.error.value ??
    clear.error.value ??
    playLocal.error.value ??
    null,
);

const subtitle = computed(() => {
  const c = counts.value;
  const parts = [
    c.downloading ? `${c.downloading} downloading` : "",
    c.active - c.downloading ? `${c.active - c.downloading} queued` : "",
    c.paused ? `${c.paused} paused` : "",
    c.failed ? `${c.failed} failed` : "",
    c.done ? `${c.done} downloaded` : "",
  ].filter(Boolean);
  return parts.length ? parts.join(" · ") : "";
});

/** "Cancel all" asks with a second click. */
const confirmCancel = ref(false);
function cancelAll() {
  if (!confirmCancel.value) {
    confirmCancel.value = true;
    return;
  }
  confirmCancel.value = false;
  change.mutate({ action: "cancel", kind: kind.value });
}

function move(item: DownloadItem, to: number) {
  reorder.mutate({ id: item.id, to });
}

function readerLink(item: DownloadItem) {
  return `/manga/${item.entryId}/read/${item.itemId}`;
}

function entryLink(item: DownloadItem) {
  return `/${item.kind}/${item.entryId}`;
}

function finishedText(item: DownloadItem) {
  return item.finishedAt ? new Date(item.finishedAt).toLocaleString() : "";
}
</script>

<template>
  <PageLayout
    title="Downloads"
    :subtitle="subtitle || `Downloaded ${kind === 'anime' ? 'episodes' : 'chapters'} open offline`"
  >
    <template #actions>
      <button
        v-if="counts.active > 0"
        type="button"
        class="btn"
        data-action="pause-all"
        :disabled="change.isPending.value"
        @click="change.mutate({ action: 'pause', kind })"
      >
        Pause all
      </button>
      <button
        v-if="counts.paused > 0"
        type="button"
        class="btn"
        data-action="resume-all"
        :disabled="change.isPending.value"
        @click="change.mutate({ action: 'resume', kind })"
      >
        Resume all
      </button>
      <button
        v-if="counts.failed > 0"
        type="button"
        class="btn"
        data-action="retry-all"
        :disabled="change.isPending.value"
        @click="change.mutate({ action: 'retry', kind })"
      >
        Retry failed
      </button>
      <button
        v-if="counts.total > 0"
        type="button"
        class="btn"
        data-action="cancel-all"
        :disabled="change.isPending.value"
        @click="cancelAll"
        @blur="confirmCancel = false"
      >
        {{ confirmCancel ? `Cancel ${counts.total} downloads?` : "Cancel all" }}
      </button>
    </template>

    <ErrorNotice v-if="error" title="Couldn't change the downloads" :error="error" />
    <p
      v-if="queue.data.value?.heldBecause && items.length"
      class="held"
      role="status"
      data-testid="queue-held"
    >
      Waiting: {{ queue.data.value.heldBecause }}.
      <RouterLink to="/settings">Settings → Downloads</RouterLink>
    </p>
    <ErrorNotice
      v-if="queue.isError.value"
      title="Couldn't load the download queue"
      :error="queue.error.value"
      @retry="queue.refetch()"
    />
    <p v-else-if="queue.isPending.value" class="muted">
      {{ backend.ready ? "Loading…" : "Waiting for the backend…" }}
    </p>

    <section
      v-if="items.length"
      class="section queue"
      aria-label="Queue"
      data-testid="download-queue"
    >
      <h2 class="section-title">Queue</h2>
      <VirtualList
        class="rows"
        :items="items"
        :item-key="(i) => i.id"
        :estimate-size="64"
        label="Download queue"
      >
        <template #default="{ item, index }">
          <div
            class="row"
            :class="{ failed: item.state === 'FAILED', paused: item.state === 'PAUSED' }"
            data-testid="download-row"
            :data-id="item.id"
            :data-state="item.state"
          >
            <div class="text">
              <span class="name">
                <RouterLink :to="entryLink(item)" class="entry">{{ item.entryTitle }}</RouterLink>
                <span class="item">{{ item.itemName }}</span>
              </span>
              <span class="sub">
                <span data-testid="download-row-state"
                  >{{ stateText(item)
                  }}<template v-if="item.progressUnit === 'BYTES' && percentOf(item) !== null">
                    · {{ percentOf(item) }}%</template
                  ></span
                >
                <span v-if="item.bytes > 0" data-testid="download-row-size">{{
                  sizeText(item)
                }}</span>
                <span v-if="item.error" class="error" data-testid="download-row-error">{{
                  item.error
                }}</span>
              </span>
              <span
                v-if="
                  item.state === 'DOWNLOADING' || (item.state !== 'QUEUED' && item.progressDone > 0)
                "
                class="bar"
                role="progressbar"
                aria-label="Progress"
                aria-valuemin="0"
                aria-valuemax="100"
                :aria-valuenow="percentOf(item) ?? 0"
              >
                <span class="bar-fill" :style="{ width: `${percentOf(item) ?? 0}%` }" />
              </span>
            </div>
            <span class="actions">
              <button
                v-if="index > 0"
                type="button"
                class="btn icon"
                data-action="move-top"
                :aria-label="`Move ${item.itemName} to the top`"
                title="Move to the top"
                @click="move(item, 0)"
              >
                ⤒
              </button>
              <button
                v-if="index > 0"
                type="button"
                class="btn icon"
                data-action="move-up"
                :aria-label="`Move ${item.itemName} up`"
                title="Move up"
                @click="move(item, index - 1)"
              >
                ↑
              </button>
              <button
                v-if="index < items.length - 1"
                type="button"
                class="btn icon"
                data-action="move-down"
                :aria-label="`Move ${item.itemName} down`"
                title="Move down"
                @click="move(item, index + 1)"
              >
                ↓
              </button>
              <button
                v-if="item.state === 'QUEUED' || item.state === 'DOWNLOADING'"
                type="button"
                class="btn"
                data-action="pause"
                @click="change.mutate({ action: 'pause', ids: [item.id] })"
              >
                Pause
              </button>
              <button
                v-else-if="item.state === 'PAUSED'"
                type="button"
                class="btn"
                data-action="resume"
                @click="change.mutate({ action: 'resume', ids: [item.id] })"
              >
                Resume
              </button>
              <button
                v-else-if="item.state === 'FAILED'"
                type="button"
                class="btn"
                data-action="retry"
                @click="change.mutate({ action: 'retry', ids: [item.id] })"
              >
                Retry
              </button>
              <button
                type="button"
                class="btn"
                data-action="cancel"
                :aria-label="`Cancel ${item.itemName}`"
                @click="change.mutate({ action: 'cancel', ids: [item.id] })"
              >
                Cancel
              </button>
            </span>
          </div>
        </template>
      </VirtualList>
    </section>
    <p v-else-if="!queue.isPending.value" class="muted" data-testid="queue-empty">
      <template v-if="kind === 'anime'">
        Nothing is downloading. Download episodes from an anime's episode list: one, a selection, or
        the next unwatched ones.
      </template>
      <template v-else>
        Nothing is downloading. Download chapters from a manga's chapter list: one, a selection, or
        the next unread ones.
      </template>
    </p>

    <section class="section" aria-label="Downloaded" data-testid="downloads-completed">
      <div class="section-head">
        <h2 class="section-title">Downloaded</h2>
        <button
          v-if="completed.items.value.length"
          type="button"
          class="btn"
          data-action="clear-completed"
          title="Empty this list; the files stay"
          :disabled="clear.isPending.value"
          @click="clear.mutate(kind)"
        >
          Clear list
        </button>
      </div>
      <ErrorNotice
        v-if="completed.isError.value"
        title="Couldn't load the downloaded list"
        :error="completed.error.value"
        @retry="completed.refetch()"
      />
      <p
        v-else-if="completed.items.value.length === 0 && !completed.isPending.value"
        class="muted"
        data-testid="completed-empty"
      >
        Finished downloads show here. Clear list empties it and keeps the files: they stay marked
        Downloaded in each {{ kind === "anime" ? "episode" : "chapter" }} list.
      </p>
      <ul v-else class="done-rows">
        <li
          v-for="item in completed.items.value"
          :key="item.id"
          class="row"
          data-testid="completed-row"
          :data-id="item.id"
        >
          <div class="text">
            <span class="name">
              <RouterLink :to="entryLink(item)" class="entry">{{ item.entryTitle }}</RouterLink>
              <span class="item">{{ item.itemName }}</span>
            </span>
            <span class="sub">
              <span>{{ formatBytes(item.bytes) }}</span>
              <span>{{ finishedText(item) }}</span>
            </span>
          </div>
          <span class="actions">
            <RouterLink
              v-if="item.kind === 'manga'"
              :to="readerLink(item)"
              class="btn"
              data-action="read-download"
              >Read</RouterLink
            >
            <button
              v-else
              type="button"
              class="btn"
              data-action="play-download"
              :aria-label="`Play ${item.itemName}`"
              :disabled="playLocal.isPending.value"
              @click="playLocal.mutate({ episodeId: item.itemId, resume: true })"
            >
              Play
            </button>
            <button
              type="button"
              class="btn"
              data-action="delete-download"
              :aria-label="`Delete the download of ${item.itemName}`"
              :disabled="remove.isPending.value"
              @click="
                remove.mutate({ kind: item.kind, entryIds: [item.entryId], itemIds: [item.itemId] })
              "
            >
              Delete
            </button>
          </span>
        </li>
      </ul>
      <button
        v-if="completed.hasNextPage.value"
        type="button"
        class="btn more"
        data-action="completed-more"
        :disabled="completed.isFetchingNextPage.value"
        @click="completed.fetchNextPage()"
      >
        {{ completed.isFetchingNextPage.value ? "Loading…" : "Show more" }}
      </button>
    </section>
  </PageLayout>
</template>

<style scoped>
.section {
  display: grid;
  gap: var(--space-2);
  max-width: 980px;
}

.queue {
  min-height: 0;
}

.queue .rows {
  max-height: 60vh;
}

.section-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.section-title {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
}

.done-rows {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  min-height: 52px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.row.failed {
  border-color: var(--color-danger);
}

.row.paused .name {
  color: var(--color-text-tertiary);
}

.text {
  display: grid;
  flex: 1;
  gap: 2px;
  min-width: 0;
}

.name {
  display: flex;
  gap: var(--space-2);
  overflow: hidden;
  white-space: nowrap;
}

.entry {
  overflow: hidden;
  color: var(--color-text);
  font-weight: var(--font-weight-semibold);
  text-overflow: ellipsis;
}

.item {
  overflow: hidden;
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
}

.sub {
  display: flex;
  gap: var(--space-3);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}

.error {
  color: var(--color-danger);
}

.bar {
  display: block;
  width: 100%;
  max-width: 320px;
  height: 3px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--color-border-strong);
}

.bar-fill {
  display: block;
  height: 100%;
  background: var(--color-accent);
}

.actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--space-1);
}

.icon {
  min-width: 2rem;
  padding-inline: var(--space-1);
}

.held {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-raised);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}

.more {
  justify-self: start;
}
</style>
