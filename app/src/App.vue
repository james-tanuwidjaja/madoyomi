<script setup lang="ts">
import { RouterView } from "vue-router";
import BackendGate from "./components/BackendGate.vue";
</script>

<template>
  <BackendGate>
    <RouterView />
  </BackendGate>
</template>
