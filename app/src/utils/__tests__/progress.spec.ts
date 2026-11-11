import { describe, expect, it } from "vitest";
import type { PlaybackSession } from "../../api/types";
import {
  WATCHED_THRESHOLD,
  episodeProgress,
  formatClock,
  progressShare,
  progressText,
  resumePoint,
} from "../progress";

/* An episode's progress: stored in milliseconds, live in seconds, and where to resume from. */

const episode = (lastSecondSeen: string, totalSeconds: string) => ({
  id: 7,
  lastSecondSeen,
  totalSeconds,
});

function live(over: Partial<PlaybackSession> = {}): PlaybackSession {
  return {
    id: "s1",
    episodeId: 7,
    animeId: 1,
    title: "t",
    videoTitle: "v",
    state: "RUNNING",
    startedAt: "1",
    endedAt: null,
    exitCode: null,
    positionSeconds: 300.5,
    durationSeconds: 1500,
    paused: false,
    ...over,
  };
}

describe("episodeProgress", () => {
  it("reads the stored milliseconds", () => {
    expect(episodeProgress(episode("752043", "1500125"))).toEqual({
      positionMs: 752043,
      durationMs: 1500125,
    });
    expect(episodeProgress(episode("0", "0"))).toEqual({ positionMs: 0, durationMs: 0 });
    expect(episodeProgress(episode("junk", "-5"))).toEqual({ positionMs: 0, durationMs: 0 });
  });

  it("takes the live session's position (seconds) for the episode it plays", () => {
    expect(episodeProgress(episode("1000", "1500125"), live())).toEqual({
      positionMs: 300500,
      durationMs: 1500000,
    });
    // another episode, or no position yet: what is stored
    expect(episodeProgress(episode("1000", "2000"), live({ episodeId: 8 })).positionMs).toBe(1000);
    expect(
      episodeProgress(episode("1000", "2000"), live({ positionSeconds: null })).positionMs,
    ).toBe(1000);
    // a live stream without a duration keeps the stored one
    expect(
      episodeProgress(episode("1000", "2000"), live({ durationSeconds: null })).durationMs,
    ).toBe(2000);
  });

  it("gives the share watched, 0 without a duration", () => {
    expect(progressShare({ positionMs: 500, durationMs: 1000 })).toBe(0.5);
    expect(progressShare({ positionMs: 500, durationMs: 0 })).toBe(0);
    expect(progressShare({ positionMs: 1200, durationMs: 1000 })).toBe(1);
  });
});

describe("resumePoint", () => {
  const at = (share: number) => ({ positionMs: Math.round(share * 100_000), durationMs: 100_000 });

  it("offers a position from 1 % up to (not including) the watched threshold, in seconds", () => {
    expect(WATCHED_THRESHOLD).toBe(0.85);
    expect(resumePoint(false, at(0.5))).toBe(50);
    expect(resumePoint(false, at(0.01))).toBe(1);
    expect(resumePoint(false, at(0.0099))).toBeNull();
    expect(resumePoint(false, at(0.8499))).toBeCloseTo(84.99);
    expect(resumePoint(false, at(0.85))).toBeNull();
  });

  it("converts milliseconds to seconds exactly", () => {
    expect(resumePoint(false, { positionMs: 752043, durationMs: 1500125 })).toBe(752.043);
  });

  it("never offers one for a watched episode, an unknown duration or no position", () => {
    expect(resumePoint(true, at(0.5))).toBeNull();
    expect(resumePoint(false, { positionMs: 50_000, durationMs: 0 })).toBeNull();
    expect(resumePoint(false, { positionMs: 0, durationMs: 100_000 })).toBeNull();
  });

  it("follows another threshold (Stage 5's setting)", () => {
    expect(resumePoint(false, at(0.9), 0.95)).toBe(90);
  });
});

describe("formatClock and progressText", () => {
  it("formats seconds as m:ss or h:mm:ss, rounded down", () => {
    expect(formatClock(0)).toBe("0:00");
    expect(formatClock(65.9)).toBe("1:05");
    expect(formatClock(752.043)).toBe("12:32");
    expect(formatClock(3723)).toBe("1:02:03");
    expect(formatClock(-3)).toBe("0:00");
    expect(formatClock(Number.NaN)).toBe("0:00");
  });

  it("says what is left, or where it stopped without a duration", () => {
    expect(progressText({ positionMs: 752043, durationMs: 1500125 })).toBe("12:28 left");
    expect(progressText({ positionMs: 61_000, durationMs: 0 })).toBe("Stopped at 1:01");
    expect(progressText({ positionMs: 0, durationMs: 1500125 })).toBe("");
  });
});
