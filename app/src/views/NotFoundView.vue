<script setup lang="ts">
import { RouterLink, useRoute } from "vue-router";
import PageLayout from "../components/PageLayout.vue";

const route = useRoute();
</script>

<template>
  <PageLayout title="Not found" :subtitle="route.fullPath">
    <p><RouterLink to="/library">Go to the library</RouterLink></p>
  </PageLayout>
</template>
