/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `pnpm dev` only: base URL of a running server, e.g. http://127.0.0.1:50266 (see src/stores/backend.ts). */
  readonly VITE_MADOYOMI_URL?: string;
  /** `pnpm dev` only: that server's MADOYOMI_TOKEN. Keep it in the gitignored `.env.local`. */
  readonly VITE_MADOYOMI_TOKEN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
