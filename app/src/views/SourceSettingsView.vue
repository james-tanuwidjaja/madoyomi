<script setup lang="ts">
import { useMediaKindStore } from "../stores/mediaKind";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

defineProps<{ id: string }>();
const mediaKind = useMediaKindStore();
</script>

<template>
  <PageLayout title="Source settings" :subtitle="`${mediaKind.kind} source ${id}`">
    <PagePlaceholder
      stage="Stage 6"
      summary="The extension's own settings (lists, text, switches, multi-select), saved as you change them."
    />
  </PageLayout>
</template>
