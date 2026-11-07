import { useMutation } from "@tanstack/vue-query";
import { ApiError } from "../api/client";
import { fetchEpisodeVideos, playEpisode } from "../api/operations";
import type { HosterChoice, PlaybackSession } from "../api/types";
import { firstPlayable } from "../utils/videos";

/*
 * Playing an episode (Phase 4). The backend owns mpv: it resolves the chosen video right before the launch, sends
 * its headers, subtitles and audio tracks, and replaces the session that was playing.
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

/** Play an episode (by id): its first video (utils/videos.ts) in mpv. The video picker replaces the choice in Stage 3. */
export function usePlayEpisode() {
  return useMutation<PlaybackSession, Error, number>({
    mutationFn: async (episodeId) => {
      const hosters = await fetchEpisodeVideos(episodeId);
      const pick = firstPlayable(hosters);
      if (!pick) throw noVideoError(hosters);
      return playEpisode({ episodeId, hosterIndex: pick.hosterIndex, videoIndex: pick.videoIndex });
    },
  });
}
