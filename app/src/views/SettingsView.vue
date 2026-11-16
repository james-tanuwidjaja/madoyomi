<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  SwitchRoot,
  SwitchThumb,
  ToggleGroupItem,
  ToggleGroupRoot,
  type AcceptableValue,
} from "reka-ui";
import { checkMpv, fetchDownloadTools } from "../api/operations";
import type { DownloadTool, MpvCheck, MpvOrigin, VideoQualityPreference } from "../api/types";
import {
  usePlayerLanguages,
  usePlayerSettings,
  useSetPlayerSettings,
} from "../composables/usePlayerSettings";
import {
  PAGE_CACHE_LIMITS_MB,
  formatBytes,
  formatLimit,
  useClearPageCache,
  usePageCache,
  useReaderSettings,
  useSetReaderSettings,
} from "../composables/useReaderSettings";
import { useSources } from "../composables/useSources";
import {
  useLibraryCheck,
  useLibraryUpdateSettings,
  useSetLibraryUpdateSettings,
} from "../composables/useLibraryUpdates";
import { clockTime } from "../utils/updates";
import {
  DEFAULT_DOWNLOAD_SETTINGS,
  useDownloadSettings,
  useSetDownloadSettings,
} from "../composables/useDownloads";
import { revealFolder } from "../utils/folders";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { NEXT_EPISODE_COUNTDOWN_SECONDS, usePlayerPrefsStore } from "../stores/playerPrefs";
import { THEME_CHOICES, useThemeStore, type ThemeChoice } from "../stores/theme";
import { formatLang } from "../utils/format";
import { canBrowse, sourceAccess } from "../utils/sources";
import ReaderPrefsFields from "../components/reader/ReaderPrefsFields.vue";
import BackupRestoreDialog from "../components/BackupRestoreDialog.vue";
import {
  useBackupFiles,
  useBackupSettings,
  useCreateBackup,
  useDeleteBackup,
  useRestoreStatus,
  useSetBackupSettings,
  useUploadBackup,
} from "../composables/useBackups";
import { BACKUP_INTERVALS, BACKUP_KEEP, backupFileText, restoreStatusText } from "../utils/backups";
import ErrorNotice from "../components/ErrorNotice.vue";
import PageLayout from "../components/PageLayout.vue";

/*
 * Settings (Phase 3 step 17): the theme and language, where the backend keeps its data and logs, and the current
 * kind's sources that have settings of their own (each opens /settings/source/:id).
 *
 * Player (Phase 4 Stage 5): the backend's player settings, each saved as it changes and used from the next play (the
 * watched threshold also by the episode playing): when an episode counts as watched, the preferred quality and server
 * (the order of the picker's videos and what is preselected), the subtitle and audio language, and which mpv to run.
 * Auto-play the next episode (Stage 4) is in the same section but stays on this device.
 *
 * Reader (Phase 5 Stage 1): the chapter-page cache's cap (the backend's setting; a lower one trims the cache before
 * the backend answers), what the cache holds now, and Clear. Stage 5: the reader's global settings on this device
 * (ReaderPrefsFields, as in the reader's settings sheet), each manga's own mode and direction coming first.
 *
 * Updates (Phase 6 Stage 3): the library update check's schedule (off, the user's default, or every 6, 12 or 24 h)
 * with the next check, which entries it skips (completed, with unwatched/unread items, not started), and the Windows
 * toast (off by default, the user's decision). The backend keeps them, for both kinds.
 *
 * Downloads (Phase 6 Stage 4): how many download at once (all sources, and from one source), the automatic download
 * of the new chapters a library update finds (off by default, the user's decision; the Categories dialog's Downloads
 * flags say which categories), and the folder downloads are saved in (the data dir's, the user's decision), with
 * Show in Explorer. The backend keeps them, for both kinds. Stage 5: new episodes too, and the two programs anime
 * downloads use, ffmpeg (HLS) and yt-dlp (its fallback), each with its path (empty: looked for) and a check, as mpv's.
 *
 * Backup (Phase 6 Stage 6): Back up now, the schedule (off by default, the user's decision; daily or weekly, keeping
 * the last N), the folder with Show in Explorer, the backup files (Restore…, Show, Delete with a second click), a file
 * the user picks (kept in the folder, then restored), and the restore's progress and problems. BackupRestoreDialog
 * shows what a file holds, merge (the default, the user's decision) or replace, and the trust prompt.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const theme = useThemeStore();
const playerPrefs = usePlayerPrefsStore();

const playerSettings = usePlayerSettings();
const languages = usePlayerLanguages();
const setPlayer = useSetPlayerSettings();
const player = computed(() => playerSettings.data.value ?? null);

const updateSettings = useLibraryUpdateSettings();
const setUpdates = useSetLibraryUpdateSettings();
const libraryCheck = useLibraryCheck();
const updates = computed(() => updateSettings.data.value ?? null);
const INTERVALS = [0, 6, 12, 24] as const;
const nextCheck = computed(() => {
  const next = libraryCheck.data.value?.nextScheduledAt;
  return updates.value?.intervalHours && next ? `Next check at ${clockTime(Number(next))}.` : "";
});
function saveInterval(e: Event) {
  const v = Number(selectValue(e)) as (typeof INTERVALS)[number];
  if (INTERVALS.includes(v)) setUpdates.mutate({ intervalHours: v });
}

const downloadSettings = useDownloadSettings();
const setDownloads = useSetDownloadSettings();
const downloads = computed(() =>
  downloadSettings.data.value
    ? { ...DEFAULT_DOWNLOAD_SETTINGS, ...downloadSettings.data.value }
    : null,
);
const PARALLEL = Array.from({ length: 10 }, (_, i) => i + 1);
const PER_SOURCE = [1, 2, 3] as const;
function saveParallel(e: Event) {
  const v = Number(selectValue(e));
  if (PARALLEL.includes(v)) setDownloads.mutate({ maxParallel: v });
}
function savePerSource(e: Event) {
  const v = Number(selectValue(e));
  if ((PER_SOURCE as readonly number[]).includes(v)) setDownloads.mutate({ perSource: v });
}

type ToolKey = "ffmpegPath" | "ytDlpPath";
const TOOLS: {
  key: ToolKey;
  tool: DownloadTool["tool"];
  label: string;
  env: string;
  hint: string;
}[] = [
  {
    key: "ffmpegPath",
    tool: "FFMPEG",
    label: "ffmpeg",
    env: "MADOYOMI_FFMPEG",
    hint: "Downloads HLS and DASH episodes.",
  },
  {
    key: "ytDlpPath",
    tool: "YT_DLP",
    label: "yt-dlp",
    env: "MADOYOMI_YTDLP",
    hint: "Tried when ffmpeg fails.",
  },
];
const toolText = ref<Record<ToolKey, string>>({ ffmpegPath: "", ytDlpPath: "" });
watch(
  () => [downloads.value?.ffmpegPath, downloads.value?.ytDlpPath] as const,
  ([f, y]) => (toolText.value = { ffmpegPath: f ?? "", ytDlpPath: y ?? "" }),
  { immediate: true },
);
const toolStatus = ref<Partial<Record<DownloadTool["tool"], DownloadTool>>>({});
const toolsChecking = ref(false);
const toolsCheckError = ref<unknown>(null);

/** Asks the backend for both tools; `paths` tries typed paths in place of the saved ones. */
async function runToolsCheck(paths: { ffmpegPath?: string; ytDlpPath?: string } = {}) {
  toolsChecking.value = true;
  toolsCheckError.value = null;
  try {
    const list = await fetchDownloadTools(paths);
    toolStatus.value = Object.fromEntries(list.map((t) => [t.tool, t]));
  } catch (e) {
    toolsCheckError.value = e;
  } finally {
    toolsChecking.value = false;
  }
}

