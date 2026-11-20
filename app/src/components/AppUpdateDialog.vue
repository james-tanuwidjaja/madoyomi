<script setup lang="ts">
import { computed, watch } from "vue";
import {
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import { useAppUpdateStore } from "../stores/appUpdate";
import { useBackendStore } from "../stores/backend";
import { usePlaybackStore } from "../stores/playback";
import { useDownloadQueue } from "../composables/useDownloads";

/**
 * "Madoyomi X is out" (Phase 7 Stage 4): the version and its release notes, then Install now, Later or Skip this
 * version (the user's choices). Install downloads the installer (its progress shown) while everything keeps running,
 * then the shell stops the backend (mpv quits, running downloads go back in the queue for the next start) and starts
 * the installer, which brings the new version up. While an episode plays or downloads run, the dialog says what will
 * stop before Install goes ahead. An MSI install (per-machine) can't update itself: it links the release page.
 *
 * The check on start runs once the backend is healthy, and only when Settings → General → "Check for app updates"
 * is on (off by default).
 */
const updates = useAppUpdateStore();
const backend = useBackendStore();
const playback = usePlaybackStore();
const queue = useDownloadQueue();

watch(
  () => backend.ready,
  async (ready) => {
    if (!ready || backend.mode !== "tauri") return;
    if (!updates.settings) await updates.loadSettings();
    await updates.checkOnStart();
  },
  { immediate: true },
);

const open = computed({
  get: () => updates.available !== null || updates.startError !== null,
  set: (value) => {
    if (!value) updates.later();
  },
});

const update = computed(() => updates.available);
const downloading = computed(
  () => (queue.data.value?.items ?? []).filter((i) => i.state === "DOWNLOADING").length,
);
const playing = computed(() => playback.session !== null);

/** What Install stops, said before it does. */
const busyNote = computed(() => {
  const parts: string[] = [];
  if (playing.value) parts.push("the episode playing stops");
  if (downloading.value > 0) {
    parts.push(
      downloading.value === 1
        ? "1 download pauses and goes on after the update"
        : `${downloading.value} downloads pause and go on after the update`,
    );
  }
  if (parts.length === 0) return null;
  const text = parts.join(", and ");
  return `Installing now: ${text}.`;
});

const percent = computed(() => {
  const p = updates.progress;
  if (p?.phase !== "downloading" || !p.total) return null;
  return Math.min(100, Math.round((p.downloaded / p.total) * 100));
});

const progressText = computed(() => {
  const p = updates.progress;
  if (!updates.installing) return null;
  if (!p) return "Starting the download…";
  if (p.phase === "downloading") {
    const mb = (n: number) => (n / 1024 / 1024).toFixed(1);
    return p.total
      ? `Downloading: ${mb(p.downloaded)} of ${mb(p.total)} MB`
      : `Downloading: ${mb(p.downloaded)} MB`;
  }
  if (p.phase === "stopping") return "Stopping the backend and the player…";
  return "Starting the installer. Madoyomi closes and opens again when it's done.";
});

const date = computed(() => {
  const d = update.value?.date;
  if (!d) return null;
  const parsed = new Date(d);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toLocaleDateString();
});
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay update-overlay" />
      <DialogContent
        v-if="!update && updates.startError"
        class="dialog update-dialog"
        data-testid="app-update-check-failed"
      >
        <DialogTitle class="dialog-title">Couldn't check for app updates</DialogTitle>
        <DialogDescription class="dialog-description">
          <span class="error" role="alert">{{ updates.startError }}</span>
          Madoyomi tries again at the next start; the check can be turned off in Settings → General.
        </DialogDescription>
        <div class="dialog-actions">
          <button
            type="button"
            class="btn"
            data-action="close-update-error"
            @click="updates.later()"
          >
            Close
          </button>
        </div>
      </DialogContent>
      <DialogContent
        v-else-if="update"
        class="dialog update-dialog"
        data-testid="app-update-dialog"
        @escape-key-down="(e: Event) => updates.installing && e.preventDefault()"
        @pointer-down-outside="(e: Event) => updates.installing && e.preventDefault()"
      >
        <DialogTitle class="dialog-title">Madoyomi {{ update.version }} is out</DialogTitle>
        <DialogDescription class="dialog-description">
          You have {{ update.currentVersion }}.<template v-if="date">
            Released {{ date }}.</template
          >
          <template v-if="!update.canInstall">
            This copy was installed with the MSI installer, which doesn't update itself: download
            the new version from the release page.
          </template>
        </DialogDescription>
        <pre v-if="update.notes" class="notes" data-testid="app-update-notes">{{
          update.notes
        }}</pre>
        <p
          v-if="busyNote && update.canInstall && !updates.installing"
          class="busy"
          data-testid="app-update-busy"
        >
          {{ busyNote }}
        </p>
        <div v-if="updates.installing" class="progress" data-testid="app-update-progress">
          <progress v-if="percent !== null" max="100" :value="percent" />
          <progress v-else />
          <span>{{ progressText }}</span>
        </div>
        <p v-if="updates.installError" class="error" role="alert" data-testid="app-update-error">
          {{ updates.installError }}
        </p>
        <div class="dialog-actions">
          <template v-if="updates.needsRestart">
            <button
              type="button"
              class="btn btn-accent"
              data-action="restart-app"
              @click="updates.restartApp()"
            >
              Restart Madoyomi
            </button>
          </template>
          <template v-else>
            <button
              type="button"
              class="btn"
              data-action="skip-update"
              :disabled="updates.installing"
              @click="updates.skip()"
            >
              Skip this version
            </button>
            <button
              type="button"
              class="btn"
              data-action="later-update"
              :disabled="updates.installing"
              @click="updates.later()"
            >
              Later
            </button>
            <button
              v-if="update.canInstall"
              type="button"
              class="btn btn-accent"
              data-action="install-update"
              :disabled="updates.installing"
              @click="updates.install()"
            >
              {{ busyNote ? "Install anyway" : "Install now" }}
            </button>
            <button
              v-else
              type="button"
              class="btn btn-accent"
              data-action="open-releases"
              @click="updates.openReleases()"
            >
              Open the release page
            </button>
          </template>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.update-dialog {
  display: grid;
  gap: var(--space-3);
  max-width: 560px;
}

.notes {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--color-surface-sunken);
  font-family: inherit;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.busy {
  margin: 0;
  color: var(--color-warning);
}

.progress {
  display: grid;
  gap: var(--space-2);
}

.progress progress {
  width: 100%;
}

.error {
  margin: 0;
  color: var(--color-danger);
  overflow-wrap: anywhere;
}
</style>

<style>
/* above the backend's restart overlay (BackendGate, z-index 1000), below the crash dialog */
.dialog-overlay.update-overlay {
  z-index: 1001;
}

.dialog.update-dialog {
  z-index: 1001;
}
</style>
