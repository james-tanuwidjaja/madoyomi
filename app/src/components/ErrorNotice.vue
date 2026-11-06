<script setup lang="ts">
import { computed } from "vue";
import { describeError } from "../api/errorText";

/**
 * A failed request: what went wrong in plain words, the backend's error code and message, and a retry. The "Try
 * again" button shows only when the parent listens for `retry` (a form's error has nothing to retry by itself).
 */
const props = defineProps<{ error: unknown; title?: string; onRetry?: () => void }>();

const info = computed(() => describeError(props.error));
</script>

<template>
  <div class="error-notice" role="alert">
    <p class="title">{{ title ?? "Couldn't load this" }}</p>
    <p v-if="info.hint" class="hint">{{ info.hint }}</p>
    <p class="message">
      <code v-if="info.code" class="code">{{ info.code }}</code> {{ info.message }}
    </p>
    <button v-if="props.onRetry" type="button" class="btn" @click="props.onRetry()">
      Try again
    </button>
  </div>
</template>

<style scoped>
.error-notice {
  display: grid;
  justify-items: start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-danger-surface);
}

p {
  margin: 0;
}

.title {
  font-weight: var(--font-weight-semibold);
}

.message {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
}

.code {
  color: var(--color-danger);
}
</style>
