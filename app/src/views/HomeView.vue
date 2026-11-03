<script setup lang="ts">
import { onMounted, ref } from "vue";
import { getVersion } from "@tauri-apps/api/app";

const version = ref<string | null>(null);

onMounted(async () => {
  try {
    version.value = await getVersion();
  } catch {
    // Not running inside Tauri (e.g. plain `pnpm dev` in a browser).
    version.value = null;
  }
});
</script>

<template>
  <main class="home">
    <h1>Madoyomi</h1>
    <p class="tagline">窓 · 読み — anime &amp; manga for Windows</p>
    <p v-if="version" class="version">v{{ version }}</p>
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

.version {
  font-size: 0.85rem;
  opacity: 0.5;
}
</style>
