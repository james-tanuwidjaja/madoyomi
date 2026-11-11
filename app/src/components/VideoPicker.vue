<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import type { Episode, HosterChoice, PlaybackSession, VideoChoice } from "../api/types";
import { noVideoError, useEpisodeVideos, usePlayEpisode } from "../composables/usePlayback";
import { usePlaybackStore } from "../stores/playback";
import { useVideoChoicesStore } from "../stores/videoChoices";
import { episodeProgress, formatClock, resumePoint } from "../utils/progress";
import {
  firstPlayable,
  initialPick,
  matchChoice,
  rememberedHoster,
  videoFacts,
  videoOf,
  type VideoPick,
} from "../utils/videos";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * The video picker (Phase 4 step 12): Play on an episode opens it. It asks the source for the episode's hosters
 * (`fetchEpisodeVideos`) and lists each with its state: a lazy one loads on request, a failed one shows why and can
 * be retried (`fetchHosterVideos`). Each video shows its quality and its subtitle and audio counts, and the source's
 * own pick is marked. The last choice for this source (the hoster's name, the video's title or resolution; kept on
 * this device) is preselected, else the source's pick, so Enter plays it.
 *
 * The resume prompt (step 13) is its footer: an episode stopped between 1 % and the watched threshold offers
 * "Resume from mm:ss" and "Start over"; any other plays from the start (a watched one too, as in Aniyomi). The dialog
 * stays open, "Starting…", until mpv is up, and shows a failure there.
 */
const props = defineProps<{
  episode: Episode | null;
  sourceId: string;
  sourceName: string;
}>();
const open = defineModel<boolean>("open", { required: true });
const emit = defineEmits<{ started: [session: PlaybackSession] }>();

const videos = useEpisodeVideos();
const play = usePlayEpisode();
const choices = useVideoChoicesStore();
const playback = usePlaybackStore();

const selected = ref<VideoPick | null>(null);
const startingFrom = ref<number | null>(null);
const primaryEl = ref<HTMLButtonElement | null>(null);

const remembered = computed(() => choices.choiceOf(props.sourceId));
const lastUsed = computed(() => matchChoice(videos.hosters.value, remembered.value));
const sourcePick = computed(() => firstPlayable(videos.hosters.value));

const progress = computed(() =>
  props.episode ? episodeProgress(props.episode, playback.session) : null,
);
const resumeAt = computed(() =>
  props.episode && progress.value ? resumePoint(props.episode.isWatched, progress.value) : null,
);

const nothingToPlay = computed(() =>
  !videos.loading.value &&
  !videos.error.value &&
  videos.episodeId.value !== null &&
  !videos.hosters.value.some((h) => h.videos.length > 0 || h.state === "IDLE")
    ? noVideoError(videos.hosters.value)
    : null,
);

const keyOf = (h: number, v: number) => `${h}:${v}`;
const selectedKey = computed({
  get: () => (selected.value ? keyOf(selected.value.hosterIndex, selected.value.videoIndex) : ""),
  set: (key: string) => {
    const [h, v] = key.split(":").map(Number);
    const found = videoOf(videos.hosters.value, { hosterIndex: h!, videoIndex: v! });
    if (found) {
      selected.value = {
        hosterIndex: found.hoster.index,
        videoIndex: found.video.index,
        hosterName: found.hoster.name,
        videoTitle: found.video.videoTitle,
      };
    }
  },
});
const chosen = computed(() => videoOf(videos.hosters.value, selected.value));

async function focusPrimary() {
  await nextTick();
  primaryEl.value?.focus();
}

async function start(episodeId: number) {
  selected.value = null;
  play.reset();
  const list = await videos.load(episodeId);
  if (!list) return;
  selected.value = initialPick(list, remembered.value);
  // The remembered hoster loads only on request: load it, then take the remembered video from it.
  const hoster = rememberedHoster(list, remembered.value);
  if (hoster && hoster.state === "IDLE") {
    const loaded = await videos.loadHoster(hoster.index);
    if (loaded && videos.episodeId.value === episodeId) {
      selected.value = matchChoice(videos.hosters.value, remembered.value) ?? selected.value;
    }
  }
  await focusPrimary();
}

watch(
  () => [open.value, props.episode?.id] as const,
  ([isOpen, id]) => {
    if (isOpen && id != null) void start(id);
    else if (!isOpen) videos.reset();
  },
  { immediate: true },
);

async function loadHoster(hoster: HosterChoice) {
  const loaded = await videos.loadHoster(hoster.index);
  if (loaded && !chosen.value) {
    const video = loaded.videos.find((v) => v.preferred) ?? loaded.videos[0];
    if (video) {
      selected.value = {
        hosterIndex: loaded.index,
        videoIndex: video.index,
        hosterName: loaded.name,
        videoTitle: video.videoTitle,
      };
    }
  }
}

async function playFrom(startSeconds: number) {
  const episode = props.episode;
  const pick = chosen.value;
  if (!episode || !pick) return;
  startingFrom.value = startSeconds;
  try {
    const session = await play.mutateAsync({
      episodeId: episode.id,
      hosterIndex: pick.hoster.index,
      videoIndex: pick.video.index,
      startSeconds: startSeconds > 0 ? startSeconds : null,
      sourceId: props.sourceId,
      remember: {
        hosterName: pick.hoster.name,
        videoTitle: pick.video.videoTitle,
        resolution: pick.video.resolution,
      },
    });
    open.value = false;
    emit("started", session);
  } catch {
    // shown below, from play.error
  } finally {
    startingFrom.value = null;
  }
}

const isSourcePick = (h: HosterChoice, v: VideoChoice) =>
  sourcePick.value?.hosterIndex === h.index && sourcePick.value.videoIndex === v.index;
