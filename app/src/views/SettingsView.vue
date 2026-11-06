<script setup lang="ts">
import { useMediaKindStore } from "../stores/mediaKind";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

const mediaKind = useMediaKindStore();
</script>

<template>
  <PageLayout title="Settings">
    <PagePlaceholder
      stage="Stage 6"
      :summary="`General settings (theme, language), the backend (data folder, logs) and ${mediaKind.kind} source preferences.`"
    />
  </PageLayout>
</template>
