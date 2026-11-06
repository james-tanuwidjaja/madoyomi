<script setup lang="ts">
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

defineProps<{ id: number }>();
</script>

<template>
  <PageLayout title="Manga" :subtitle="`manga #${id}`">
    <PagePlaceholder
      stage="Stage 5"
      summary="Cover, title, status, genres and description, refresh, add to library, and the chapter list with sort and filter."
    />
  </PageLayout>
</template>
