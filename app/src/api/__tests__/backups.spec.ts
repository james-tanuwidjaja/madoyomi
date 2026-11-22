import { afterEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ gql: vi.fn(), fetchAsset: vi.fn() }));

vi.mock("../client", async (importOriginal) => {
  const real = await importOriginal<typeof import("../client")>();
  return { ...real, api: { ...real.api, gql: client.gql, fetchAsset: client.fetchAsset } };
});

import {
  createBackup,
  downloadBackup,
  fetchBackups,
  fetchBackupSettings,
  RESTORE_MADOYOMI_BACKUP_MUTATION,
  restoreBackup,
  SET_MADOYOMI_BACKUP_SETTINGS_MUTATION,
  setBackupSettings,
  uploadBackup,
  validateBackup,
} from "../operations/backups";
import {
  animeSummary,
  backupFileText,
  restoreDefaults,
  restoreStatusText,
  settingsSummary,
} from "../../utils/backups";
import type { BackupReport, RestoreStatus } from "../types";

/* Backups' calls over a mocked client, and Settings → Backup's wording. */
afterEach(() => vi.clearAllMocks());

const fileNode = {
  name: "Madoyomi_auto_2026-11-16_21-00-00.madoyomi-backup",
  path: "D:\\data\\backups\\Madoyomi_auto_2026-11-16_21-00-00.madoyomi-backup",
  size: "2048",
  modifiedAt: "1700000000000",
  automatic: true,
  format: "MADOYOMI",
};

describe("backup operations", () => {
  it("reads the files and the schedule with numbers as the app uses them", async () => {
    client.gql.mockResolvedValueOnce({ madoyomiBackups: [fileNode] });
    expect(await fetchBackups()).toEqual([{ ...fileNode, size: 2048, modifiedAt: 1700000000000 }]);
    client.gql.mockResolvedValueOnce({
      madoyomiBackupSettings: {
        intervalHours: 24,
        keep: 7,
        nextScheduledAt: "1700086400000",
        folder: "D:\\b",
        lastError: null,
      },
    });
    expect((await fetchBackupSettings()).nextScheduledAt).toBe(1700086400000);
    client.gql.mockResolvedValueOnce({ createMadoyomiBackup: { backup: fileNode } });
    expect((await createBackup()).size).toBe(2048);
  });

  it("sends only the fields given to the schedule, and the whole request to a restore", async () => {
    client.gql.mockResolvedValueOnce({
      setMadoyomiBackupSettings: {
        settings: {
          intervalHours: 0,
          keep: 3,
          nextScheduledAt: null,
          folder: "D:\\b",
          lastError: null,
        },
      },
    });
    await setBackupSettings({ keep: 3 });
    expect(client.gql).toHaveBeenLastCalledWith(SET_MADOYOMI_BACKUP_SETTINGS_MUTATION, {
      intervalHours: null,
      keep: 3,
    });
    const status = {
      state: "RUNNING",
      name: "x.madoyomi-backup",
      mode: "REPLACE",
      step: null,
      current: 0,
      total: 0,
      title: null,
      problems: [],
      startedAt: "5",
      finishedAt: "0",
    };
    client.gql.mockResolvedValueOnce({ restoreMadoyomiBackup: { status } });
    const request = {
      name: "x.madoyomi-backup",
      mode: "REPLACE" as const,
      library: true,
      settings: false,
      repos: ["https://r"],
      certificates: [],
      extensions: ["a.b"],
    };
    expect((await restoreBackup(request)).startedAt).toBe(5);
    expect(client.gql).toHaveBeenLastCalledWith(RESTORE_MADOYOMI_BACKUP_MUTATION, request);
  });

  it("uploads a picked file to the backend's route and gets its name there; downloads by name", async () => {
    client.fetchAsset.mockResolvedValueOnce(
      new Blob([JSON.stringify({ name: "my backup (2).madoyomi-backup" })]),
    );
    const file = new Blob(["bytes"]);
    expect(await uploadBackup(file, "my backup.madoyomi-backup")).toBe(
      "my backup (2).madoyomi-backup",
    );
    expect(client.fetchAsset).toHaveBeenLastCalledWith(
      "/api/v1/madoyomi/backups?name=my%20backup.madoyomi-backup",
      { method: "POST", body: file },
    );
    client.fetchAsset.mockResolvedValueOnce(new Blob(["x"]));
    await downloadBackup("a b.madoyomi-backup");
    expect(client.fetchAsset).toHaveBeenLastCalledWith(
      "/api/v1/madoyomi/backups/a%20b.madoyomi-backup",
      {
        reload: true,
      },
    );
  });

  it("validates with the time as a number", async () => {
    client.gql.mockResolvedValueOnce({
      validateMadoyomiBackup: { name: "n", createdAt: "1700000000000" },
    });
    expect((await validateBackup("n")).createdAt).toBe(1700000000000);
  });
});

describe("backup wording", () => {
  const r = {
    anime: 1,
    seasons: 0,
    episodes: 1,
    watchedEpisodes: 0,
    animeHistory: 0,
    animeCategories: 1,
    repos: [
      { indexUrl: "a", added: false },
      { indexUrl: "b", added: true },
    ],
    certificates: [{ hash: "h", trusted: true }],
    extensions: [
      { pkgName: "p", installed: false, inRepo: true },
      { pkgName: "q", installed: false, inRepo: false },
      { pkgName: "s", installed: true, inRepo: true },
    ],
  } as unknown as BackupReport;

  it("words a report and picks the trust prompt's defaults", () => {
    expect(animeSummary(r)).toBe("1 entry, 1 episode (0 watched), 0 in history, 1 category");
    expect(restoreDefaults(r)).toEqual({ repos: ["a"], certificates: [], extensions: ["p"] });
  });

  it("words the settings line with singulars, as the exit run asked", () => {
    const one = {
      settingsFiles: ["library-update.json"],
      sourcePreferences: 1,
    } as unknown as BackupReport;
    expect(settingsSummary(one)).toBe("1 settings file, 1 source's settings");
    const many = {
      settingsFiles: ["a.json", "b.json"],
      sourcePreferences: 0,
    } as unknown as BackupReport;
    expect(settingsSummary(many)).toBe("2 settings files, 0 sources' settings");
  });

  it("words a file and a restore's status", () => {
    const now = new Date(1700000000000);
    expect(
      backupFileText(
        { ...fileNode, size: 2048, modifiedAt: 1700000000000, format: "TACHIYOMI" },
        now,
      ),
    ).toMatch(/^\d{1,2}:\d{2} [AP]M · 2 KB · Mihon\/Aniyomi backup \(manga only\)$/);
    const s = {
      state: "RUNNING",
      step: "ANIME",
      current: 2,
      total: 5,
      title: "Oshi no Ko",
      problems: [],
    } as unknown as RestoreStatus;
    expect(restoreStatusText(s)).toBe("Restoring the anime: 2 of 5, Oshi no Ko");
    expect(restoreStatusText({ ...s, step: null })).toBe("Starting the restore…");
    expect(restoreStatusText({ ...s, state: "SUCCESS" })).toBe("Restored");
    expect(restoreStatusText({ ...s, state: "FAILURE" })).toBe("The restore failed");
  });
});
