<script setup lang="ts">
import type { Preference, SourcePreferenceChange } from "../api/types";
import type { PreferenceStatus } from "../composables/usePreferences";
import { changeFor, type PreferenceValue } from "../utils/preferences";
import PreferenceField from "./PreferenceField.vue";

/**
 * A source's settings screen (Phase 3 step 19), drawn from the backend's `Preference` union: List, EditText, Switch,
 * CheckBox and MultiSelect, each with its title and summary, disabled when the extension disables it, hidden when it
 * hides it. A change emits the one preference that changed, by its position in the full list (hidden ones count),
 * which is how the server addresses it.
 */
const props = defineProps<{
  preferences: Preference[];
  /** Per position: the outcome of the last change. */
  status?: Record<number, PreferenceStatus>;
  /** The extension, named in a refusal. */
  owner?: string;
}>();
const emit = defineEmits<{ change: [change: SourcePreferenceChange] }>();

function onUpdate(position: number, value: PreferenceValue) {
  const p = props.preferences[position];
  if (p) emit("change", changeFor(position, p, value));
}
</script>

<template>
  <div class="preferences" data-testid="preferences">
    <template v-for="(p, i) in preferences" :key="`${i}:${p.key ?? ''}`">
      <PreferenceField
        v-if="p.visible"
        :preference="p"
        :status="status?.[i]"
        :owner="owner"
        :data-position="i"
        @update="(v) => onUpdate(i, v)"
      />
    </template>
  </div>
</template>

<style scoped>
.preferences {
  display: grid;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.preferences > :deep(* + *) {
  border-top: 1px solid var(--color-border);
}
</style>
