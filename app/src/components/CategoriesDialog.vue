<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import type { LibraryCategory, LibraryEntry, MediaKind } from "../api/types";
import {
  useCategories,
  useCreateCategory,
  useDeleteCategory,
  useMoveCategory,
  useUpdateCategory,
} from "../composables/useCategories";
import { categoryNameError, entriesIn } from "../utils/categories";
import { formatCount } from "../utils/format";
import ErrorNotice from "./ErrorNotice.vue";

/*
 * The Library's "Categories" dialog (Phase 6 Stage 1): the kind's categories in order, to create, rename (inline),
 * move up and down, flag for new entries ("Add new entries here", upstream's `default`) and delete (confirmed in the
 * row, saying where its entries go). Default is built in and isn't listed: an entry is in it by being in no other.
 * Every change is saved at once.
 */
const props = defineProps<{ kind: MediaKind; entries: readonly LibraryEntry[] }>();
const open = defineModel<boolean>("open", { required: true });

const categories = useCategories(() => props.kind);
const create = useCreateCategory();
const update = useUpdateCategory();
const move = useMoveCategory();
const remove = useDeleteCategory();

const list = computed(() => (categories.data.value ?? []).filter((c) => !c.isDefaultCategory));

const newName = ref("");
const newTouched = ref(false);
const newError = computed(() => categoryNameError(newName.value, categories.data.value ?? []));

const editing = ref<number | null>(null);
const editName = ref("");
const editError = computed(() =>
  editing.value === null
    ? null
    : categoryNameError(editName.value, categories.data.value ?? [], editing.value),
);
const confirmingDelete = ref<number | null>(null);
const rowInputs = ref<Record<number, HTMLInputElement | null>>({});

watch(open, (isOpen) => {
  if (!isOpen) return;
  newName.value = "";
  newTouched.value = false;
  editing.value = null;
  confirmingDelete.value = null;
  for (const m of [create, update, move, remove]) m.reset();
});

/** The last change's error, whichever it was. */
const error = computed(
  () => create.error.value ?? update.error.value ?? move.error.value ?? remove.error.value,
);

async function add() {
  newTouched.value = true;
  if (newError.value || create.isPending.value) return;
  try {
    await create.mutateAsync({ kind: props.kind, name: newName.value });
    newName.value = "";
    newTouched.value = false;
  } catch {
    // shown below
  }
}

async function startRename(c: LibraryCategory) {
  editing.value = c.id;
  editName.value = c.name;
  confirmingDelete.value = null;
  await nextTick();
  rowInputs.value[c.id]?.select();
}

async function saveRename(c: LibraryCategory) {
  if (editing.value !== c.id) return;
  if (editName.value.trim() === c.name) {
    editing.value = null;
    return;
  }
  if (editError.value) return;
  try {
    await update.mutateAsync({ kind: props.kind, id: c.id, patch: { name: editName.value } });
    editing.value = null;
  } catch {
    // shown below; the field stays open
  }
}

function moveBy(c: LibraryCategory, step: -1 | 1) {
  const index = list.value.findIndex((x) => x.id === c.id);
  const position = index + 1 + step;
  if (index < 0 || position < 1 || position > list.value.length) return;
  move.mutate({ kind: props.kind, id: c.id, position });
}

function toggleNewEntries(c: LibraryCategory) {
  update.mutate({ kind: props.kind, id: c.id, patch: { default: !c.default } });
}

function countIn(c: LibraryCategory) {
  return entriesIn(props.entries, c).length;
}

