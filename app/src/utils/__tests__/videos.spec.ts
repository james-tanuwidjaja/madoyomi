import { describe, expect, it } from "vitest";
import { ApiError } from "../../api/client";
import type { HosterChoice, VideoChoice } from "../../api/types";
import { noVideoError } from "../../composables/usePlayback";
import { firstPlayable } from "../videos";

/* Play's choice until the video picker: the source's order, its preferred video. */

function video(index: number, over: Partial<VideoChoice> = {}): VideoChoice {
  return {
    index,
    videoTitle: `v${index}`,
    resolution: null,
    bitrate: null,
    preferred: false,
    initialized: true,
    subtitleTracks: [],
    audioTracks: [],
    ...over,
  };
}

function hoster(index: number, over: Partial<HosterChoice> = {}): HosterChoice {
  return {
    index,
    name: `h${index}`,
    lazy: false,
    state: "READY",
    errorCode: null,
    errorMessage: null,
    videos: [video(0), video(1)],
    ...over,
  };
}

describe("firstPlayable", () => {
  it("takes the first video of the first ready hoster, in the source's order", () => {
    expect(firstPlayable([hoster(0), hoster(1)])).toEqual({
      hosterIndex: 0,
      videoIndex: 0,
      hosterName: "h0",
      videoTitle: "v0",
    });
  });

  it("prefers the video the source marks as preferred", () => {
    const h = hoster(0, { videos: [video(0), video(1, { preferred: true }), video(2)] });
    expect(firstPlayable([h])?.videoIndex).toBe(1);
  });

  it("skips failed, lazy (idle) and empty hosters", () => {
    const pick = firstPlayable([
      hoster(0, { state: "ERROR", videos: [] }),
      hoster(1, { state: "IDLE", lazy: true, videos: [] }),
      hoster(2, { videos: [] }),
      hoster(3, { videos: [video(4)] }),
    ]);
    expect(pick).toMatchObject({ hosterIndex: 3, videoIndex: 4 });
  });

  it("is null when nothing can play", () => {
    expect(firstPlayable([])).toBeNull();
    expect(firstPlayable([hoster(0, { state: "ERROR", videos: [] })])).toBeNull();
  });
});

describe("noVideoError", () => {
  it("is the first failed hoster's error, with its code", () => {
    const e = noVideoError([
      hoster(0, { videos: [] }),
      hoster(1, {
        name: "Vidstream",
        state: "ERROR",
        videos: [],
        errorCode: "SOURCE_FAILED",
        errorMessage: "HTTP error 403",
      }),
    ]);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).code).toBe("SOURCE_FAILED");
    expect(e.message).toBe("Vidstream: HTTP error 403");
  });

  it("says there is no video otherwise", () => {
    expect(noVideoError([]).message).toBe("The source returned no video for this episode.");
    expect(noVideoError([hoster(0, { videos: [] })]).message).toMatch(
      /None of the source's servers/,
    );
  });
});
