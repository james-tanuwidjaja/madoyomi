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

- `src/`: Vue app
  - `api/`: the backend API.
    - `client.ts`: GraphQL with the token, `ApiError`, and `assetUrl` for binary routes.
    - `errorText.ts`: a readable sentence for each error code (shown by `ErrorNotice`).
    - `types.ts`: hand-written types.
    - `operations/`: the GraphQL documents and typed calls.
    - `ws.ts`: our `graphql-transport-ws` client.
    - `realtime.ts`: the vue-query defaults and the app-wide event subscriptions.
  - `composables/`: vue-query hooks (`useExtensionStores`, `useExtensions`, the repo mutations `useAddRepo` (with the type detection, `addRepoDetectingKind`), `useRemoveRepo`, `useRefreshRepos`, the extension mutations `useExtensionAction`, `useTrustExtension`, and `useExtensionActionState` (each package's latest action, from the mutation cache), `useSources`, `useSourceFilters`, `useSourceBrowse` (infinite), `useAnime`/`useEpisodes`, `useManga`/`useChapters`) and the query keys.
  - `stores/`: Pinia: `backend` (the shell), `events` (live events, the latest install step per package, socket state), `mediaKind` (the anime/manga switch, persisted), `extensionFilters` (the Extensions tab and filters; "Show 18+" persisted).
  - `router/`: the routes.
  - `components/`: the shell (`AppShell`, `ShellFooter`), `BackendGate`, the page building blocks (`PageLayout` with `fill` for a page with its own scroll area, `ErrorNotice`, `PagePlaceholder`), `VirtualList` (TanStack Virtual above 60 rows), `ExtensionRow` and `TrustDialog`.
  - `test/`: test helpers: `mountView` (Pinia, vue-query and a memory router, attached to `<body>` for Reka's portals), `fixtures`, `withSetup`, `fakeSocket`, `backendStatus`.
  - `views/`: one per route.
  - `styles/tokens.css`: the design tokens.
- `src-tauri/`: Rust shell (window, backend sidecar lifecycle in `src/sidecar.rs`, later the updater)

## Routes

| Route                               | View                                   | Status                                                         |
| ----------------------------------- | -------------------------------------- | -------------------------------------------------------------- |
| `/` → `/library`                    | `LibraryView`                          | placeholder (Stage 5)                                          |
| `/browse`                           | `BrowseView`                           | lists the sources of the current kind; full screen Stage 4     |
| `/browse/:sourceId`                 | `SourceView`                           | placeholder (Stage 4)                                          |
| `/anime/:id`, `/manga/:id`          | `AnimeDetailsView`, `MangaDetailsView` | placeholder (Stage 5); they set the anime/manga switch         |
| `/extensions`                       | `ExtensionsView`                       | done (Stage 3): tabs, filters, install/update/uninstall, trust |
| `/repos`                            | `ReposView`                            | done (Stage 3): add (type detected), remove, refresh, events   |
| `/downloads`                        | `DownloadsView`                        | placeholder (Phase 6)                                          |
| `/settings`, `/settings/source/:id` | `SettingsView`, `SourceSettingsView`   | placeholder (Stage 6)                                          |
| anything else                       | `NotFoundView`                         |                                                                |

The routes of one kind (`/browse/:sourceId`, `/anime/:id`, `/manga/:id`, `/settings/source/:id`) go back to their section's list when the switch changes kind.

## Live check of the API operations

`src/api/__tests__/operations.live.spec.ts` runs only when `MADOYOMI_LIVE_URL` and `MADOYOMI_LIVE_TOKEN` point at a running server, e.g. one from `../tools/run-server.ps1`:

```powershell
$env:MADOYOMI_LIVE_URL = 'http://127.0.0.1:<port>'; $env:MADOYOMI_LIVE_TOKEN = '<its token>'
pnpm vitest run src/api/__tests__/operations.live.spec.ts
```

It validates every document in `src/api/operations/` against the server's schema, checks that a wrong token gets 401, checks that adding yuzono as a manga repo is refused with `REPO_TYPE_MISMATCH` and the packages (what the Repos screen's type detection relies on; it fetches the index and writes nothing), and receives `extensionStoreRefreshed` through `ws.ts` after a repo refresh (that needs at least one repo). Run it after changing an operation or a type.

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