async function confirmDelete(c: LibraryCategory) {
  try {
    await remove.mutateAsync({ kind: props.kind, id: c.id });
    confirmingDelete.value = null;
  } catch {
    // shown below
  }
}
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="dialog wide" data-testid="categories-dialog">
        <DialogTitle class="dialog-title"
          >{{ kind === "anime" ? "Anime" : "Manga" }} categories</DialogTitle
        >
        <DialogDescription class="dialog-description">
          Changes are saved at once. Entries in no category are in Default.
        </DialogDescription>

        <form class="new" @submit.prevent="add">
          <input
            v-model="newName"
            class="field"
            type="text"
            maxlength="64"
            placeholder="New category"
            aria-label="New category"
            data-testid="new-category-name"
            @input="newTouched = true"
          />
          <button
            type="submit"
            class="btn btn-accent"
            data-action="add-category"
            :disabled="!newName.trim() || create.isPending.value"
          >
            Add
          </button>
        </form>
        <p
          v-if="newTouched && newName.trim() && newError"
          class="field-error"
          data-testid="new-category-error"
        >
          {{ newError }}
        </p>

        <ErrorNotice
          v-if="categories.isError.value"
          title="Couldn't load the categories"
          :error="categories.error.value"
          @retry="categories.refetch()"
        />
        <p v-else-if="categories.isPending.value" class="muted">Loading…</p>
        <p v-else-if="list.length === 0" class="muted" data-testid="categories-none">
          No categories yet. With one, the Library shows a tab per category.
        </p>
        <ol v-else class="rows" data-testid="category-rows">
          <li
            v-for="(c, i) in list"
            :key="c.id"
            class="row"
            :data-id="c.id"
            data-testid="category-row"
          >
            <template v-if="confirmingDelete === c.id">
              <span class="grow" data-testid="delete-question">
                Delete “{{ c.name }}”?
                <span class="muted">
                  {{
                    countIn(c)
                      ? `Its ${formatCount(countIn(c), "entry goes", "entries go")} to Default unless in another category.`
                      : "It has no entries."
                  }}
                </span>
              </span>
              <button
                type="button"
                class="btn danger"
                data-action="confirm-delete"
                :disabled="remove.isPending.value"
                @click="confirmDelete(c)"
              >
                Delete
              </button>
              <button type="button" class="btn" data-action="keep" @click="confirmingDelete = null">
                Keep
              </button>
            </template>
            <template v-else>
              <form v-if="editing === c.id" class="grow rename" @submit.prevent="saveRename(c)">
                <input
                  :ref="(el) => (rowInputs[c.id] = el as HTMLInputElement | null)"
                  v-model="editName"
                  class="field"
                  type="text"
                  maxlength="64"
                  :aria-label="`Rename ${c.name}`"
                  data-testid="rename-field"
                  @keydown.esc.stop.prevent="editing = null"
                />
                <button
                  type="submit"
                  class="btn"
                  data-action="save-rename"
                  :disabled="!!editError || update.isPending.value"
                >
                  Save
                </button>
                <span v-if="editError" class="field-error" data-testid="rename-error">{{
                  editError
                }}</span>
              </form>
              <span v-else class="grow name">
                <span data-testid="category-name">{{ c.name }}</span>
                <span class="muted count">{{ countIn(c).toLocaleString("en-US") }}</span>
              </span>
              <label class="flag" :title="'New library entries go into it'">
                <input
                  type="checkbox"
                  :checked="c.default"
                  data-testid="category-default"
                  @change="toggleNewEntries(c)"
                />
                New entries
              </label>
              <button
                type="button"
                class="btn icon"
                :aria-label="`Move ${c.name} up`"
                data-action="move-up"
                :disabled="i === 0"
                @click="moveBy(c, -1)"
              >
                ↑
              </button>
              <button
                type="button"
                class="btn icon"
                :aria-label="`Move ${c.name} down`"
                data-action="move-down"
                :disabled="i === list.length - 1"
                @click="moveBy(c, 1)"
              >
                ↓
              </button>
              <button
                v-if="editing !== c.id"
                type="button"
                class="btn"
                data-action="rename"
                @click="startRename(c)"
              >
                Rename
              </button>
              <button
                type="button"
                class="btn"
                data-action="delete"
                @click="
                  confirmingDelete = c.id;
                  editing = null;
                "
              >
                Delete
              </button>
            </template>
          </li>
        </ol>

        <ErrorNotice v-if="error" title="Couldn't save the change" :error="error" />

        <div class="dialog-actions">
          <DialogClose class="btn" data-action="close-categories">Close</DialogClose>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.wide {
  width: min(640px, calc(100vw - 2 * var(--space-6)));
}

.new,
.rename {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.new .field,
.rename .field {
  flex: 1;
  min-width: 0;
}

.rows {
  display: grid;
  gap: var(--space-1);
  max-height: 50vh;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.grow {
  flex: 1;
  min-width: 0;
}

.name {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  overflow-wrap: anywhere;
}

.count {
  font-size: var(--font-size-caption);
}

.flag {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  white-space: nowrap;
}

.danger {
  border-color: var(--color-danger);
  color: var(--color-danger);
}

.icon {
  min-width: 2rem;
  padding-inline: var(--space-2);
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
