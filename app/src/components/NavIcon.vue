<script setup lang="ts">
import type { NavItem } from "../router";

/** 20 px line icons for the sidebar, drawn with `currentColor`. */
defineProps<{ name: NavItem }>();
</script>

<template>
  <svg
    class="nav-icon"
    width="20"
    height="20"
    viewBox="0 0 20 20"
    fill="none"
    stroke="currentColor"
    stroke-width="1.4"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'library'">
      <rect x="3" y="3" width="3.5" height="14" rx="1" />
      <rect x="8.25" y="3" width="3.5" height="14" rx="1" />
      <path d="M13.6 4.2l3.2-0.9 2 13.3-3.3 0.9z" />
    </template>
    <template v-else-if="name === 'browse'">
      <circle cx="10" cy="10" r="7" />
      <path d="M12.9 7.1l-1.6 4.2-4.2 1.6 1.6-4.2z" />
    </template>
    <template v-else-if="name === 'extensions'">
      <path
        d="M8 3.5a1.5 1.5 0 0 1 3 0V5h3.5a.5.5 0 0 1 .5.5V9h-1.5a1.5 1.5 0 0 0 0 3H15v3.5a.5.5 0 0 1-.5.5H11v-1.5a1.5 1.5 0 0 0-3 0V16H4.5a.5.5 0 0 1-.5-.5V12h1.5a1.5 1.5 0 0 0 0-3H4V5.5a.5.5 0 0 1 .5-.5H8z"
      />
    </template>
    <template v-else-if="name === 'downloads'">
      <path d="M10 3v10M6 9.5l4 4 4-4M4 17h12" />
    </template>
    <template v-else>
      <circle cx="10" cy="10" r="2.5" />
      <path
        d="M10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M4.7 15.3l1.4-1.4M13.9 6.1l1.4-1.4"
      />
    </template>
  </svg>
</template>

<style scoped>
.nav-icon {
  flex: none;
}
</style>
