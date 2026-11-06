<script setup lang="ts">
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

defineProps<{ id: number }>();
</script>

<template>
  <PageLayout title="Anime" :subtitle="`anime #${id}`">
    <PagePlaceholder
      stage="Stage 5"
      summary="Cover, title, status, genres and description, refresh, add to library, and the episode list with sort and filter."
    />
  </PageLayout>
</template>
