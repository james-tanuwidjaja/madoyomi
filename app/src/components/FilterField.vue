<script setup lang="ts">
import { computed, useId } from "vue";
import {
  CheckboxIndicator,
  CheckboxRoot,
  SelectContent,
  SelectItem,
  SelectItemIndicator,
  SelectItemText,
  SelectPortal,
  SelectRoot,
  SelectTrigger,
  SelectValue,
  SelectViewport,
} from "reka-ui";
import type { Filter, GroupFilter, SortSelection, TriState } from "../api/types";
import type { LeafValue } from "../utils/filters";

/**
 * One filter of a source's list (not a group: FilterRenderer draws those), as a control of our own look over Reka's
 * primitives. It shows `value` and emits the new value; FilterRenderer keeps the state.
 */
const props = defineProps<{ filter: Exclude<Filter, GroupFilter>; value: LeafValue }>();
const emit = defineEmits<{ update: [value: LeafValue] }>();

const id = useId();

const NEXT_TRI: Record<TriState, TriState> = {
  IGNORE: "INCLUDE",
  INCLUDE: "EXCLUDE",
  EXCLUDE: "IGNORE",
};
const TRI_LABEL: Record<TriState, string> = {
  IGNORE: "ignored",
  INCLUDE: "included",
  EXCLUDE: "excluded",
};

const tri = computed<TriState>(() => (props.value as TriState | null) ?? "IGNORE");
/** Reka's checkbox: checked for include, its indeterminate state (drawn as ✕) for exclude. */
const triChecked = computed(() =>
  tri.value === "INCLUDE" ? true : tri.value === "EXCLUDE" ? "indeterminate" : false,
);

const sort = computed(() => props.value as SortSelection | null);
/** -1 stands for "no sort" in the field list, offered only when the source's default is none. */
const NO_SORT = -1;

function pickSort(index: number) {
  if (index === NO_SORT) return emit("update", null);
  // a new field starts descending, as in Mihon; the same field keeps its direction
  emit("update", { index, ascending: sort.value?.ascending ?? false });
}

function flipSort() {
  if (sort.value) emit("update", { index: sort.value.index, ascending: !sort.value.ascending });
}
</script>

