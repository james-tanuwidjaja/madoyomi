<script setup lang="ts" generic="T">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type ComponentPublicInstance,
} from "vue";
import { useVirtualizer } from "@tanstack/vue-virtual";

/**
 * A grid of cards in its own scroll area (Phase 3 step 20), built like VirtualList: the columns come from the scroll
 * area's width (as many `minItemWidth` columns as fit, stretched to fill it), and above `threshold` items only the
 * rows in view (plus `overscan` rows) are in the DOM (`@tanstack/vue-virtual`, rows measured). Give it a height.
 *
 * It emits `end-reached` when the scroll comes within `endOffset` px of the end, on a scroll and after the items
 * change, so a first page that doesn't fill the view asks for the next one too. It never emits while it has no
 * height (hidden). The parent decides whether there is more to load. The `footer` slot goes below the last row.
 */
const props = withDefaults(
  defineProps<{
    items: readonly T[];
    itemKey: (item: T) => string | number;
    /** A card's narrowest width, px. */
    minItemWidth?: number;
    /** The space between cards, px. */
    gap?: number;
    /** A card's height for its width, before its row is measured. */
    estimateItemHeight?: (width: number) => number;
    threshold?: number;
    /** Rows, not items. */
    overscan?: number;
    endOffset?: number;
    resetKey?: unknown;
    label?: string;
  }>(),
  {
    minItemWidth: 150,
    gap: 12,
    estimateItemHeight: (w: number) => Math.round(w * 1.5) + 48,
    threshold: 60,
    overscan: 3,
    endOffset: 600,
    resetKey: undefined,
    label: undefined,
  },
);

defineSlots<{ default(props: { item: T; index: number }): unknown; footer(): unknown }>();
const emit = defineEmits<{ "end-reached": [] }>();

const scrollEl = ref<HTMLElement | null>(null);
const width = ref(0);

const columns = computed(() =>
  Math.max(1, Math.floor((width.value + props.gap) / (props.minItemWidth + props.gap))),
);
const itemWidth = computed(() =>
  Math.max(props.minItemWidth, (width.value - props.gap * (columns.value - 1)) / columns.value),
);
const rowCount = computed(() => Math.ceil(props.items.length / columns.value));
const virtual = computed(() => props.items.length > props.threshold);

const virtualizer = useVirtualizer(
  computed(() => ({
    count: virtual.value ? rowCount.value : 0,
    getScrollElement: () => scrollEl.value,
    estimateSize: () => props.estimateItemHeight(itemWidth.value) + props.gap,
    overscan: props.overscan,
    // a row is its first item: the same row as long as the columns don't change
    getItemKey: (row: number) => props.itemKey(props.items[row * columns.value]),
  })),
);

const rows = computed(() => virtualizer.value.getVirtualItems());
const totalSize = computed(() => virtualizer.value.getTotalSize());

function rowItems(row: number): { item: T; index: number }[] {
  const start = row * columns.value;
  return props.items
    .slice(start, start + columns.value)
    .map((item, i) => ({ item, index: start + i }));
}

function measure(el: Element | ComponentPublicInstance | null) {
  if (el instanceof Element) virtualizer.value.measureElement(el);
}

const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${columns.value}, minmax(0, 1fr))`,
  gap: `${props.gap}px`,
}));

function checkEnd() {
  const el = scrollEl.value;
  if (!el || el.clientHeight <= 0) return;
  if (el.scrollHeight - el.scrollTop - el.clientHeight <= props.endOffset) emit("end-reached");
}

let observer: ResizeObserver | null = null;

function readWidth() {
  if (scrollEl.value) width.value = scrollEl.value.clientWidth;
}

onMounted(() => {
  readWidth();
  if (typeof ResizeObserver !== "undefined" && scrollEl.value) {
    observer = new ResizeObserver(() => {
      readWidth();
      checkEnd();
    });
    observer.observe(scrollEl.value);
  }
  void nextTick(checkEnd);
});

onBeforeUnmount(() => observer?.disconnect());

// New columns move every item to another row: measure the rows again.
watch(columns, () => virtualizer.value.measure());

watch(
  () => props.items.length,
  async () => {
    await nextTick();
    checkEnd();
  },
);

watch(
  () => props.resetKey,
  async () => {
    await nextTick();
    if (scrollEl.value) scrollEl.value.scrollTop = 0;
  },
);
</script>

<template>
  <div
    ref="scrollEl"
    class="virtual-grid"
    role="list"
    :aria-label="label"
    :data-virtual="virtual ? 'on' : 'off'"
    :data-columns="columns"
    @scroll.passive="checkEnd"
  >
    <div v-if="!virtual" class="grid" :style="gridStyle">
      <div v-for="(item, index) in items" :key="itemKey(item)" class="cell" role="listitem">
        <slot :item="item" :index="index" />
      </div>
    </div>
    <div v-else class="sizer" :style="{ height: `${totalSize}px` }">
      <div
        v-for="row in rows"
        :key="row.key as PropertyKey"
        :ref="measure"
        class="row"
        :data-index="row.index"
        :style="{ transform: `translateY(${row.start}px)`, paddingBottom: `${gap}px` }"
      >
        <div class="grid" :style="gridStyle">
          <div v-for="c in rowItems(row.index)" :key="itemKey(c.item)" class="cell" role="listitem">
            <slot :item="c.item" :index="c.index" />
          </div>
        </div>
      </div>
    </div>
    <div v-if="$slots.footer" class="footer"><slot name="footer" /></div>
  </div>
</template>

<style scoped>
.virtual-grid {
  overflow: auto;
  overscroll-behavior: contain;
}

.grid {
  display: grid;
}

.sizer {
  position: relative;
  width: 100%;
}

.row {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
}

.cell {
  min-width: 0;
}

.footer {
  padding: var(--space-3) 0 var(--space-4);
}
</style>
