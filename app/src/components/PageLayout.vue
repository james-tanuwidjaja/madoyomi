<script setup lang="ts">
/**
 * A page: the title row (with optional actions) and the content below it. With `fill`, the page takes the window's
 * height and its last child gets what's left, for a page with its own scroll area (a virtualized list).
 */
defineProps<{ title: string; subtitle?: string; fill?: boolean }>();
</script>

<template>
  <section class="page" :class="{ fill }">
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

.page.fill {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding-bottom: 0;
}

.page.fill > :deep(:last-child) {
  flex: 1;
  min-height: 0;
}

.page-header {
  display: flex;
  flex: none;
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
