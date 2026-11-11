import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { InfiniteData, QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type {
  Anime,
  AnimeRefresh,
  Episode,
  HosterChoice,
  PlaybackSession,
  SourcePage,
} from "../../api/types";
import { queryKeys } from "../../composables/queryKeys";
import { resetAutoRefresh } from "../../composables/useEntries";
import { DEFAULT_PLAYER_SETTINGS } from "../../composables/usePlayerSettings";
import {
  makeAnime,
  makeEpisodes,
  makePage,
  makePlaybackEvent,
  makePlaybackSession,
  makeSource,
} from "../../test/fixtures";
import { usePlaybackStore } from "../../stores/playback";
import { usePlayerPrefsStore } from "../../stores/playerPrefs";
import { useVideoChoicesStore } from "../../stores/videoChoices";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchAnime: vi.fn(),
  fetchEpisodes: vi.fn(),
  fetchSeasons: vi.fn(),
  refreshAnime: vi.fn(),
  setInLibrary: vi.fn(),
  fetchEpisodeVideos: vi.fn(),
  fetchHosterVideos: vi.fn(),
  playEpisode: vi.fn(),
  stopPlayback: vi.fn(),
  updateEpisodes: vi.fn(),
  fetchPlayerSettings: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import AnimeDetailsView from "../AnimeDetailsView.vue";

/* An anime's details against mocked operations: the stored data, the refresh, the library and the seasons. */
const animegg = makeSource();

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;
let stored: Anime;
let episodes: Episode[];

function hoster(over: Partial<HosterChoice> = {}): HosterChoice {
  return {
    index: 0,
    sourceIndex: 0,
    name: "Main",
    lazy: false,
    state: "READY",
    errorCode: null,
    errorMessage: null,
    videos: [
      {
        index: 0,
        videoTitle: "720p",
        resolution: 720,
        bitrate: null,
        preferred: false,
        sourceIndex: 0,
        matchesPreference: false,
        initialized: true,
        subtitleTracks: [],
        audioTracks: [],
      },
      {
        index: 1,
        videoTitle: "1080p",
        resolution: 1080,
        bitrate: null,
        preferred: true,
        sourceIndex: 1,
        matchesPreference: false,
        initialized: false,
        subtitleTracks: [{ lang: "English" }],
        audioTracks: [],
      },
    ],
    ...over,
  };
}

function session(episodeId: number): PlaybackSession {
  return {
    id: "s1",
    episodeId,
    animeId: 12,
    title: "One Piece - Episode 3",
    videoTitle: "1080p",
    state: "RUNNING",
    startedAt: "1700000000000",
    endedAt: null,
    exitCode: null,
    positionSeconds: null,
    durationSeconds: null,
    paused: false,
  };
}

function refreshResult(over: Partial<AnimeRefresh> = {}): AnimeRefresh {
  return { anime: stored, episodes, seasons: [], error: null, ...over };
}

async function mountDetails(id = 12) {
  wrapper = mountView(AnimeDetailsView, { pinia, queryClient }, { id });
  await flushPromises();
}

const names = () => wrapper.findAll('[data-testid="item-row"] .name').map((n) => n.text());
const button = (action: string) => wrapper.find(`[data-action="${action}"]`);

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  resetAutoRefresh();
  stored = makeAnime();
  episodes = makeEpisodes(3);
  ops.fetchSources.mockResolvedValue([animegg]);
  ops.fetchAnime.mockImplementation(async () => stored);
  ops.fetchEpisodes.mockImplementation(async () => episodes);
  ops.fetchSeasons.mockResolvedValue([]);
  ops.refreshAnime.mockImplementation(async () => refreshResult());
  ops.fetchPlayerSettings.mockResolvedValue(DEFAULT_PLAYER_SETTINGS);
  ops.setInLibrary.mockImplementation(async (_k: string, _id: number, inLibrary: boolean) => {
    stored = { ...stored, inLibrary, inLibraryAt: inLibrary ? "1700000000" : stored.inLibraryAt };
    return stored;
  });
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("AnimeDetailsView", () => {
  it("shows the stored details and the episodes, newest first, without asking the source", async () => {
    await mountDetails();

    expect(wrapper.find('[data-testid="entry-title"]').text()).toBe("One Piece");
    expect(wrapper.find('[data-testid="entry-people"]').text()).toBe("Toei Animation");
    expect(wrapper.find('[data-testid="entry-status"]').text()).toBe("Ongoing");
    expect(wrapper.find('[data-testid="entry-source"]').text()).toBe("AnimeGG");
    expect(wrapper.find('[data-testid="entry-source"]').attributes("href")).toBe(
      `/browse/${animegg.id}`,
    );
    expect(wrapper.findAll(".genres .badge").map((g) => g.text())).toEqual(["Action", "Adventure"]);
    expect(wrapper.find('[data-testid="entry-cover"]').attributes("src")).toBe(
      "http://127.0.0.1:50266/api/v1/anime/12/thumbnail?token=t",
    );
    expect(names()).toEqual(["Episode 3", "Episode 2", "Episode 1"]);
    expect(ops.refreshAnime).not.toHaveBeenCalled();
  });

  it("refreshes an entry that was never fetched, once per session", async () => {
    stored = makeAnime({ initialized: false, description: null });
    episodes = [];
    ops.refreshAnime.mockImplementation(async () => {
      stored = makeAnime({ description: "Fetched." });
      episodes = makeEpisodes(2);
      return refreshResult();
    });
    await mountDetails();

    expect(ops.refreshAnime).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-testid="entry-description"]').text()).toBe("Fetched.");
    expect(names()).toEqual(["Episode 2", "Episode 1"]);

    // a second visit of a still-uninitialized entry (here: the site failed) doesn't ask again
    wrapper.unmount();
    queryClient.clear();
    stored = makeAnime({ initialized: false });
    await mountDetails();
    expect(ops.refreshAnime).toHaveBeenCalledTimes(1);
  });

  it("refreshes from the button, and keeps the stored data with the source's error", async () => {
    await mountDetails();
    ops.refreshAnime.mockResolvedValueOnce(
      refreshResult({
        error: new ApiError({ kind: "graphql", code: "SOURCE_FAILED", message: "HTTP error 503" }),
      }),
    );

    await button("refresh").trigger("click");
    await flushPromises();

    expect(ops.refreshAnime).toHaveBeenCalledWith(12);
    const error = wrapper.find('[data-testid="refresh-error"]');
    expect(error.text()).toContain("Couldn't refresh from AnimeGG");
    expect(error.text()).toContain("SOURCE_FAILED");
    expect(error.text()).toContain("HTTP error 503");
    expect(wrapper.find('[data-testid="entry-title"]').text()).toBe("One Piece");
    expect(names()).toHaveLength(3);

    // Try again: this time it works, and the notice goes
    episodes = makeEpisodes(4);
    await error.find("button").trigger("click");
    await flushPromises();
    expect(ops.refreshAnime).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="refresh-error"]').exists()).toBe(false);
    expect(names()).toHaveLength(4);
  });

  it("shows a refresh that failed outright (the backend went away) as an error, keeping the page", async () => {
    await mountDetails();
    ops.refreshAnime.mockRejectedValueOnce(
      new ApiError({ kind: "network", code: "NETWORK", message: "The backend didn't answer" }),
    );

    await button("refresh").trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="refresh-error"]').text()).toContain("NETWORK");
    expect(names()).toHaveLength(3);
  });

  it("adds to and removes from the library, marking the cached browse cards", async () => {
    const browseKey = queryKeys.browse({
      kind: "anime",
      sourceId: animegg.id,
      type: "POPULAR",
      query: null,
      filters: null,
    });
    queryClient.setQueryData<InfiniteData<SourcePage>>(browseKey, {
      pages: [makePage(11, 3, false)],
      pageParams: [1],
    });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    await mountDetails();
    expect(button("library").text()).toBe("Add to library");

    await button("library").trigger("click");
    await flushPromises();

    expect(ops.setInLibrary).toHaveBeenCalledWith("anime", 12, true);
    expect(button("library").text()).toBe("Remove from library");
    expect(wrapper.find('[data-testid="in-library"]').exists()).toBe(true);
    const cards = queryClient.getQueryData<InfiniteData<SourcePage>>(browseKey)!.pages[0].items;
    expect(cards.map((c) => [c.id, c.inLibrary])).toEqual([
      [11, false],
      [12, true],
      [13, false],
    ]);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.library("anime") });

    await button("library").trigger("click");
    await flushPromises();
    expect(ops.setInLibrary).toHaveBeenLastCalledWith("anime", 12, false);
    expect(button("library").text()).toBe("Add to library");
    expect(
      queryClient.getQueryData<InfiniteData<SourcePage>>(browseKey)!.pages[0].items[1].inLibrary,
    ).toBe(false);
  });

  it("lists a Seasons entry's seasons as cards that open their own details", async () => {
    stored = makeAnime({ id: 40, fetchType: "Seasons", title: "Monogatari" });
    ops.fetchSeasons.mockResolvedValue([
      { id: 41, title: "Bakemonogatari", thumbnailUrl: null, inLibrary: false, seasonNumber: 1 },
      { id: 42, title: "Nisemonogatari", thumbnailUrl: null, inLibrary: true, seasonNumber: 2 },
    ]);
    await mountDetails(40);

    expect(ops.fetchEpisodes).not.toHaveBeenCalled();
    const cards = wrapper.findAll('[data-testid="media-card"]');
    expect(cards.map((c) => c.attributes("href"))).toEqual(["/anime/41", "/anime/42"]);
    expect(cards[1].find('[data-testid="in-library"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("2 seasons");
  });

  it("links a season to the entry it belongs to", async () => {
    const parent = makeAnime({ id: 40, fetchType: "Seasons", title: "Monogatari" });
    stored = makeAnime({ id: 41, parentId: 40, seasonNumber: 1, title: "Bakemonogatari" });
    ops.fetchAnime.mockImplementation(async (id: number) => (id === 40 ? parent : stored));
    await mountDetails(41);

    const context = wrapper.find('[data-testid="season-of"]');
    expect(context.text()).toBe("Season 1 of Monogatari");
    expect(context.find("a").attributes("href")).toBe("/anime/40");
  });

  it("doesn't refresh from an untrusted source, and points to Extensions", async () => {
    ops.fetchSources.mockResolvedValue([
      makeSource({ extension: { ...animegg.extension, isTrusted: false, isLoaded: false } }),
    ]);
    stored = makeAnime({ initialized: false });
    await mountDetails();

    expect(ops.refreshAnime).not.toHaveBeenCalled();
    expect(button("refresh").attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="source-unusable"]').text()).toContain("isn't trusted");
    expect(wrapper.find('[data-testid="entry-source"]').element.tagName).toBe("SPAN");
    // the stored data still shows
    expect(names()).toHaveLength(3);
  });

  it("says when the entry isn't stored", async () => {
    ops.fetchAnime.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "NOT_FOUND", message: "No anime with id 99" }),
    );
    await mountDetails(99);

    expect(wrapper.find('[data-testid="not-stored"]').text()).toContain("This anime isn't stored");
  });

  it("plays an episode through the picker: its first ready hoster's preferred video preselected", async () => {
    let started!: (s: PlaybackSession) => void;
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster({ index: 0, name: "Down", state: "ERROR", videos: [], errorCode: "SOURCE_FAILED" }),
      hoster({ index: 1 }),
    ]);
    ops.playEpisode.mockImplementation(() => new Promise((r) => (started = r)));
    await mountDetails();

    const play = wrapper.find('[data-action="play"]');
    expect(play.attributes("aria-label")).toBe("Play Episode 3");
    await play.trigger("click");
    await flushPromises();

    expect(ops.fetchEpisodeVideos).toHaveBeenCalledWith(1003);
    const picker = document.body.querySelector('[data-testid="video-picker"]')!;
    expect(picker.querySelector(".dialog-title")!.textContent).toBe("Episode 3");
    const confirm = picker.querySelector<HTMLButtonElement>('[data-action="confirm-play"]')!;
    confirm.click();
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenCalledWith({
      episodeId: 1003,
      hosterIndex: 1,
      videoIndex: 1,
      startSeconds: null,
    });
    expect(confirm.textContent).toContain("Starting…");

    started(session(1003));
    await flushPromises();
    expect(document.body.querySelector('[data-testid="video-picker"]')).toBeNull();
  });

  it("resumes an episode stopped halfway from the picker", async () => {
    episodes = makeEpisodes(3).map((e) =>
      e.id === 1002 ? { ...e, lastSecondSeen: "752043", totalSeconds: "1500125" } : e,
    );
    ops.fetchEpisodeVideos.mockResolvedValue([hoster()]);
    ops.playEpisode.mockResolvedValue(session(1002));
    await mountDetails();

    const row = wrapper.findAll('[data-testid="item-row"]')[1]!;
    expect(row.find('[data-testid="item-progress-text"]').text()).toBe("12:28 left");
    await row.find('[data-action="play"]').trigger("click");
    await flushPromises();
    document.body.querySelector<HTMLButtonElement>('[data-action="resume"]')!.click();
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenCalledWith(
      expect.objectContaining({ episodeId: 1002, startSeconds: 752.043 }),
    );
  });

  it("marks selected episodes watched and unwatched, updating the rows from what is stored", async () => {
    episodes = makeEpisodes(3).map((e) =>
      e.id === 1001 ? { ...e, lastSecondSeen: "60000", totalSeconds: "1500000" } : e,
    );
    ops.updateEpisodes.mockImplementation(async (ids: number[], patch: { isWatched: boolean }) =>
      episodes
        .filter((e) => ids.includes(e.id))
        .map((e) => ({
          ...e,
          isWatched: patch.isWatched,
          lastSecondSeen: patch.isWatched ? e.lastSecondSeen : "0",
        })),
    );
    await mountDetails();
    const boxes = () => wrapper.findAll('[data-testid="item-select"]');
    const watched = () =>
      wrapper.findAll('[data-testid="item-row"]').map((r) => r.text().includes("Watched"));

    // shown newest first: 3, 2, 1
    await boxes()[1]!.trigger("click");
    await boxes()[2]!.trigger("click");
    await button("mark-watched").trigger("click");
    await flushPromises();
    expect(ops.updateEpisodes).toHaveBeenCalledWith([1002, 1001], { isWatched: true });
    expect(watched()).toEqual([false, true, true]);
    expect(wrapper.find('[data-testid="bulk-bar"]').exists()).toBe(false);
    expect(ops.fetchEpisodes).toHaveBeenCalledTimes(1);

    await boxes()[2]!.trigger("click");
    await button("mark-unwatched").trigger("click");
    await flushPromises();
    expect(ops.updateEpisodes).toHaveBeenLastCalledWith([1001], { isWatched: false });
    expect(watched()).toEqual([false, true, false]);
    // unwatched also cleared the position
    const cached = queryClient.getQueryData<Episode[]>(queryKeys.episodes(12))!;
    expect(cached.find((e) => e.id === 1001)!.lastSecondSeen).toBe("0");
  });

  it("marks every earlier episode in the source's order watched", async () => {
    episodes = makeEpisodes(4).map((e) => (e.id === 1002 ? { ...e, isWatched: true } : e));
    ops.updateEpisodes.mockImplementation(async (ids: number[]) =>
      episodes.filter((e) => ids.includes(e.id)).map((e) => ({ ...e, isWatched: true })),
    );
    await mountDetails();

    // shown newest first: 4, 3, 2, 1; select episode 4
    await wrapper.findAll('[data-testid="item-select"]')[0]!.trigger("click");
    await button("mark-previous").trigger("click");
    await flushPromises();
    // episode 2 was already watched
    expect(ops.updateEpisodes).toHaveBeenCalledWith([1001, 1003], { isWatched: true });

    // the oldest has no previous one
    await wrapper.findAll('[data-testid="item-select"]')[3]!.trigger("click");
    expect(button("mark-previous").attributes("disabled")).toBeDefined();
  });

  it("shows a failed mark above the rows", async () => {
    ops.updateEpisodes.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "NOT_FOUND", message: "No episode with id 1003" }),
    );
    await mountDetails();
    await wrapper.findAll('[data-testid="item-select"]')[0]!.trigger("click");
    await button("mark-watched").trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="mark-error"]').text()).toContain("NOT_FOUND");
    // the selection stays, for a retry
    expect(wrapper.find('[data-testid="selected-count"]').text()).toBe("1 selected");
  });
});

