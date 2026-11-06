import { defineComponent, h, type Component } from "vue";
import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
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

/**
 * Mounts a view or component with Pinia, vue-query and a memory router (every path renders nothing, so `RouterLink`
 * works), attached to <body> because Reka's dialogs and popovers portal there.
 */
export function mountView(
  component: Component,
  plugins: { pinia: Pinia; queryClient: QueryClient },
  props: Record<string, unknown> = {},
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:any(.*)*", component: Empty }],
  });
  return mount(component, {
    props,
    attachTo: document.body,
    global: {
      plugins: [plugins.pinia, router, [VueQueryPlugin, { queryClient: plugins.queryClient }]],
    },
  });
}
