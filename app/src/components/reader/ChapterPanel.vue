<script setup lang="ts">
/**
 * The transition panels around a chapter's pages (Phase 5, every mode): "Previous chapter: …" before the first page
 * (only when there is a previous chapter), and "Finished: … · Next chapter: …" after the last one, or that there is
 * no next chapter. Stage 4: they are the seamless transition: one more step past them goes on into that chapter
 * without leaving the reader (its page list is fetched ahead, so it opens at once). Their buttons don't reach the
 * reader's zones.
 */
defineProps<{
  kind: "previous" | "end";
  chapterName: string;
  mangaTitle: string;
  pageCount: number;
  previousName: string | null;
  nextName: string | null;
}>();

const emit = defineEmits<{ nextChapter: []; previousChapter: []; back: [] }>();
</script>

<template>
  <div v-if="kind === 'previous'" class="panel" data-testid="panel-previous">
    <p class="muted">Start of {{ chapterName }}</p>
    <p class="title">Previous chapter: {{ previousName }}</p>
    <div class="actions">
      <button type="button" class="btn btn-accent" @click.stop="emit('previousChapter')">
        Read {{ previousName }}
      </button>
    </div>
  </div>

  <div v-else class="panel" data-testid="panel-end">
    <p class="muted">
      {{ pageCount === 0 ? `${chapterName} has no pages.` : `Finished: ${chapterName}` }}
    </p>
    <template v-if="nextName">
      <p class="title">Next chapter: {{ nextName }}</p>
      <div class="actions">
        <button
          type="button"
          class="btn btn-accent"
          data-action="panel-next"
          @click.stop="emit('nextChapter')"
        >
          Read {{ nextName }}
        </button>
        <button type="button" class="btn" @click.stop="emit('back')">
          Back to {{ mangaTitle }}
        </button>
      </div>
    </template>
    <template v-else>
      <p class="title">There's no next chapter.</p>
      <div class="actions">
        <button
          type="button"
          class="btn btn-accent"
          data-action="panel-back"
          @click.stop="emit('back')"
        >
          Back to {{ mangaTitle }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.panel {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  padding: var(--space-5);
  color: #fff;
  text-align: center;
}

.panel p {
  margin: 0;
}

.title {
  font-size: var(--font-size-body-large);
  font-weight: var(--font-weight-semibold);
}

.muted {
  color: rgb(255 255 255 / 60%);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
}
</style>
