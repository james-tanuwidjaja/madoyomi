<script setup lang="ts">
import { RouterView } from "vue-router";
import AppShell from "./components/AppShell.vue";
import BackendGate from "./components/BackendGate.vue";
</script>

<template>
  <BackendGate>
    <AppShell>
      <RouterView />
    </AppShell>
  </BackendGate>
</template>