describe("AnimeDetailsView: the next episode and failed videos (Phase 4 Stage 4)", () => {
  const toast = () => wrapper.find('[data-testid="next-episode"]');
  const ended = (episodeId = 1001, nextEpisodeId: number | null = 1002) =>
    usePlaybackStore().apply(
      makePlaybackEvent("ENDED", {
        session: makePlaybackSession({ episodeId, state: "EXITED" }),
        nextEpisodeId,
        reason: "eof",
      }),
    );

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster(),
      hoster({
        index: 1,
        name: "Backup",
        videos: [{ ...hoster().videos[0]!, videoTitle: "480p" }],
      }),
    ]);
    ops.playEpisode.mockImplementation(async (r: { episodeId: number }) => session(r.episodeId));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("offers the next episode with a countdown, then plays it with the remembered choice, from its resume point", async () => {
    episodes = makeEpisodes(3).map((e) =>
      e.id === 1002 ? { ...e, lastSecondSeen: "600000", totalSeconds: "1500000" } : e,
    );
    useVideoChoicesStore().remember(animegg.id, {
      hosterName: "Main",
      videoTitle: "720p",
      resolution: 720,
    });
    await mountDetails();

    ended();
    await flushPromises();
    expect(toast().find('[data-testid="next-episode-name"]').text()).toBe("Next: Episode 2");
    expect(toast().find('[data-testid="next-episode-countdown"]').text()).toBe("Plays in 10 s");
    vi.advanceTimersByTime(3000);
    await flushPromises();
    expect(toast().find('[data-testid="next-episode-countdown"]').text()).toBe("Plays in 7 s");
    expect(ops.playEpisode).not.toHaveBeenCalled();

    vi.advanceTimersByTime(7000);
    await flushPromises();
    expect(ops.fetchEpisodeVideos).toHaveBeenCalledWith(1002);
    // no picker: the remembered video (not the source's pick, 1080p) from where the episode stopped
    expect(document.body.querySelector('[data-testid="video-picker"]')).toBeNull();
    expect(ops.playEpisode).toHaveBeenCalledWith({
      episodeId: 1002,
      hosterIndex: 0,
      videoIndex: 0,
      startSeconds: 600,
    });
    expect(toast().exists()).toBe(false);
  });

  it("plays the source's pick from the start without a remembered choice, at once with Play now", async () => {
    await mountDetails();
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenCalledWith({
      episodeId: 1002,
      hosterIndex: 0,
      videoIndex: 1,
      startSeconds: null,
    });
    // what auto-play played is remembered, as a play from the picker is
    expect(useVideoChoicesStore().choiceOf(animegg.id)).toEqual({
      hosterName: "Main",
      videoTitle: "1080p",
      resolution: 1080,
    });
  });

  it("stops on Cancel, on another Play, and when the page goes away", async () => {
    await mountDetails();
    ended();
    await flushPromises();
    await toast().find('[data-action="cancel-next"]').trigger("click");
    vi.advanceTimersByTime(15_000);
    await flushPromises();
    expect(toast().exists()).toBe(false);
    expect(ops.fetchEpisodeVideos).not.toHaveBeenCalled();

    ended();
    await flushPromises();
    expect(toast().exists()).toBe(true);
    await wrapper.find('[data-action="play"]').trigger("click");
    await flushPromises();
    expect(toast().exists()).toBe(false);
    document.body.querySelector<HTMLButtonElement>('[data-action="cancel-play"]')!.click();
    await flushPromises();

    ended();
    await flushPromises();
    usePlaybackStore().apply(
      makePlaybackEvent("STARTED", { session: makePlaybackSession({ id: "s9", episodeId: 1003 }) }),
    );
    await flushPromises();
    expect(toast().exists()).toBe(false);

    ended();
    await flushPromises();
    expect(toast().exists()).toBe(true);
    wrapper.unmount();
    ops.fetchEpisodeVideos.mockClear();
    vi.advanceTimersByTime(15_000);
    await flushPromises();
    expect(ops.fetchEpisodeVideos).not.toHaveBeenCalled();
    await mountDetails();
  });

  it("Cancel while it starts plays nothing, and stops an mpv whose start was already on its way", async () => {
    ops.stopPlayback.mockResolvedValue(null);
    await mountDetails();

    // still fetching the videos: nothing is played
    let videosDone!: (v: unknown) => void;
    const hosters = await ops.fetchEpisodeVideos();
    ops.fetchEpisodeVideos.mockImplementationOnce(() => new Promise((r) => (videosDone = r)));
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    expect(toast().find('[data-testid="next-episode-countdown"]').text()).toBe("Starting…");
    await toast().find('[data-action="cancel-next"]').trigger("click");
    videosDone(hosters);
    await flushPromises();
    expect(ops.playEpisode).not.toHaveBeenCalled();
    expect(ops.stopPlayback).not.toHaveBeenCalled();

    // playEpisode already sent: once it returns, mpv is stopped
    let playDone!: (v: unknown) => void;
    ops.playEpisode.mockImplementationOnce(() => new Promise((r) => (playDone = r)));
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenCalledTimes(1);
    await toast().find('[data-action="cancel-next"]').trigger("click");
    expect(toast().exists()).toBe(false);
    expect(ops.stopPlayback).not.toHaveBeenCalled();
    playDone(session(1002));
    await flushPromises();
    expect(ops.stopPlayback).toHaveBeenCalledTimes(1);
  });

  it("only offers it with auto-play off, and ignores another anime's end or a last episode", async () => {
    usePlayerPrefsStore().autoPlayNext = false;
    await mountDetails();
    ended();
    await flushPromises();
    expect(toast().find('[data-testid="next-episode-countdown"]').text()).toBe("Auto-play is off");
    vi.advanceTimersByTime(30_000);
    await flushPromises();
    expect(ops.playEpisode).not.toHaveBeenCalled();
    await toast().find('[data-action="cancel-next"]').trigger("click");

    usePlaybackStore().apply(
      makePlaybackEvent("ENDED", {
        session: makePlaybackSession({ animeId: 99, episodeId: 5001 }),
        nextEpisodeId: 5002,
      }),
    );
    ended(1003, null);
    await flushPromises();
    expect(toast().exists()).toBe(false);
  });

  it("opens the picker for the next episode when it can't auto-play", async () => {
    ops.playEpisode.mockRejectedValueOnce(
      new ApiError({ code: "PLAYER_NOT_FOUND", message: "mpv was not found", kind: "graphql" }),
    );
    await mountDetails();
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    await flushPromises();
    const picker = document.body.querySelector('[data-testid="video-picker"]')!;
    expect(picker.querySelector(".dialog-title")!.textContent).toBe("Episode 2");
    expect(picker.querySelector('[data-testid="picker-failure"]')!.textContent).toContain(
      "Couldn't play the next episode",
    );
    expect(toast().exists()).toBe(false);
  });

  it("shows Retrying… on the row while the videos are fetched again", async () => {
    await mountDetails();
    usePlaybackStore().apply(
      makePlaybackEvent("RETRYING", {
        session: makePlaybackSession({ episodeId: 1003, state: "EXITED" }),
        message: "mpv could not play the video: loading failed (HTTP 403)",
        httpStatus: 403,
      }),
    );
    await flushPromises();
    const row = wrapper.findAll('[data-testid="item-row"]')[0]!;
    expect(row.find('[data-testid="item-playing"]').text()).toBe("Retrying…");
  });

  it("reopens the picker with the error and the next video when a video fails after the refetch", async () => {
    await mountDetails();
    usePlaybackStore().apply(
      makePlaybackEvent("ERROR", {
        session: makePlaybackSession({ episodeId: 1003, state: "EXITED", exitCode: 2 }),
        message: "mpv could not play the video: loading failed (HTTP 403)",
        httpStatus: 403,
        retried: true,
        failedVideo: { hosterName: "Main", videoTitle: "1080p", resolution: 1080 },
        nextVideo: { hosterName: "Backup", videoTitle: "480p", resolution: 720 },
      }),
    );
    await flushPromises();
    const picker = document.body.querySelector('[data-testid="video-picker"]')!;
    expect(picker.querySelector(".dialog-title")!.textContent).toBe("Episode 3");
    const failure = picker.querySelector('[data-testid="picker-failure"]')!.textContent!;
    expect(failure).toContain("Couldn't play 1080p");
    expect(failure).toContain("HTTP 403");
    expect(picker.querySelector('[data-testid="picker-instead"]')!.textContent).toContain(
      "480p is selected instead",
    );
    const checked = [...picker.querySelectorAll<HTMLElement>('[data-testid="picker-video"]')].find(
      (l) => l.querySelector<HTMLInputElement>("input")!.checked,
    );
    expect(checked!.textContent).toContain("480p");
    picker.querySelector<HTMLButtonElement>('[data-action="confirm-play"]')!.click();
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenCalledWith({
      episodeId: 1003,
      hosterIndex: 1,
      videoIndex: 0,
      startSeconds: null,
    });
  });
});

