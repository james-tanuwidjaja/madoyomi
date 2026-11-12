import { onBeforeUnmount, ref } from "vue";
import { isTauri } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";

/*
 * The reader's fullscreen (Phase 5 Stage 2): the app's window itself in the desktop app (Tauri's `setFullscreen`;
 * WebView2's element fullscreen would only fill the webview), the document's fullscreen in a plain browser
 * (`pnpm dev`). Leaving the reader leaves fullscreen.
 */
export function useFullscreen() {
  const active = ref(false);

  async function set(on: boolean) {
    try {
      if (isTauri()) {
        await getCurrentWindow().setFullscreen(on);
        active.value = on;
      } else if (on) {
        await document.documentElement.requestFullscreen?.();
        active.value = document.fullscreenElement != null;
      } else {
        if (document.fullscreenElement) await document.exitFullscreen?.();
        active.value = false;
      }
    } catch (e) {
      console.warn("fullscreen:", e);
    }
  }

  const onChange = () => {
    if (!isTauri()) active.value = document.fullscreenElement != null;
  };
  document.addEventListener("fullscreenchange", onChange);
  onBeforeUnmount(() => {
    document.removeEventListener("fullscreenchange", onChange);
    if (active.value) void set(false);
  });

  return {
    active,
    toggle: () => set(!active.value),
    exit: () => set(false),
  };
}
