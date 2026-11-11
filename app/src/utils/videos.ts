import type { HosterChoice, VideoChoice } from "../api/types";

/*
 * Choosing a video of an episode (Phase 4): the source's own choice, and the user's last choice for that source
 * (Stage 3's picker). Hosters and videos come back in the source's order (its sortHosters/sortVideos, its quality
 * setting), so the source's choice is its first READY hoster's `preferred` video.
 */

export interface VideoPick {
  hosterIndex: number;
  videoIndex: number;
  hosterName: string;
  videoTitle: string;
}

/** What is remembered of a choice, per source: indexes change between episodes, names and qualities don't. */
export interface RememberedChoice {
  hosterName: string;
  videoTitle: string;
  resolution: number | null;
}

function pickOf(hoster: HosterChoice, video: VideoChoice): VideoPick {
  return {
    hosterIndex: hoster.index,
    videoIndex: video.index,
    hosterName: hoster.name,
    videoTitle: video.videoTitle,
  };
}

/** The hoster's `preferred` video, else its first; null without videos. */
function preferredOf(hoster: HosterChoice): VideoChoice | null {
  return hoster.videos.find((v) => v.preferred) ?? hoster.videos[0] ?? null;
}

/**
 * The first READY hoster with videos (in the source's order), and its video marked `preferred`, else its first.
 * Null if no hoster has a video (all failed, lazy or empty).
 */
export function firstPlayable(hosters: readonly HosterChoice[]): VideoPick | null {
  for (const hoster of hosters) {
    if (hoster.state !== "READY") continue;
    const video = preferredOf(hoster);
    if (video) return pickOf(hoster, video);
  }
  return null;
}

/** The hoster the remembered choice names, if the episode has one by that name. */
export function rememberedHoster(
  hosters: readonly HosterChoice[],
  remembered: RememberedChoice | null | undefined,
): HosterChoice | null {
  if (!remembered) return null;
  return hosters.find((h) => h.name === remembered.hosterName) ?? null;
}

/**
 * The remembered choice in this episode's hosters: the hoster by name (READY), then the video by its exact title,
 * else by resolution, else the hoster's preferred or first video. Null when that hoster isn't there or has no video
 * (yet: a lazy one isn't loaded, a failed one has none).
 */
export function matchChoice(
  hosters: readonly HosterChoice[],
  remembered: RememberedChoice | null | undefined,
): VideoPick | null {
  const hoster = rememberedHoster(hosters, remembered);
  if (!remembered || !hoster || hoster.state !== "READY") return null;
  const video =
    hoster.videos.find((v) => v.videoTitle === remembered.videoTitle) ??
    (remembered.resolution != null
      ? hoster.videos.find((v) => v.resolution === remembered.resolution)
      : undefined) ??
    preferredOf(hoster);
  return video ? pickOf(hoster, video) : null;
}

/** What to preselect: the remembered choice if this episode has it, else the source's. */
export function initialPick(
  hosters: readonly HosterChoice[],
  remembered: RememberedChoice | null | undefined,
): VideoPick | null {
  return matchChoice(hosters, remembered) ?? firstPlayable(hosters);
}

/** The video of a pick, if the hosters still have it. */
export function videoOf(
  hosters: readonly HosterChoice[],
  pick: Pick<VideoPick, "hosterIndex" | "videoIndex"> | null,
): { hoster: HosterChoice; video: VideoChoice } | null {
  if (!pick) return null;
  const hoster = hosters.find((h) => h.index === pick.hosterIndex);
  const video = hoster?.videos.find((v) => v.index === pick.videoIndex);
  return hoster && video ? { hoster, video } : null;
}

/** A video's extra facts for the picker: "1080p · 2 subtitles · 1 audio track" (only what it has). */
export function videoFacts(video: VideoChoice): string[] {
  const facts: string[] = [];
  if (video.resolution && !video.videoTitle.includes(`${video.resolution}p`)) {
    facts.push(`${video.resolution}p`);
  }
  const subs = video.subtitleTracks.length;
  const audio = video.audioTracks.length;
  if (subs) facts.push(`${subs} ${subs === 1 ? "subtitle" : "subtitles"}`);
  if (audio) facts.push(`${audio} ${audio === 1 ? "audio track" : "audio tracks"}`);
  return facts;
}
