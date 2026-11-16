import { api } from "../client";
import type {
  CompletedDownloadsPage,
  DownloadItem,
  DownloadItemState,
  DownloadQueue,
  DownloadSettings,
  DownloadTool,
  ExtensionKind,
  LongString,
  MediaKind,
  PlaybackSession,
} from "../types";
import { PLAYBACK_SESSION_FIELDS } from "./playback";
import { extensionKindOf, mediaKindOf } from "../types";

/*
 * The download queue (Phase 6 Stage 4), the backend's for both kinds: the queue (`downloadQueue`; the
 * `downloadsChanged` subscription is in ./subscriptions), the completed list of a kind (the last finished first, paged
 * by offset), queueing chapters or episodes, pause, resume, retry, cancel and reorder, deleting downloads (the files),
 * clearing the completed list, and Settings → Downloads. Stage 5: the tools' check (`downloadTools`) and playing a
 * downloaded episode (`playDownloadedEpisode`).
 */

export const DOWNLOAD_ITEM_FIELDS = /* GraphQL */ `
  fragment DownloadItemFields on DownloadItemType {
    id
    kind
    state
    entryId
    itemId
    entryTitle
    itemName
    progressDone
    progressTotal
    progressUnit
    bytes
    totalBytes
    tries
    error
    addedAt
    finishedAt
  }
`;

export const DOWNLOAD_QUEUE_FIELDS = /* GraphQL */ `
  fragment DownloadQueueFields on DownloadQueueType {
    items {
      ...DownloadItemFields
    }
    mangaDone
    animeDone
    lastFinishedAt
  }
  ${DOWNLOAD_ITEM_FIELDS}
`;

export const DOWNLOAD_QUEUE_QUERY = /* GraphQL */ `
  query DownloadQueue {
    downloadQueue {
      ...DownloadQueueFields
    }
  }
  ${DOWNLOAD_QUEUE_FIELDS}
`;

export const COMPLETED_DOWNLOADS_QUERY = /* GraphQL */ `
  query CompletedDownloads($kind: ExtensionKind, $limit: Int, $offset: Int) {
    completedDownloads(kind: $kind, limit: $limit, offset: $offset) {
      hasMore
      items {
        ...DownloadItemFields
      }
    }
  }
  ${DOWNLOAD_ITEM_FIELDS}
`;

export const ENQUEUE_DOWNLOADS_MUTATION = /* GraphQL */ `
  mutation EnqueueDownloads($kind: ExtensionKind!, $itemIds: [Int!]!) {
    enqueueDownloads(input: { kind: $kind, itemIds: $itemIds }) {
      queued
      downloadQueue {
        ...DownloadQueueFields
      }
    }
  }
  ${DOWNLOAD_QUEUE_FIELDS}
`;

const itemsMutation = (name: string, op: string) => /* GraphQL */ `
  mutation ${name}($kind: ExtensionKind, $ids: [Int!]) {
    ${op}(input: { kind: $kind, ids: $ids }) {
      changed
      downloadQueue {
        ...DownloadQueueFields
      }
    }
  }
  ${DOWNLOAD_QUEUE_FIELDS}
`;

export const PAUSE_DOWNLOADS_MUTATION = itemsMutation("PauseDownloads", "pauseDownloads");
export const RESUME_DOWNLOADS_MUTATION = itemsMutation("ResumeDownloads", "resumeDownloads");
export const RETRY_DOWNLOADS_MUTATION = itemsMutation("RetryDownloads", "retryDownloads");
export const CANCEL_DOWNLOADS_MUTATION = itemsMutation("CancelDownloads", "cancelDownloads");

export const REORDER_DOWNLOAD_MUTATION = /* GraphQL */ `
  mutation ReorderDownload($id: Int!, $to: Int!) {
    reorderDownload(input: { id: $id, to: $to }) {
      downloadQueue {
        ...DownloadQueueFields
      }
    }
  }
  ${DOWNLOAD_QUEUE_FIELDS}
`;

export const DELETE_DOWNLOADS_MUTATION = /* GraphQL */ `
  mutation DeleteDownloads($kind: ExtensionKind!, $itemIds: [Int!]!) {
    deleteDownloads(input: { kind: $kind, itemIds: $itemIds }) {
      deleted
      downloadQueue {
        ...DownloadQueueFields
      }
    }
  }
  ${DOWNLOAD_QUEUE_FIELDS}
`;

export const CLEAR_COMPLETED_DOWNLOADS_MUTATION = /* GraphQL */ `
  mutation ClearCompletedDownloads($kind: ExtensionKind) {
    clearCompletedDownloads(input: { kind: $kind }) {
      cleared
    }
  }
`;

