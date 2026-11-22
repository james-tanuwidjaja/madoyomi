<script setup lang="ts">
import { computed } from "vue";
import type { MediaKind } from "../api/types";
import {
  useLibraryCheck,
  useStartLibraryUpdate,
  useStopLibraryUpdate,
} from "../composables/useLibraryUpdates";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * "Update library", in the Library's toolbar and on the Updates page: checks the kind's library for
 * new episodes or chapters (the categories flagged for updates), or with `category` only that category, whatever its
 * flag. While a check runs (of either kind, scheduled too) it shows how far it got and Stop.
 */
const props = defineProps<{ kind: MediaKind; category?: { id: number; name: string } | null }>();

const check = useLibraryCheck();
const start = useStartLibraryUpdate();
const stop = useStopLibraryUpdate();

const running = computed(() => check.data.value?.running === true);
const progress = computed(() => {
  const c = check.data.value;
  return c ? `${c.done.toLocaleString("en-US")} of ${c.total.toLocaleString("en-US")}` : "";
});
const error = computed(() => start.error.value ?? stop.error.value);

function run(categoryId?: number) {
  stop.reset();
  start.mutate({ kind: props.kind, categoryId });
}
</script>

<template>
  <span class="update" data-testid="library-update">
    <template v-if="running">
      <span class="muted" role="status" data-testid="update-progress"
        >Checking {{ progress }}…</span
      >
      <button
        type="button"
        class="btn"
        data-action="stop-update"
        :disabled="stop.isPending.value"
        @click="stop.mutate()"
      >
        Stop
      </button>
    </template>
    <template v-else>
      <button
        type="button"
        class="btn"
        data-action="update-library"
        :disabled="start.isPending.value || !check.isSuccess.value"
        title="Check the library's sources for new episodes and chapters"
        @click="run()"
      >
        Update library
      </button>
      <button
        v-if="category"
        type="button"
        class="btn"
        data-action="update-category"
        :disabled="start.isPending.value || !check.isSuccess.value"
        :title="`Check only ${category.name}, whatever its update setting`"
        @click="run(category.id)"
      >
        Update {{ category.name }}
      </button>
    </template>
    <ErrorNotice
      v-if="error"
      class="update-error"
      title="Couldn't start the check"
      :error="error"
    />
  </span>
</template>

<style scoped>
.update {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.update-error {
  flex-basis: 100%;
}

.muted {
  color: var(--color-text-tertiary);
}
</style>
