import { ref, shallowRef } from "vue";
import { useMutation } from "@tanstack/vue-query";
import { ApiError } from "../api/client";
import {
  fetchEpisodeVideos,
  fetchHosterVideos,
  playEpisode,
  type PlayRequest,
} from "../api/operations";
import type { HosterChoice, PlaybackSession } from "../api/types";
import { useVideoChoicesStore } from "../stores/videoChoices";
import type { RememberedChoice } from "../utils/videos";

/*
 * Playing an episode (Phase 4). The backend owns mpv: it resolves the chosen video right before the launch, sends
 * its headers, subtitles and audio tracks, and replaces the session that was playing. The UI only chooses: the
 * video picker (Stage 3) lists the episode's hosters and videos, then plays one, from the start or a position.
 */

/** Why an episode has nothing to play: the first failed hoster's error, or a plain "no video". */
export function noVideoError(hosters: readonly HosterChoice[]): Error {
  const failed = hosters.find((h) => h.state === "ERROR" && h.errorCode);
  if (failed) {
    return new ApiError({
      code: failed.errorCode!,
      message: `${failed.name}: ${failed.errorMessage ?? "failed"}`,
      kind: "graphql",
    });
  }
  return new Error(
    hosters.length === 0
      ? "The source returned no video for this episode."
      : "None of the source's servers returned a video for this episode.",
  );
}

/**
 * An episode's hosters for the picker, as local state: `load` asks the source for the whole list
 * (`fetchEpisodeVideos`, which also becomes the list `playEpisode` plays from), `loadHoster` one hoster (a lazy one,
 * or a retry of a failed one; `fetchHosterVideos`), which replaces it in the list. A call for an episode that is no
 * longer the one shown (the picker reopened for another) is dropped.
 */
export function useEpisodeVideos() {
  const episodeId = ref<number | null>(null);
  const hosters = shallowRef<HosterChoice[]>([]);
  const loading = ref(false);
  const error = shallowRef<unknown>(null);
  /** Hosters (by index) being loaded on their own. */
  const loadingHosters = ref<number[]>([]);
  /** A hoster's own load that failed outright (the backend, not the source: that is the hoster's ERROR state). */
  const hosterErrors = shallowRef<Record<number, unknown>>({});

  function reset() {
    episodeId.value = null;
    hosters.value = [];
    loading.value = false;
    error.value = null;
    loadingHosters.value = [];
    hosterErrors.value = {};
  }

  async function load(id: number): Promise<HosterChoice[] | null> {
    reset();
    episodeId.value = id;
    loading.value = true;
    try {
      const list = await fetchEpisodeVideos(id);
      if (episodeId.value !== id) return null;
      hosters.value = list;
      return list;
    } catch (e) {
      if (episodeId.value === id) error.value = e;
      return null;
    } finally {
      if (episodeId.value === id) loading.value = false;
    }
  }

  async function loadHoster(index: number): Promise<HosterChoice | null> {
    const id = episodeId.value;
    if (id === null || loadingHosters.value.includes(index)) return null;
    loadingHosters.value = [...loadingHosters.value, index];
    hosterErrors.value = Object.fromEntries(
      Object.entries(hosterErrors.value).filter(([i]) => Number(i) !== index),
    );
    try {
      const hoster = await fetchHosterVideos(id, index);
      if (episodeId.value !== id) return null;
      hosters.value = hosters.value.map((h) => (h.index === index ? hoster : h));
      return hoster;
    } catch (e) {
      if (episodeId.value === id) hosterErrors.value = { ...hosterErrors.value, [index]: e };
      return null;
    } finally {
      if (episodeId.value === id)
        loadingHosters.value = loadingHosters.value.filter((i) => i !== index);
    }
  }

  return {
    episodeId,
    hosters,
    loading,
    error,
    loadingHosters,
    hosterErrors,
    load,
    loadHoster,
    reset,
  };
}

export interface PlayChoice extends PlayRequest {
  /** The anime's source, to remember the choice for. */
  sourceId: string;
  remember: RememberedChoice;
}

/**
 * Plays a chosen video (`playEpisode`; `startSeconds` to resume) and, once mpv is up, remembers the choice for the
 * source, so the picker preselects it next time.
 */
export function usePlayEpisode() {
  const choices = useVideoChoicesStore();
  return useMutation<PlaybackSession, Error, PlayChoice>({
    mutationFn: ({ episodeId, hosterIndex, videoIndex, startSeconds }) =>
      playEpisode({ episodeId, hosterIndex, videoIndex, startSeconds }),
    onSuccess: (_session, choice) => choices.remember(choice.sourceId, choice.remember),
  });
}
