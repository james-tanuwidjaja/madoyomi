import type { Episode, PlaybackSession } from "../api/types";

/*
 * An episode's playback progress (Phase 4 Stage 3): the stored position and duration, which the backend saves in
 * MILLISECONDS (`lastSecondSeen`/`totalSeconds`, as the Aniyomi app does, despite the names), or the live session's
 * while it plays. Pure, so the list and the picker share it.
 */

/**
 * The share of the duration at which the backend marks an episode watched (`PlaybackSessions.watchedThreshold`).
 * Stage 5's player settings make it a setting; until then the UI uses the same constant.
 */
export const WATCHED_THRESHOLD = 0.85;

/** Below this share of the duration, a stored position isn't worth resuming from. */
export const RESUME_MIN = 0.01;

export interface Progress {
  positionMs: number;
  /** 0 while unknown. */
  durationMs: number;
}

/** A LongString or number of milliseconds, 0 for anything that isn't a positive finite number. */
function millis(v: string | number | null | undefined): number {
  const n = Number(v ?? 0);
  return Number.isFinite(n) && n > 0 ? Math.round(n) : 0;
}

/**
 * The episode's progress: the live session's position (seconds) when `live` plays this episode and mpv has
 * reported one, else what is stored. A live duration of 0/null keeps the stored one.
 */
export function episodeProgress(
  episode: Pick<Episode, "id" | "lastSecondSeen" | "totalSeconds">,
  live?: PlaybackSession | null,
): Progress {
  const stored = {
    positionMs: millis(episode.lastSecondSeen),
    durationMs: millis(episode.totalSeconds),
  };
  if (!live || live.episodeId !== episode.id || live.positionSeconds == null) return stored;
  const liveDuration = millis((live.durationSeconds ?? 0) * 1000);
  return {
    positionMs: millis(live.positionSeconds * 1000),
    durationMs: liveDuration || stored.durationMs,
  };
}

/** position / duration, 0 when the duration is unknown, at most 1. */
export function progressShare(p: Progress): number {
  return p.durationMs > 0 ? Math.min(1, p.positionMs / p.durationMs) : 0;
}

/**
 * Where to offer to resume from, in seconds (what `playEpisode`'s `startSeconds` takes), or null to start at the
 * beginning without asking:
 * - a watched episode starts over (as in Aniyomi);
 * - a position is offered when it is at least 1 % and below the watched threshold of a known duration;
 * - without a duration (never played, or a live stream) nothing is offered.
 */
export function resumePoint(
  watched: boolean,
  p: Progress,
  threshold: number = WATCHED_THRESHOLD,
): number | null {
  if (watched || p.durationMs <= 0 || p.positionMs <= 0) return null;
  const share = p.positionMs / p.durationMs;
  return share >= RESUME_MIN && share < threshold ? p.positionMs / 1000 : null;
}

/** Seconds as a clock: "4:05", "12:34", "1:02:03" (rounded down). */
export function formatClock(seconds: number): string {
  const total = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/**
 * The row's progress line: "12:34 left" with a known duration, "Stopped at 12:34" without one, "" when there is
 * nothing to show (not started).
 */
export function progressText(p: Progress): string {
  if (p.positionMs <= 0) return "";
  if (p.durationMs > 0) {
    return `${formatClock(Math.max(0, p.durationMs - p.positionMs) / 1000)} left`;
  }
  return `Stopped at ${formatClock(p.positionMs / 1000)}`;
}
