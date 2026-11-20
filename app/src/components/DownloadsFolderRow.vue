<script setup lang="ts">
import { computed, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import type { DownloadsFolderMode } from "../api/types";
import { useDownloadsFolder, useSetDownloadsFolder } from "../composables/useDownloads";
import { useBackendStore } from "../stores/backend";
import { formatBytes } from "../utils/downloads";
import { revealFolder } from "../utils/folders";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * Settings → Downloads → Download folder (Phase 7 Stage 3): where downloads go, Show in Explorer, Change… (the shell's
 * folder picker) and Use the default. Each change asks what to do with what is downloaded (the user's decision):
 * move it to the new folder, or start an empty folder and leave the old files where they are. A move shows its
 * progress; a folder that isn't there (an unplugged drive) says so.
 */
const props = defineProps<{ fallbackPath: string }>();

const backend = useBackendStore();
const folder = useDownloadsFolder();
const setFolder = useSetDownloadsFolder();

const current = computed(() => folder.data.value ?? null);
const path = computed(() => current.value?.path ?? props.fallbackPath);
const change = computed(() => current.value?.change ?? null);
const moving = computed(() => change.value?.state === "MOVING");
const managed = computed(() => backend.mode === "tauri");

/** The folder picked, waiting for the choice (null: the default folder). */
const target = ref<string | null | undefined>(undefined);
const dialogOpen = computed({
  get: () => target.value !== undefined,
  set: (v) => {
    if (!v) target.value = undefined;
  },
});
const mode = ref<DownloadsFolderMode>("MOVE");
const pickError = ref<string | null>(null);

const targetText = computed(() =>
  target.value === null ? (current.value?.defaultPath ?? "the default folder") : target.value,
);

async function choose() {
  pickError.value = null;
  try {
    const picked = await invoke<string | null>("pick_folder", {
      title: "Choose the downloads folder",
      start: path.value,
    });
    if (picked) open(picked);
  } catch (e) {
    pickError.value = e instanceof Error ? e.message : String(e);
  }
}

function open(next: string | null) {
  setFolder.reset();
  mode.value = current.value?.reachable === false ? "START_EMPTY" : "MOVE";
  target.value = next;
}

function confirm() {
  if (target.value === undefined) return;
  setFolder.mutate(
    { path: target.value, mode: mode.value },
    { onSuccess: () => (target.value = undefined) },
  );
}

const progress = computed(() => {
  const c = change.value;
  if (!c || c.state !== "MOVING" || c.mode !== "MOVE") return null;
  return {
    value: c.bytesTotal > 0 ? c.bytesDone / c.bytesTotal : 0,
    text: `${c.filesDone} of ${c.filesTotal} files · ${formatBytes(c.bytesDone)} of ${formatBytes(c.bytesTotal)}`,
  };
});

const doneText = computed(() => {
  const c = change.value;
  if (!c || c.state !== "DONE") return null;
  const what =
    c.mode === "MOVE"
      ? `Moved ${c.filesTotal} files (${formatBytes(c.bytesTotal)}) to the new folder.`
      : `Now saving to the new folder. The old files stay in ${c.from}.`;
  return c.message ? `${what} Note: ${c.message}.` : what;
});
</script>

<template>
  <div class="folder-row" data-testid="downloads-folder">
    <div class="line">
      <div class="text">
        <span>Download folder</span>
        <span class="summary">
          Chapters are saved as CBZ files under manga/, episodes under anime/ with their subtitles
          beside them.
        </span>
      </div>
      <div class="path-actions">
        <code class="path" data-testid="downloads-path">{{ path }}</code>
        <template v-if="managed">
          <button
            type="button"
            class="btn"
            data-action="open-downloads"
            :disabled="current?.reachable === false"
            @click="revealFolder(path)"
          >
            Show in Explorer
          </button>
          <button
            type="button"
            class="btn"
            data-action="change-downloads-folder"
            :disabled="moving || !backend.ready"
            @click="choose"
          >
            Change…
          </button>
          <button
            v-if="current && !current.isDefault"
            type="button"
            class="btn"
            data-action="default-downloads-folder"
            :disabled="moving"
            @click="open(null)"
          >
            Use the default
          </button>
        </template>
      </div>
    </div>
    <p
      v-if="current?.reachable === false"
      class="note danger"
      role="alert"
      data-testid="folder-unreachable"
    >
      This folder isn't reachable (is its drive unplugged?). Downloads wait, and downloaded chapters
      and episodes can't be opened, until it is back. Or choose another folder and start it empty.
    </p>
    <div v-if="moving" class="note" role="status" data-testid="folder-moving">
      <span>Moving the downloads to {{ change?.to }}…</span>
      <progress v-if="progress" :value="progress.value" max="1"></progress>
      <span v-if="progress">{{ progress.text }}</span>
    </div>
    <p
      v-else-if="change?.state === 'FAILED'"
      class="note danger"
      role="alert"
      data-testid="folder-failed"
    >
      Changing the folder failed, and nothing moved: {{ change.message }}
    </p>
    <p v-else-if="doneText" class="note" role="status" data-testid="folder-done">{{ doneText }}</p>
    <p v-if="pickError" class="note danger" role="alert">
      Couldn't open the folder picker: {{ pickError }}
    </p>

    <DialogRoot v-model:open="dialogOpen">
      <DialogPortal>
        <DialogOverlay class="dialog-overlay" />
        <DialogContent class="dialog folder-dialog" data-testid="folder-dialog">
          <DialogTitle class="dialog-title">Change the downloads folder?</DialogTitle>
          <DialogDescription class="dialog-description">
            New downloads will be saved in <code>{{ targetText }}</code
            >. What should happen to what is already downloaded in <code>{{ path }}</code
            >?
          </DialogDescription>
          <div class="choices" role="radiogroup">
            <label class="choice">
              <input
                v-model="mode"
                type="radio"
                value="MOVE"
                data-testid="folder-mode-move"
                :disabled="current?.reachable === false"
              />
              <span>
                <strong>Move my downloads</strong>
                <span class="hint">
                  Everything in the folder moves to the new one. Across drives this copies first and
                  deletes after, so it can take a while.
                </span>
              </span>
            </label>
            <label class="choice">
              <input
                v-model="mode"
                type="radio"
                value="START_EMPTY"
                data-testid="folder-mode-empty"
              />
              <span>
                <strong>Start an empty folder</strong>
                <span class="hint">
                  The old files stay where they are, but Madoyomi forgets them: chapters and
                  episodes are no longer marked downloaded.
                </span>
              </span>
            </label>
          </div>
          <p class="hint">
            The new folder must be empty, and no download may be queued or running (pause them
            first).
          </p>
          <ErrorNotice
            v-if="setFolder.error.value"
            :error="setFolder.error.value"
            title="The folder wasn't changed"
          />
          <div class="dialog-actions">
            <DialogClose class="btn" data-action="cancel-folder">Cancel</DialogClose>
            <button
              type="button"
              class="btn btn-accent"
              data-action="confirm-folder"
              :disabled="setFolder.isPending.value"
              @click="confirm"
            >
              {{ mode === "MOVE" ? "Move and change" : "Change folder" }}
            </button>
          </div>
        </DialogContent>
      </DialogPortal>
    </DialogRoot>
  </div>
</template>

<style scoped>
.folder-row {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
}

.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.summary,
.note,
.hint {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.note {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  overflow-wrap: anywhere;
}

.note progress {
  width: 100%;
}

.danger {
  color: var(--color-danger);
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
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  min-width: 0;
}

.path-actions .btn {
  flex: none;
  white-space: nowrap;
}

.choices {
  display: grid;
  gap: var(--space-3);
}

.choice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
}

.choice > span {
  display: grid;
  gap: 2px;
}

.dialog-description code {
  overflow-wrap: anywhere;
}
</style>
