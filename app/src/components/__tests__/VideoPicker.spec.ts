import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { Episode, HosterChoice, PlaybackSession, VideoChoice } from "../../api/types";
import type { PickerFailure } from "../../composables/useNextEpisode";
import { DEFAULT_PLAYER_SETTINGS } from "../../composables/usePlayerSettings";
import { usePlaybackStore } from "../../stores/playback";
import { VIDEO_CHOICES_KEY, useVideoChoicesStore } from "../../stores/videoChoices";
import { makeEpisodes } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchEpisodeVideos: vi.fn(),
  fetchHosterVideos: vi.fn(),
  playEpisode: vi.fn(),
  fetchPlayerSettings: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import VideoPicker from "../VideoPicker.vue";

/* The video picker and its resume prompt (Phase 4 steps 12-13), against mocked operations. */

const SOURCE = "2196071447214431390";

function video(index: number, over: Partial<VideoChoice> = {}): VideoChoice {
  return {
    index,
    videoTitle: `${[720, 1080][index] ?? 480}p`,
    resolution: [720, 1080][index] ?? 480,
    bitrate: null,
    preferred: false,
    sourceIndex: index,
    matchesPreference: false,
    initialized: true,
    subtitleTracks: [],
    audioTracks: [],
    ...over,
  };
}

function hoster(index: number, over: Partial<HosterChoice> = {}): HosterChoice {
  return {
    index,
    sourceIndex: index,
    name: `Server ${index}`,
    lazy: false,
    state: "READY",
    errorCode: null,
    errorMessage: null,
    videos: [video(0), video(1)],
    ...over,
  };
}

function session(over: Partial<PlaybackSession> = {}): PlaybackSession {
  return {
    id: "s1",
    episodeId: 1001,
    animeId: 12,
    title: "One Piece - Episode 1",
    videoTitle: "1080p",
    state: "RUNNING",
    startedAt: "1700000000000",
    endedAt: null,
    exitCode: null,
    positionSeconds: null,
    durationSeconds: null,
    paused: false,
    ...over,
  };
}

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper | null = null;

const q = <T extends Element = HTMLElement>(sel: string) => document.body.querySelector<T>(sel);
const qa = (sel: string) => [...document.body.querySelectorAll<HTMLElement>(sel)];
const action = (name: string) => q<HTMLButtonElement>(`[data-action="${name}"]`);
const checkedVideo = () =>
  qa('[data-testid="picker-video"]').find(
    (l) => l.querySelector<HTMLInputElement>("input")!.checked,
  );
const hosterEl = (index: number) => q(`[data-testid="picker-hoster"][data-index="${index}"]`)!;

