<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import type { UpdatesItem } from "../api/types";
import {
  useClearMarkOnOpen,
  useClearUpdateMarks,
  useLibraryCheck,
  useLibraryUpdateSettings,
  useUpdateMarks,
  useUpdates,
} from "../composables/useLibraryUpdates";
import { useBackendStore } from "../stores/backend";
import { useMediaKindStore } from "../stores/mediaKind";
import { groupByDay } from "../utils/history";
import { checkSummary, clockTime, failuresOf } from "../utils/updates";
import ErrorNotice from "../components/ErrorNotice.vue";
import LibraryUpdateButton from "../components/LibraryUpdateButton.vue";
import PageLayout from "../components/PageLayout.vue";
import UpdatesRow from "../components/UpdatesRow.vue";

/*
 * Updates of the kind the switch shows, as History: the new episodes or chapters of library entries,
 * newest fetch first, grouped by day, 50 at a time (Mihon's Updates screen). A row opens the episode's picker or the
 * chapter in the reader, and clears its entry's "new" mark; "Mark all seen" clears them all. The header says what the
 * last check found and when the next scheduled one comes, has "Update library" (Stop while one runs), and lists the
 * entries a check couldn't reach.
 */
const backend = useBackendStore();
const mediaKind = useMediaKindStore();
const kind = computed(() => mediaKind.kind);

const updates = useUpdates(kind);
const check = useLibraryCheck();
const settings = useLibraryUpdateSettings();
const marks = useUpdateMarks(kind);
const clearAll = useClearUpdateMarks();
const clearOne = useClearMarkOnOpen();

// the summary's "5 min ago" moves on while the page is open
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => (timer = setInterval(() => (now.value = Date.now()), 30_000)));
onBeforeUnmount(() => clearInterval(timer));

const days = computed(() => groupByDay<UpdatesItem>(updates.items.value));
const summary = computed(() => checkSummary(check.data.value, kind.value, now.value));
const failures = computed(() => failuresOf(check.data.value, kind.value));
const schedule = computed(() => {
  const s = settings.data.value;
  if (!s) return null;
  if (s.intervalHours === 0) return "Scheduled checks are off";
  const next = check.data.value?.nextScheduledAt;
  return next
    ? `Every ${s.intervalHours} h, next at ${clockTime(Number(next))}`
    : `Every ${s.intervalHours} h`;
});
const subtitle = computed(() => [summary.value, schedule.value].filter(Boolean).join(" · "));
const what = computed(() => (kind.value === "anime" ? "episodes" : "chapters"));

function opened(item: UpdatesItem) {
  clearOne(kind.value, item.entry.id);
}
</script>

<template>
  <PageLayout title="Updates" :subtitle="subtitle || `New ${what} in your ${kind} library`">
    <template #actions>
      <button
        v-if="marks.total.value > 0"
        type="button"
        class="btn"
        data-action="mark-all-seen"
        :disabled="clearAll.isPending.value"
        title="Take the New mark off every entry"
        @click="clearAll.mutate({ kind })"
      >
        Mark all seen
      </button>
      <LibraryUpdateButton :kind="kind" />
    </template>

    <details v-if="failures.length" class="failures" data-testid="update-failures">
      <summary>
        {{ failures.length.toLocaleString("en-US") }}
        {{ failures.length === 1 ? "entry" : "entries" }} couldn't be checked
      </summary>
      <ul>
        <li v-for="f in failures" :key="f.entry.id">
          <RouterLink :to="`/${kind}/${f.entry.id}`">{{ f.entry.title }}</RouterLink>
          <span class="muted">: {{ f.error }}</span>
        </li>
      </ul>
    </details>

    <ErrorNotice
      v-if="updates.isError.value"
      :title="`Couldn't load the ${kind} updates`"
      :error="updates.error.value"
      @retry="updates.refetch()"
    />
    <p v-else-if="updates.isPending.value" class="muted">
      {{ backend.ready ? "Loading…" : "Waiting for the backend…" }}
    </p>
    <div v-else-if="updates.items.value.length === 0" class="empty" data-testid="updates-empty">
      <p>No new {{ what }} yet.</p>
      <p class="muted">
        Update library checks the sources of your library's {{ kind }} for new {{ what }}; they show
        up here. Settings → Updates can run it on a schedule.
      </p>
      <RouterLink to="/settings" class="btn">Update settings</RouterLink>
    </div>
    <div v-else class="days" data-testid="updates">
      <section v-for="day in days" :key="day.key" class="day" data-testid="updates-day">
        <h2 class="day-label">{{ day.label }}</h2>
        <ul class="rows">
          <UpdatesRow
            v-for="item in day.items"
            :key="item.item.id"
            :item="item"
            @open="opened(item)"
          />
        </ul>
      </section>
      <button
        v-if="updates.hasNextPage.value"
        type="button"
        class="btn more"
        data-action="updates-more"
        :disabled="updates.isFetchingNextPage.value"
        @click="updates.fetchNextPage()"
      >
        {{ updates.isFetchingNextPage.value ? "Loading…" : "Show more" }}
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

.failures {
  max-width: 900px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

.failures summary {
  cursor: pointer;
}

.failures ul {
  margin: var(--space-2) 0 0;
  padding-left: var(--space-5);
}

.muted {
  color: var(--color-text-tertiary);
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