/** Where a tool's path came from, as Settings says it. */
function toolOrigin(env: string, origin: NonNullable<DownloadTool["origin"]>) {
  return origin === "ENV"
    ? env
    : { SETTINGS: "this setting", SYSTEM_PROPERTY: "a system property", PATH: "PATH" }[origin];
}

const cleanPath = (v: string) => v.trim().replace(/^"(.*)"$/, "$1");

async function saveToolPath(key: ToolKey) {
  const v = cleanPath(toolText.value[key]);
  toolText.value = { ...toolText.value, [key]: v };
  if (!downloads.value || v === downloads.value[key]) return;
  try {
    await setDownloads.mutateAsync(key === "ffmpegPath" ? { ffmpegPath: v } : { ytDlpPath: v });
  } catch {
    return; // shown below, from setDownloads.error
  }
  await runToolsCheck();
}

function checkTypedTools() {
  void runToolsCheck({
    ffmpegPath: cleanPath(toolText.value.ffmpegPath),
    ytDlpPath: cleanPath(toolText.value.ytDlpPath),
  });
}

watch(
  () => backend.ready,
  (ready) => {
    if (ready && !toolStatus.value.FFMPEG && !toolsChecking.value) void runToolsCheck();
  },
  { immediate: true },
);

/** 50 % to 95 % in steps of 5 (the backend's range). */
const THRESHOLDS = Array.from({ length: 10 }, (_, i) => 50 + i * 5);
const thresholdPercent = computed(() =>
  player.value ? Math.round(player.value.watchedThreshold * 100) : 85,
);

const QUALITY_LABELS: Record<VideoQualityPreference, string> = {
  SOURCE: "The source's choice",
  HIGHEST: "Highest",
  P2160: "2160p",
  P1080: "1080p",
  P720: "720p",
  P480: "480p",
  P360: "360p",
};

function selectValue(e: Event): string {
  return (e.target as HTMLSelectElement).value;
}

// Backup (Phase 6 Stage 6)
const backupFiles = useBackupFiles();
const backupSettings = useBackupSettings();
const setBackup = useSetBackupSettings();
const createBackupNow = useCreateBackup();
const deleteBackupFile = useDeleteBackup();
const uploadBackupFile = useUploadBackup();
const restoreStatus = useRestoreStatus();
const backup = computed(() => backupSettings.data.value);
const restoreName = ref<string | null>(null);
const restoreOpen = ref(false);
const armedDelete = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const restoreShown = computed(() => {
  const s = restoreStatus.data.value;
  return s && s.state !== "IDLE" ? s : null;
});
const backupError = computed(
  () =>
    createBackupNow.error.value ??
    uploadBackupFile.error.value ??
    deleteBackupFile.error.value ??
    setBackup.error.value,
);

function openRestore(name: string) {
  restoreName.value = name;
  restoreOpen.value = true;
}

async function pickBackupFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  try {
    openRestore(await uploadBackupFile.mutateAsync(file));
  } catch {
    // shown below, from backupError
  }
}

function removeBackup(name: string) {
  if (armedDelete.value !== name) {
    armedDelete.value = name;
    return;
  }
  armedDelete.value = null;
  deleteBackupFile.mutate(name);
}

function saveBackupInterval(e: Event) {
  setBackup.mutate({ intervalHours: Number(selectValue(e)) });
}

function saveBackupKeep(e: Event) {
  setBackup.mutate({ keep: Number(selectValue(e)) });
}

