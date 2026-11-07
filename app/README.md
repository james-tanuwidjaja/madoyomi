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
  - `composables/`: vue-query hooks (`useExtensionStores`, `useExtensions`, the repo mutations `useAddRepo` (with the type detection, `addRepoDetectingKind`), `useRemoveRepo`, `useRefreshRepos`, the extension mutations `useExtensionAction`, `useTrustExtension`, and `useExtensionActionState` (each package's latest action, from the mutation cache), `useSources`, `useSourceFilters`, `useSourceBrowse` (infinite), `useOpenInExtensions` (Extensions' Installed tab, searched for one package), the entries in `useEntries` (`useAnime`/`useEpisodes`/`useSeasons`, `useManga`/`useChapters`, `useLibrary`, the refreshes `useRefreshAnime`/`useRefreshManga` and `useSetInLibrary`, which patches the cached browse cards), `useDetailsPage` (what both details pages share: the source and its access, the automatic first refresh, the library toggle), `usePreferences` (`useSourcePreferences`, and `useUpdateSourcePreference`: one preference change at a time, shown at once, rolled back on an error, with a refusal reported per position)) and the query keys.
  - `stores/`: Pinia: `backend` (the shell), `events` (live events, the latest install step per package, socket state), `mediaKind` (the anime/manga switch, persisted), `extensionFilters` (the Extensions tab and filters; "Show 18+" persisted), `pinnedSources` (Browse's pins per kind, persisted), `sourceBrowse` (each source page's tab, search and applied filters, for the session), `itemList` (the episode/chapter list's sort per kind, persisted; its filters per entry, for the session), `librarySort` (the Library's sort per kind, persisted, and `sortLibrary`), `theme` (System/Dark/Light, persisted as `madoyomi.theme`, set on `<html data-theme>`; `index.html` applies it before the first paint).
  - `router/`: the routes.
  - `components/`: the shell (`AppShell`, `ShellFooter`), `BackendGate`, the page building blocks (`PageLayout` with `fill` for a page with its own scroll area, `ErrorNotice`, `PagePlaceholder`), `VirtualList` (TanStack Virtual above 60 rows), `VirtualGrid` (the same for card grids: columns from the width, `end-reached` near the end), `MediaCard` (cover through `useAssetUrl`, title, in-library mark), the details parts: `EntryHeader` (cover, title, people, status, genres, source, the description with More) and `EpisodeList`/`ChapterList` (thin wrappers of `EntryItemList`: sort, filters and a `VirtualList` of rows), `ExtensionRow`, `TrustDialog`, the filters: `FilterRenderer` (a source's filter list, each filter drawn by `FilterField`) in `FilterSheet` (a side sheet with Apply, Reset and Cancel), and the preferences: `PreferenceRenderer` (a source's settings screen, each preference drawn by `PreferenceField`: a switch, checkbox or list applies at once, an edit text or multi-select in a dialog).
  - `test/`: test helpers: `mountView` (Pinia, vue-query and a memory router, attached to `<body>` for Reka's portals), `fixtures` (with `makeSource`, `makeMangaSource`, `makePage`, `makeAnime`, `makeManga`, `makeEpisodes`, `makeChapters`), `filterFixtures` (shared with the server's `AnimeBrowseApiTest`), `preferenceFixtures` (one preference of each kind, a disabled and a hidden one), `withSetup`, `fakeSocket`, `backendStatus`.
  - `utils/`: `filters.ts` (a filter list's state, the `FilterChange[]` it sends and back, its signature), `sources.ts` (whether a source can be browsed), `entryItems.ts` (episodes and chapters as list rows, their sort and filters), `preferences.ts` (a preference's value, the change that sets it, whether the server took it, its summary), `format.ts`.
  - `views/`: one per route.
  - `styles/tokens.css`: the design tokens.
- `src-tauri/`: Rust shell (window, backend sidecar lifecycle in `src/sidecar.rs`, later the updater)

## Routes

