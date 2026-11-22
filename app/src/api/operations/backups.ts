import { api } from "../client";
import type {
  BackupFile,
  BackupFormat,
  BackupReport,
  BackupSettings,
  LongString,
  RestoreRequest,
  RestoreStatus,
} from "../types";

/*
 * Backups, the backend's `.madoyomi-backup` files in `<data dir>/backups`: the list, Back up now, the
 * schedule (Settings → Backup), deleting one, what a file holds checked against this install (the trust prompt's
 * content), the restore with merge or replace, and its status (a query and a subscription). A file the user picks is
 * sent to `POST /api/v1/madoyomi/backups` and kept in the folder first, so every restore names a file there.
 * Upstream's manga-only `createBackup`/`restoreBackup` are not used.
 */

const BACKUP_FILE_FIELDS = /* GraphQL */ `
  name
  path
  size
  modifiedAt
  automatic
  format
`;

const BACKUP_SETTINGS_FIELDS = /* GraphQL */ `
  intervalHours
  keep
  nextScheduledAt
  folder
  lastError
`;

const RESTORE_STATUS_FIELDS = /* GraphQL */ `
  state
  name
  mode
  step
  current
  total
  title
  problems
  startedAt
  finishedAt
`;

export const MADOYOMI_BACKUPS_QUERY = /* GraphQL */ `
  query MadoyomiBackups {
    madoyomiBackups {
      ${BACKUP_FILE_FIELDS}
    }
  }
`;

export const MADOYOMI_BACKUP_SETTINGS_QUERY = /* GraphQL */ `
  query MadoyomiBackupSettings {
    madoyomiBackupSettings {
      ${BACKUP_SETTINGS_FIELDS}
    }
  }
`;

export const SET_MADOYOMI_BACKUP_SETTINGS_MUTATION = /* GraphQL */ `
  mutation SetMadoyomiBackupSettings($intervalHours: Int, $keep: Int) {
    setMadoyomiBackupSettings(input: { intervalHours: $intervalHours, keep: $keep }) {
      settings {
        ${BACKUP_SETTINGS_FIELDS}
      }
    }
  }
`;

export const CREATE_MADOYOMI_BACKUP_MUTATION = /* GraphQL */ `
  mutation CreateMadoyomiBackup {
    createMadoyomiBackup(input: {}) {
      backup {
        ${BACKUP_FILE_FIELDS}
      }
    }
  }
`;

export const DELETE_MADOYOMI_BACKUP_MUTATION = /* GraphQL */ `
  mutation DeleteMadoyomiBackup($name: String!) {
    deleteMadoyomiBackup(input: { name: $name }) {
      backups {
        ${BACKUP_FILE_FIELDS}
      }
    }
  }
`;

export const VALIDATE_MADOYOMI_BACKUP_QUERY = /* GraphQL */ `
  query ValidateMadoyomiBackup($name: String!) {
    validateMadoyomiBackup(name: $name) {
      name
      format
      createdAt
      appVersion
      automatic
      anime
      seasons
      episodes
      watchedEpisodes
      animeHistory
      animeCategories
      manga
      chapters
      readChapters
      mangaHistory
      mangaCategories
      repos {
        indexUrl
        kind
        name
        signingKey
        added
      }
      certificates {
        hash
        trusted
      }
      extensions {
        pkgName
        kind
        name
        versionName
        repoUrl
        signatureHash
        installed
        inRepo
        trustedAfterRestore
      }
      missingSources {
        id
        kind
        name
        entries
        extension
      }
      settingsFiles
      sourcePreferences
    }
  }
`;

export const RESTORE_MADOYOMI_BACKUP_MUTATION = /* GraphQL */ `
  mutation RestoreMadoyomiBackup(
    $name: String!
    $mode: RestoreMode
    $library: Boolean
    $settings: Boolean
    $repos: [String!]
    $certificates: [String!]
    $extensions: [String!]
  ) {
    restoreMadoyomiBackup(
      input: {
        name: $name
        mode: $mode
        library: $library
        settings: $settings
        repos: $repos
        certificates: $certificates
        extensions: $extensions
      }
    ) {
      status {
        ${RESTORE_STATUS_FIELDS}
      }
    }
  }
`;

export const MADOYOMI_RESTORE_STATUS_QUERY = /* GraphQL */ `
  query MadoyomiRestoreStatus {
    madoyomiRestoreStatus {
      ${RESTORE_STATUS_FIELDS}
    }
  }
`;

/** The restore's status (the WS "backup.restore" event): at once on subscribing, then on every change. */
export const MADOYOMI_RESTORE_STATUS_SUBSCRIPTION = /* GraphQL */ `
  subscription MadoyomiRestoreStatusChanged {
    madoyomiRestoreStatusChanged {
      ${RESTORE_STATUS_FIELDS}
    }
  }
`;

export interface BackupFileNode {
  name: string;
  path: string;
  size: LongString;
  modifiedAt: LongString;
  automatic: boolean;
  format: BackupFormat;
}

export interface BackupSettingsNode {
  intervalHours: number;
  keep: number;
  nextScheduledAt: LongString | null;
  folder: string;
  lastError: string | null;
}

