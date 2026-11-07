import { defineStore } from "pinia";
import { ref, watch } from "vue";

/**
 * `localStorage` key of the theme. index.html reads it too, before the first paint, so a dark or light choice
 * doesn't flash the other theme while the app loads.
 */
export const THEME_KEY = "madoyomi.theme";

/** `system` follows Windows (`prefers-color-scheme`); the others override it. */
export type ThemeChoice = "system" | "dark" | "light";

export const THEME_CHOICES: readonly ThemeChoice[] = ["system", "dark", "light"];

function stored(): ThemeChoice {
  try {
    const v = localStorage.getItem(THEME_KEY);
    return v === "dark" || v === "light" ? v : "system";
  } catch {
    return "system";
  }
}

/** Sets `<html data-theme>`, which styles/tokens.css reads, or removes it for `system`. */
export function applyTheme(choice: ThemeChoice) {
  const root = document.documentElement;
  if (choice === "system") delete root.dataset.theme;
  else root.dataset.theme = choice;
}

/** The app's theme (Phase 3 §9 "Stage 1": dark and light, following Windows unless chosen), kept across sessions. */
export const useThemeStore = defineStore("theme", () => {
  const choice = ref<ThemeChoice>(stored());

  watch(
    choice,
    (c) => {
      applyTheme(c);
      try {
        if (c === "system") localStorage.removeItem(THEME_KEY);
        else localStorage.setItem(THEME_KEY, c);
      } catch {
        // storage full or disabled: kept for this session
      }
    },
    { immediate: true, flush: "sync" },
  );

  function set(next: ThemeChoice) {
    if (THEME_CHOICES.includes(next)) choice.value = next;
  }

  return { choice, set };
});