async function open(episode: Episode = makeEpisodes(1)[0]!, failure: PickerFailure | null = null) {
  wrapper = mountView(
    VideoPicker,
    { pinia, queryClient },
    {
      open: true,
      episode,
      sourceId: SOURCE,
      sourceName: "AnimeGG",
      failure,
      "onUpdate:open": vi.fn(),
    },
  );
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  ops.fetchPlayerSettings.mockResolvedValue(DEFAULT_PLAYER_SETTINGS);
  ops.playEpisode.mockImplementation(async (r: { episodeId: number }) =>
    session({ episodeId: r.episodeId }),
  );
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  queryClient.clear();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("VideoPicker", () => {
  it("lists the hosters and their videos with quality and track counts, the source's pick preselected", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(0, {
        name: "VidStreaming",
        videos: [
          video(0, { videoTitle: "VidStreaming", resolution: 720 }),
          video(1, {
            videoTitle: "VidStreaming",
            resolution: 1080,
            preferred: true,
            subtitleTracks: [{ lang: "English" }, { lang: "Spanish" }],
            audioTracks: [{ lang: "Japanese" }, { lang: "English" }],
          }),
        ],
      }),
      hoster(1, { name: "BirdStream" }),
    ]);
    await open();

    expect(ops.fetchEpisodeVideos).toHaveBeenCalledWith(1001);
    const names = qa(".hoster-name").map((n) => n.textContent);
    expect(names).toEqual(["VidStreaming", "BirdStream"]);
    const first = hosterEl(0).querySelectorAll('[data-testid="picker-video"]')[1]!;
    expect(first.textContent).toContain("1080p");
    expect(first.textContent).toContain("2 subtitles");
    expect(first.textContent).toContain("2 audio tracks");
    expect(first.querySelector('[data-testid="source-pick"]')).not.toBeNull();
    expect(checkedVideo()).toBe(first);
    // nothing stopped halfway: a plain Play, focused
    expect(action("resume")).toBeNull();
    expect(document.activeElement).toBe(action("confirm-play"));
  });

  it("says it is fetching, and shows a failed fetch with Try again", async () => {
    let fail!: (e: unknown) => void;
    ops.fetchEpisodeVideos.mockImplementationOnce(() => new Promise((_, r) => (fail = r)));
    await open();
    expect(q('[data-testid="picker-loading"]')!.textContent).toContain(
      "Fetching the videos from AnimeGG",
    );
    expect(action("confirm-play")!.disabled).toBe(true);

    fail(new ApiError({ kind: "graphql", code: "SOURCE_FAILED", message: "HTTP error 503" }));
    await flushPromises();
    const error = q('[data-testid="picker-error"]')!;
    expect(error.textContent).toContain("SOURCE_FAILED");

    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    error.querySelector("button")!.click();
    await flushPromises();
    expect(q('[data-testid="picker-error"]')).toBeNull();
    expect(qa('[data-testid="picker-video"]')).toHaveLength(2);
  });

  it("loads a lazy hoster on request, and shows a failed hoster with Retry", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(0, { name: "Lazy", lazy: true, state: "IDLE", videos: [] }),
      hoster(1, {
        name: "Down",
        state: "ERROR",
        videos: [],
        errorCode: "SOURCE_FAILED",
        errorMessage: "HTTP error 403",
      }),
    ]);
    await open();

    // nothing to preselect yet
    expect(checkedVideo()).toBeUndefined();
    expect(action("confirm-play")!.disabled).toBe(true);
    expect(hosterEl(0).textContent).toContain("Not loaded");
    expect(hosterEl(1).querySelector('[data-testid="hoster-error"]')!.textContent).toContain(
      "SOURCE_FAILED HTTP error 403",
    );

    ops.fetchHosterVideos.mockResolvedValueOnce(hoster(0, { name: "Lazy", lazy: true }));
    hosterEl(0).querySelector<HTMLButtonElement>('[data-action="load-hoster"]')!.click();
    await flushPromises();
    expect(ops.fetchHosterVideos).toHaveBeenCalledWith(1001, 0);
    expect(hosterEl(0).querySelectorAll('[data-testid="picker-video"]')).toHaveLength(2);
    // the first video loaded becomes the choice
    expect(checkedVideo()!.closest('[data-testid="picker-hoster"]')).toBe(hosterEl(0));
    expect(action("confirm-play")!.disabled).toBe(false);

    // a retry that fails outright (the backend) says so, and the hoster stays as it was
    ops.fetchHosterVideos.mockRejectedValueOnce(
      new ApiError({ kind: "network", code: "NETWORK", message: "The backend didn't answer" }),
    );
    hosterEl(1).querySelector<HTMLButtonElement>('[data-action="retry-hoster"]')!.click();
    await flushPromises();
    expect(ops.fetchHosterVideos).toHaveBeenLastCalledWith(1001, 1);
    expect(hosterEl(1).textContent).toContain("Couldn't load Down");
    expect(hosterEl(1).textContent).toContain("NETWORK");
  });

  it("says when nothing can play", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(0, { state: "ERROR", videos: [], errorCode: "SOURCE_FAILED", errorMessage: "gone" }),
    ]);
    await open();
    expect(q('[data-testid="picker-empty"]')!.textContent).toContain("Server 0: gone");
  });

  it("plays the chosen video and remembers it for the source", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0), hoster(1, { name: "Backup" })]);
    const w = await open();

    const choice = hosterEl(1).querySelectorAll<HTMLInputElement>(
      '[data-testid="picker-video"] input',
    )[0]!;
    choice.click();
    await flushPromises();
    action("confirm-play")!.click();
    await flushPromises();

    expect(ops.playEpisode).toHaveBeenCalledWith({
      episodeId: 1001,
      hosterIndex: 1,
      videoIndex: 0,
      startSeconds: null,
    });
    expect(useVideoChoicesStore().choiceOf(SOURCE)).toEqual({
      hosterName: "Backup",
      videoTitle: "720p",
      resolution: 720,
    });
    expect(JSON.parse(localStorage.getItem(VIDEO_CHOICES_KEY)!)[SOURCE].hosterName).toBe("Backup");
    expect(w.emitted("update:open")?.slice(-1)[0]).toEqual([false]);
    expect(w.emitted("started")?.[0]?.[0]).toMatchObject({ episodeId: 1001 });
  });

  it("opened after a failure, shows it and preselects the video it names instead of the remembered one", async () => {
    useVideoChoicesStore().remember(SOURCE, {
      hosterName: "Server 0",
      videoTitle: "1080p",
      resolution: 1080,
    });
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0), hoster(1)]);
    await open(makeEpisodes(1)[0]!, {
      title: "Couldn't play 1080p",
      error: new Error("mpv could not play the video: loading failed (HTTP 403)"),
      preselect: { hosterName: "Server 1", videoTitle: "720p", resolution: 720 },
    });

    const failure = q('[data-testid="picker-failure"]')!.textContent!;
    expect(failure).toContain("Couldn't play 1080p");
    expect(failure).toContain("HTTP 403");
    expect(q('[data-testid="picker-instead"]')!.textContent).toContain("720p is selected instead");
    expect(
      checkedVideo()!.closest('[data-testid="picker-hoster"]')!.getAttribute("data-index"),
    ).toBe("1");
    expect(checkedVideo()!.textContent).toContain("720p");
    // the remembered one is still marked as such
    expect(hosterEl(0).querySelector('[data-testid="last-used"]')).not.toBeNull();
  });

  it("opened after a failure whose video isn't there, preselects as usual", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    await open(makeEpisodes(1)[0]!, {
      title: "Couldn't play the next episode",
      error: new Error("There was no video to play."),
      preselect: { hosterName: "Gone", videoTitle: "720p", resolution: 720 },
    });
    expect(q('[data-testid="picker-failure"]')!.textContent).toContain("no video to play");
    expect(q('[data-testid="picker-instead"]')).toBeNull();
    expect(checkedVideo()!.textContent).toContain("720p");
  });

  it("preselects the remembered choice in another episode, by hoster name and title or resolution", async () => {
    useVideoChoicesStore().remember(SOURCE, {
      hosterName: "Backup",
      videoTitle: "Full HD",
      resolution: 1080,
    });
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(0, { name: "Main" }),
      hoster(4, { name: "Backup" }),
    ]);
    await open();

    const picked = checkedVideo()!;
    expect(picked.closest('[data-testid="picker-hoster"]')).toBe(hosterEl(4));
    expect(picked.textContent).toContain("1080p");
    expect(picked.querySelector('[data-testid="last-used"]')).not.toBeNull();
  });

  it("loads a remembered lazy hoster by itself and preselects its video", async () => {
    useVideoChoicesStore().remember(SOURCE, {
      hosterName: "Lazy",
      videoTitle: "720p",
      resolution: 720,
    });
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(0, { name: "Main" }),
      hoster(1, { name: "Lazy", lazy: true, state: "IDLE", videos: [] }),
    ]);
    ops.fetchHosterVideos.mockResolvedValue(hoster(1, { name: "Lazy", lazy: true }));
    await open();

    expect(ops.fetchHosterVideos).toHaveBeenCalledWith(1001, 1);
    const picked = checkedVideo()!;
    expect(picked.closest('[data-testid="picker-hoster"]')).toBe(hosterEl(1));
    expect(picked.textContent).toContain("720p");
  });

  it("falls back to the source's pick when the remembered hoster failed", async () => {
    useVideoChoicesStore().remember(SOURCE, {
      hosterName: "Down",
      videoTitle: "720p",
      resolution: 720,
    });
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(0, { name: "Down", state: "ERROR", videos: [], errorCode: "SOURCE_FAILED" }),
      hoster(1, { name: "Main", videos: [video(0), video(1, { preferred: true })] }),
    ]);
    await open();
    expect(ops.fetchHosterVideos).not.toHaveBeenCalled();
    expect(checkedVideo()!.querySelector('[data-testid="source-pick"]')).not.toBeNull();
  });

  it("offers to resume an episode stopped halfway, in seconds, or to start over", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    const [episode] = makeEpisodes(1);
    await open({ ...episode!, lastSecondSeen: "752043", totalSeconds: "1500125" });

    expect(action("confirm-play")).toBeNull();
    expect(action("resume")!.textContent).toContain("Resume from 12:32");
    expect(document.activeElement).toBe(action("resume"));
    action("resume")!.click();
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenLastCalledWith(
      expect.objectContaining({ episodeId: 1001, startSeconds: 752.043 }),
    );
  });

  it("starts over from 0 when asked", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    const [episode] = makeEpisodes(1);
    await open({ ...episode!, lastSecondSeen: "752043", totalSeconds: "1500125" });

    action("start-over")!.click();
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenLastCalledWith(
      expect.objectContaining({ startSeconds: null }),
    );
  });

  it("doesn't ask below 1 %, from the threshold on, or for a watched episode", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    const [episode] = makeEpisodes(1);
    for (const e of [
      { ...episode!, lastSecondSeen: "9000", totalSeconds: "1000000" }, // 0.9 %
      { ...episode!, lastSecondSeen: "850000", totalSeconds: "1000000" }, // 85 %
      { ...episode!, lastSecondSeen: "500000", totalSeconds: "1000000", isWatched: true },
    ]) {
      await open(e);
      expect(action("resume")).toBeNull();
      expect(action("confirm-play")).not.toBeNull();
      wrapper!.unmount();
      wrapper = null;
    }
    // exactly 1 % asks
    await open({ ...episode!, lastSecondSeen: "10000", totalSeconds: "1000000" });
    expect(action("resume")!.textContent).toContain("Resume from 0:10");
  });

  it("resumes the episode mpv is playing from its live position", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    usePlaybackStore().apply({
      type: "PROGRESS",
      session: session({ positionSeconds: 900.5, durationSeconds: 1500 }),
      nextEpisodeId: null,
      reason: null,
      message: null,
      httpStatus: null,
      retried: false,
      failedVideo: null,
      nextVideo: null,
    });
    const [episode] = makeEpisodes(1);
    await open({ ...episode!, lastSecondSeen: "300000", totalSeconds: "1500000" });
    expect(action("resume")!.textContent).toContain("Resume from 15:00");
  });

  it("stays open with Starting… until mpv is up, and shows a failure there", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    let reject!: (e: unknown) => void;
    ops.playEpisode.mockImplementationOnce(() => new Promise((_, r) => (reject = r)));
    const w = await open();

    action("confirm-play")!.click();
    await flushPromises();
    expect(action("confirm-play")!.textContent).toContain("Starting…");
    expect(action("confirm-play")!.disabled).toBe(true);

    reject(
      new ApiError({ kind: "graphql", code: "PLAYER_NOT_FOUND", message: "mpv was not found" }),
    );
    await flushPromises();
    expect(q('[data-testid="play-error"]')!.textContent).toContain("mpv wasn't found");
    expect(w.emitted("update:open")).toBeUndefined();
    expect(useVideoChoicesStore().choiceOf(SOURCE)).toBeNull();
  });
});

