<script setup lang="ts" generic="T">
import { computed, nextTick, ref, watch, type ComponentPublicInstance } from "vue";
import { useVirtualizer } from "@tanstack/vue-virtual";

/**
 * A list in its own scroll area. Above `threshold` items only the rows in view (plus `overscan`) are in the DOM
 * (`@tanstack/vue-virtual`, rows measured, so they may differ in height); below it every row is rendered, which is
 * as fast for a short list and keeps it simple to test. Give it a height (PageLayout's `fill`, or a parent's).
 *
 * The scroll position goes back to the top when `resetKey` changes (a new tab or search), not when the items do (an
 * install that changes one row).
 */
const props = withDefaults(
  defineProps<{
    items: readonly T[];
    itemKey: (item: T) => string | number;
    /** A row's height before it is measured, px. */
    estimateSize?: number;
    threshold?: number;
    overscan?: number;
    resetKey?: unknown;
    label?: string;
  }>(),
  { estimateSize: 64, threshold: 60, overscan: 8, resetKey: undefined, label: undefined },
);

defineSlots<{ default(props: { item: T; index: number }): unknown }>();

const scrollEl = ref<HTMLElement | null>(null);
const virtual = computed(() => props.items.length > props.threshold);

const virtualizer = useVirtualizer(
  computed(() => ({
    count: virtual.value ? props.items.length : 0,
    getScrollElement: () => scrollEl.value,
    estimateSize: () => props.estimateSize,
    overscan: props.overscan,
    getItemKey: (index: number) => props.itemKey(props.items[index]),
  })),
);

const rows = computed(() => virtualizer.value.getVirtualItems());
const totalSize = computed(() => virtualizer.value.getTotalSize());

function measure(el: Element | ComponentPublicInstance | null) {
  if (el instanceof Element) virtualizer.value.measureElement(el);
}

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
    class="virtual-list"
    role="list"
    :aria-label="label"
    :data-virtual="virtual ? 'on' : 'off'"
  >
    <template v-if="!virtual">
      <div v-for="(item, index) in items" :key="itemKey(item)" class="item" role="listitem">
        <slot :item="item" :index="index" />
      </div>
    </template>
    <div v-else class="sizer" :style="{ height: `${totalSize}px` }">
      <div
        v-for="row in rows"
        :key="row.key as PropertyKey"
        :ref="measure"
        class="item virtual"
        role="listitem"
        :data-index="row.index"
        :style="{ transform: `translateY(${row.start}px)` }"
      >
        <slot :item="items[row.index]" :index="row.index" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.virtual-list {
  overflow: auto;
  overscroll-behavior: contain;
}

.sizer {
  position: relative;
  width: 100%;
}

.item {
  padding-bottom: var(--space-1);
}

.item.virtual {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
}
</style>
