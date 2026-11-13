<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive } from "vue";

/**
 * The reader's numbers (Phase 5 Stage 3, step 15), in dev builds only: the reader toggles it with the ` key. Counted
 * from when it opens (Reset starts over): frames per second over the last second, frames longer than 50 ms and the
 * longest, frames during which a page in the webtoon strip's viewport wasn't shown yet ("blank"), the `<img>`
 * elements and strip items in the DOM (now and the most), the JS heap, and the time to the chapter's first page.
 */
defineProps<{ firstPageMs: number | null }>();
const emit = defineEmits<{ close: [] }>();

const LONG_FRAME_MS = 50;

const shown = reactive({
  fps: 0,
  frames: 0,
  long: 0,
  worst: 0,
  blankFrames: 0,
  blankMs: 0,
  imgs: 0,
  imgsMax: 0,
  items: 0,
  itemsMax: 0,
  heapMb: null as number | null,
});

let counts = { frames: 0, long: 0, worst: 0, blankFrames: 0, blankMs: 0, imgsMax: 0, itemsMax: 0 };
let recent: number[] = [];
let last = 0;
let raf = 0;
let timer: ReturnType<typeof setInterval> | null = null;

function reset() {
  counts = { frames: 0, long: 0, worst: 0, blankFrames: 0, blankMs: 0, imgsMax: 0, itemsMax: 0 };
  recent = [];
  last = 0;
}

function frame(now: number) {
  if (last > 0) {
    const dt = now - last;
    counts.frames++;
    if (dt > LONG_FRAME_MS) counts.long++;
    counts.worst = Math.max(counts.worst, dt);
    const strip = document.querySelector('[data-testid="webtoon-reader"]');
    if (strip && strip.getAttribute("data-blank") !== "0") {
      counts.blankFrames++;
      counts.blankMs += dt;
    }
  }
  last = now;
  recent.push(now);
  while (recent.length && recent[0]! < now - 1000) recent.shift();
  raf = requestAnimationFrame(frame);
}

function publish() {
  const imgs = document.querySelectorAll('[data-testid="reader"] img').length;
  const items = document.querySelectorAll('[data-testid="webtoon-reader"] [data-position]').length;
  counts.imgsMax = Math.max(counts.imgsMax, imgs);
  counts.itemsMax = Math.max(counts.itemsMax, items);
  const memory = (performance as { memory?: { usedJSHeapSize: number } }).memory;
  Object.assign(shown, {
    fps: recent.length,
    frames: counts.frames,
    long: counts.long,
    worst: Math.round(counts.worst),
    blankFrames: counts.blankFrames,
    blankMs: Math.round(counts.blankMs),
    imgs,
    imgsMax: counts.imgsMax,
    items,
    itemsMax: counts.itemsMax,
    heapMb: memory ? Math.round(memory.usedJSHeapSize / 1048576) : null,
  });
}

onMounted(() => {
  raf = requestAnimationFrame(frame);
  timer = setInterval(publish, 250);
});
onBeforeUnmount(() => {
  cancelAnimationFrame(raf);
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="stats" data-testid="reader-stats" @click.stop>
    <div>{{ shown.fps }} fps · {{ shown.frames }} frames</div>
    <div>long (&gt;50 ms) {{ shown.long }} · worst {{ shown.worst }} ms</div>
    <div>blank {{ shown.blankFrames }} frames ({{ shown.blankMs }} ms)</div>
    <div>
      img {{ shown.imgs }} (max {{ shown.imgsMax }}) · items {{ shown.items }} (max
      {{ shown.itemsMax }})
    </div>
    <div>heap {{ shown.heapMb ?? "?" }} MB · first page {{ firstPageMs ?? "…" }} ms</div>
    <div class="actions">
      <!-- They don't take the focus: Space would press them again instead of scrolling the strip. -->
      <button type="button" class="btn" @mousedown.prevent @click="reset">Reset</button>
      <button type="button" class="btn" @mousedown.prevent @click="emit('close')">Close</button>
    </div>
  </div>
</template>

<style scoped>
.stats {
  position: absolute;
  top: 56px;
  left: var(--space-4);
  z-index: 2;
  display: grid;
  gap: 2px;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: rgb(0 0 0 / 80%);
  color: #9f9;
  font:
    12px/1.4 ui-monospace,
    Consolas,
    monospace;
}

.actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-1);
}
</style>
