<script setup lang="ts">
import { computed, ref } from "vue";
import {
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import { useBackendStore } from "../stores/backend";
import ExportLogs from "./ExportLogs.vue";

/**
 * The repeated-crash dialog (Phase 7 Stage 3): when the backend stopped `CRASH_ALERT` (3) times within 10 minutes
 * (the shell counts them) but still restarts, this says so, with Export logs and Open logs folder. Closing it keeps it
 * closed until the next crash. When the shell gives up (`failed`), the full-screen panel offers the same.
 */
const backend = useBackendStore();
const dismissedAt = ref(0);

const open = computed({
  get: () => {
    const s = backend.status;
    return (
      backend.mode === "tauri" &&
      !!s?.crashAlert &&
      s.state !== "failed" &&
      s.state !== "stopped" &&
      s.crashCount > dismissedAt.value
    );
  },
  set: (value) => {
    if (!value) dismissedAt.value = backend.status?.crashCount ?? 0;
  },
});
const crashes = computed(() => backend.status?.recentCrashes ?? 0);
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay crash-overlay" />
      <DialogContent class="dialog crash-dialog" data-testid="crash-dialog">
        <DialogTitle class="dialog-title">The backend keeps stopping</DialogTitle>
        <DialogDescription class="dialog-description">
          It stopped {{ crashes }} times in the last 10 minutes. Madoyomi starts it again each time,
          and the logs say why. Export them to attach to a bug report, or open the logs folder.
        </DialogDescription>
        <p v-if="backend.status?.error" class="last-error" data-testid="crash-error">
          Last time: {{ backend.status.error }}
        </p>
        <ExportLogs />
        <div class="dialog-actions">
          <button
            type="button"
            class="btn"
            data-action="open-logs-folder"
            @click="backend.openLogs()"
          >
            Open logs folder
          </button>
          <button type="button" class="btn" data-action="close-crash" @click="open = false">
            Close
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.crash-dialog {
  display: grid;
  gap: var(--space-3);
  max-width: 560px;
}

.last-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
}
</style>

<style>
/* above the backend's restart overlay (BackendGate, z-index 1000) */
.dialog-overlay.crash-overlay {
  z-index: 1001;
}

.dialog.crash-dialog {
  z-index: 1002;
}
</style>