describe("VideoPicker: the player settings (Phase 4 Stage 5)", () => {
  // as the backend sorts them: the preferred server's hoster first, its preferred quality first
  const sorted = () => [
    hoster(0, {
      sourceIndex: 1,
      name: "Filemoon",
      videos: [
        video(0, {
          videoTitle: "Filemoon 720p",
          resolution: 720,
          sourceIndex: 1,
          matchesPreference: true,
        }),
        video(1, { videoTitle: "Filemoon 1080p", resolution: 1080, sourceIndex: 0 }),
      ],
    }),
    hoster(1, {
      sourceIndex: 0,
      name: "VidStreaming",
      videos: [video(0, { videoTitle: "VidStreaming 1080p", resolution: 1080, preferred: true })],
    }),
  ];
  const videoEl = (h: number, v: number) =>
    hosterEl(h).querySelectorAll<HTMLElement>('[data-testid="picker-video"]')[v]!;

  it("marks and preselects the preference's pick, and keeps the source's pick on the source's first hoster", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue(sorted());
    await open();
    expect(qa(".hoster-name").map((n) => n.textContent)).toEqual(["Filemoon", "VidStreaming"]);
    expect(videoEl(0, 0).querySelector('[data-testid="preference-pick"]')).not.toBeNull();
    expect(videoEl(1, 0).querySelector('[data-testid="source-pick"]')).not.toBeNull();
    expect(qa('[data-testid="preference-pick"]')).toHaveLength(1);
    expect(checkedVideo()).toBe(videoEl(0, 0));
  });

  it("preselects the remembered choice over the preference's", async () => {
    useVideoChoicesStore().remember(SOURCE, {
      hosterName: "VidStreaming",
      videoTitle: "VidStreaming 1080p",
      resolution: 1080,
    });
    ops.fetchEpisodeVideos.mockResolvedValue(sorted());
    await open();
    expect(checkedVideo()).toBe(videoEl(1, 0));
    expect(videoEl(1, 0).querySelector('[data-testid="last-used"]')).not.toBeNull();
    expect(videoEl(0, 0).querySelector('[data-testid="preference-pick"]')).not.toBeNull();
  });

  it("offers to resume up to the backend's watched threshold", async () => {
    const at90 = { ...makeEpisodes(1)[0]!, lastSecondSeen: "1350000", totalSeconds: "1500000" };
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(0)]);
    await open(at90);
    // the default 85 %: past it, so it starts over
    expect(action("resume")).toBeNull();
    wrapper!.unmount();
    queryClient.clear();

    ops.fetchPlayerSettings.mockResolvedValue({
      ...DEFAULT_PLAYER_SETTINGS,
      watchedThreshold: 0.95,
    });
    await open(at90);
    expect(action("resume")!.textContent).toContain("Resume from 22:30");
  });
});
