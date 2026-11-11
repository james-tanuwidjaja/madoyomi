import type { HosterChoice, VideoChoice } from "../api/types";

/*
 * Choosing a video of an episode (Phase 4): the user's last choice for that source (Stage 3's picker), the player
 * settings' preferred server and quality (Stage 5), and the source's own choice. The backend returns the hosters and
 * videos sorted by the preference after the source's own sort (`index` is the list's order, `sourceIndex` the
 * source's), and marks the videos that match it (`matchesPreference`). What is preselected, and what auto-play plays:
 * the remembered choice, else the preference's, else the source's.
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

const bySourceOrder = <T extends { sourceIndex: number; index: number }>(a: T, b: T) =>
  a.sourceIndex - b.sourceIndex || a.index - b.index;

/** The source's choice in a hoster: its video marked `preferred`, else its first in the source's order. */
function sourcePreferredOf(hoster: HosterChoice): VideoChoice | null {
  return (
    hoster.videos.find((v) => v.preferred) ?? [...hoster.videos].sort(bySourceOrder)[0] ?? null
  );
}

/**
 * The best video of one hoster: the first that matches the preference (the list is sorted by it), else the source's
 * choice. Null without videos.
 */
export function bestOf(hoster: HosterChoice): VideoChoice | null {
  return hoster.videos.find((v) => v.matchesPreference) ?? sourcePreferredOf(hoster);
}

/**
 * The source's pick: its first READY hoster with videos (in the source's order, whatever the preference), and its
 * video marked `preferred`, else its first. Null if no hoster has a video (all failed, lazy or empty).
 */
export function firstPlayable(hosters: readonly HosterChoice[]): VideoPick | null {
  for (const hoster of [...hosters].sort(bySourceOrder)) {
    if (hoster.state !== "READY") continue;
    const video = sourcePreferredOf(hoster);
    if (video) return pickOf(hoster, video);
  }
  return null;
}

/**
 * The preference's pick: the first video, in the list's order (sorted by the preferred server, then quality), that
 * matches the preference. Null without a preference, or when nothing matches it.
 */
export function preferencePick(hosters: readonly HosterChoice[]): VideoPick | null {
  for (const hoster of hosters) {
    if (hoster.state !== "READY") continue;
    const video = hoster.videos.find((v) => v.matchesPreference);
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
 * else by resolution, else that hoster's best video ({@link bestOf}). Null when that hoster isn't there or has no
 * video (yet: a lazy one isn't loaded, a failed one has none).
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
    bestOf(hoster);
  return video ? pickOf(hoster, video) : null;
}

/**
 * What to preselect (and what auto-play plays): the remembered choice if this episode has its hoster, else the
 * preference's pick, else the source's (Phase 4 §9, Stage 5).
 */
export function initialPick(
  hosters: readonly HosterChoice[],
  remembered: RememberedChoice | null | undefined,
): VideoPick | null {
  return matchChoice(hosters, remembered) ?? preferencePick(hosters) ?? firstPlayable(hosters);
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
