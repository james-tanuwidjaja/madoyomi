<script setup lang="ts">
import { onMounted } from "vue";
import { useBackendStore } from "../stores/backend";
import BackendStatusPanel from "./BackendStatusPanel.vue";

/**
 * Shows the app (the slot) once the backend is ready. Before that, a full-screen splash or error. After it has been
 * ready once, the app stays mounted and a restart shows an overlay over it.
 */
const backend = useBackendStore();

onMounted(() => void backend.init());
</script>

<template>
  <template v-if="backend.mode === 'browser' || backend.hasBeenReady">
    <slot />
    <p v-if="backend.mode === 'browser'" class="browser-banner" role="status">
      Browser mode: no backend. Use <code>pnpm tauri dev</code>, or set
      <code>VITE_MADOYOMI_URL</code> and <code>VITE_MADOYOMI_TOKEN</code> in
      <code>.env.local</code>.
    </p>
    <div v-else-if="!backend.ready" class="overlay">
      <BackendStatusPanel />
    </div>
  </template>
  <div v-else class="screen">
    <BackendStatusPanel />
  </div>
</template>

<style scoped>
.screen,
.overlay {
  display: grid;
  place-items: center;
  padding: var(--space-5);
}

.screen {
  min-height: 100vh;
}

.overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: var(--color-overlay);
}

.browser-banner {
  position: fixed;
  right: var(--space-3);
  bottom: var(--space-3);
  max-width: 420px;
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-raised);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  box-shadow: var(--shadow-flyout);
}
</style>
