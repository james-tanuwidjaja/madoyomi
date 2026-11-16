import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql } };
});

import {
  CANCEL_DOWNLOADS_MUTATION,
  changeDownloads,
  COMPLETED_DOWNLOADS_QUERY,
  DELETE_DOWNLOADS_MUTATION,
  deleteDownloads,
  DOWNLOAD_TOOLS_QUERY,
  fetchDownloadTools,
  PLAY_DOWNLOADED_EPISODE_MUTATION,
  playDownloadedEpisode,
  ENQUEUE_DOWNLOADS_MUTATION,
  enqueueDownloads,
  fetchCompletedDownloads,
  fetchDownloadQueue,
  PAUSE_DOWNLOADS_MUTATION,
  REORDER_DOWNLOAD_MUTATION,
  reorderDownload,
  SET_DOWNLOAD_SETTINGS_MUTATION,
  setDownloadSettings,
  type DownloadItemNode,
} from "../operations/downloads";

/* The download queue's calls (Phase 6 Stage 4) over a mocked client. */
afterEach(() => vi.clearAllMocks());

const node = (overrides: Partial<DownloadItemNode> = {}): DownloadItemNode => ({
  id: 7,
  kind: "MANGA",
  state: "DOWNLOADING",
  entryId: 51,
  itemId: 900,
  entryTitle: "Kingdom",
  itemName: "Chapter 892",
  progressDone: 4,
  progressTotal: 20,
  progressUnit: "PAGES",
  bytes: "123456",
  totalBytes: "0",
  tries: 0,
  error: null,
  addedAt: "1700000000000",
  finishedAt: "0",
  ...overrides,
});
const queueNode = { items: [node()], mangaDone: 3, animeDone: 0, lastFinishedAt: "1700000005000" };

describe("download operations", () => {
  it("reads the queue with the kind and the numbers as the app uses them", async () => {
    client.gql.mockResolvedValue({ downloadQueue: queueNode });
    const queue = await fetchDownloadQueue();
    expect(queue).toEqual({
      items: [
        {
          ...node(),
          kind: "manga",
          bytes: 123456,
          totalBytes: 0,
          addedAt: 1_700_000_000_000,
          finishedAt: 0,
        },
      ],
      mangaDone: 3,
      animeDone: 0,
      lastFinishedAt: 1_700_000_005_000,
    });
  });

  it("queues chapters, and changes, moves and deletes downloads", async () => {
    client.gql.mockResolvedValueOnce({ enqueueDownloads: { queued: 2, downloadQueue: queueNode } });
    expect((await enqueueDownloads("manga", [900, 901])).queued).toBe(2);
    expect(client.gql).toHaveBeenLastCalledWith(ENQUEUE_DOWNLOADS_MUTATION, {
      kind: "MANGA",
      itemIds: [900, 901],
    });

    client.gql.mockResolvedValueOnce({ pauseDownloads: { changed: 5, downloadQueue: queueNode } });
    expect((await changeDownloads("pause", { kind: "manga" })).changed).toBe(5);
    expect(client.gql).toHaveBeenLastCalledWith(PAUSE_DOWNLOADS_MUTATION, {
      kind: "MANGA",
      ids: null,
    });
    client.gql.mockResolvedValueOnce({ cancelDownloads: { changed: 1, downloadQueue: queueNode } });
    await changeDownloads("cancel", { ids: [7] });
    expect(client.gql).toHaveBeenLastCalledWith(CANCEL_DOWNLOADS_MUTATION, {
      kind: null,
      ids: [7],
    });

    client.gql.mockResolvedValueOnce({ reorderDownload: { downloadQueue: queueNode } });
    expect((await reorderDownload(7, 0)).items).toHaveLength(1);
    expect(client.gql).toHaveBeenLastCalledWith(REORDER_DOWNLOAD_MUTATION, { id: 7, to: 0 });

    client.gql.mockResolvedValueOnce({ deleteDownloads: { deleted: 1, downloadQueue: queueNode } });
    expect((await deleteDownloads("manga", [900])).deleted).toBe(1);
    expect(client.gql).toHaveBeenLastCalledWith(DELETE_DOWNLOADS_MUTATION, {
      kind: "MANGA",
      itemIds: [900],
    });
  });

  it("pages the completed list of a kind and saves settings", async () => {
    client.gql.mockResolvedValueOnce({
      completedDownloads: {
        hasMore: true,
        items: [node({ state: "DONE", finishedAt: "1700000009000" })],
      },
    });
    const page = await fetchCompletedDownloads("manga", 50, 50);
    expect(page.hasMore).toBe(true);
    expect(page.items[0]).toMatchObject({ state: "DONE", finishedAt: 1_700_000_009_000 });
    expect(client.gql).toHaveBeenLastCalledWith(
      COMPLETED_DOWNLOADS_QUERY,
      { kind: "MANGA", limit: 50, offset: 50 },
      { signal: undefined },
    );

    const settings = {
      maxParallel: 4,
      perSource: 1,
      autoDownload: true,
      path: "D:\\data\\downloads",
    };
    client.gql.mockResolvedValueOnce({ setDownloadSettings: { settings } });
    expect(await setDownloadSettings({ maxParallel: 4, autoDownload: true })).toEqual(settings);
    expect(client.gql).toHaveBeenLastCalledWith(SET_DOWNLOAD_SETTINGS_MUTATION, {
      maxParallel: 4,
      autoDownload: true,
    });
  });

  it("checks the download tools and plays a downloaded episode (Stage 5)", async () => {
    const tool = {
      tool: "FFMPEG",
      ok: true,
      path: "C:/ffmpeg.exe",
      origin: "PATH",
      version: "ffmpeg version 7",
      message: null,
    };
    client.gql.mockResolvedValueOnce({ downloadTools: [tool] });
    expect(await fetchDownloadTools()).toEqual([tool]);
    expect(client.gql).toHaveBeenLastCalledWith(
      DOWNLOAD_TOOLS_QUERY,
      { ffmpegPath: null, ytDlpPath: null },
      { signal: undefined },
    );
    client.gql.mockResolvedValueOnce({ downloadTools: [tool] });
    await fetchDownloadTools({ ffmpegPath: "D:/x/ffmpeg.exe" });
    expect(client.gql.mock.lastCall?.[1]).toEqual({
      ffmpegPath: "D:/x/ffmpeg.exe",
      ytDlpPath: null,
    });

    client.gql.mockResolvedValueOnce({ playDownloadedEpisode: { session: { id: "s1" } } });
    expect(await playDownloadedEpisode(81, 42)).toEqual({ id: "s1" });
    expect(client.gql).toHaveBeenLastCalledWith(PLAY_DOWNLOADED_EPISODE_MUTATION, {
      episodeId: 81,
      startSeconds: 42,
      resume: false,
    });
    client.gql.mockResolvedValueOnce({ playDownloadedEpisode: { session: { id: "s2" } } });
    await playDownloadedEpisode(81, null, true);
    expect(client.gql.mock.lastCall?.[1]).toEqual({
      episodeId: 81,
      startSeconds: null,
      resume: true,
    });
  });
});
