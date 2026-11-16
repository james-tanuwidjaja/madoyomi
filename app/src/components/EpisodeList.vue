<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import type { DownloadItem, Episode } from "../api/types";
import {
  useChangeDownloads,
  useDeleteDownloads,
  useEnqueueDownloads,
  useEntryDownloads,
} from "../composables/useDownloads";
import { useUpdateEpisodes } from "../composables/useEntries";
import { useNextEpisode, type PickerFailure } from "../composables/useNextEpisode";
import { usePlayDownloadedEpisode } from "../composables/usePlayback";
import { useWatchedThreshold } from "../composables/usePlayerSettings";
import { usePlaybackStore } from "../stores/playback";
import { NEXT_UNREAD_COUNTS, nextUnwatched, percentOf, stateText } from "../utils/downloads";
import { fromEpisode } from "../utils/entryItems";
import { episodeProgress, resumePoint } from "../utils/progress";
import EntryItemList from "./EntryItemList.vue";
import ErrorNotice from "./ErrorNotice.vue";
import NextEpisodeToast from "./NextEpisodeToast.vue";
import VideoPicker from "./VideoPicker.vue";

/**
 * An anime's episodes, sorted and filtered, virtualized (EntryItemList), each with Play, which opens the video
 * picker (VideoPicker: the episode's hosters and videos, the remembered choice, resume or start over).
 *
 * Each row shows how far it was watched, from the stored position, and live from the playback events while mpv
 * plays it (stores/playback.ts: no refetch per second). Rows can be selected for "Mark watched", "Mark unwatched"
 * (which also clears the position, as in Aniyomi) and, with one row, "Mark previous as watched" (every earlier
 * episode in the source's order), through `updateEpisodes`.
 *
 * Stage 4: when an episode of this anime ends, the next-episode toast (`useNextEpisode`) counts down and plays the
 * next one with the remembered choice, without the picker. When mpv can't play a video even after the backend
 * fetched the videos again (ERROR), the picker reopens for that episode with the error and the next video
 * preselected. Both only while this page is open.
 *
 * Phase 6 Stage 2: `playOnOpen` (History's Resume, `?play=` on the details page) opens the picker for that episode
 * once it is in the list, then emits `play-opened`.
 *
 * Phase 6 Stage 5, downloads (as the chapter list's): a row's Download queues it (the backend resolves the video by the
 * player settings' preference right before), and while it is in the queue the row says how far it got with Cancel
 * (Retry once it failed); a downloaded one has a badge, and its Play plays the file from where it stopped, with
 * nothing asked of the source (offline too), while "Stream…" opens the picker. The selection has Download and Delete
 * downloads, and the toolbar's "Download…" queues the next 1, 5, 10 or 25 unwatched episodes, or all of them.
 */
defineOptions({ inheritAttrs: false });

const props = defineProps<{
  animeId: number;
  episodes: readonly Episode[];
  /** The anime's source: the picker remembers the last choice per source. */
  sourceId: string;
  sourceName: string;
  playOnOpen?: number | null;
}>();
const emit = defineEmits<{ "play-opened": [] }>();

const playback = usePlaybackStore();
const live = computed(() =>
  playback.session && playback.session.animeId === props.animeId ? playback.session : null,
);
const items = computed(() =>
  props.episodes.map((e) => fromEpisode(e, live.value, playback.retrying)),
);

const pickerOpen = ref(false);
const pickerEpisode = ref<Episode | null>(null);
const pickerFailure = shallowRef<PickerFailure | null>(null);
function openPicker(id: number, failure: PickerFailure | null = null) {
  next.dismiss();
  pickerEpisode.value = props.episodes.find((e) => e.id === id) ?? null;
  pickerFailure.value = pickerEpisode.value ? failure : null;
  pickerOpen.value = pickerEpisode.value !== null;
}
watch(pickerOpen, (isOpen) => {
  if (!isOpen) pickerFailure.value = null;
});

const next = useNextEpisode({
  animeId: () => props.animeId,
  episodes: () => props.episodes,
  sourceId: () => props.sourceId,
  openPicker: (id, failure) => openPicker(id, failure),
});

// The backend fetched the videos again and the video still didn't play: offer the next one.
watch(
  () => playback.failed,
  (event) => {
    if (!event || event.session.animeId !== props.animeId) return;
    openPicker(event.session.episodeId, {
      title: event.failedVideo
        ? `Couldn't play ${event.failedVideo.videoTitle || "the video"}`
        : "Couldn't play the video",
      error: new Error(event.message ?? "mpv could not play the video."),
      preselect: event.nextVideo,
    });
  },
);
watch(
  () => [props.playOnOpen, props.episodes] as const,
  ([id, list]) => {
    if (id == null || !list.some((e) => e.id === id)) return;
    openPicker(id);
    emit("play-opened");
  },
  { immediate: true },
);

// The picker reads the stored progress of the episode as it is now (a refetch after a session replaces the row).
watch(
  () => props.episodes,
  (list) => {
    const current = pickerEpisode.value;
    if (current) pickerEpisode.value = list.find((e) => e.id === current.id) ?? current;
  },
);

const selected = ref<number[]>([]);
const update = useUpdateEpisodes(() => props.animeId);

const selectedOne = computed(() =>
  selected.value.length === 1 ? props.episodes.find((e) => e.id === selected.value[0]) : undefined,
);
/** The episodes before the selected one in the source's order that aren't watched yet. */
const previousIds = computed(() => {
  const one = selectedOne.value;
  if (!one) return [];
  return props.episodes
    .filter((e) => e.sourceOrder < one.sourceOrder && !e.isWatched)
    .map((e) => e.id);
});

