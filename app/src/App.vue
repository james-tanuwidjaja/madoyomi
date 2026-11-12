<script setup lang="ts">
import { RouterView, useRoute } from "vue-router";
import AppShell from "./components/AppShell.vue";
import BackendGate from "./components/BackendGate.vue";

// The reader (Phase 5) takes the whole window: its route is `bare`, without the shell.
const route = useRoute();
</script>

<template>
  <BackendGate>
    <RouterView v-if="route.meta.bare" />
    <AppShell v-else>
      <RouterView />
    </AppShell>
  </BackendGate>
</template>