const DOWNLOAD_SETTINGS_FIELDS = `
  maxParallel
  perSource
  autoDownload
  path
  ffmpegPath
  ytDlpPath
`;

export const DOWNLOAD_SETTINGS_QUERY = /* GraphQL */ `
  query DownloadSettings {
    downloadSettings {
      ${DOWNLOAD_SETTINGS_FIELDS}
    }
  }
`;

export const SET_DOWNLOAD_SETTINGS_MUTATION = /* GraphQL */ `
  mutation SetDownloadSettings(
    $maxParallel: Int
    $perSource: Int
    $autoDownload: Boolean
    $ffmpegPath: String
    $ytDlpPath: String
  ) {
    setDownloadSettings(
      input: {
        maxParallel: $maxParallel
        perSource: $perSource
        autoDownload: $autoDownload
        ffmpegPath: $ffmpegPath
        ytDlpPath: $ytDlpPath
      }
    ) {
      settings {
        ${DOWNLOAD_SETTINGS_FIELDS}
      }
    }
  }
`;

export const DOWNLOAD_TOOLS_QUERY = /* GraphQL */ `
  query DownloadTools($ffmpegPath: String, $ytDlpPath: String) {
    downloadTools(ffmpegPath: $ffmpegPath, ytDlpPath: $ytDlpPath) {
      tool
      ok
      path
      origin
      version
      message
    }
  }
`;

export const PLAY_DOWNLOADED_EPISODE_MUTATION = /* GraphQL */ `
  mutation PlayDownloadedEpisode($episodeId: Int!, $startSeconds: Float, $resume: Boolean) {
    playDownloadedEpisode(
      input: { episodeId: $episodeId, startSeconds: $startSeconds, resume: $resume }
    ) {
      session {
        ...PlaybackSessionFields
      }
    }
  }
  ${PLAYBACK_SESSION_FIELDS}
`;

/** A download item as the backend sends it: the kind by its GraphQL name, the 64-bit numbers as strings. */
export interface DownloadItemNode {
  id: number;
  kind: ExtensionKind;
  state: DownloadItemState;
  entryId: number;
  itemId: number;
  entryTitle: string;
  itemName: string;
  progressDone: number;
  progressTotal: number;
  progressUnit: DownloadItem["progressUnit"];
  bytes: LongString;
  totalBytes: LongString;
  tries: number;
  error: string | null;
  addedAt: LongString;
  finishedAt: LongString;
}

export interface DownloadQueueNode {
  items: DownloadItemNode[];
  mangaDone: number;
  animeDone: number;
  lastFinishedAt: LongString;
}

export function toDownloadItem(n: DownloadItemNode): DownloadItem {
  return {
    ...n,
    kind: mediaKindOf(n.kind),
    bytes: Number(n.bytes),
    totalBytes: Number(n.totalBytes),
    addedAt: Number(n.addedAt),
    finishedAt: Number(n.finishedAt),
  };
}

export function toDownloadQueue(n: DownloadQueueNode): DownloadQueue {
  return {
    items: n.items.map(toDownloadItem),
    mangaDone: n.mangaDone,
    animeDone: n.animeDone,
    lastFinishedAt: Number(n.lastFinishedAt),
  };
}

export async function fetchDownloadQueue(signal?: AbortSignal): Promise<DownloadQueue> {
  return toDownloadQueue(
    (
      await api.gql<{ downloadQueue: DownloadQueueNode }>(DOWNLOAD_QUEUE_QUERY, undefined, {
        signal,
      })
    ).downloadQueue,
  );
}

/** The page of `kind`'s completed downloads that starts at `offset`, the last finished first. */
export async function fetchCompletedDownloads(
  kind: MediaKind,
  offset: number,
  limit: number,
  signal?: AbortSignal,
): Promise<CompletedDownloadsPage> {
  const { completedDownloads: page } = await api.gql<{
    completedDownloads: { hasMore: boolean; items: DownloadItemNode[] };
  }>(COMPLETED_DOWNLOADS_QUERY, { kind: extensionKindOf(kind), limit, offset }, { signal });
  return { hasMore: page.hasMore, items: page.items.map(toDownloadItem) };
}

/** Queues chapters (or episodes) after what is queued, in reading order; returns how many and the queue. */
export async function enqueueDownloads(
  kind: MediaKind,
  itemIds: number[],
): Promise<{ queued: number; queue: DownloadQueue }> {
  const { enqueueDownloads: r } = await api.gql<{
    enqueueDownloads: { queued: number; downloadQueue: DownloadQueueNode };
  }>(ENQUEUE_DOWNLOADS_MUTATION, { kind: extensionKindOf(kind), itemIds });
  return { queued: r.queued, queue: toDownloadQueue(r.downloadQueue) };
}