async function mark(ids: number[], isWatched: boolean) {
  if (ids.length === 0) return;
  try {
    await update.mutateAsync({ ids, patch: { isWatched } });
    selected.value = [];
  } catch {
    // shown above the rows, from update.error
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Downloads (Phase 6 Stage 5)

const downloads = useEntryDownloads("anime", () => props.animeId);
const enqueue = useEnqueueDownloads();
const change = useChangeDownloads();
const remove = useDeleteDownloads();
const playLocal = usePlayDownloadedEpisode();
const threshold = useWatchedThreshold();
const downloadError = computed(
  () =>
    enqueue.error.value ??
    change.error.value ??
    remove.error.value ??
    playLocal.error.value ??
    null,
);

const byId = computed(() => new Map(props.episodes.map((e) => [e.id, e])));

function downloadOf(id: number): DownloadItem | undefined {
  return downloads.value.get(id);
}

function queue(ids: number[]) {
  const fresh = ids.filter((id) => !byId.value.get(id)?.downloaded && !downloads.value.has(id));
  if (fresh.length === 0) return;
  enqueue.mutate({ kind: "anime", entryId: props.animeId, itemIds: fresh });
}

const selectedDownloads = computed(() =>
  selected.value.filter((id) => byId.value.get(id)?.downloaded || downloads.value.has(id)),
);
const selectedToDownload = computed(() =>
  selected.value.filter((id) => !byId.value.get(id)?.downloaded && !downloads.value.has(id)),
);

async function deleteSelected() {
  const ids = selectedDownloads.value;
  if (ids.length === 0) return;
  try {
    await remove.mutateAsync({ kind: "anime", entryIds: [props.animeId], itemIds: ids });
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

/** Play on a downloaded episode: its file, from where it stopped (as the next episode and History resume). */
function play(id: number) {
  const episode = byId.value.get(id);
  if (!episode?.downloaded) return openPicker(id);
  next.dismiss();
  playLocal.reset();
  const startSeconds = resumePoint(
    episode.isWatched,
    episodeProgress(episode, playback.session),
    threshold.value,
  );
  playLocal.mutate({ episodeId: id, startSeconds });
}

/** "Download…": the select resets to its title after each pick. */
const nextChoice = ref("");
const nextOptions = computed(() => [
  ...NEXT_UNREAD_COUNTS.map((n) => ({
    value: String(n),
    label: n === 1 ? "Next unwatched episode" : `Next ${n} unwatched`,
    count: nextUnwatched(props.episodes, n, downloads.value).length,
  })),
  {
    value: "all",
    label: "All unwatched",
    count: nextUnwatched(props.episodes, "all", downloads.value).length,
  },
]);
function onNext() {
  const choice = nextChoice.value;
  nextChoice.value = "";
  if (!choice) return;
  queue(nextUnwatched(props.episodes, choice === "all" ? "all" : Number(choice), downloads.value));
}
</script>

<template>
  <EntryItemList
    v-bind="$attrs"
    v-model:selected="selected"
    kind="anime"
    :entry-id="animeId"
    :items="items"
    :noun="{ one: 'episode', many: 'episodes' }"
    :done-labels="{ done: 'Watched', notDone: 'Unwatched' }"
    note="Play lets you choose the video, and resumes where you stopped. Downloaded episodes play offline."
    selectable
  >
    <template #tools>
      <label class="control">
        <span class="sr-only">Download unwatched episodes</span>
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
        title="Couldn't change the episodes"
        :error="update.error.value"
        data-testid="mark-error"
      />
      <ErrorNotice
        v-if="downloadError"
        :title="
          playLocal.error.value ? 'Couldn\'t play the download' : 'Couldn\'t change the downloads'
        "
        :error="downloadError"
        data-testid="download-error"
      />
    </template>
    <template #bulk-actions="{ ids }">
      <button
        type="button"
        class="btn"
        data-action="mark-watched"
        :disabled="update.isPending.value"
        @click="mark(ids, true)"
      >
        Mark watched
      </button>
      <button
        type="button"
        class="btn"
        data-action="mark-unwatched"
        :disabled="update.isPending.value"
        @click="mark(ids, false)"
      >
        Mark unwatched
      </button>
      <button
        v-if="selectedOne"
        type="button"
        class="btn"
        data-action="mark-previous"
        :disabled="update.isPending.value || previousIds.length === 0"
        :title="previousIds.length === 0 ? 'Every earlier episode is watched' : undefined"
        @click="mark(previousIds, true)"
      >
        Mark previous as watched
      </button>
      <button
        type="button"
        class="btn"
        data-action="download-selected"
        :disabled="enqueue.isPending.value || selectedToDownload.length === 0"
        :title="
          selectedToDownload.length === 0
            ? 'Every selected episode is downloaded or queued'
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
          selectedDownloads.length === 0 ? 'None of the selected episodes is downloaded' : undefined
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
        v-else
        type="button"
        class="btn"
        data-action="stream"
        :aria-label="`Stream ${item.name}`"
        title="Choose a video from the source instead of the download"
        @click="openPicker(item.id)"
      >
        Stream…
      </button>
      <button
        type="button"
        class="btn"
        data-action="play"
        :aria-label="`Play ${item.name}`"
        :disabled="playLocal.isPending.value && playLocal.variables.value?.episodeId === item.id"
        @click="play(item.id)"
      >
        Play
      </button>
    </template>
  </EntryItemList>
  <VideoPicker
    v-model:open="pickerOpen"
    :episode="pickerEpisode"
    :source-id="sourceId"
    :source-name="sourceName"
    :failure="pickerFailure"
  />
  <NextEpisodeToast
    v-if="next.offer.value"
    :name="next.offer.value.episode.name"
    :seconds-left="next.offer.value.secondsLeft"
    :starting="next.starting.value"
    @play="next.playNow()"
    @cancel="next.cancel()"
  />
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
