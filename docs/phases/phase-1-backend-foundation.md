# Phase 1 — Backend foundation

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 0](phase-0-spikes.md) · Next: [Phase 2](phase-2-anime-sources.md)

## 1. Goal

Turn the Phase 0 findings into a real backend in `server/`: a Suwayomi-based Kotlin/JVM service, rebranded as Madoyomi, that knows about both **anime** and **manga**, manages user-added repos in both index formats, and installs, updates, trusts and uninstalls extensions of both types through the API. No anime source calls yet (that is Phase 2). This phase builds the plumbing they run on.

## 2. Depends on / inputs

- Phase 0 complete; the fork-vs-library and REST-vs-GraphQL decisions are recorded.
- `docs/research/suwayomi-notes.md` and `docs/research/index-pb.proto` from Phase 0.
- The sidecar handshake from Phase 0 S5 (`MADOYOMI_PORT=<port>` on stdout).

## 3. Deliverables

- `server/` Gradle multi-module project:
  - `server/core`: DB, settings, shared models, migrations
  - `server/ext-host`: APK download, dex2jar, classloader, Android stubs (from Suwayomi)
  - `server/ext-manga`: Mihon source adapter (from Suwayomi)
  - `server/ext-anime`: Aniyomi adapter (skeleton only in this phase)
  - `server/api`: HTTP + WebSocket layer
- Anime domain: tables and entities for anime, episodes, anime sources and anime extensions.
- Repo manager: JSON (anime) and protobuf (manga) parsers, plus add/remove/refresh.
- Extension lifecycle: install, update, uninstall, trust by signing-cert hash.
- API endpoints: `/api/repos`, `/api/extensions`, `/api/extensions/{pkg}/install|trust|uninstall|update`, `/health`.
- `NOTICE` / `THIRD_PARTY.md` with Suwayomi (MPL-2.0) and Aniyomi/Mihon (Apache-2.0) attribution.

## 4. Detailed steps

### Backend — fork & structure
1. Fork Suwayomi-Server into `server/` (keep git history as a subtree or note the upstream commit in `server/UPSTREAM.md`).
2. Rename packages/app name/data dir to Madoyomi (`%APPDATA%/Madoyomi`). Keep MPL-2.0 headers on Suwayomi-derived files.
3. Split into the modules above. Move Suwayomi's extension loading into `ext-host` and the manga source code into `ext-manga`.
4. Turn off the Suwayomi parts we don't need (bundled WebUI, its own tray/launcher) behind flags or delete them.
5. Startup: bind `127.0.0.1:0`, print `MADOYOMI_PORT=<port>`, and accept a `--data-dir` argument plus a shared auth token passed in by Tauri (reject requests without it).

### Backend — domain & DB
6. Add a `type` discriminator (`MANGA` | `ANIME`) to repos and extensions. **Design note:** repos and extensions share upstream's `EXTENSIONSTORE` and `EXTENSION` tables, with a `type` column, because their lifecycle code (refresh, install, update, trust, uninstall) is shared. Sources are typed by table instead: manga in `SOURCE`, anime in `ANIME_SOURCE`. That keeps upstream's manga-only `SOURCE` queries untouched. Upstream queries on the shared tables that assume manga filter on `type = MANGA` (listed in `server/UPSTREAM.md`, "Domain & DB").
7. Add anime tables: `anime`, `episode`, `anime_source`, plus `anime_history` and `anime_category` stubs for Phase 6. Keep them separate from the manga tables.
8. Write migrations for the new tables, and test that upgrading from an empty DB works.

### Backend — repo manager
9. `RepoIndexParser` interface with `JsonIndexParser` (yuzono `index.min.json`) and `ProtobufIndexParser` (Keiyoushi `index.pb`), both producing a common `ExtensionListing` model.
10. Detect the format from the URL suffix or content sniffing; let the user override it.
11. Repo CRUD + refresh (manual, plus on app start). Cache the last good index if a refresh fails.

### Backend — extension lifecycle
12. Install: download APK → verify package name and version code → extract the signing cert SHA-256 → dex2jar → store under `data/extensions/{type}/{pkg}/`.
13. Trust: untrusted extensions are installed but **not loaded** until the user trusts their cert hash. Store trusted hashes in a table.
14. Update: compare the version code with the repo listing and mark "update available"; updating re-runs install and keeps trust if the cert is unchanged.
15. Uninstall: unload the classloader, delete the files and remove the source rows.
16. Anime extensions: install/trust/list only. Instantiating sources comes in Phase 2.

### API
17. Implement the repo and extension endpoints from PLAN.md §5 in the API style chosen in Phase 0.
18. WebSocket `/ws`: emit `extension.installProgress` and `repo.refreshed` events.
19. Error model: `{ code, message, details }`, used the same way on every endpoint.