const isLastUsed = (h: HosterChoice, v: VideoChoice) =>
  lastUsed.value?.hosterIndex === h.index && lastUsed.value.videoIndex === v.index;
const hosterLoading = (h: HosterChoice) => videos.loadingHosters.value.includes(h.index);
const busy = computed(() => play.isPending.value);
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="dialog picker" data-testid="video-picker">
        <DialogTitle class="dialog-title">{{ episode?.name ?? "Play" }}</DialogTitle>
        <DialogDescription class="dialog-description">
          Choose a video. It plays in mpv's own window.
        </DialogDescription>

        <p v-if="videos.loading.value" class="muted" data-testid="picker-loading">
          Fetching the videos from {{ sourceName }}…
        </p>
        <ErrorNotice
          v-else-if="videos.error.value"
          title="Couldn't get the videos"
          :error="videos.error.value"
          data-testid="picker-error"
          @retry="episode && start(episode.id)"
        />
        <ErrorNotice
          v-else-if="nothingToPlay"
          title="Nothing to play"
          :error="nothingToPlay"
          data-testid="picker-empty"
          @retry="episode && start(episode.id)"
        />

        <div
          v-if="!videos.loading.value && videos.hosters.value.length"
          class="hosters"
          role="radiogroup"
          aria-label="Videos"
        >
          <section
            v-for="h in videos.hosters.value"
            :key="h.index"
            class="hoster"
            data-testid="picker-hoster"
            :data-index="h.index"
          >
            <header class="hoster-head">
              <span class="hoster-name">{{ h.name }}</span>
              <span v-if="h.state === 'READY'" class="muted">
                {{ h.videos.length }} {{ h.videos.length === 1 ? "video" : "videos" }}
              </span>
              <template v-else-if="h.state === 'IDLE'">
                <span class="muted">Not loaded</span>
                <button
                  type="button"
                  class="btn btn-small"
                  data-action="load-hoster"
                  :disabled="hosterLoading(h)"
                  @click="loadHoster(h)"
                >
                  {{ hosterLoading(h) ? "Loading…" : "Load" }}
                </button>
              </template>
              <template v-else>
                <span class="badge danger">Failed</span>
                <button
                  type="button"
                  class="btn btn-small"
                  data-action="retry-hoster"
                  :disabled="hosterLoading(h)"
                  @click="loadHoster(h)"
                >
                  {{ hosterLoading(h) ? "Loading…" : "Retry" }}
                </button>
              </template>
            </header>
            <p v-if="h.state === 'ERROR'" class="hoster-error" data-testid="hoster-error">
              <code v-if="h.errorCode">{{ h.errorCode }}</code> {{ h.errorMessage ?? "failed" }}
            </p>
            <ErrorNotice
              v-if="videos.hosterErrors.value[h.index]"
              :title="`Couldn't load ${h.name}`"
              :error="videos.hosterErrors.value[h.index]"
            />
            <label
              v-for="v in h.videos"
              :key="v.index"
              class="video"
              :class="{ selected: selectedKey === keyOf(h.index, v.index) }"
              data-testid="picker-video"
            >
              <input
                v-model="selectedKey"
                type="radio"
                name="video"
                :value="keyOf(h.index, v.index)"
                :disabled="busy"
              />
              <span class="video-title">{{ v.videoTitle || "Video" }}</span>
              <span class="facts">
                <span v-for="f in videoFacts(v)" :key="f">{{ f }}</span>
              </span>
              <span v-if="isSourcePick(h, v)" class="badge" data-testid="source-pick">
                Source's pick
              </span>
              <span v-if="isLastUsed(h, v)" class="badge ok" data-testid="last-used"
                >Last used</span
              >
            </label>
          </section>
        </div>

        <p v-if="resumeAt !== null" class="muted" data-testid="resume-note">
          You stopped at {{ formatClock(resumeAt) }}.
        </p>

        <ErrorNotice
          v-if="play.error.value"
          title="Couldn't play the episode"
          :error="play.error.value"
          data-testid="play-error"
        />

        <div class="dialog-actions">
          <DialogClose class="btn" data-action="cancel-play">Cancel</DialogClose>
          <template v-if="resumeAt !== null">
            <button
              type="button"
              class="btn"
              data-action="start-over"
              :disabled="!chosen || busy"
              @click="playFrom(0)"
            >
              {{ busy && startingFrom === 0 ? "Starting…" : "Start over" }}
            </button>
            <button
              ref="primaryEl"
              type="button"
              class="btn btn-accent"
              data-action="resume"
              :disabled="!chosen || busy"
              @click="playFrom(resumeAt)"
            >
              {{
                busy && startingFrom === resumeAt
                  ? "Starting…"
                  : `Resume from ${formatClock(resumeAt)}`
              }}
            </button>
          </template>
          <button
            v-else
            ref="primaryEl"
            type="button"
            class="btn btn-accent"
            data-action="confirm-play"
            :disabled="!chosen || busy"
            @click="playFrom(0)"
          >
            {{ busy ? "Starting…" : "Play" }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.picker {
  width: min(640px, calc(100vw - 2 * var(--space-6)));
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}

.hosters {
  display: grid;
  gap: var(--space-3);
  max-height: 50vh;
  overflow: auto;
}

.hoster {
  display: grid;
  gap: var(--space-1);
}

.hoster-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.hoster-name {
  font-weight: var(--font-weight-semibold);
}

.hoster-error {
  margin: 0;
  color: var(--color-danger);
  overflow-wrap: anywhere;
}

.video {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
}

.video:hover {
  background: var(--color-surface-hover);
}

.video.selected {
  border-color: var(--color-accent);
}

.video-title {
  min-width: 0;
  overflow-wrap: anywhere;
}

.facts {
  display: flex;
  gap: var(--space-2);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}
</style>
