<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import type { Filter } from "../api/types";
import { activeFilterCount, defaultFilterState, type FilterState } from "../utils/filters";
import FilterRenderer from "./FilterRenderer.vue";

/**
 * The filter sheet of a source page: a side sheet (Reka Dialog) with the source's filters. It edits a copy of the
 * applied values, made each time it opens. Apply emits that copy; Reset puts the source's defaults back into it;
 * Cancel, Escape or a click outside throw it away.
 */
const props = defineProps<{ filters: readonly Filter[]; applied: FilterState | null }>();
const open = defineModel<boolean>("open", { required: true });
const emit = defineEmits<{ apply: [state: FilterState] }>();

const clone = (s: FilterState): FilterState => JSON.parse(JSON.stringify(s)) as FilterState;

const draft = ref<FilterState>(defaultFilterState(props.filters));

watch(
  open,
  (isOpen) => {
    if (isOpen) draft.value = clone(props.applied ?? defaultFilterState(props.filters));
  },
  { immediate: true },
);

const changed = computed(() => activeFilterCount(props.filters, draft.value));

function reset() {
  draft.value = defaultFilterState(props.filters);
}

function apply() {
  emit("apply", clone(draft.value));
  open.value = false;
}
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="sheet" data-testid="filter-sheet">
        <header class="sheet-header">
          <DialogTitle class="dialog-title">Filters</DialogTitle>
          <DialogDescription class="dialog-description">
            {{
              changed === 0
                ? "The source's defaults."
                : `${changed} ${changed === 1 ? "filter" : "filters"} changed from the source's defaults.`
            }}
            Filters apply to a search.
          </DialogDescription>
        </header>

        <div class="sheet-body">
          <FilterRenderer v-model="draft" :filters="filters" />
        </div>

        <footer class="sheet-actions">
          <button
            type="button"
            class="btn"
            data-action="reset"
            :disabled="changed === 0"
            @click="reset"
          >
            Reset
          </button>
          <span class="spacer" />
          <DialogClose class="btn" data-action="cancel">Cancel</DialogClose>
          <button type="button" class="btn btn-accent" data-action="apply" @click="apply">
            Apply
          </button>
        </footer>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
/* A side sheet: the window's full height at the right edge, over the dialog overlay. */
.sheet {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 51;
  display: flex;
  flex-direction: column;
  width: min(400px, 100vw);
  border-left: 1px solid var(--color-border-strong);
  background: var(--color-surface);
  box-shadow: var(--shadow-dialog);
}

.sheet-header {
  display: grid;
  gap: var(--space-1);
  padding: var(--space-5) var(--space-5) var(--space-3);
}

.sheet-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 var(--space-5) var(--space-4);
}

.sheet-actions {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--color-border);
}

.spacer {
  flex: 1;
}
</style>
