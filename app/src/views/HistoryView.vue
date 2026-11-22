<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useClearHistory, useDeleteHistory, useHistory } from "../composables/useHistory";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { groupByDay } from "../utils/history";
import ErrorNotice from "../components/ErrorNotice.vue";
import HistoryRow from "../components/HistoryRow.vue";
import PageLayout from "../components/PageLayout.vue";

/*
 * History of the kind the switch shows (the user's choice, as every other section): one
 * row per entry, its episode or chapter played or read last, grouped by day, newest first, 50 at a time. Resume
 * opens the chapter in the reader at its page or the episode's picker on its details page (the next one when it is
 * finished); Remove takes the entry out of history; Clear all, confirmed by a second click, empties it. Neither
 * touches the watched or read marks and positions.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const kind = computed(() => mediaKind.kind);

const history = useHistory(kind);
const remove = useDeleteHistory();
const clear = useClearHistory();

const days = computed(() => groupByDay(history.items.value));
const confirmingClear = ref(false);
const removingId = ref<number | null>(null);
watch(kind, () => (confirmingClear.value = false));

const verb = computed(() => (kind.value === "anime" ? "watched" : "read"));

async function removeOne(id: number) {
  removingId.value = id;
  try {
    await remove.mutateAsync({ kind: kind.value, ids: [id] });
  } catch {
    // shown above the list
  } finally {
    removingId.value = null;
  }
}

async function clearAll() {
  if (!confirmingClear.value) {
    confirmingClear.value = true;
    return;
  }
  try {
    await clear.mutateAsync(kind.value);
  } catch {
    // shown above the list
  } finally {
    confirmingClear.value = false;
  }
}

const changeError = computed(() => remove.error.value ?? clear.error.value);
</script>

<template>
  <PageLayout title="History" :subtitle="`The ${kind} you ${verb} last`">
    <template v-if="history.items.value.length" #actions>
      <button
        type="button"
        class="btn"
        :class="{ danger: confirmingClear }"
        data-action="clear-history"
        :disabled="clear.isPending.value"
        @click="clearAll"
        @blur="confirmingClear = false"
      >
        {{ confirmingClear ? `Clear all ${kind} history?` : "Clear all" }}
      </button>
    </template>

    <ErrorNotice
      v-if="history.isError.value"
      :title="`Couldn't load your ${kind} history`"
      :error="history.error.value"
      @retry="history.refetch()"
    />
    <p v-else-if="history.isPending.value" class="muted">
      {{ backend.ready ? "Loading…" : "Waiting for the backend…" }}
    </p>
    <div v-else-if="history.items.value.length === 0" class="empty" data-testid="history-empty">
      <p>Nothing {{ verb }} yet.</p>
      <p class="muted">
        {{
          kind === "anime"
            ? "Episodes you play show up here, with where you stopped."
            : "Chapters you open show up here, at the page you reached."
        }}
      </p>
      <RouterLink to="/library" class="btn">Go to your {{ kind }} library</RouterLink>
    </div>
    <div v-else class="days" data-testid="history">
      <ErrorNotice v-if="changeError" title="Couldn't change the history" :error="changeError" />
      <section v-for="day in days" :key="day.key" class="day" data-testid="history-day">
        <h2 class="day-label">{{ day.label }}</h2>
        <ul class="rows">
          <HistoryRow
            v-for="item in day.items"
            :key="item.entry.id"
            :item="item"
            :removing="removingId === item.entry.id"
            @remove="removeOne(item.entry.id)"
          />
        </ul>
      </section>
      <button
        v-if="history.hasNextPage.value"
        type="button"
        class="btn more"
        data-action="history-more"
        :disabled="history.isFetchingNextPage.value"
        @click="history.fetchNextPage()"
      >
        {{ history.isFetchingNextPage.value ? "Loading…" : "Show more" }}
      </button>
    </div>
  </PageLayout>
</template>

<style scoped>
.days {
  display: grid;
  gap: var(--space-4);
  max-width: 900px;
}

.day {
  display: grid;
  gap: var(--space-1);
}

.day-label {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
}

.rows {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}

.more {
  justify-self: start;
}

.danger {
  border-color: var(--color-danger);
  color: var(--color-danger);
}

.empty {
  display: grid;
  justify-items: start;
  gap: var(--space-2);
}

.empty p {
  margin: 0;
}
</style>
