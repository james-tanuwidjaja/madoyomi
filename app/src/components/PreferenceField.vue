<script setup lang="ts">
import { computed, ref, useId } from "vue";
import {
  CheckboxIndicator,
  CheckboxRoot,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
  SelectContent,
  SelectItem,
  SelectItemIndicator,
  SelectItemText,
  SelectPortal,
  SelectRoot,
  SelectTrigger,
  SelectValue,
  SelectViewport,
  SwitchRoot,
  SwitchThumb,
} from "reka-ui";
import type { Preference } from "../api/types";
import type { PreferenceStatus } from "../composables/usePreferences";
import {
  editTextValueLine,
  entryLabel,
  preferenceSummary,
  preferenceValue,
  sameValue,
  valueLabel,
  type PreferenceValue,
} from "../utils/preferences";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * One preference of a source's settings screen, as a row: its title and summary, and the control for its kind over
 * Reka's primitives. A switch, checkbox or list applies at once; an edit text and a multi-select open a dialog, as
 * on Android, and apply on Save. It emits the new value only when it differs; PreferenceRenderer sends the change.
 */
const props = defineProps<{
  preference: Preference;
  /** The last change's outcome. */
  status?: PreferenceStatus;
  /** Who refused a value, in the refusal line ("AnimePahe didn't accept …"). */
  owner?: string;
}>();
const emit = defineEmits<{ update: [value: PreferenceValue] }>();

const id = useId();
const p = computed(() => props.preference);
const value = computed(() => preferenceValue(p.value));
const summary = computed(() => preferenceSummary(p.value));
const valueLine = computed(() => editTextValueLine(p.value));
const title = computed(() => p.value.title || p.value.key || "Untitled setting");
const disabled = computed(() => !p.value.enabled);

function update(next: PreferenceValue) {
  if (!sameValue(next, value.value)) emit("update", next);
}

// List: Reka's Select works on the entry's index (an entry value may be "", which a select item can't be).
const listIndex = computed(() =>
  p.value.__typename === "ListPreference" ? p.value.entryValues.indexOf(value.value as string) : -1,
);
function pickListEntry(index: number) {
  if (p.value.__typename !== "ListPreference") return;
  const v = p.value.entryValues[index];
  if (v !== undefined) update(v);
}

// Edit text and multi-select: a dialog with a draft, applied on Save.
const dialogOpen = ref(false);
const draftText = ref("");
const draftValues = ref<string[]>([]);
const dialogTitle = computed(() =>
  p.value.__typename === "EditTextPreference" || p.value.__typename === "MultiSelectListPreference"
    ? p.value.dialogTitle || title.value
    : title.value,
);
const dialogMessage = computed(() =>
  p.value.__typename === "EditTextPreference" || p.value.__typename === "MultiSelectListPreference"
    ? p.value.dialogMessage
    : null,
);

function openDialog() {
  if (disabled.value) return;
  if (p.value.__typename === "EditTextPreference") draftText.value = value.value as string;
  else draftValues.value = [...(value.value as string[])];
  dialogOpen.value = true;
}

function toggleDraft(v: string, on: boolean) {
  const set = new Set(draftValues.value);
  if (on) set.add(v);
  else set.delete(v);
  // the entries' order, not the order of the clicks
  if (p.value.__typename === "MultiSelectListPreference") {
    draftValues.value = p.value.entryValues.filter((x) => set.has(x));
  }
}

function saveDialog() {
  dialogOpen.value = false;
  update(p.value.__typename === "EditTextPreference" ? draftText.value : draftValues.value);
}

const multiCount = computed(() =>
  p.value.__typename === "MultiSelectListPreference" ? (value.value as string[]).length : 0,
);
const multiLabel = computed(() => {
  if (p.value.__typename !== "MultiSelectListPreference") return "";
  const n = multiCount.value;
  if (n === 0) return "None";
  if (n === 1) return entryLabel(p.value, (value.value as string[])[0]);
  return `${n} selected`;
});
</script>

