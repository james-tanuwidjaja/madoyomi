import { defineStore } from "pinia";
import { ref } from "vue";
import type { ExtensionInstallProgress, ExtensionStoreRefreshed } from "../api/types";
import type { SubscriptionState } from "../api/ws";

/** One event from the backend's subscriptions, as src/api/realtime.ts received it. */
export type RealtimeEvent =
  | { at: number; kind: "installProgress"; event: ExtensionInstallProgress }
  | { at: number; kind: "storeRefreshed"; event: ExtensionStoreRefreshed };

const RECENT_MAX = 20;

/**
 * What the subscriptions report (src/api/realtime.ts fills it in): the latest install step of each extension, the
 * last few events, and the WebSocket's state.
 */
export const useEventsStore = defineStore("events", () => {
  /** The latest `extensionInstallProgress` per package. Nothing is replayed after a reconnect. */
  const installProgress = ref<Record<string, ExtensionInstallProgress>>({});
  /** The newest first. */
  const recent = ref<RealtimeEvent[]>([]);
  const wsState = ref<SubscriptionState>("idle");
  /** Each `connection_ack`, the first one included. */
  const connections = ref(0);

  function push(e: RealtimeEvent) {
    recent.value = [e, ...recent.value].slice(0, RECENT_MAX);
  }

  function recordInstall(event: ExtensionInstallProgress) {
    installProgress.value = { ...installProgress.value, [event.pkgName]: event };
    push({ at: Date.now(), kind: "installProgress", event });
  }

  function recordStoreRefresh(event: ExtensionStoreRefreshed) {
    push({ at: Date.now(), kind: "storeRefreshed", event });
  }

  return {
    installProgress,
    recent,
    wsState,
    connections,
    recordInstall,
    recordStoreRefresh,
  };
});
