<script setup lang="ts">
import { onMounted, ref } from "vue";
import { getVersion } from "@tauri-apps/api/app";
import { invoke, isTauri } from "@tauri-apps/api/core";

type BackendStatus =
  { state: "checking" } | { state: "ok"; url: string } | { state: "error"; message: string };

const version = ref<string | null>(null);
// Null outside Tauri (plain `pnpm dev` in a browser): there is no sidecar to talk to.
const backend = ref<BackendStatus | null>(null);

async function checkBackend() {
  backend.value = { state: "checking" };
  try {
    const url = await invoke<string>("get_backend_url");
    const res = await fetch(`${url}/health`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = (await res.json()) as { status?: string };
    if (body.status !== "ok") throw new Error(`unexpected status ${String(body.status)}`);
    backend.value = { state: "ok", url };
  } catch (e) {
    backend.value = { state: "error", message: e instanceof Error ? e.message : String(e) };
  }
}

onMounted(async () => {
  if (!isTauri()) return;
  try {
    version.value = await getVersion();
  } catch {
    version.value = null;
  }
  await checkBackend();
});
</script>

<template>
  <main class="home">
    <h1>Madoyomi</h1>
    <p class="tagline">窓 · 読み — anime &amp; manga for Windows</p>
    <p v-if="version" class="version">v{{ version }}</p>
    <p v-if="backend" class="backend" :data-state="backend.state">
      <template v-if="backend.state === 'checking'">Backend: starting…</template>
      <template v-else-if="backend.state === 'ok'">Backend: ok ({{ backend.url }})</template>
      <template v-else>Backend: error ({{ backend.message }})</template>
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

.tagline {
  opacity: 0.7;
}

.version,
.backend {
  font-size: 0.85rem;
  opacity: 0.5;
}

.backend[data-state="error"] {
  color: #d33;
  opacity: 0.9;
}
</style>
