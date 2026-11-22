<script setup lang="ts">
import { computed, ref } from "vue";
import { useBackendStore } from "../stores/backend";
import { exportLogs, fileNameOf, type ExportSummary } from "../utils/logExport";
import { formatBytes } from "../utils/downloads";
import { revealFolder } from "../utils/folders";

/**
 * Export logs, in Settings → Backend, the crash dialog and the failed panel: an opt-in box for the
 * library's titles, the button (the shell's save dialog, then the zip), and what was written with Show in folder.
 */
const props = withDefaults(defineProps<{ compact?: boolean }>(), { compact: false });

const backend = useBackendStore();
const includeLibrary = ref(false);
const busy = ref(false);
const result = ref<ExportSummary | null>(null);
const error = ref<string | null>(null);

const withoutBackend = computed(() => !backend.ready);

async function run() {
  busy.value = true;
  error.value = null;
  try {
    const written = await exportLogs({
      includeLibrary: includeLibrary.value,
      backendReady: backend.ready,
    });
    if (written) result.value = written;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="export" :class="{ compact: props.compact }" data-testid="export-logs">
    <div class="controls">
      <label class="opt-in">
        <input v-model="includeLibrary" type="checkbox" data-testid="export-include-library" />
        Include my library list (titles and sources)
      </label>
      <button
        type="button"
        class="btn"
        :class="{ 'btn-accent': props.compact }"
        data-action="export-logs"
        :disabled="busy"
        @click="run"
      >
        {{ busy ? "Exporting…" : "Export logs…" }}
      </button>
    </div>
    <p v-if="withoutBackend" class="note" data-testid="export-without-backend">
      The backend isn't running: the zip gets the logs and the installed extensions' names, without
      its report.
    </p>
    <p v-if="result" class="note saved" role="status" data-testid="export-result">
      Saved {{ fileNameOf(result.path) }} ({{ formatBytes(result.bytes) }},
      {{ result.files.length }} files).
      <button
        type="button"
        class="link"
        data-action="show-export"
        @click="revealFolder(result.path)"
      >
        Show in folder
      </button>
    </p>
    <p v-if="error" class="note danger" role="alert" data-testid="export-error">
      Couldn't export the logs: {{ error }}
    </p>
  </div>
</template>

<style scoped>
.export {
  display: grid;
  gap: var(--space-1);
  justify-items: end;
}

.export.compact {
  justify-items: center;
}

.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
}

.opt-in {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
}

.danger {
  color: var(--color-danger);
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-accent);
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}
</style>
