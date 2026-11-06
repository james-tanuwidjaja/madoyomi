<script setup lang="ts">
import { computed } from "vue";
import { isApiError } from "../api/client";

/** A failed request: what the backend said (its error code and message), and a retry. */
const props = defineProps<{ error: unknown; title?: string }>();
const emit = defineEmits<{ retry: [] }>();

const info = computed(() => {
  const e = props.error;
  if (isApiError(e)) return { code: e.code, message: e.message };
  return { code: null, message: e instanceof Error ? e.message : String(e) };
});
</script>

<template>
  <div class="error-notice" role="alert">
    <p class="title">{{ title ?? "Couldn't load this" }}</p>
    <p class="message">
      <code v-if="info.code" class="code">{{ info.code }}</code> {{ info.message }}
    </p>
    <button type="button" class="btn" @click="emit('retry')">Try again</button>
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

.code {
  color: var(--color-danger);
}
</style>
