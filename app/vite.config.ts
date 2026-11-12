import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import process from "node:process";

const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],

  // The reader (Phase 5) is a lazy route that imports the window API: pre-bundled up front, so its first open in
  // `pnpm dev` doesn't make Vite optimize it and reload the page mid-navigation.
  optimizeDeps: { include: ["@tauri-apps/api/window"] },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
  },
});
