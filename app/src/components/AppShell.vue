<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { ToggleGroupItem, ToggleGroupRoot } from "reka-ui";
import type { AcceptableValue } from "reka-ui";
import type { MediaKind } from "../api/types";
import type { NavItem } from "../router";
import { useMediaKindStore } from "../stores/mediaKind";
import NavIcon from "./NavIcon.vue";
import ShellFooter from "./ShellFooter.vue";

/**
 * The app shell (Phase 3 step 9): a Fluent-like navigation pane with the anime/manga switch, and the page (the slot)
 * on a raised content layer. The window keeps its native Windows title bar, which is the drag region (§9).
 */
const route = useRoute();
const router = useRouter();
const mediaKind = useMediaKindStore();

const items: { id: NavItem; label: string; to: string }[] = [
  { id: "library", label: "Library", to: "/library" },
  { id: "browse", label: "Browse", to: "/browse" },
  { id: "extensions", label: "Extensions", to: "/extensions" },
  { id: "downloads", label: "Downloads", to: "/downloads" },
];
const settings = { id: "settings" as const, label: "Settings", to: "/settings" };

// The section of the last page that has one of its own. A details page opened from Browse keeps Browse marked rather
// than jumping to Library (found in the exit run, §10); opened directly, it marks its default.
const lastSection = ref<NavItem | undefined>();
watch(
  () => route.meta,
  (meta) => {
    if (!meta.navFromPrevious) lastSection.value = meta.nav;
  },
  { immediate: true },
);
const active = computed(() =>
  route.meta.navFromPrevious ? (lastSection.value ?? route.meta.nav) : route.meta.nav,
);

function onKind(value: AcceptableValue | AcceptableValue[]) {
  // A single toggle group can be emptied by clicking the active item; the switch always has one kind.
  if (value === "anime" || value === "manga") mediaKind.set(value as MediaKind);
}

// A route of one kind (a details page, a source) doesn't fit the other kind: go to its section's list, the one the
// sidebar marks.
watch(
  () => mediaKind.kind,
  (kind) => {
    let target = route.meta.kindScopedTo;
    if (route.meta.navFromPrevious) {
      target = [...items, settings].find((i) => i.id === active.value)?.to ?? target;
    }
    const routeKind = route.meta.kind;
    if (target && routeKind !== kind) void router.push(target);
  },
);
</script>

<template>
  <div class="shell">
    <nav class="pane" aria-label="Main">
      <div class="pane-top">
        <p class="brand">Madoyomi</p>
        <ToggleGroupRoot
          type="single"
          class="kind-switch"
          aria-label="Anime or manga"
          :model-value="mediaKind.kind"
          @update:model-value="onKind"
        >
          <ToggleGroupItem value="anime" class="kind-option">Anime</ToggleGroupItem>
          <ToggleGroupItem value="manga" class="kind-option">Manga</ToggleGroupItem>
        </ToggleGroupRoot>
      </div>

      <ul class="nav-list">
        <li v-for="item in items" :key="item.id">
          <RouterLink
            :to="item.to"
            class="nav-item"
            :class="{ active: active === item.id }"
            :aria-current="active === item.id ? 'page' : undefined"
            :data-nav="item.id"
          >
            <NavIcon :name="item.id" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </li>
      </ul>

      <div class="pane-bottom">
        <RouterLink
          :to="settings.to"
          class="nav-item"
          :class="{ active: active === settings.id }"
          :aria-current="active === settings.id ? 'page' : undefined"
          :data-nav="settings.id"
        >
          <NavIcon :name="settings.id" />
          <span>{{ settings.label }}</span>
        </RouterLink>
        <ShellFooter />
      </div>
    </nav>

    <main class="content">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  height: 100vh;
  background: var(--color-bg);
}

.pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: var(--space-2) var(--space-1) 0;
}

.pane-top {
  display: grid;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3) var(--space-4);
}

.brand {
  margin: 0;
  font-family: var(--font-family-display);
  font-size: var(--font-size-body-large);
  font-weight: var(--font-weight-semibold);
}

/* Segmented control (Reka ToggleGroup). */
.kind-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
}

.kind-option {
  min-height: calc(var(--control-height) - 6px);
  border: 0;
  border-radius: calc(var(--radius-sm) - 1px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--easing-standard);
}

.kind-option:hover {
  background: var(--color-surface-hover);
}

.kind-option[data-state="on"] {
  background: var(--color-surface-raised);
  color: var(--color-text);
  box-shadow: 0 0 0 1px var(--color-border);
}

.nav-list {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: 36px;
  margin: 2px 0;
  padding: 0 var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--color-text);
  text-decoration: none;
  transition: background-color var(--duration-fast) var(--easing-standard);
}

.nav-item:hover {
  background: var(--color-surface-hover);
}

.nav-item.active {
  background: var(--color-surface-hover);
}

/* Fluent's selection pill. */
.nav-item.active::before {
  content: "";
  position: absolute;
  left: 0;
  top: 50%;
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: var(--color-accent);
  transform: translateY(-50%);
}

.pane-bottom {
  border-top: 1px solid var(--color-border);
  padding-top: var(--space-1);
}

/* The page on a raised layer over the pane, as Windows 11 apps do. */
.content {
  min-width: 0;
  overflow: auto;
  border-top: 1px solid var(--color-border);
  border-left: 1px solid var(--color-border);
  border-top-left-radius: var(--radius-md);
  background: var(--color-surface);
}
</style>