<template>
  <div
    class="pref"
    :class="{ disabled }"
    :data-preference="preference.__typename"
    :data-key="preference.key ?? undefined"
  >
    <div class="pref-row">
      <div class="text">
        <span :id="id" class="title">{{ title }}</span>
        <span v-if="summary" class="summary" data-testid="preference-summary">{{ summary }}</span>
        <span v-if="valueLine" class="value-line" data-testid="preference-value">{{
          valueLine
        }}</span>
      </div>

      <div class="control">
        <SwitchRoot
          v-if="preference.__typename === 'SwitchPreference'"
          class="switch"
          :model-value="value as boolean"
          :disabled="disabled"
          :aria-labelledby="id"
          @update:model-value="(v) => update(v === true)"
        >
          <SwitchThumb class="switch-thumb" />
        </SwitchRoot>

        <CheckboxRoot
          v-else-if="preference.__typename === 'CheckBoxPreference'"
          class="check"
          :model-value="value as boolean"
          :disabled="disabled"
          :aria-labelledby="id"
          @update:model-value="(v) => update(v === true)"
        >
          <CheckboxIndicator class="check-mark">✓</CheckboxIndicator>
        </CheckboxRoot>

        <SelectRoot
          v-else-if="preference.__typename === 'ListPreference'"
          :model-value="listIndex >= 0 ? listIndex : undefined"
          :disabled="disabled"
          @update:model-value="(v) => pickListEntry(Number(v))"
        >
          <SelectTrigger class="field select-trigger" :aria-labelledby="id">
            <SelectValue>{{ listIndex >= 0 ? preference.entries[listIndex] : "None" }}</SelectValue>
            <span aria-hidden="true" class="chevron">⌄</span>
          </SelectTrigger>
          <SelectPortal>
            <SelectContent class="flyout select-content" position="popper" :side-offset="4">
              <SelectViewport>
                <SelectItem
                  v-for="(label, i) in preference.entries"
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
          v-else
          type="button"
          class="btn value-button"
          data-action="edit"
          :disabled="disabled"
          :aria-labelledby="id"
          @click="openDialog"
        >
          <template v-if="preference.__typename === 'EditTextPreference'">Edit…</template>
          <template v-else>{{ multiLabel }}</template>
        </button>
      </div>
    </div>

    <p v-if="status?.state === 'saving'" class="status muted" data-testid="preference-saving">
      Saving…
    </p>
    <p
      v-else-if="status?.state === 'refused'"
      class="status refused"
      role="alert"
      data-testid="preference-refused"
    >
      {{ owner ?? "The extension" }} didn't accept that value and kept {{ valueLabel(preference) }}.
    </p>
    <ErrorNotice
      v-else-if="status?.state === 'error'"
      class="status"
      title="Couldn't save this setting"
      :error="status.error"
    />

    <DialogRoot
      v-if="
        preference.__typename === 'EditTextPreference' ||
        preference.__typename === 'MultiSelectListPreference'
      "
      v-model:open="dialogOpen"
    >
      <DialogPortal>
        <DialogOverlay class="dialog-overlay" />
        <!-- without a message there is no description: say so, rather than Reka warning about it -->
        <DialogContent
          class="dialog"
          data-testid="preference-dialog"
          v-bind="dialogMessage ? {} : { 'aria-describedby': undefined }"
        >
          <DialogTitle class="dialog-title">{{ dialogTitle }}</DialogTitle>
          <DialogDescription v-if="dialogMessage" class="dialog-description message">
            {{ dialogMessage }}
          </DialogDescription>

          <form
            v-if="preference.__typename === 'EditTextPreference'"
            class="dialog-form"
            @submit.prevent="saveDialog"
          >
            <input
              v-model="draftText"
              class="field"
              type="text"
              spellcheck="false"
              :aria-label="title"
              data-testid="preference-text"
            />
          </form>

          <ul v-else class="choices" data-testid="preference-choices">
            <li v-for="(v, i) in preference.entryValues" :key="v">
              <label class="check-row">
                <CheckboxRoot
                  class="check"
                  :model-value="draftValues.includes(v)"
                  @update:model-value="(on) => toggleDraft(v, on === true)"
                >
                  <CheckboxIndicator class="check-mark">✓</CheckboxIndicator>
                </CheckboxRoot>
                <span>{{ preference.entries[i] ?? v }}</span>
              </label>
            </li>
          </ul>

          <div class="dialog-actions">
            <DialogClose class="btn" data-action="cancel">Cancel</DialogClose>
            <button type="button" class="btn btn-accent" data-action="save" @click="saveDialog">
              Save
            </button>
          </div>
        </DialogContent>
      </DialogPortal>
    </DialogRoot>
  </div>
</template>

<style scoped>
.pref {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
}

.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.title {
  overflow-wrap: anywhere;
}

.summary {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
  white-space: pre-line;
}

.value-line {
  color: var(--color-text);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
}

.disabled .title,
.disabled .summary {
  color: var(--color-text-tertiary);
}

.control {
  display: flex;
  flex: none;
  justify-content: flex-end;
  max-width: 50%;
}

.status {
  margin: 0;
  font-size: var(--font-size-caption);
}

.muted {
  color: var(--color-text-tertiary);
}

.refused {
  color: var(--color-warning);
}

.switch {
  position: relative;
  width: 40px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--color-text-secondary);
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
}

.switch[data-state="checked"] {
  border-color: var(--color-accent);
  background: var(--color-accent);
}

.switch-thumb {
  display: block;
  width: 12px;
  height: 12px;
  margin-left: 3px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--easing-standard);
}

.switch[data-state="checked"] .switch-thumb {
  background: var(--color-on-accent);
  transform: translateX(20px);
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

.check-mark {
  font-size: 13px;
  line-height: 1;
}

.switch:disabled,
.check:disabled {
  opacity: 0.4;
  cursor: default;
}

.select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  min-width: 200px;
  max-width: 100%;
  text-align: left;
  cursor: pointer;
}

.select-trigger:disabled {
  opacity: 0.5;
  cursor: default;
}

.select-trigger :deep(span:first-child) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chevron {
  color: var(--color-text-secondary);
}

.value-button {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message {
  white-space: pre-line;
}

.dialog-form {
  display: grid;
}

.choices {
  display: grid;
  gap: var(--space-1);
  max-height: 50vh;
  margin: 0;
  overflow: auto;
  padding: 0;
  list-style: none;
}

.check-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 28px;
  cursor: pointer;
}
</style>
