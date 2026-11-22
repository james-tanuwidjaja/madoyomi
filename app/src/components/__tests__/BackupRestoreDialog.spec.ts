import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import type { BackupReport } from "../../api/types";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({ validateBackup: vi.fn(), restoreBackup: vi.fn() }));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  validateBackup: ops.validateBackup,
  restoreBackup: ops.restoreBackup,
}));

import BackupRestoreDialog from "../BackupRestoreDialog.vue";

/* The restore dialog: the report, merge or replace, and the trust prompt, over mocked calls. */

const NAME = "Madoyomi_2026-11-16_21-00-00.madoyomi-backup";
const KEY = "c7d8".repeat(16);
const CERT = "ab".repeat(32);

function report(overrides: Partial<BackupReport> = {}): BackupReport {
  return {
    name: NAME,
    format: "MADOYOMI",
    createdAt: 1_700_000_000_000,
    appVersion: "0.6.0",
    automatic: false,
    anime: 6,
    seasons: 1,
    episodes: 40,
    watchedEpisodes: 12,
    animeHistory: 3,
    animeCategories: 5,
    manga: 7,
    chapters: 950,
    readChapters: 20,
    mangaHistory: 9,
    mangaCategories: 1,
    repos: [
      {
        indexUrl: "https://repo.example/index.min.json",
        kind: "ANIME",
        name: "Yuzono",
        signingKey: KEY,
        added: false,
      },
      {
        indexUrl: "https://other.example/index.pb",
        kind: "MANGA",
        name: "Keiyoushi",
        signingKey: KEY,
        added: true,
      },
    ],
    certificates: [{ hash: CERT, trusted: false }],
    extensions: [
      {
        pkgName: "eu.kanade.tachiyomi.animeextension.en.kickassanime",
        kind: "ANIME",
        name: "KickAssAnime",
        versionName: "14.50",
        repoUrl: "https://repo.example/index.min.json",
        signatureHash: KEY,
        installed: false,
        inRepo: true,
        trustedAfterRestore: true,
      },
      {
        pkgName: "eu.kanade.tachiyomi.extension.en.gone",
        kind: "MANGA",
        name: "Gone",
        versionName: "1.4",
        repoUrl: null,
        signatureHash: null,
        installed: false,
        inRepo: false,
        trustedAfterRestore: false,
      },
    ],
    missingSources: [{ id: "77", kind: "MANGA", name: "Gone", entries: 2, extension: null }],
    settingsFiles: ["player-settings.json", "reader-settings.json"],
    sourcePreferences: 2,
    ...overrides,
  };
}

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper | null = null;

const dialog = () => document.body.querySelector<HTMLElement>('[data-testid="restore-dialog"]');
const q = <T extends Element = HTMLElement>(sel: string) => document.body.querySelector<T>(sel);

async function open() {
  wrapper = mountView(BackupRestoreDialog, { pinia, queryClient }, { open: true, name: NAME });
  await flushPromises();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  ops.restoreBackup.mockResolvedValue({
    state: "RUNNING",
    name: NAME,
    mode: "MERGE",
    step: null,
    current: 0,
    total: 0,
    title: null,
    problems: [],
    startedAt: 1,
    finishedAt: 0,
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  queryClient.clear();
  vi.clearAllMocks();
});

describe("BackupRestoreDialog", () => {
  it("shows what the backup holds and what isn't installed here", async () => {
    ops.validateBackup.mockResolvedValue(report());
    await open();
    expect(ops.validateBackup).toHaveBeenCalledWith(NAME);
    const d = dialog()!;
    expect(q('[data-testid="restore-anime"]')!.textContent).toBe(
      "6 entries (1 season), 40 episodes (12 watched), 3 in history, 5 categories",
    );
    expect(q('[data-testid="restore-manga"]')!.textContent).toBe(
      "7 entries, 950 chapters (20 read), 9 in history, 1 category",
    );
    expect(q('[data-testid="restore-settings-summary"]')!.textContent).toBe(
      "2 settings files, 2 sources' settings",
    );
    expect(q('[data-testid="restore-missing"]')!.textContent).toContain("Gone (manga, 2 entries)");
    expect(d.textContent).toContain("full access to this PC");
    // only what isn't here yet is offered; what can't be reinstalled is off
    expect(document.body.querySelectorAll("[data-repo]")).toHaveLength(1);
    const gone = q<HTMLInputElement>('[data-extension="eu.kanade.tachiyomi.extension.en.gone"]')!;
    expect(gone.disabled).toBe(true);
    expect(gone.checked).toBe(false);
    expect(q<HTMLInputElement>('[data-testid="mode-merge"]')!.checked).toBe(true);
    expect(q('[data-action="confirm-restore"]')!.textContent!.trim()).toBe("Trust and restore");
  });

  it("restores with what stays checked, and replace when chosen", async () => {
    ops.validateBackup.mockResolvedValue(report());
    await open();
    const repo = q<HTMLInputElement>('[data-repo="https://repo.example/index.min.json"]')!;
    repo.click();
    q<HTMLInputElement>('[data-testid="mode-replace"]')!.click();
    q<HTMLInputElement>('[data-testid="restore-settings"]')!.click();
    await flushPromises();
    q<HTMLButtonElement>('[data-action="confirm-restore"]')!.click();
    await flushPromises();
    expect(ops.restoreBackup).toHaveBeenCalledWith({
      name: NAME,
      mode: "REPLACE",
      library: true,
      settings: false,
      repos: [],
      certificates: [CERT],
      extensions: ["eu.kanade.tachiyomi.animeextension.en.kickassanime"],
    });
    expect(dialog()).toBeNull();
  });

  it("says plain Restore with nothing to trust, and reads a .tachibk's manga only", async () => {
    ops.validateBackup.mockResolvedValue(
      report({
        format: "TACHIYOMI",
        createdAt: null,
        repos: [],
        certificates: [],
        extensions: [],
        missingSources: [],
      }),
    );
    await open();
    expect(q('[data-testid="restore-tachibk"]')).not.toBeNull();
    expect(q('[data-testid="restore-anime"]')).toBeNull();
    expect(q('[data-testid="restore-trust"]')).toBeNull();
    expect(q('[data-testid="restore-settings"]')).toBeNull();
    expect(q('[data-action="confirm-restore"]')!.textContent!.trim()).toBe("Restore");
  });

  it("says why a file can't be read", async () => {
    ops.validateBackup.mockRejectedValue(
      new ApiError({
        kind: "graphql",
        code: "BAD_REQUEST",
        message: "Not a backup file: it isn't gzipped",
      }),
    );
    await open();
    expect(dialog()!.textContent).toContain("Couldn't read this backup");
    expect(dialog()!.textContent).toContain("it isn't gzipped");
    expect(q<HTMLButtonElement>('[data-action="confirm-restore"]')!.disabled).toBe(true);
  });
});
