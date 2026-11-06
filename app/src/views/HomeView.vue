<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { getVersion } from "@tauri-apps/api/app";
import { isTauri } from "@tauri-apps/api/core";
import { useBackendStore } from "../stores/backend";

// Placeholder home until the app shell and its screens arrive (Phase 3 Stage 2).
const version = ref<string | null>(null);
const backend = useBackendStore();

const server = computed(() =>
  backend.ready && backend.status
    ? {
        version: backend.status.serverVersion ?? "?",
        url: backend.status.baseUrl,
      }
    : null,
);

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
  <main class="home">
    <h1>Madoyomi</h1>
    <p class="tagline">窓 · 読み — anime &amp; manga for Windows</p>
    <p v-if="version" class="version">v{{ version }}</p>
    <p v-if="server" class="backend" :data-state="backend.status?.state">
      Madoyomi-Server {{ server.version }} · {{ server.url }}
    </p>
  </main>
</template>

<style scoped>
.home {
  display: grid;
  place-content: center;
  min-height: 100vh;
  text-align: center;
}

h1 {
  margin: 0;
  font-family: var(--font-family-display);
  font-size: var(--font-size-title);
  font-weight: var(--font-weight-semibold);
}

.tagline {
  color: var(--color-text-secondary);
}

.version,
.backend {
  margin: 0;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}
</style>
