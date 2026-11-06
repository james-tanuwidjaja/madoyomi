<script setup lang="ts">
/** A page: the title row (with optional actions) and the content below it. */
defineProps<{ title: string; subtitle?: string }>();
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>{{ title }}</h1>
        <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </header>
    <slot />
  </section>
</template>

<style scoped>
.page {
  display: grid;
  align-content: start;
  gap: var(--space-4);
  max-width: 1200px;
  padding: var(--space-5) var(--space-6);
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

h1 {
  margin: 0;
  font-family: var(--font-family-display);
  font-size: var(--font-size-title);
  font-weight: var(--font-weight-semibold);
}

.subtitle {
  margin: var(--space-1) 0 0;
  color: var(--color-text-secondary);
}

.actions {
  display: flex;
  gap: var(--space-2);
}
</style>
