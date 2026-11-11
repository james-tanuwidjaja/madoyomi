import { describe, expect, it } from "vitest";
import { ApiError } from "../../api/client";
import type { HosterChoice, VideoChoice } from "../../api/types";
import { noVideoError } from "../../composables/usePlayback";
import { firstPlayable, initialPick, matchChoice, videoFacts, videoOf } from "../videos";

/* Choosing a video: the source's own choice (its order, its preferred video), and the remembered one per source. */

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

describe("matchChoice", () => {
  const remembered = { hosterName: "h1", videoTitle: "1080p", resolution: 1080 };
  const hosters = () => [
    hoster(0, { videos: [video(0, { videoTitle: "1080p", resolution: 1080 })] }),
    hoster(1, {
      videos: [
        video(0, { videoTitle: "720p", resolution: 720, preferred: true }),
        video(1, { videoTitle: "1080p", resolution: 1080 }),
      ],
    }),
  ];

  it("finds the remembered hoster by name and the video by its title", () => {
    expect(matchChoice(hosters(), remembered)).toMatchObject({ hosterIndex: 1, videoIndex: 1 });
  });

  it("falls back to the resolution, then to the hoster's preferred video", () => {
    const renamed = hosters();
    renamed[1]!.videos[1]!.videoTitle = "FHD";
    expect(matchChoice(renamed, remembered)).toMatchObject({ hosterIndex: 1, videoIndex: 1 });
    expect(matchChoice(renamed, { ...remembered, resolution: 480 })).toMatchObject({
      hosterIndex: 1,
      videoIndex: 0,
    });
  });

  it("is null without a choice, without that hoster, or while it isn't ready", () => {
    expect(matchChoice(hosters(), null)).toBeNull();
    expect(matchChoice(hosters(), { ...remembered, hosterName: "gone" })).toBeNull();
    const lazy = hosters();
    lazy[1] = hoster(1, { state: "IDLE", lazy: true, videos: [] });
    expect(matchChoice(lazy, remembered)).toBeNull();
    const failed = hosters();
    failed[1] = hoster(1, { state: "ERROR", videos: [] });
    expect(matchChoice(failed, remembered)).toBeNull();
  });

  it("preselects the remembered choice, else the source's", () => {
    expect(initialPick(hosters(), remembered)).toMatchObject({ hosterIndex: 1, videoIndex: 1 });
    expect(initialPick(hosters(), { ...remembered, hosterName: "gone" })).toMatchObject({
      hosterIndex: 0,
      videoIndex: 0,
    });
    expect(initialPick([], remembered)).toBeNull();
  });

  it("finds a pick's video again, or nothing once it is gone", () => {
    expect(videoOf(hosters(), { hosterIndex: 1, videoIndex: 0 })?.video.videoTitle).toBe("720p");
    expect(videoOf(hosters(), { hosterIndex: 5, videoIndex: 0 })).toBeNull();
    expect(videoOf(hosters(), null)).toBeNull();
  });
});

describe("videoFacts", () => {
  it("names the resolution (unless the title has it) and counts the tracks", () => {
    expect(
      videoFacts(
        video(0, {
          videoTitle: "VidStreaming",
          resolution: 1080,
          subtitleTracks: [{ lang: "English" }, { lang: "Spanish" }],
          audioTracks: [{ lang: "Japanese" }],
        }),
      ),
    ).toEqual(["1080p", "2 subtitles", "1 audio track"]);
    expect(videoFacts(video(0, { videoTitle: "AnimeGG:1080p", resolution: 1080 }))).toEqual([]);
  });
});
