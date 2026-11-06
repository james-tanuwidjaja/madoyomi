import { createApp, defineComponent, h } from "vue";
import { VueQueryPlugin, type QueryClient } from "@tanstack/vue-query";
import type { Pinia } from "pinia";

/** Runs a composable inside a mounted component (with Pinia and vue-query), for hook tests. */
export function withSetup<T>(
  composable: () => T,
  plugins: { pinia: Pinia; queryClient: QueryClient },
) {
  let result!: T;
  const app = createApp(
    defineComponent({
      setup() {
        result = composable();
        return () => h("div");
      },
    }),
  );
  app.use(plugins.pinia).use(VueQueryPlugin, { queryClient: plugins.queryClient });
  app.mount(document.createElement("div"));
  return { result, unmount: () => app.unmount() };
}