### Tests & tooling
20. Unit tests for both index parsers, using fixture files in `server/*/src/test/resources`.
21. Integration test: add a repo → list → install → trust → list sources (manga), using a small fixture APK or a mocked download.
22. `./gradlew build` runs the tests. Add a `tools/run-server.ps1` dev script.

## 5. Checklist

### Fork & structure
- [x] Suwayomi forked into `server/`; upstream commit recorded in `server/UPSTREAM.md`
- [x] Rebranded (app name, data dir, config prefix, logs, User-Agents, version). Package names are kept upstream by decision; new code goes under `madoyomi.*` (see `server/UPSTREAM.md`)
- [x] MPL-2.0 headers kept; `NOTICE` / `THIRD_PARTY.md` added (moved upstream files are byte-identical renames except the few `// Madoyomi:` edits; the ported `ext-anime` files carry Apache-2.0 headers and `server/ext-anime/NOTICE.md`)
- [x] Modules created: `core`, `ext-host`, `ext-manga`, `ext-anime`, `api` (`api → core → {ext-manga, ext-anime} → ext-host → {AndroidCompat, server-config}`). Deviation from §3: GraphQL and Javalin stay in `core`, because about 30 non-GraphQL upstream files import GraphQL types. `api` holds the entry point, the sidecar HTTP layer and, from Stage 7, Madoyomi's API additions. `PackageTools` (dex2jar wrapper, source loading) also stays in `core`, because it needs `ApplicationDirs`. Old → new paths are in `server/UPSTREAM.md` "Moved paths".
- [x] Unused Suwayomi features removed or disabled (WebUI, tray, browser/Electron launch, KCEF by default, OPDS, SyncYomi/KOReader, update checks, app mutex)
- [x] Random port + `MADOYOMI_PORT` handshake + auth token (plus `--data-dir`, loopback-only bind, Tauri-only CORS, stdin watchdog, `GET /health`)

### Domain & DB
- [x] `type` discriminator on repos/extensions; sources typed by table (see step 6). Migration `M0069_MadoyomiExtensionKinds`; upstream manga queries filter on `type = MANGA` (`server/UPSTREAM.md`, "Domain & DB")
- [x] Anime tables + migrations (`M0070_AnimeTables`: `ANIME_SOURCE`, `ANIME`, `EPISODE`, plus `ANIME_HISTORY` and `ANIME_CATEGORY` stubs; `TRUSTED_SIGNATURE` in `M0069`). Numbering and the upstream-collision rule are in `server/UPSTREAM.md`, "Database migrations"
- [x] Migration test from an empty DB (`MigrationsFromEmptyDbTest`: empty DB, second run is a no-op, upgrade from a pre-Madoyomi DB, Madoyomi migrations re-runnable, Exposed tables match the schema)

### Repo manager
- [ ] `JsonIndexParser` (anime)
- [ ] `ProtobufIndexParser` (manga)
- [ ] Format detection + override
- [ ] Repo add/remove/refresh with last-good cache

### Extension lifecycle
- [ ] Install (download, verify, cert hash, dex2jar, store)
- [ ] Trust prompt flow: untrusted extensions are not loaded
- [ ] Update detection + update
- [ ] Uninstall (unload + delete)
- [ ] Works for both anime and manga APKs

### API
- [ ] `GET/POST/DELETE /api/repos`
- [ ] `GET /api/extensions?type=`
- [ ] `POST /api/extensions/{pkg}/install|update|uninstall|trust`
- [ ] `GET /api/sources?type=` (manga sources work; anime list may be empty)
- [ ] `/ws` progress events
- [ ] Uniform error model

### Tests
- [ ] Parser unit tests with fixtures
- [ ] Install → trust → list integration test
- [ ] `./gradlew build` passes

## 6. Exit criteria

- [ ] From the API alone (curl/HTTPie), a user can add the yuzono and Keiyoushi repos, list available extensions of both types, install one of each, trust them, and see them as installed.
- [ ] Manga sources from installed extensions still browse and read (no regression from Suwayomi).
- [ ] All tests pass in `./gradlew build`.

## 7. Risks & notes

- Big fork, big merge cost later: record the upstream commit and keep Madoyomi changes in clearly separate files where possible (PLAN.md §6, "License mixing").
- Extensions are arbitrary code. Never load an extension before trust (PLAN.md §6, "Extensions are arbitrary JVM code").
- If Suwayomi already parses `index.pb`, reuse its parser instead of writing a new one.

## 8. Decisions closed here

- One JVM process for both extension types, or isolated processes? (final answer; PLAN.md §7)
- DB choice confirmed (Suwayomi's existing DB vs. SQLite).
