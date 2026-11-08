import type { HosterChoice } from "../api/types";

/*
 * Choosing a video of an episode. Until the video picker (Phase 4 Stage 3), Play takes the first one the source
 * offers: hosters and videos come back in the source's own order (its sortHosters/sortVideos, its quality setting).
 */

export interface VideoPick {
  hosterIndex: number;
  videoIndex: number;
  hosterName: string;
  videoTitle: string;
}

/**
 * The first READY hoster with videos (in the source's order), and its video marked `preferred`, else its first.
 * Null if no hoster has a video (all failed, lazy or empty).
 */
export function firstPlayable(hosters: readonly HosterChoice[]): VideoPick | null {
  for (const hoster of hosters) {
    if (hoster.state !== "READY" || hoster.videos.length === 0) continue;
    const video = hoster.videos.find((v) => v.preferred) ?? hoster.videos[0]!;
    return {
      hosterIndex: hoster.index,
      videoIndex: video.index,
      hosterName: hoster.name,
      videoTitle: video.videoTitle,
    };
  }
  return null;
}
