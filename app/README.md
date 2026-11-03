# Madoyomi app (Tauri 2 + Vue 3)

Desktop shell and UI. See [../docs/PLAN.md](../docs/PLAN.md) for the architecture.

```powershell
pnpm install
pnpm tauri dev      # run the desktop app (Vite + Rust shell)
pnpm dev            # UI only, in a browser at http://localhost:1420
pnpm test           # Vitest
pnpm lint           # ESLint
pnpm format         # Prettier
pnpm typecheck      # vue-tsc
pnpm tauri build    # NSIS/MSI installer
```

- `src/`: Vue app (router, Pinia stores, views)
- `src-tauri/`: Rust shell (window, sidecar lifecycle, updater)
