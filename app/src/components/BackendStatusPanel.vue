<script setup lang="ts">
import { computed } from "vue";
import { useBackendStore } from "../stores/backend";

/** What the backend is doing while it isn't ready: starting, restarting, failed. Used full-screen and as overlay. */
const backend = useBackendStore();

type View = "starting" | "connecting" | "restarting" | "failed" | "stopped";

const view = computed<View>(() => {
  switch (backend.status?.state) {
    case "healthy":
      return "connecting";
    case "restarting":
      return "restarting";
    case "failed":
      return "failed";
    case "stopped":
      return "stopped";
    default:
      return "starting";
  }
});

const seconds = (ms: number | null | undefined) => Math.max(0, Math.ceil((ms ?? 0) / 1000));
const elapsed = computed(() => Math.floor((backend.status?.elapsedMs ?? 0) / 1000));
const showLogs = computed(() => backend.mode === "tauri" && !!backend.status?.logDir);
</script>

<template>
  <section
    class="panel"
    :data-state="view"
    :role="view === 'failed' ? 'alert' : 'status'"
    aria-live="polite"
  >
    <template v-if="view === 'starting' || view === 'connecting'">
      <div class="spinner" aria-hidden="true"></div>
      <h2>{{ view === "starting" ? "Starting the backend…" : "Connecting…" }}</h2>
      <p v-if="view === 'starting' && elapsed > 0" class="detail elapsed">
        {{ elapsed }} s<template v-if="(backend.status?.attempt ?? 1) > 1">
          · attempt {{ backend.status?.attempt }}</template
        >
      </p>
      <p v-if="backend.status?.slow" class="detail slow">
        This can take a minute or more on a busy machine.
      </p>
    </template>

    <template v-else-if="view === 'restarting'">
      <div class="spinner" aria-hidden="true"></div>
      <h2>The backend stopped</h2>
      <p class="error">{{ backend.status?.error }}</p>
      <p class="detail retry">Restarting in {{ seconds(backend.status?.retryInMs) }} s…</p>
    </template>

    <template v-else-if="view === 'failed'">
      <h2>The backend could not start</h2>
      <p class="error">{{ backend.status?.error }}</p>
      <p v-if="backend.mode === 'tauri'" class="detail">
        Madoyomi stopped restarting it after {{ backend.status?.attempt }}
        {{ backend.status?.attempt === 1 ? "try" : "tries" }}.
      </p>
    </template>

    <template v-else>
      <h2>The backend is stopped</h2>
    </template>

    <p v-if="showLogs && (view === 'failed' || view === 'restarting')" class="detail logs">
      Logs: <code>{{ backend.status?.logDir }}</code>
    </p>
    <div v-if="view === 'failed' || view === 'restarting'" class="actions">
      <button type="button" class="btn btn-accent retry-button" @click="backend.retry()">
        {{ view === "failed" ? "Retry" : "Restart now" }}
      </button>
      <button v-if="showLogs" type="button" class="btn logs-button" @click="backend.openLogs()">
        Open logs
      </button>
    </div>
  </section>
</template>

<style scoped>
.panel {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  max-width: 560px;
  padding: var(--space-6);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-flyout);
  text-align: center;
}

h2 {
  margin: var(--space-2) 0 0;
  font-family: var(--font-family-display);
  font-size: var(--font-size-subtitle);
  font-weight: var(--font-weight-semibold);
}

p {
  margin: 0;
}

.detail {
  color: var(--color-text-secondary);
}

.error {
  color: var(--color-danger);
  overflow-wrap: anywhere;
}

.logs code {
  overflow-wrap: anywhere;
  user-select: all;
}

.actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-border-strong);
  border-top-color: var(--color-accent);
  border-radius: 50%;
  animation: spin 900ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 3s;
  }
}
</style>
