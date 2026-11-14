<script setup lang="ts">
import { computed } from "vue";
import { describeError } from "../../api/errorText";
import { isApiError } from "../../api/client";

/**
 * A page that failed to load (Phase 5, both modes): the error with its code and the source's HTTP status, and a click
 * to fetch it again (the click doesn't reach the reader's zones).
 */
const props = defineProps<{ index: number; error: unknown }>();
const emit = defineEmits<{ retry: [index: number] }>();

const failure = computed(() => {
  const d = describeError(props.error);
  const details = isApiError(props.error)
    ? (props.error.details as { httpCode?: number } | null)
    : null;
  return { ...d, httpCode: details?.httpCode ?? null };
});
</script>

<template>
  <button
    type="button"
    class="page-failed"
    data-testid="page-failed"
    @click.stop="emit('retry', index)"
  >
    <span class="title">Couldn't load page {{ index + 1 }}</span>
    <span v-if="failure.hint" class="hint">{{ failure.hint }}</span>
    <span class="message">
      <code v-if="failure.code">{{ failure.code }}</code>
      <template v-if="failure.httpCode"> HTTP {{ failure.httpCode }}:</template>
      {{ failure.message }}
    </span>
    <span class="retry">Click to retry</span>
  </button>
</template>

<style scoped>
.page-failed {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  max-width: 480px;
  margin: 0;
  padding: var(--space-5);
  border: 1px solid var(--reader-line, rgb(255 255 255 / 16%));
  border-radius: var(--radius-md);
  background: var(--reader-veil, rgb(255 255 255 / 6%));
  color: var(--reader-muted, rgb(255 255 255 / 70%));
  font: inherit;
  text-align: center;
  cursor: pointer;
}

.title {
  color: var(--reader-fg, #fff);
  font-size: var(--font-size-body-large);
  font-weight: var(--font-weight-semibold);
}

.retry {
  color: var(--color-accent);
}
</style>