function saveThreshold(e: Event) {
  setPlayer.mutate({ watchedThreshold: Number(selectValue(e)) / 100 });
}

// The text fields are saved when they lose focus or on Enter, and follow the backend's value otherwise.
const serverText = ref("");
const mpvText = ref("");
watch(
  () => player.value?.preferredServer,
  (v) => (serverText.value = v ?? ""),
  { immediate: true },
);
watch(
  () => player.value?.mpvPath,
  (v) => (mpvText.value = v ?? ""),
  { immediate: true },
);

function saveServer() {
  const v = serverText.value.trim();
  if (player.value && v !== player.value.preferredServer) setPlayer.mutate({ preferredServer: v });
}

const ORIGIN_LABELS: Record<MpvOrigin, string> = {
  SETTINGS: "this setting",
  SYSTEM_PROPERTY: "a system property",
  ENV: "MADOYOMI_MPV",
  PATH: "PATH",
};
const mpvStatus = ref<MpvCheck | null>(null);
const mpvChecking = ref(false);
const mpvCheckError = ref<unknown>(null);

/** Runs `mpv --version` on `path`, or on the mpv the backend would start now. */
async function runMpvCheck(path: string | null) {
  mpvChecking.value = true;
  mpvCheckError.value = null;
  try {
    mpvStatus.value = await checkMpv(path);
  } catch (e) {
    mpvStatus.value = null;
    mpvCheckError.value = e;
  } finally {
    mpvChecking.value = false;
  }
}

async function saveMpvPath() {
  const v = mpvText.value.trim().replace(/^"(.*)"$/, "$1");
  mpvText.value = v;
  if (!player.value || v === player.value.mpvPath) return;
  try {
    await setPlayer.mutateAsync({ mpvPath: v });
  } catch {
    return; // shown below, from setPlayer.error
  }
  await runMpvCheck(null);
}

function checkTyped() {
  const v = mpvText.value.trim().replace(/^"(.*)"$/, "$1");
  void runMpvCheck(v || null);
}

watch(
  () => backend.ready,
  (ready) => {
    if (ready && !mpvStatus.value && !mpvChecking.value) void runMpvCheck(null);
  },
  { immediate: true },
);

const readerSettings = useReaderSettings();
const setReader = useSetReaderSettings();
const pageCache = usePageCache();
const clearCache = useClearPageCache();
const reader = computed(() => readerSettings.data.value ?? null);
/** The offered caps, with the current one if a hand-edited file has another. */
const cacheLimits = computed(() => {
  const current = reader.value?.pageCacheLimitMb;
  const limits: number[] = [...PAGE_CACHE_LIMITS_MB];
  if (current !== undefined && !limits.includes(current)) limits.push(current);
  return limits.sort((a, b) => a - b);
});
const cacheUsage = computed(() => {
  const c = pageCache.data.value;
  if (!c) return null;
  const pages = `${c.files.toLocaleString()} ${c.files === 1 ? "page" : "pages"}`;
  return `${formatBytes(c.bytes)} in ${pages}`;
});

function saveCacheLimit(e: Event) {
  setReader.mutate({ pageCacheLimitMb: Number(selectValue(e)) });
}

const THEME_LABELS: Record<ThemeChoice, string> = {
  system: "System",
  dark: "Dark",
  light: "Light",
};

function onTheme(v: AcceptableValue | AcceptableValue[] | undefined) {
  // a click on the chosen option unsets a single toggle group: keep the choice
  if (typeof v === "string") theme.set(v as ThemeChoice);
}

const STATE_LABELS: Record<string, string> = {
  starting: "Starting",
  healthy: "Running",
  restarting: "Restarting",
  failed: "Stopped after failing to start",
  stopped: "Stopped",
};
const status = computed(() => backend.status);
const managed = computed(() => backend.mode === "tauri");

const kind = computed(() => mediaKind.kind);
const sources = useSources(kind);
const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });
const configurable = computed(() =>
  (sources.data.value ?? [])
    .filter((s) => s.isConfigurable === true && canBrowse(sourceAccess(s)))
    .sort((a, b) => collator.compare(a.name, b.name) || collator.compare(a.lang, b.lang)),
);
/** Sources the backend can't use right now: whether they have settings isn't known. */
const unknown = computed(
  () => (sources.data.value ?? []).filter((s) => !canBrowse(sourceAccess(s))).length,
);
</script>

