import { defineComponent, h, type Component } from "vue";
import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { VueQueryPlugin, type QueryClient } from "@tanstack/vue-query";
import type { Pinia } from "pinia";
import { useBackendStore } from "../stores/backend";
import { healthyStatus } from "./backendStatus";

const Empty = defineComponent({ render: () => h("div") });

/** Marks the backend as healthy, so the hooks are enabled. */
export function setBackendReady() {
  const backend = useBackendStore();
  backend.info = { baseUrl: "http://127.0.0.1:50266", token: "t" };
  backend.status = healthyStatus(2);
}

/** The memory router of {@link mountView}: every path renders nothing; the named routes the views link to resolve. */
export function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/manga/:id/read/:chapterId", name: "reader", component: Empty },
      { path: "/manga/:id", name: "manga", component: Empty },
      { path: "/:any(.*)*", component: Empty },
    ],
  });
}

/**
 * Mounts a view or component with Pinia, vue-query and a memory router (every path renders nothing, so `RouterLink`
 * works), attached to <body> because Reka's dialogs and popovers portal there. Pass `router` to start elsewhere.
 */
export function mountView(
  component: Component,
  plugins: { pinia: Pinia; queryClient: QueryClient; router?: Router },
  props: Record<string, unknown> = {},
) {
  const router = plugins.router ?? createTestRouter();
  return mount(component, {
    props,
    attachTo: document.body,
    global: {
      plugins: [plugins.pinia, router, [VueQueryPlugin, { queryClient: plugins.queryClient }]],
    },
  });
}