<template>
  <h3 v-if="filter.__typename === 'HeaderFilter'" class="header" data-filter="header">
    {{ filter.name }}
  </h3>

  <div
    v-else-if="filter.__typename === 'SeparatorFilter'"
    class="separator"
    role="separator"
    data-filter="separator"
  >
    <span v-if="filter.name">{{ filter.name }}</span>
  </div>

  <label v-else-if="filter.__typename === 'TextFilter'" class="field-row" data-filter="text">
    <span class="label">{{ filter.name }}</span>
    <input
      class="field"
      type="text"
      spellcheck="false"
      :value="value as string"
      @input="emit('update', ($event.target as HTMLInputElement).value)"
    />
  </label>

  <label
    v-else-if="filter.__typename === 'CheckBoxFilter'"
    class="check-row"
    data-filter="checkbox"
  >
    <CheckboxRoot
      class="check"
      :model-value="value === true"
      @update:model-value="(v) => emit('update', v === true)"
    >
      <CheckboxIndicator class="check-mark">✓</CheckboxIndicator>
    </CheckboxRoot>
    <span>{{ filter.name }}</span>
  </label>

  <label
    v-else-if="filter.__typename === 'TriStateFilter'"
    class="check-row"
    data-filter="tristate"
    :data-state="tri"
  >
    <CheckboxRoot
      class="check"
      :class="{ exclude: tri === 'EXCLUDE' }"
      :model-value="triChecked"
      :aria-label="`${filter.name}: ${TRI_LABEL[tri]}`"
      @update:model-value="emit('update', NEXT_TRI[tri])"
    >
      <CheckboxIndicator class="check-mark">{{ tri === "EXCLUDE" ? "✕" : "✓" }}</CheckboxIndicator>
    </CheckboxRoot>
    <span>{{ filter.name }}</span>
  </label>

  <div v-else-if="filter.__typename === 'SelectFilter'" class="field-row" data-filter="select">
    <span :id="id" class="label">{{ filter.name }}</span>
    <SelectRoot
      :model-value="value as number"
      @update:model-value="(v) => emit('update', Number(v))"
    >
      <SelectTrigger class="field select-trigger" :aria-labelledby="id">
        <SelectValue>{{ filter.values[value as number] ?? "" }}</SelectValue>
        <span aria-hidden="true" class="chevron">⌄</span>
      </SelectTrigger>
      <SelectPortal>
        <SelectContent class="flyout select-content" position="popper" :side-offset="4">
          <SelectViewport>
            <SelectItem v-for="(label, i) in filter.values" :key="i" :value="i" class="select-item">
              <SelectItemText>{{ label }}</SelectItemText>
              <SelectItemIndicator class="select-mark">✓</SelectItemIndicator>
            </SelectItem>
          </SelectViewport>
        </SelectContent>
      </SelectPortal>
    </SelectRoot>
  </div>

  <div v-else-if="filter.__typename === 'SortFilter'" class="field-row" data-filter="sort">
    <span :id="id" class="label">{{ filter.name }}</span>
    <div class="sort">
      <SelectRoot
        :model-value="sort?.index ?? NO_SORT"
        @update:model-value="(v) => pickSort(Number(v))"
      >
        <SelectTrigger class="field select-trigger" :aria-labelledby="id">
          <SelectValue>{{ sort ? filter.values[sort.index] : "None" }}</SelectValue>
          <span aria-hidden="true" class="chevron">⌄</span>
        </SelectTrigger>
        <SelectPortal>
          <SelectContent class="flyout select-content" position="popper" :side-offset="4">
            <SelectViewport>
              <SelectItem v-if="filter.sortDefault === null" :value="NO_SORT" class="select-item">
                <SelectItemText>None</SelectItemText>
                <SelectItemIndicator class="select-mark">✓</SelectItemIndicator>
              </SelectItem>
              <SelectItem
                v-for="(label, i) in filter.values"
                :key="i"
                :value="i"
                class="select-item"
              >
                <SelectItemText>{{ label }}</SelectItemText>
                <SelectItemIndicator class="select-mark">✓</SelectItemIndicator>
              </SelectItem>
            </SelectViewport>
          </SelectContent>
        </SelectPortal>
      </SelectRoot>
      <button
        type="button"
        class="btn sort-dir"
        data-action="sort-direction"
        :disabled="!sort"
        :aria-label="
          sort?.ascending ? 'Ascending; switch to descending' : 'Descending; switch to ascending'
        "
        @click="flipSort"
      >
        {{ sort?.ascending ? "↑ Ascending" : "↓ Descending" }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.header {
  margin: var(--space-2) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-semibold);
  text-transform: uppercase;
}

.separator {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}

.separator::before,
.separator::after {
  flex: 1;
  border-top: 1px solid var(--color-border);
  content: "";
}

.separator:empty::after {
  display: none;
}

.field-row {
  display: grid;
  gap: var(--space-1);
}

.label {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.check-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 28px;
  cursor: pointer;
}

.check {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--color-text-secondary);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-on-accent);
  cursor: pointer;
}

.check[data-state="checked"] {
  border-color: var(--color-accent);
  background: var(--color-accent);
}

.check.exclude {
  border-color: var(--color-danger);
  background: var(--color-danger);
}

.check-mark {
  font-size: 13px;
  line-height: 1;
}

.select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  width: 100%;
  text-align: left;
  cursor: pointer;
}

.chevron {
  color: var(--color-text-secondary);
}

.sort {
  display: flex;
  gap: var(--space-2);
}

.sort .select-trigger {
  flex: 1;
  min-width: 0;
}

.sort-dir {
  flex: none;
}
</style>

<style>
/* The select's list portals to <body>, above the filter sheet's dialog (z-index 51). */
.select-content {
  z-index: 60;
  min-width: var(--reka-select-trigger-width);
  max-height: min(360px, var(--reka-select-content-available-height));
  padding: var(--space-1);
}

.select-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  min-height: var(--control-height);
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm);
  color: var(--color-text);
  cursor: pointer;
  outline: none;
  user-select: none;
}

.select-item[data-highlighted] {
  background: var(--color-surface-hover);
}

.select-item[data-state="checked"] {
  font-weight: var(--font-weight-semibold);
}

.select-mark {
  color: var(--color-accent);
}
</style>
