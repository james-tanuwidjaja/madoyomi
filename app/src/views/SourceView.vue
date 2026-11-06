<script setup lang="ts">
import { useMediaKindStore } from "../stores/mediaKind";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

defineProps<{ sourceId: string }>();
const mediaKind = useMediaKindStore();
</script>

<template>
  <PageLayout title="Source" :subtitle="`${mediaKind.kind} source ${sourceId}`">
    <PagePlaceholder
      stage="Stage 4"
      summary="Popular, Latest and Search, the filter sheet, and an endless grid of results."
    />
  </PageLayout>
</template>