| Route                      | View                                   | Status                                                                                                             |
| -------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `/` → `/library`           | `LibraryView`                          | done (Stage 5): the saved entries of the current kind, sorted by date added or title, empty state to Browse        |
| `/browse`                  | `BrowseView`                           | done (Stage 4): by language, pins, built-in Local, unusable ones point to Extensions                               |
| `/browse/:sourceId`        | `SourceView`                           | done (Stage 4): Popular/Latest/Search, filter sheet, endless grid                                                  |
| `/anime/:id`, `/manga/:id` | `AnimeDetailsView`, `MangaDetailsView` | done (Stage 5): details, refresh, library, the episode/chapter list (or seasons); they set the anime/manga switch  |
| `/extensions`              | `ExtensionsView`                       | done (Stage 3): tabs, filters, install/update/uninstall, trust                                                     |
| `/repos`                   | `ReposView`                            | done (Stage 3): add (type detected), remove, refresh, events                                                       |
| `/downloads`               | `DownloadsView`                        | placeholder (Phase 6)                                                                                              |
| `/settings`                | `SettingsView`                         | done (Stage 6): theme, language, the backend's version, folders and logs, the kind's sources with settings         |
| `/settings/source/:id`     | `SourceSettingsView`                   | done (Stage 6): the source's preferences (`PreferenceRenderer`), saved as they change; unusable ones to Extensions |
| anything else              | `NotFoundView`                         |                                                                                                                    |

The routes of one kind (`/browse/:sourceId`, `/anime/:id`, `/manga/:id`, `/settings/source/:id`) go back to their section's list when the switch changes kind. A details page (`meta.navFromPrevious`) belongs to the section it was opened from: the sidebar keeps marking Browse or Library, and a kind change goes back to that list. Opened directly, it marks Library.

## Live check of the API operations

`src/api/__tests__/operations.live.spec.ts` runs only when `MADOYOMI_LIVE_URL` and `MADOYOMI_LIVE_TOKEN` point at a running server, e.g. one from `../tools/run-server.ps1`:

```powershell
$env:MADOYOMI_LIVE_URL = 'http://127.0.0.1:<port>'; $env:MADOYOMI_LIVE_TOKEN = '<its token>'
pnpm vitest run src/api/__tests__/operations.live.spec.ts
```

It validates every document in `src/api/operations/` against the server's schema, checks that a wrong token gets 401, checks that adding yuzono as a manga repo is refused with `REPO_TYPE_MISMATCH` and the packages (what the Repos screen's type detection relies on; it fetches the index and writes nothing), receives `extensionStoreRefreshed` through `ws.ts` after a repo refresh (that needs at least one repo), and runs a filtered search on an installed source of each kind: one filter changed through `filterChanges`, which must find entries (it needs the source's site; a source whose site fails is recorded and the next one tried, and a kind with no installed source is skipped). It also refreshes the first Popular entry of one source per kind the way the details page does, and puts it into the library and out again (an entry that was already there is put back). Last, it changes one preference (a switch, checkbox or list) of a configurable source of each kind the way the settings page does, reads it back and restores it. Run it after changing an operation or a type.

## Backend sidecar

`pnpm tauri dev` starts the newest `../server/api/build/Madoyomi-Server-*.jar`, so build it once with `.\gradlew.bat :api:shadowJar` in `server/`. The shell (`src-tauri/src/sidecar.rs`):

- runs `java -jar <jar> --data-dir <dir>` with a per-session random `MADOYOMI_TOKEN`, and keeps the backend's stdin open (the backend exits when it closes);
- waits for `MADOYOMI_PORT=<port>` as long as the JVM lives (up to 5 min), then for `GET /health`, and checks `/health` every 5 s after that;
- restarts the backend after an exit or a hang, after 1, 2, 4 and 8 s, and gives up after 5 failed starts in a row (Retry in the UI);
- resolves java and the jar again for every start: a missing jar fails at once, with no restarts, and Retry looks for it again (so does a restart in a debug build, which then runs a jar rebuilt since);
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