export interface RestoreStatusNode extends Omit<RestoreStatus, "startedAt" | "finishedAt"> {
  startedAt: LongString;
  finishedAt: LongString;
}

export type BackupReportNode = Omit<BackupReport, "createdAt"> & { createdAt: LongString | null };

export const toBackupFile = (n: BackupFileNode): BackupFile => ({
  ...n,
  size: Number(n.size),
  modifiedAt: Number(n.modifiedAt),
});

export const toBackupSettings = (n: BackupSettingsNode): BackupSettings => ({
  ...n,
  nextScheduledAt: n.nextScheduledAt === null ? null : Number(n.nextScheduledAt),
});

export const toRestoreStatus = (n: RestoreStatusNode): RestoreStatus => ({
  ...n,
  startedAt: Number(n.startedAt),
  finishedAt: Number(n.finishedAt),
});

export async function fetchBackups(signal?: AbortSignal): Promise<BackupFile[]> {
  return (
    await api.gql<{ madoyomiBackups: BackupFileNode[] }>(MADOYOMI_BACKUPS_QUERY, undefined, {
      signal,
    })
  ).madoyomiBackups.map(toBackupFile);
}

export async function fetchBackupSettings(signal?: AbortSignal): Promise<BackupSettings> {
  return toBackupSettings(
    (
      await api.gql<{ madoyomiBackupSettings: BackupSettingsNode }>(
        MADOYOMI_BACKUP_SETTINGS_QUERY,
        undefined,
        { signal },
      )
    ).madoyomiBackupSettings,
  );
}

export async function setBackupSettings(
  patch: Partial<Pick<BackupSettings, "intervalHours" | "keep">>,
): Promise<BackupSettings> {
  return toBackupSettings(
    (
      await api.gql<{ setMadoyomiBackupSettings: { settings: BackupSettingsNode } }>(
        SET_MADOYOMI_BACKUP_SETTINGS_MUTATION,
        { intervalHours: patch.intervalHours ?? null, keep: patch.keep ?? null },
      )
    ).setMadoyomiBackupSettings.settings,
  );
}

/** Backs up everything now; the new file. */
export async function createBackup(): Promise<BackupFile> {
  return toBackupFile(
    (
      await api.gql<{ createMadoyomiBackup: { backup: BackupFileNode } }>(
        CREATE_MADOYOMI_BACKUP_MUTATION,
      )
    ).createMadoyomiBackup.backup,
  );
}

/** Deletes a backup file; the list afterwards. */
export async function deleteBackup(name: string): Promise<BackupFile[]> {
  return (
    await api.gql<{ deleteMadoyomiBackup: { backups: BackupFileNode[] } }>(
      DELETE_MADOYOMI_BACKUP_MUTATION,
      { name },
    )
  ).deleteMadoyomiBackup.backups.map(toBackupFile);
}

/** What the file holds, checked against this install; `BAD_REQUEST` when it isn't a backup. */
export async function validateBackup(name: string, signal?: AbortSignal): Promise<BackupReport> {
  const n = (
    await api.gql<{ validateMadoyomiBackup: BackupReportNode }>(
      VALIDATE_MADOYOMI_BACKUP_QUERY,
      { name },
      { signal },
    )
  ).validateMadoyomiBackup;
  return { ...n, createdAt: n.createdAt === null ? null : Number(n.createdAt) };
}

/** Starts the restore in the background; its first status. */
export async function restoreBackup(request: RestoreRequest): Promise<RestoreStatus> {
  return toRestoreStatus(
    (
      await api.gql<{ restoreMadoyomiBackup: { status: RestoreStatusNode } }>(
        RESTORE_MADOYOMI_BACKUP_MUTATION,
        { ...request },
      )
    ).restoreMadoyomiBackup.status,
  );
}

export async function fetchRestoreStatus(signal?: AbortSignal): Promise<RestoreStatus> {
  return toRestoreStatus(
    (
      await api.gql<{ madoyomiRestoreStatus: RestoreStatusNode }>(
        MADOYOMI_RESTORE_STATUS_QUERY,
        undefined,
        { signal },
      )
    ).madoyomiRestoreStatus,
  );
}

export const BACKUP_UPLOAD_PATH = "/api/v1/madoyomi/backups";

/**
 * Sends a file the user picked to the backend, which checks that it is a backup (`BAD_REQUEST` if not) and keeps it in
 * the backups folder under its name ("name (2)" when taken); the name it got.
 */
export async function uploadBackup(file: Blob, name: string): Promise<string> {
  const blob = await api.fetchAsset(`${BACKUP_UPLOAD_PATH}?name=${encodeURIComponent(name)}`, {
    method: "POST",
    body: file,
  });
  const body = JSON.parse(await blob.text()) as { name: string };
  return body.name;
}

/** The bytes of a backup file (`GET /api/v1/madoyomi/backups/<name>`). */
export async function downloadBackup(name: string): Promise<Blob> {
  return api.fetchAsset(`${BACKUP_UPLOAD_PATH}/${encodeURIComponent(name)}`, { reload: true });
}
