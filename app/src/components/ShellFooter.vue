<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { getVersion } from "@tauri-apps/api/app";
import { isTauri } from "@tauri-apps/api/core";
import { useBackendStore } from "../stores/backend";
import { useEventsStore } from "../stores/events";

/** The sidebar's footer: the app version, the backend's version and address, and the event connection. */
const version = ref<string | null>(null);
const backend = useBackendStore();
const events = useEventsStore();

const server = computed(() =>
  backend.ready && backend.status
    ? {
        version: backend.status.serverVersion ?? "?",
        url: backend.status.baseUrl,
      }
    : null,
);

const live = computed(() => {
  if (!backend.ready) return { state: "offline", label: "Offline" };
  if (events.wsState === "connected") return { state: "live", label: "Live updates" };
  return { state: "connecting", label: "Connecting…" };
});

onMounted(async () => {
  // Null outside Tauri (plain `pnpm dev` in a browser).
  if (!isTauri()) return;
  try {
    version.value = await getVersion();
  } catch {
    version.value = null;
  }
});
</script>

<template>
  <footer class="shell-footer">
    <p class="app">
      <span class="name">Madoyomi</span>
      <span v-if="version" class="version">v{{ version }}</span>
    </p>
    <p v-if="server" class="backend" :data-state="backend.status?.state">
      Madoyomi-Server {{ server.version }} · {{ server.url }}
    </p>
    <p v-if="backend.mode !== 'browser'" class="live" :data-state="live.state">
      <span class="dot" aria-hidden="true" />{{ live.label }}
    </p>
  </footer>
</template>

<style scoped>
.shell-footer {
  display: grid;
  gap: 2px;
  padding: var(--space-3) var(--space-4);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}

p {
  margin: 0;
  overflow-wrap: anywhere;
}

.app {
  display: flex;
  gap: var(--space-2);
}

.name {
  color: var(--color-text-secondary);
}

.live {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
}

.live[data-state="live"] .dot {
  background: var(--color-success);
}

.live[data-state="connecting"] .dot {
  background: var(--color-warning);
}
</style>