describe("AnimeDetailsView: the player settings (Phase 4 Stage 5)", () => {
  const toast = () => wrapper.find('[data-testid="next-episode"]');
  const ended = () =>
    usePlaybackStore().apply(
      makePlaybackEvent("ENDED", {
        session: makePlaybackSession({ episodeId: 1001, state: "EXITED" }),
        nextEpisodeId: 1002,
        reason: "eof",
      }),
    );
  const backup = (matchesPreference: boolean) =>
    hoster({
      index: 1,
      sourceIndex: 1,
      name: "Backup",
      videos: [{ ...hoster().videos[0]!, videoTitle: "480p", resolution: 480, matchesPreference }],
    });

  beforeEach(() => {
    ops.playEpisode.mockImplementation(async (r: { episodeId: number }) => session(r.episodeId));
  });

  it("auto-plays the preference's pick without a remembered choice, and the remembered one over it", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([hoster(), backup(true)]);
    await mountDetails();
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    await flushPromises();
    // not the source's pick (Main 1080p): the video the backend marks as the preference's
    expect(ops.playEpisode).toHaveBeenLastCalledWith(
      expect.objectContaining({ episodeId: 1002, hosterIndex: 1, videoIndex: 0 }),
    );

    useVideoChoicesStore().remember(animegg.id, {
      hosterName: "Main",
      videoTitle: "720p",
      resolution: 720,
    });
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenLastCalledWith(
      expect.objectContaining({ episodeId: 1002, hosterIndex: 0, videoIndex: 0 }),
    );
  });

  it("resumes below the backend's watched threshold, not the default one", async () => {
    ops.fetchPlayerSettings.mockResolvedValue({
      ...DEFAULT_PLAYER_SETTINGS,
      watchedThreshold: 0.95,
    });
    ops.fetchEpisodeVideos.mockResolvedValue([hoster()]);
    // 90 %: past the default 85 %, below 95 %
    episodes = makeEpisodes(3).map((e) =>
      e.id === 1002 ? { ...e, lastSecondSeen: "1350000", totalSeconds: "1500000" } : e,
    );
    await mountDetails();
    ended();
    await flushPromises();
    await toast().find('[data-action="play-next"]').trigger("click");
    await flushPromises();
    expect(ops.playEpisode).toHaveBeenCalledWith(
      expect.objectContaining({ episodeId: 1002, startSeconds: 1350 }),
    );
  });
});
