import { defineStore } from "pinia";
import { ref, watch } from "vue";

/** `localStorage` key of the Extensions screen's "Show 18+" switch. */
export const SHOW_NSFW_KEY = "madoyomi.showNsfw";

export type ExtensionTab = "available" | "installed" | "updates";

function storedShowNsfw(): boolean {
  try {
    return localStorage.getItem(SHOW_NSFW_KEY) === "true";
  } catch {
    return false;
  }
}

/**
 * The Extensions screen's tab and filters. They stay while the app runs, so leaving the screen (to Repos, say) and
 * coming back keeps them. "Show 18+" is kept across sessions too; it starts off.
 */
export const useExtensionFiltersStore = defineStore("extensionFilters", () => {
  const tab = ref<ExtensionTab>("available");
  const search = ref("");
  /** A language code, or "" for any. */
  const lang = ref("");
  const showNsfw = ref(storedShowNsfw());

  watch(
    showNsfw,
    (v) => {
      try {
        localStorage.setItem(SHOW_NSFW_KEY, String(v));
      } catch {
        // storage full or disabled: the switch still works for this session
      }
    },
    { flush: "sync" },
  );

  return { tab, search, lang, showNsfw };
});
