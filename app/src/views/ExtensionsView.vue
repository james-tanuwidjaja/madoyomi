<script setup lang="ts">
import { RouterLink } from "vue-router";
import { useMediaKindStore } from "../stores/mediaKind";
import PageLayout from "../components/PageLayout.vue";
import PagePlaceholder from "../components/PagePlaceholder.vue";

const mediaKind = useMediaKindStore();
</script>

<template>
  <PageLayout title="Extensions">
    <template #actions>
      <RouterLink to="/repos" class="btn">Repos</RouterLink>
    </template>
    <PagePlaceholder
      stage="Stage 3"
      :summary="`Available, installed and updatable ${mediaKind.kind} extensions, with search, language and NSFW filters, install progress and the trust prompt.`"
    />
  </PageLayout>
</template>

<style scoped>
.btn {
  text-decoration: none;
}
</style>