export type DownloadAction = "pause" | "resume" | "retry" | "cancel";

const ACTIONS: Record<DownloadAction, { doc: string; field: string }> = {
  pause: { doc: PAUSE_DOWNLOADS_MUTATION, field: "pauseDownloads" },
  resume: { doc: RESUME_DOWNLOADS_MUTATION, field: "resumeDownloads" },
  retry: { doc: RETRY_DOWNLOADS_MUTATION, field: "retryDownloads" },
  cancel: { doc: CANCEL_DOWNLOADS_MUTATION, field: "cancelDownloads" },
};

/**
 * Pauses, resumes, retries or cancels the downloads `ids` (download ids), or with no ids every one of `kind` that
 * can change so. Returns how many changed and the queue.
 */
export async function changeDownloads(
  action: DownloadAction,
  target: { kind?: MediaKind; ids?: number[] },
): Promise<{ changed: number; queue: DownloadQueue }> {
  const { doc, field } = ACTIONS[action];
  const data = await api.gql<Record<string, { changed: number; downloadQueue: DownloadQueueNode }>>(
    doc,
    {
      kind: target.kind ? extensionKindOf(target.kind) : null,
      ids: target.ids ?? null,
    },
  );
  const r = data[field]!;
  return { changed: r.changed, queue: toDownloadQueue(r.downloadQueue) };
}

/** Moves a download to place `to` (from 0) among its kind's unfinished items. */
export async function reorderDownload(id: number, to: number): Promise<DownloadQueue> {
  return toDownloadQueue(
    (
      await api.gql<{ reorderDownload: { downloadQueue: DownloadQueueNode } }>(
        REORDER_DOWNLOAD_MUTATION,
        { id, to },
      )
    ).reorderDownload.downloadQueue,
  );
}

/** Deletes the downloads of chapters (or episodes): queued ones are cancelled, downloaded files deleted. */
export async function deleteDownloads(
  kind: MediaKind,
  itemIds: number[],
): Promise<{ deleted: number; queue: DownloadQueue }> {
  const { deleteDownloads: r } = await api.gql<{
    deleteDownloads: { deleted: number; downloadQueue: DownloadQueueNode };
  }>(DELETE_DOWNLOADS_MUTATION, { kind: extensionKindOf(kind), itemIds });
  return { deleted: r.deleted, queue: toDownloadQueue(r.downloadQueue) };
}

/** Empties `kind`'s completed list; the files stay. */
export async function clearCompletedDownloads(kind: MediaKind): Promise<number> {
  return (
    await api.gql<{ clearCompletedDownloads: { cleared: number } }>(
      CLEAR_COMPLETED_DOWNLOADS_MUTATION,
      { kind: extensionKindOf(kind) },
    )
  ).clearCompletedDownloads.cleared;
}

export async function fetchDownloadSettings(signal?: AbortSignal): Promise<DownloadSettings> {
  return (
    await api.gql<{ downloadSettings: DownloadSettings }>(DOWNLOAD_SETTINGS_QUERY, undefined, {
      signal,
    })
  ).downloadSettings;
}

/** Changes the fields given; new limits apply at once. */
export async function setDownloadSettings(
  patch: Partial<Omit<DownloadSettings, "path">>,
): Promise<DownloadSettings> {
  return (
    await api.gql<{ setDownloadSettings: { settings: DownloadSettings } }>(
      SET_DOWNLOAD_SETTINGS_MUTATION,
      patch,
    )
  ).setDownloadSettings.settings;
}

/** ffmpeg and yt-dlp as the backend would use them now; a path given is tried in place of the saved one. */
export async function fetchDownloadTools(
  paths: { ffmpegPath?: string; ytDlpPath?: string } = {},
  signal?: AbortSignal,
): Promise<DownloadTool[]> {
  return (
    await api.gql<{ downloadTools: DownloadTool[] }>(
      DOWNLOAD_TOOLS_QUERY,
      { ffmpegPath: paths.ffmpegPath ?? null, ytDlpPath: paths.ytDlpPath ?? null },
      { signal },
    )
  ).downloadTools;
}

/**
 * Plays a downloaded episode's file in mpv (the same session as `playEpisode`), from `startSeconds`, or with `resume`
 * (and no start) from where it stopped, as the backend reckons it.
 */
export async function playDownloadedEpisode(
  episodeId: number,
  startSeconds?: number | null,
  resume = false,
): Promise<PlaybackSession> {
  return (
    await api.gql<{ playDownloadedEpisode: { session: PlaybackSession } }>(
      PLAY_DOWNLOADED_EPISODE_MUTATION,
      { episodeId, startSeconds: startSeconds ?? null, resume },
    )
  ).playDownloadedEpisode.session;
}
