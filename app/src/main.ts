import { createApp } from "vue";
import { createPinia } from "pinia";
import { VueQueryPlugin } from "@tanstack/vue-query";

import App from "./App.vue";
import { router } from "./router";
import { createQueryClient, startRealtime } from "./api/realtime";
import { useThemeStore } from "./stores/theme";
import "./styles/main.css";

const queryClient = createQueryClient();
const app = createApp(App).use(createPinia()).use(router).use(VueQueryPlugin, { queryClient });
// After Pinia: it follows the backend store, and fills the events store.
startRealtime({ queryClient });
// The theme is already on <html> (index.html); the store keeps it in step with Settings.
useThemeStore();
app.mount("#app");
