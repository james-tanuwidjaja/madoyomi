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
import type { LibraryEntry, MediaKind } from "../api/types";
import {
  useCategories,
  useCreateCategory,
  useSetEntriesCategories,
} from "../composables/useCategories";
import {
  assignmentPatch,
  categoryNameError,
  membership,
  nextMembership,
  type Membership,
} from "../utils/categories";
import { formatCount } from "../utils/format";
import ErrorNotice from "./ErrorNotice.vue";

/*
 * Sets the categories of one library entry or of several (Phase 6 Stage 1), as Mihon's "Set categories": a box per
 * category (Default isn't one: an entry is in it by being in no other). With several entries a box can be mixed (some
 * of them are in it); a click makes it checked, then unchecked. A mixed box left alone changes nothing. Save sends one
 * change for all the entries; a new category can be made here and is checked at once.
 */
const props = defineProps<{ kind: MediaKind; entries: readonly LibraryEntry[] }>();
const open = defineModel<boolean>("open", { required: true });
const emit = defineEmits<{ saved: [] }>();

const categories = useCategories(() => props.kind);
const create = useCreateCategory();
const save = useSetEntriesCategories();

const choices = computed(() => (categories.data.value ?? []).filter((c) => !c.isDefaultCategory));
const before = ref(new Map<number, Membership>());
const after = ref(new Map<number, Membership>());
const newName = ref("");
const nameTouched = ref(false);

function reset() {
  const initial = new Map(choices.value.map((c) => [c.id, membership(props.entries, c.id)]));
  before.value = initial;
  after.value = new Map(initial);
  newName.value = "";
  nameTouched.value = false;
  create.reset();
  save.reset();
}

watch(open, (isOpen) => {
  if (isOpen) reset();
});
// categories that arrive (or are made) while it is open get their state
watch(choices, (list) => {
  for (const c of list) {
    if (!before.value.has(c.id)) {
      const state = membership(props.entries, c.id);
      before.value.set(c.id, state);
      if (!after.value.has(c.id)) after.value.set(c.id, state);
    }
  }
});

const stateOf = (id: number): Membership => after.value.get(id) ?? "none";
function toggle(id: number) {
  after.value = new Map(after.value).set(id, nextMembership(stateOf(id)));
}

const patch = computed(() => assignmentPatch(before.value, after.value));
const changed = computed(() => patch.value.add.length + patch.value.remove.length > 0);

const nameError = computed(() => categoryNameError(newName.value, categories.data.value ?? []));
async function addCategory() {
  nameTouched.value = true;
  if (nameError.value || create.isPending.value) return;
  try {
    const category = await create.mutateAsync({ kind: props.kind, name: newName.value });
    before.value.set(category.id, "none");
    after.value = new Map(after.value).set(category.id, "all");
    newName.value = "";
    nameTouched.value = false;
  } catch {
    // shown below, from create.error
  }
}

async function confirm() {
  if (!changed.value) {
    open.value = false;
    return;
  }
  try {
    await save.mutateAsync({
      kind: props.kind,
      ids: props.entries.map((e) => e.id),
      ...patch.value,
    });
    open.value = false;
    emit("saved");
  } catch {
    // shown below, from save.error
  }
}

const description = computed(() =>
  props.entries.length === 1
    ? `“${props.entries[0]!.title}” is in the checked categories. In none, it's in Default.`
    : `${formatCount(props.entries.length, "entry", "entries")} selected. A mixed box means some of them are in it.`,
);
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="dialog" data-testid="category-picker">
        <DialogTitle class="dialog-title">Set categories</DialogTitle>
        <DialogDescription class="dialog-description">{{ description }}</DialogDescription>

        <ErrorNotice
          v-if="categories.isError.value"
          title="Couldn't load the categories"
          :error="categories.error.value"
          @retry="categories.refetch()"
        />
        <p v-else-if="categories.isPending.value" class="muted">Loading…</p>
        <p v-else-if="choices.length === 0" class="muted" data-testid="picker-none">
          No categories yet. Make one below.
        </p>
        <ul v-else class="choices">
          <li v-for="c in choices" :key="c.id">
            <label class="choice" :data-id="c.id" data-testid="picker-choice">
              <input
                type="checkbox"
                :checked="stateOf(c.id) === 'all'"
                :indeterminate="stateOf(c.id) === 'some'"
                :data-state="stateOf(c.id)"
                @change="toggle(c.id)"
              />
              <span>{{ c.name }}</span>
            </label>
          </li>
        </ul>

        <form class="new" @submit.prevent="addCategory">
          <input
            v-model="newName"
            class="field"
            type="text"
            maxlength="64"
            placeholder="New category"
            aria-label="New category"
            data-testid="picker-new-name"
            @input="nameTouched = true"
          />
          <button
            type="submit"
            class="btn"
            data-action="picker-add"
            :disabled="!newName.trim() || create.isPending.value"
          >
            Add
          </button>
        </form>
        <p
          v-if="nameTouched && newName.trim() && nameError"
          class="field-error"
          data-testid="picker-name-error"
        >
          {{ nameError }}
        </p>

        <ErrorNotice
          v-if="create.error.value"
          title="Couldn't add it"
          :error="create.error.value"
        />
        <ErrorNotice v-if="save.error.value" title="Couldn't save" :error="save.error.value" />

        <div class="dialog-actions">
          <DialogClose class="btn" data-action="picker-cancel">Cancel</DialogClose>
          <button
            type="button"
            class="btn btn-accent"
            data-action="picker-save"
            :disabled="save.isPending.value"
            @click="confirm"
          >
            {{ save.isPending.value ? "Saving…" : "Save" }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.choices {
  display: grid;
  gap: var(--space-1);
  max-height: 50vh;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.choice {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.choice:hover {
  background: var(--color-surface-hover);
}

.choice input {
  margin: 0;
}

.new {
  display: flex;
  gap: var(--space-2);
}

.new .field {
  flex: 1;
  min-width: 0;
}

.field-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}

.muted {
  margin: 0;
  color: var(--color-text-tertiary);
}
</style>
