<script setup lang="ts">
import { useMediaKindStore } from "../stores/mediaKind";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

const mediaKind = useMediaKindStore();
</script>

<template>
  <PageLayout title="Library">
    <PagePlaceholder
      stage="Stage 5"
      :summary="`Your saved ${mediaKind.kind}, with a sort and an empty state that links to Browse.`"
    />
  </PageLayout>
</template>
