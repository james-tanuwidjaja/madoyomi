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
import { makeAnime, makeEpisodes, makePage, makeSource } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchAnime: vi.fn(),
  fetchEpisodes: vi.fn(),
  fetchSeasons: vi.fn(),
  refreshAnime: vi.fn(),
  setInLibrary: vi.fn(),
  fetchEpisodeVideos: vi.fn(),
  playEpisode: vi.fn(),
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

  it("plays an episode: its first ready hoster's preferred video, with the row busy until mpv starts", async () => {
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
    expect(ops.playEpisode).toHaveBeenCalledWith({
      episodeId: 1003,
      hosterIndex: 1,
      videoIndex: 1,
    });
    expect(play.text()).toBe("Starting…");
    expect(play.attributes("disabled")).toBeDefined();
    // the other rows stay playable
    expect(wrapper.findAll('[data-action="play"]')[1]!.attributes("disabled")).toBeUndefined();

    started(session(1003));
    await flushPromises();
    expect(play.text()).toBe("Play");
    expect(wrapper.find('[data-testid="play-error"]').exists()).toBe(false);
  });

  it("shows why an episode can't play: the failed hoster's error, or mpv's", async () => {
    ops.fetchEpisodeVideos.mockResolvedValue([
      hoster({
        name: "Down",
        state: "ERROR",
        videos: [],
        errorCode: "SOURCE_FAILED",
        errorMessage: "HTTP error 503",
      }),
    ]);
    await mountDetails();

    await wrapper.find('[data-action="play"]').trigger("click");
    await flushPromises();
    expect(ops.playEpisode).not.toHaveBeenCalled();
    const notice = wrapper.find('[data-testid="play-error"]');
    expect(notice.text()).toContain("Couldn't play the episode");
    expect(notice.text()).toContain("SOURCE_FAILED");
    expect(notice.text()).toContain("Down: HTTP error 503");

    ops.fetchEpisodeVideos.mockResolvedValue([hoster()]);
    ops.playEpisode.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "PLAYER_NOT_FOUND", message: "mpv was not found" }),
    );
    await wrapper.find('[data-action="play"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="play-error"]').text()).toContain("mpv wasn't found");
  });
});
