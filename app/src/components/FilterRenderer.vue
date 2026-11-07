<script setup lang="ts">
import { CollapsibleContent, CollapsibleRoot, CollapsibleTrigger } from "reka-ui";
import type { Filter, GroupFilter } from "../api/types";
import {
  activeFilterCount,
  withFilterValue,
  type FilterState,
  type LeafValue,
} from "../utils/filters";
import FilterField from "./FilterField.vue";

/**
 * A source's filter list, driven by what the backend reports (Phase 3 step 18): Header, Separator, Text, CheckBox,
 * TriState, Select, Sort and Group (collapsible, one level). It edits `modelValue`, a {@link FilterState} parallel to
 * `filters`; `filterChanges` (utils/filters.ts) turns that into what the backend takes.
 */
const props = defineProps<{ filters: readonly Filter[]; modelValue: FilterState }>();
const emit = defineEmits<{ "update:modelValue": [state: FilterState] }>();

function set(path: readonly [number] | readonly [number, number], value: LeafValue) {
  emit("update:modelValue", withFilterValue(props.modelValue, path, value));
}

const members = (i: number) =>
  Array.isArray(props.modelValue[i]) ? (props.modelValue[i] as LeafValue[]) : [];

/** How many of a group's members differ from their defaults, for its header. */
const changedIn = (group: GroupFilter, i: number) => activeFilterCount([group], [members(i)]);
</script>

<template>
  <div class="filter-list" data-testid="filter-renderer">
    <template v-for="(f, i) in filters" :key="i">
      <CollapsibleRoot
        v-if="f.__typename === 'GroupFilter'"
        class="group"
        data-filter="group"
        :data-position="i"
      >
        <CollapsibleTrigger class="group-trigger">
          <span>{{ f.name }}</span>
          <span v-if="changedIn(f, i)" class="badge">{{ changedIn(f, i) }}</span>
          <span aria-hidden="true" class="chevron">⌄</span>
        </CollapsibleTrigger>
        <CollapsibleContent class="group-content">
          <div v-for="(child, j) in f.filters" :key="j" :data-position="`${i}.${j}`">
            <FilterField
              :filter="child"
              :value="members(i)[j] ?? null"
              @update="(v) => set([i, j], v)"
            />
          </div>
        </CollapsibleContent>
      </CollapsibleRoot>
      <div v-else :data-position="i">
        <FilterField
          :filter="f"
          :value="(modelValue[i] as LeafValue) ?? null"
          @update="(v) => set([i], v)"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.filter-list {
  display: grid;
  gap: var(--space-3);
}

.group {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.group-trigger {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  min-height: 36px;
  padding: 0 var(--space-3);
  border: 0;
  background: none;
  color: var(--color-text);
  font: inherit;
  font-weight: var(--font-weight-semibold);
  text-align: left;
  cursor: pointer;
}

.group-trigger .chevron {
  margin-left: auto;
  color: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--easing-standard);
}

.group-trigger[data-state="open"] .chevron {
  transform: rotate(180deg);
}

.group-content {
  display: grid;
  gap: var(--space-1);
  padding: 0 var(--space-3) var(--space-3);
}

/* Reka keeps the closed content's element (hidden="", children unmounted); `display: grid` would show its padding */
.group-content[hidden] {
  display: none;
}
</style>
