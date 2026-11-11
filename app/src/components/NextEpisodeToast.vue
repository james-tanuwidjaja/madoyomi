<script setup lang="ts">
/**
 * The next-episode toast (Phase 4 step 15): after an episode ends, "Next: <episode>" with a countdown (auto-play
 * on) or without one (off), Play now and Cancel. Shown by the details page (EpisodeList, `useNextEpisode`), which
 * plays the episode; this only draws it.
 */
defineProps<{
  name: string;
  /** Seconds before it plays by itself; null without auto-play. */
  secondsLeft: number | null;
  starting: boolean;
}>();
const emit = defineEmits<{ play: []; cancel: [] }>();
</script>

<template>
  <div class="toast" role="status" aria-live="polite" data-testid="next-episode">
    <div class="text">
      <span class="title" data-testid="next-episode-name">Next: {{ name }}</span>
      <span class="summary" data-testid="next-episode-countdown">
        <template v-if="starting">Starting…</template>
        <template v-else-if="secondsLeft !== null">Plays in {{ secondsLeft }} s</template>
        <template v-else>Auto-play is off</template>
      </span>
    </div>
    <div class="actions">
      <button type="button" class="btn" data-action="cancel-next" @click="emit('cancel')">
        Cancel
      </button>
      <button
        type="button"
        class="btn btn-accent"
        data-action="play-next"
        :disabled="starting"
        @click="emit('play')"
      >
        Play now
      </button>
    </div>
  </div>
</template>

<style scoped>
.toast {
  position: fixed;
  right: var(--space-5);
  bottom: var(--space-5);
  z-index: 45;
  display: flex;
  align-items: center;
  gap: var(--space-4);
  max-width: min(480px, calc(100vw - 2 * var(--space-5)));
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
  box-shadow: var(--shadow-flyout);
}

.text {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.title {
  font-weight: var(--font-weight-semibold);
  overflow-wrap: anywhere;
}

.summary {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.actions {
  display: flex;
  flex: none;
  gap: var(--space-2);
}
</style>
