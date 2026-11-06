# Madoyomi app (Tauri 2 + Vue 3)

Desktop shell and UI. See [../docs/PLAN.md](../docs/PLAN.md) for the architecture and [../docs/phases/phase-3-frontend-mvp.md](../docs/phases/phase-3-frontend-mvp.md) for the current phase.

```powershell
pnpm install
pnpm tauri dev      # run the desktop app (Vite + Rust shell); starts the backend jar
pnpm dev            # UI only, in a browser at http://localhost:1420
pnpm test           # Vitest
pnpm lint           # ESLint
pnpm format         # Prettier
pnpm typecheck      # vue-tsc
pnpm tauri build    # NSIS/MSI installer
cd src-tauri; cargo test; cargo clippy --all-targets   # Rust shell tests and lints
```

- `src/`: Vue app (router, Pinia stores, views, components; design tokens in `styles/tokens.css`)
- `src-tauri/`: Rust shell (window, backend sidecar lifecycle in `src/sidecar.rs`, later the updater)

## Backend sidecar

`pnpm tauri dev` starts the newest `../server/api/build/Madoyomi-Server-*.jar`, so build it once with `.\gradlew.bat :api:shadowJar` in `server/`. The shell (`src-tauri/src/sidecar.rs`):

- runs `java -jar <jar> --data-dir <dir>` with a per-session random `MADOYOMI_TOKEN`, and keeps the backend's stdin open (the backend exits when it closes);
- waits for `MADOYOMI_PORT=<port>` as long as the JVM lives (up to 5 min), then for `GET /health`, and checks `/health` every 5 s after that;
- restarts the backend after an exit or a hang, after 1, 2, 4 and 8 s, and gives up after 5 failed starts in a row (Retry in the UI);
- writes the backend's output and its own lifecycle lines to `<data dir>/logs/sidecar.log`.

Environment overrides: `MADOYOMI_SIDECAR_JAR` (jar), `MADOYOMI_JAVA` (`java.exe`; default `../tools/bin/jdk-21`), `MADOYOMI_DATA_DIR` (default `%APPDATA%\Madoyomi`).

The UI talks to it through:

| Command or event       | What it does                                                                                                                                                                                                         |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `get_backend_status`   | Status snapshot: `state` (`starting`, `healthy`, `restarting`, `failed`, `stopped`), `attempt`, `elapsedMs`, `slow`, `baseUrl`, `pid`, `serverVersion`, `error`, `exitCode`, `retryInMs`, `dataDir`, `logDir`, `seq` |
| `backend-status` event | The same snapshot, on every change and once a second while starting                                                                                                                                                  |
| `get_backend_info`     | `{ baseUrl, token }` once healthy (waits while starting; the port changes after a restart, the token doesn't)                                                                                                        |
| `restart_backend`      | Retry after `failed`, or skip the backoff                                                                                                                                                                            |
| `open_backend_logs`    | Show `sidecar.log` in Explorer                                                                                                                                                                                       |

`src/stores/backend.ts` wraps these, and `src/components/BackendGate.vue` shows the splash, the failure screen and the restart overlay.

In plain `pnpm dev` there is no shell. Put `VITE_MADOYOMI_URL` and `VITE_MADOYOMI_TOKEN` of a server started with `../tools/run-server.ps1` into `.env.local` (gitignored) to use it from the browser.
