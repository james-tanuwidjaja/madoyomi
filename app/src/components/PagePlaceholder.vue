<script setup lang="ts">
/** A screen that a later stage builds (Phase 3 §8): says which, and what it will hold. */
defineProps<{ stage: string; summary: string }>();
</script>

<template>
  <div class="placeholder" role="note">
    <p class="summary">{{ summary }}</p>
    <p class="stage">Coming in {{ stage }}.</p>
    <slot />
  </div>
</template>

<style scoped>
.placeholder {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-5);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
}

p {
  margin: 0;
}

.stage {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}
</style>