<template>
  <PageLayout title="Settings">
    <section class="group" aria-labelledby="settings-general">
      <h2 id="settings-general">General</h2>
      <div class="card">
        <div class="row">
          <div class="text">
            <span id="settings-theme">Theme</span>
            <span class="summary">System follows Windows' light or dark mode.</span>
          </div>
          <ToggleGroupRoot
            type="single"
            class="segmented"
            aria-labelledby="settings-theme"
            :model-value="theme.choice"
            data-testid="theme-switch"
            @update:model-value="onTheme"
          >
            <ToggleGroupItem
              v-for="c in THEME_CHOICES"
              :key="c"
              :value="c"
              class="segment"
              :data-theme-choice="c"
            >
              {{ THEME_LABELS[c] }}
            </ToggleGroupItem>
          </ToggleGroupRoot>
        </div>
        <div class="row">
          <div class="text">
            <span>Language</span>
            <span class="summary">English is the only language so far.</span>
          </div>
          <span class="value" data-testid="language">English</span>
        </div>
      </div>
    </section>

    <section class="group" aria-labelledby="settings-updates">
      <h2 id="settings-updates">Updates</h2>
      <div class="card" data-testid="update-settings">
        <template v-if="updates">
          <div class="row">
            <label class="text" for="settings-interval">
              <span>Check for new episodes and chapters</span>
              <span class="summary">
                Checks the library's sources while the app runs, both anime and manga. Update
                library in the Library or Updates checks at once. {{ nextCheck }}
              </span>
            </label>
            <select
              id="settings-interval"
              class="field"
              data-testid="interval-select"
              :value="updates.intervalHours"
              @change="saveInterval"
            >
              <option v-for="h in INTERVALS" :key="h" :value="h">
                {{ h === 0 ? "Off" : `Every ${h} hours` }}
              </option>
            </select>
          </div>
          <div class="row">
            <div class="text">
              <span id="settings-skip-completed">Skip completed entries</span>
              <span class="summary">Entries whose source says they are finished.</span>
            </div>
            <SwitchRoot
              :model-value="updates.skipCompleted"
              class="switch"
              aria-labelledby="settings-skip-completed"
              data-testid="skip-completed-switch"
              @update:model-value="(v: boolean) => setUpdates.mutate({ skipCompleted: v })"
            >
              <SwitchThumb class="switch-thumb" />
            </SwitchRoot>
          </div>
          <div class="row">
            <div class="text">
              <span id="settings-skip-unseen">Skip entries with unwatched or unread items</span>
              <span class="summary">Catch up first, then they are checked again.</span>
            </div>
            <SwitchRoot
              :model-value="updates.skipUnseen"
              class="switch"
              aria-labelledby="settings-skip-unseen"
              data-testid="skip-unseen-switch"
              @update:model-value="(v: boolean) => setUpdates.mutate({ skipUnseen: v })"
            >
              <SwitchThumb class="switch-thumb" />
            </SwitchRoot>
          </div>
          <div class="row">
            <div class="text">
              <span id="settings-skip-not-started">Skip entries not started</span>
              <span class="summary">Nothing watched or read in them yet.</span>
            </div>
            <SwitchRoot
              :model-value="updates.skipNotStarted"
              class="switch"
              aria-labelledby="settings-skip-not-started"
              data-testid="skip-not-started-switch"
              @update:model-value="(v: boolean) => setUpdates.mutate({ skipNotStarted: v })"
            >
              <SwitchThumb class="switch-thumb" />
            </SwitchRoot>
          </div>
          <div class="row">
            <div class="text">
              <span id="settings-notify">Windows notification</span>
              <span class="summary">
                When a check finds new episodes or chapters while the app is in the background.
              </span>
            </div>
            <SwitchRoot
              :model-value="updates.notify"
              class="switch"
              aria-labelledby="settings-notify"
              data-testid="notify-switch"
              @update:model-value="(v: boolean) => setUpdates.mutate({ notify: v })"
            >
              <SwitchThumb class="switch-thumb" />
            </SwitchRoot>
          </div>
        </template>
        <ErrorNotice
          v-else-if="updateSettings.isError.value"
          class="row"
          title="Couldn't load the update settings"
          :error="updateSettings.error.value"
          @retry="updateSettings.refetch()"
        />
        <p v-else class="row muted">Loading…</p>
      </div>
      <ErrorNotice
        v-if="setUpdates.error.value"
        title="Couldn't save the change"
        :error="setUpdates.error.value"
      />
    </section>

    <section class="group" aria-labelledby="settings-downloads">
      <h2 id="settings-downloads">Downloads</h2>
      <div class="card" data-testid="download-settings">
        <template v-if="downloads">
          <div class="row">
            <label class="text" for="settings-parallel">
              <span>Downloads at once</span>
              <span class="summary">All sources together, anime and manga.</span>
            </label>
            <select
              id="settings-parallel"
              class="field"
              data-testid="parallel-select"
              :value="downloads.maxParallel"
              @change="saveParallel"
            >
              <option v-for="n in PARALLEL" :key="n" :value="n">{{ n }}</option>
            </select>
          </div>
          <div class="row">
            <label class="text" for="settings-per-source">
              <span>Downloads at once from one source</span>
              <span class="summary">
                More is faster but some sites limit how much one visitor fetches.
              </span>
            </label>
            <select
              id="settings-per-source"
              class="field"
              data-testid="per-source-select"
              :value="downloads.perSource"
              @change="savePerSource"
            >
              <option v-for="n in PER_SOURCE" :key="n" :value="n">{{ n }}</option>
            </select>
          </div>
          <div class="row">
            <div class="text">
              <span id="settings-auto-download">Download new episodes and chapters</span>
              <span class="summary">
                When a library update finds new episodes or chapters in a category with Downloads on
                (the Library's Categories dialog), they are queued.
              </span>
            </div>
            <SwitchRoot
              :model-value="downloads.autoDownload"
              class="switch"
              aria-labelledby="settings-auto-download"
              data-testid="auto-download-switch"
              @update:model-value="(v: boolean) => setDownloads.mutate({ autoDownload: v })"
            >
              <SwitchThumb class="switch-thumb" />
            </SwitchRoot>
          </div>
          <div class="row">
            <div class="text">
              <span>Download folder</span>
              <span class="summary">
                Chapters are saved as CBZ files under manga/, episodes under anime/ with their
                subtitles beside them.
              </span>
            </div>
            <div class="path-actions">
              <code class="path" data-testid="downloads-path">{{ downloads.path }}</code>
              <button
                v-if="backend.mode === 'tauri'"
                type="button"
                class="btn"
                data-action="open-downloads"
                @click="revealFolder(downloads.path)"
              >
                Show in Explorer
              </button>
            </div>
          </div>
          <div v-for="t in TOOLS" :key="t.key" class="row mpv-row" :data-testid="`tool-${t.tool}`">
            <label class="text" :for="`settings-${t.key}`">
              <span>{{ t.label }}</span>
              <span class="summary">
                {{ t.hint }} Empty: {{ t.env }} if set, else {{ t.label }}.exe on PATH.
              </span>
            </label>
            <div class="mpv-field">
              <div class="path-actions">
                <input
                  :id="`settings-${t.key}`"
                  v-model="toolText[t.key]"
                  class="field text-field path-field"
                  type="text"
                  spellcheck="false"
                  :placeholder="`C:\\Tools\\${t.label}.exe`"
                  :data-testid="`tool-input-${t.tool}`"
                  @blur="saveToolPath(t.key)"
                  @keydown.enter="saveToolPath(t.key)"
                />
                <button
                  type="button"
                  class="btn"
                  :data-action="`check-${t.tool}`"
                  :disabled="toolsChecking"
                  @mousedown.prevent
                  @click="checkTypedTools"
                >
                  {{ toolsChecking ? "Checking…" : "Check" }}
                </button>
              </div>
              <p
                v-if="toolStatus[t.tool]?.ok"
                class="summary"
                :data-testid="`tool-status-${t.tool}`"
              >
                {{ toolStatus[t.tool]!.version }} · {{ toolStatus[t.tool]!.path }}
                <template v-if="toolStatus[t.tool]!.origin">
                  (from {{ toolOrigin(t.env, toolStatus[t.tool]!.origin!) }})</template
                >
              </p>
              <p
                v-else-if="toolStatus[t.tool]"
                class="summary danger"
                :data-testid="`tool-status-${t.tool}`"
              >
                {{ toolStatus[t.tool]!.message }}
              </p>
            </div>
          </div>
          <ErrorNotice
            v-if="toolsCheckError"
            class="row"
            title="Couldn't check the download tools"
            :error="toolsCheckError"
          />
        </template>
        <ErrorNotice
          v-else-if="downloadSettings.isError.value"
          class="row"
          title="Couldn't load the download settings"
          :error="downloadSettings.error.value"
          @retry="downloadSettings.refetch()"
        />
        <p v-else class="row muted">Loading…</p>
      </div>
      <ErrorNotice
        v-if="setDownloads.error.value"
        title="Couldn't save the change"
        :error="setDownloads.error.value"
      />
    </section>

    <section class="group" aria-labelledby="settings-backup">
      <h2 id="settings-backup">Backup</h2>
      <div class="card" data-testid="backup-settings">
        <div class="row">
          <div class="text">
            <span>Back up now</span>
            <span class="summary">
              Both kinds' library, categories, progress and history, the repos, the certificates you
              trusted, the extensions (not their files), the source settings and these settings. Not
              the downloads.
            </span>
          </div>
          <button
            type="button"
            class="btn btn-accent"
            data-action="backup-now"
            :disabled="createBackupNow.isPending.value"
            @click="createBackupNow.mutate()"
          >
            {{ createBackupNow.isPending.value ? "Backing up…" : "Back up now" }}
          </button>
        </div>
        <p v-if="createBackupNow.data.value" class="row summary" data-testid="backup-created">
          Saved {{ createBackupNow.data.value.name }} ({{
            formatBytes(createBackupNow.data.value.size)
          }}).
        </p>
        <template v-if="backup">
          <div class="row">
            <label class="text" for="settings-backup-interval">
              <span>Automatic backups</span>
              <span class="summary">
                <template v-if="backup.intervalHours > 0 && backup.nextScheduledAt">
                  Next at {{ clockTime(backup.nextScheduledAt) }}.
                </template>
                <template v-else>Only when you back up.</template>
              </span>
            </label>
            <select
              id="settings-backup-interval"
              class="field"
              data-testid="backup-interval-select"
              :value="backup.intervalHours"
              @change="saveBackupInterval"
            >
              <option v-for="c in BACKUP_INTERVALS" :key="c.value" :value="c.value">
                {{ c.label }}
              </option>
            </select>
          </div>
          <div v-if="backup.intervalHours > 0" class="row">
            <label class="text" for="settings-backup-keep">
              <span>Keep the last</span>
              <span class="summary">Older automatic backups are deleted; yours are kept.</span>
            </label>
            <select
              id="settings-backup-keep"
              class="field"
              data-testid="backup-keep-select"
              :value="backup.keep"
              @change="saveBackupKeep"
            >
              <option v-for="n in BACKUP_KEEP" :key="n" :value="n">{{ n }}</option>
            </select>
          </div>
          <p v-if="backup.lastError" class="row summary danger" data-testid="backup-last-error">
            The last automatic backup failed: {{ backup.lastError }}
          </p>
          <div class="row">
            <div class="text">
              <span>Backup folder</span>
              <span class="summary">.madoyomi-backup files: gzipped, readable by Madoyomi.</span>
            </div>
            <div class="path-actions">
              <code class="path" data-testid="backup-path">{{ backup.folder }}</code>
              <button
                v-if="backend.mode === 'tauri'"
                type="button"
                class="btn"
                data-action="open-backups"
                @click="revealFolder(backup.folder)"
              >
                Show in Explorer
              </button>
            </div>
          </div>
        </template>
        <ErrorNotice
          v-else-if="backupSettings.isError.value"
          class="row"
          title="Couldn't load the backup settings"
          :error="backupSettings.error.value"
          @retry="backupSettings.refetch()"
        />
        <div class="row">
          <div class="text">
            <span>Restore</span>
            <span class="summary">
              From a backup below, or from a file: a .madoyomi-backup, or a Mihon or Aniyomi
              .tachibk (its manga).
            </span>
          </div>
          <input
            ref="fileInput"
            type="file"
            class="hidden-input"
            accept=".madoyomi-backup,.tachibk,.gz"
            data-testid="backup-file-input"
            @change="pickBackupFile"
          />
          <button
            type="button"
            class="btn"
            data-action="restore-file"
            :disabled="uploadBackupFile.isPending.value || restoreShown?.state === 'RUNNING'"
            @click="fileInput?.click()"
          >
            {{ uploadBackupFile.isPending.value ? "Reading…" : "Restore from a file…" }}
          </button>
        </div>
        <div v-if="restoreShown" class="row restore-status" data-testid="restore-status">
          <div class="text">
            <span>{{ restoreStatusText(restoreShown) }}</span>
            <span class="summary">{{ restoreShown.name }}</span>
            <progress
              v-if="restoreShown.state === 'RUNNING' && restoreShown.total > 0"
              :max="restoreShown.total"
              :value="restoreShown.current"
            />
            <ul v-if="restoreShown.problems.length" class="problems" data-testid="restore-problems">
              <li v-for="(p, i) in restoreShown.problems" :key="i">{{ p }}</li>
            </ul>
          </div>
        </div>
        <ul v-if="backupFiles.data.value?.length" class="backup-list" data-testid="backup-list">
          <li v-for="f in backupFiles.data.value" :key="f.name" class="row" :data-backup="f.name">
            <div class="text">
              <span class="backup-name">{{ f.name }}</span>
              <span class="summary">{{ backupFileText(f) }}</span>
            </div>
            <div class="path-actions">
              <button
                type="button"
                class="btn"
                data-action="restore-backup"
                :disabled="restoreShown?.state === 'RUNNING'"
                @click="openRestore(f.name)"
              >
                Restore…
              </button>
              <button
                v-if="backend.mode === 'tauri'"
                type="button"
                class="btn"
                data-action="show-backup"
                @click="revealFolder(f.path)"
              >
                Show
              </button>
              <button
                type="button"
                class="btn"
                :class="{ danger: armedDelete === f.name }"
                data-action="delete-backup"
                @click="removeBackup(f.name)"
              >
                {{ armedDelete === f.name ? "Delete it?" : "Delete" }}
              </button>
            </div>
          </li>
        </ul>
        <p v-else-if="backupFiles.isSuccess.value" class="row muted" data-testid="backup-empty">
          No backups yet.
        </p>
      </div>
      <ErrorNotice v-if="backupError" title="Couldn't do that" :error="backupError" />
      <BackupRestoreDialog v-model:open="restoreOpen" :name="restoreName" />
    </section>

    <section class="group" aria-labelledby="settings-player">
      <h2 id="settings-player">Player</h2>
      <div class="card" data-testid="player-settings">
        <div class="row">
          <div class="text">
            <span id="settings-autoplay">Auto-play the next episode</span>
            <span class="summary">
              When an episode ends, the next one plays after {{ NEXT_EPISODE_COUNTDOWN_SECONDS }} s,
              with your last choice of video for its source. Off, it is only offered. Kept on this
              device.
            </span>
          </div>
          <SwitchRoot
            v-model="playerPrefs.autoPlayNext"
            class="switch"
            aria-labelledby="settings-autoplay"
            data-testid="autoplay-switch"
          >
            <SwitchThumb class="switch-thumb" />
          </SwitchRoot>
        </div>
        <template v-if="player">
          <div class="row">
            <label class="text" for="settings-threshold">
              <span>Mark as watched at</span>
              <span class="summary">
                An episode counts as watched once you pass this share of it, also the one playing.
                Below it, Play offers to resume where you stopped.
              </span>
            </label>
            <select
              id="settings-threshold"
              class="field"
              data-testid="threshold-select"
              :value="thresholdPercent"
              @change="saveThreshold"
            >
              <option v-for="t in THRESHOLDS" :key="t" :value="t">{{ t }} %</option>
            </select>
          </div>
          <div class="row">
            <label class="text" for="settings-quality">
              <span>Preferred quality</span>
              <span class="summary">
                Goes first in each server's videos: this one, else the closest below it, else the
                closest above. Your last choice for a source still comes first.
              </span>
            </label>
            <select
              id="settings-quality"
              class="field"
              data-testid="quality-select"
              :value="player.preferredQuality"
              @change="
                setPlayer.mutate({
                  preferredQuality: selectValue($event) as VideoQualityPreference,
                })
              "
            >
              <option v-for="(label, q) in QUALITY_LABELS" :key="q" :value="q">{{ label }}</option>
            </select>
          </div>
          <div class="row">
            <label class="text" for="settings-server">
              <span>Preferred server</span>
              <span class="summary">
                Servers (and videos) whose name contains this go first, e.g. VidStreaming. Empty:
                the source's order.
              </span>
            </label>
            <input
              id="settings-server"
              v-model="serverText"
              class="field text-field"
              type="text"
              maxlength="100"
              placeholder="Any server"
              data-testid="server-input"
              @blur="saveServer"
              @keydown.enter="saveServer"
            />
          </div>
          <div class="row">
            <label class="text" for="settings-subtitles">
              <span>Subtitles</span>
              <span class="summary">
                The first track in this language, the video's own or the source's. Without one, the
                source's first.
              </span>
            </label>
            <select
              id="settings-subtitles"
              class="field"
              data-testid="subtitle-select"
              :value="player.subtitleLanguage"
              @change="setPlayer.mutate({ subtitleLanguage: selectValue($event) })"
            >
              <option value="">The source's first</option>
              <option value="off">Off</option>
              <option v-for="l in languages.data.value ?? []" :key="l.code" :value="l.code">
                {{ l.name }}
              </option>
            </select>
          </div>
          <div class="row">
            <label class="text" for="settings-audio">
              <span>Audio</span>
              <span class="summary">
                The first audio track in this language. Without one, the video's own audio, else the
                source's first track.
              </span>
            </label>
            <select
              id="settings-audio"
              class="field"
              data-testid="audio-select"
              :value="player.audioLanguage"
              @change="setPlayer.mutate({ audioLanguage: selectValue($event) })"
            >
              <option value="">The source's choice</option>
              <option v-for="l in languages.data.value ?? []" :key="l.code" :value="l.code">
                {{ l.name }}
              </option>
            </select>
          </div>
          <div class="row mpv-row">
            <label class="text" for="settings-mpv">
              <span>mpv</span>
              <span class="summary">
                The mpv.exe to play with. Empty: MADOYOMI_MPV if set, else mpv.exe on PATH.
              </span>
            </label>
            <div class="mpv-field">
              <div class="path-actions">
                <input
                  id="settings-mpv"
                  v-model="mpvText"
                  class="field text-field path-field"
                  type="text"
                  spellcheck="false"
                  placeholder="C:\Program Files\MPV Player\mpv.exe"
                  data-testid="mpv-input"
                  @blur="saveMpvPath"
                  @keydown.enter="saveMpvPath"
                />
                <button
                  type="button"
                  class="btn"
                  data-action="check-mpv"
                  :disabled="mpvChecking"
                  @mousedown.prevent
                  @click="checkTyped"
                >
                  {{ mpvChecking ? "Checking…" : "Check" }}
                </button>
              </div>
              <p v-if="mpvStatus?.ok" class="summary" data-testid="mpv-status">
                {{ mpvStatus.version }} · {{ mpvStatus.path }}
                <template v-if="mpvStatus.origin">
                  (from {{ ORIGIN_LABELS[mpvStatus.origin] }})</template
                >
              </p>
              <p v-else-if="mpvStatus" class="summary danger" data-testid="mpv-status">
                {{ mpvStatus.message }}
              </p>
              <ErrorNotice v-if="mpvCheckError" title="Couldn't check mpv" :error="mpvCheckError" />
            </div>
          </div>
        </template>
        <div v-else-if="playerSettings.isError.value" class="row">
          <ErrorNotice
            title="Couldn't read the player settings"
            :error="playerSettings.error.value"
            @retry="playerSettings.refetch()"
          />
        </div>
        <p v-else class="row muted" data-testid="player-settings-pending">
          {{ backend.ready ? "Loading the player settings…" : "Waiting for the backend…" }}
        </p>
      </div>
      <ErrorNotice
        v-if="setPlayer.error.value"
        title="Couldn't save the player setting"
        :error="setPlayer.error.value"
        data-testid="player-save-error"
      />
    </section>

    <section class="group" aria-labelledby="settings-reader">
      <h2 id="settings-reader">Reader</h2>
      <div class="card" data-testid="reader-prefs-card">
        <ReaderPrefsFields id-prefix="settings-reader" />
      </div>
      <div class="card" data-testid="reader-settings">
        <template v-if="reader">
          <div class="row">
            <label class="text" for="settings-cache-limit">
              <span>Page cache</span>
              <span class="summary">
                Chapter pages stay on disk, so a chapter opens at once the next time. Past this
                size, the pages read longest ago go first. Downloads and covers don't count.
              </span>
            </label>
            <select
              id="settings-cache-limit"
              class="field"
              data-testid="cache-limit-select"
              :value="reader.pageCacheLimitMb"
              @change="saveCacheLimit"
            >
              <option v-for="mb in cacheLimits" :key="mb" :value="mb">
                {{ formatLimit(mb) }}
              </option>
            </select>
          </div>
          <div class="row">
            <div class="text">
              <span>Cached pages</span>
              <span class="summary" data-testid="cache-usage">
                {{ cacheUsage ?? (pageCache.isError.value ? "Not known" : "Counting…") }}
              </span>
            </div>
            <div class="path-actions">
              <code v-if="pageCache.data.value" class="path" data-testid="cache-path">
                {{ pageCache.data.value.path }}
              </code>
              <button
                type="button"
                class="btn"
                data-action="clear-cache"
                :disabled="clearCache.isPending.value || !pageCache.data.value?.files"
                @click="clearCache.mutate()"
              >
                {{ clearCache.isPending.value ? "Clearing…" : "Clear" }}
              </button>
            </div>
          </div>
        </template>
        <div v-else-if="readerSettings.isError.value" class="row">
          <ErrorNotice
            title="Couldn't read the reader settings"
            :error="readerSettings.error.value"
            @retry="readerSettings.refetch()"
          />
        </div>
        <p v-else class="row muted" data-testid="reader-settings-pending">
          {{ backend.ready ? "Loading the reader settings…" : "Waiting for the backend…" }}
        </p>
      </div>
      <ErrorNotice
        v-if="setReader.error.value"
        title="Couldn't save the reader setting"
        :error="setReader.error.value"
        data-testid="reader-save-error"
      />
      <ErrorNotice
        v-if="pageCache.isError.value"
        title="Couldn't read the page cache"
        :error="pageCache.error.value"
        data-testid="cache-error"
        @retry="pageCache.refetch()"
      />
      <ErrorNotice
        v-if="clearCache.error.value"
        title="Couldn't clear the page cache"
        :error="clearCache.error.value"
        data-testid="cache-clear-error"
      />
    </section>

    <section class="group" aria-labelledby="settings-backend">
      <h2 id="settings-backend">Backend</h2>
      <div class="card" data-testid="backend-info">
        <div class="row">
          <div class="text">
            <span>Server</span>
            <span class="summary" data-testid="backend-state">
              {{ status ? (STATE_LABELS[status.state] ?? status.state) : "Not known yet" }}
            </span>
          </div>
          <span v-if="backend.ready && status" class="value" data-testid="backend-server">
            Madoyomi-Server {{ status.serverVersion ?? "?" }} · {{ status.baseUrl }}
          </span>
        </div>
        <template v-if="managed && status">
          <div class="row">
            <div class="text">
              <span>Data folder</span>
              <span class="summary">The library, extensions and settings.</span>
            </div>
            <code class="path" data-testid="backend-data-dir">{{ status.dataDir }}</code>
          </div>
          <div class="row">
            <div class="text">
              <span>Log folder</span>
              <span class="summary">
                sidecar.log (the app's view of the backend) and madoyomi.log (the backend's own).
              </span>
            </div>
            <div class="path-actions">
              <code class="path" data-testid="backend-log-dir">{{ status.logDir }}</code>
              <button type="button" class="btn" data-action="open-logs" @click="backend.openLogs()">
                Open logs
              </button>
            </div>
          </div>
        </template>
        <p v-else class="row muted" data-testid="backend-external">
          This backend wasn't started by the app, so its folders aren't known here.
        </p>
      </div>
    </section>

    <section class="group" aria-labelledby="settings-sources">
      <h2 id="settings-sources">{{ kind === "anime" ? "Anime" : "Manga" }} source settings</h2>
      <ErrorNotice
        v-if="sources.isError.value"
        :error="sources.error.value"
        @retry="sources.refetch()"
      />
      <p v-else-if="sources.isPending.value" class="muted">
        {{ backend.ready ? "Loading the sources…" : "Waiting for the backend…" }}
      </p>
      <p v-else-if="configurable.length === 0" class="muted" data-testid="settings-sources-empty">
        None of your {{ kind }} sources has settings of its own.
      </p>
      <ul v-else class="card list" data-testid="settings-sources">
        <li v-for="s in configurable" :key="s.id">
          <RouterLink
            :to="`/settings/source/${s.id}`"
            class="row source-link"
            data-testid="settings-source"
          >
            <span class="text">
              <span>{{ s.name }}</span>
              <span class="summary">{{ formatLang(s.lang) }} · {{ s.extension.name }}</span>
            </span>
            <span aria-hidden="true" class="chevron">›</span>
          </RouterLink>
        </li>
      </ul>
      <p v-if="unknown > 0" class="muted note" data-testid="settings-sources-unknown">
        {{ unknown }} {{ unknown === 1 ? "source isn't" : "sources aren't" }} loaded, so
        {{ unknown === 1 ? "its settings aren't" : "their settings aren't" }} known.
        <RouterLink to="/extensions">Extensions</RouterLink> shows why.
      </p>
    </section>
  </PageLayout>
</template>

<style scoped>
.group {
  display: grid;
  gap: var(--space-2);
  max-width: 880px;
}

h2 {
  margin: 0;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
}

.card {
  display: grid;
  margin: 0;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  list-style: none;
}

.card > * + * {
  border-top: 1px solid var(--color-border);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin: 0;
  padding: var(--space-3) var(--space-4);
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.summary {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.value {
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
  text-align: right;
}

.path {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
  user-select: all;
}

.path-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.path-actions .btn {
  flex: none;
  white-space: nowrap;
}

.text-field {
  width: 220px;
  flex: none;
}

.mpv-row {
  align-items: flex-start;
}

.mpv-field {
  display: grid;
  gap: var(--space-1);
  justify-items: end;
  min-width: 0;
}

.path-field {
  width: 340px;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
}

.hidden-input {
  display: none;
}

.backup-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.backup-name {
  overflow-wrap: anywhere;
}

.restore-status progress {
  width: 100%;
  margin-top: var(--space-2);
}

.problems {
  margin: var(--space-2) 0 0;
  padding-left: var(--space-5);
  color: var(--color-text-secondary);
}

.danger {
  color: var(--color-danger);
}

.source-link {
  color: var(--color-text);
  text-decoration: none;
}

.source-link:hover {
  background: var(--color-surface-hover);
}

.chevron {
  color: var(--color-text-secondary);
  font-size: var(--font-size-body-large);
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}

.note {
  font-size: var(--font-size-caption);
}

/* Segmented control (Reka ToggleGroup), as the sidebar's anime/manga switch. */
.segmented {
  display: grid;
  flex: none;
  grid-template-columns: repeat(3, minmax(72px, 1fr));
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
}

.segment {
  min-height: calc(var(--control-height) - 6px);
  padding: 0 var(--space-3);
  border: 0;
  border-radius: calc(var(--radius-sm) - 1px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  cursor: pointer;
}

.segment:hover {
  background: var(--color-surface-hover);
}

.segment[data-state="on"] {
  background: var(--color-surface-raised);
  color: var(--color-text);
  box-shadow: 0 0 0 1px var(--color-border);
}

.switch {
  position: relative;
  flex: none;
  width: 40px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--color-text-secondary);
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
}

.switch[data-state="checked"] {
  border-color: var(--color-accent);
  background: var(--color-accent);
}

.switch-thumb {
  display: block;
  width: 12px;
  height: 12px;
  margin-left: 3px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--easing-standard);
}

.switch[data-state="checked"] .switch-thumb {
  background: var(--color-on-accent);
  transform: translateX(20px);
}
</style>
