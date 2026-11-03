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
9. `RepoIndexParser` interface with `JsonIndexParser` (yuzono `index.min.json`) and `ProtobufIndexParser` (Keiyoushi `index.pb`), both producing a common `ExtensionListing` model. The parsers take the repo kind, because Aniyomi's `index.pb` (yuzono) uses a different schema from Mihon's (§10).
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
- [x] `JsonIndexParser` (anime): legacy `index.min.json` with the `torrent` flag, `repo.json`, JSON v2 (`madoyomi.core.repo`)
- [x] `ProtobufIndexParser` (manga): Mihon `index.pb` via upstream's DTO, plus Aniyomi's `index.pb` schema for anime (§10)
- [x] Format detection + override (`AUTO` = upstream sniffing + `index_v2`; `JSON`/`PROTOBUF` force a parser; stored per repo)
- [x] Repo add/remove/refresh with last-good cache (`RepoService`: type check, store-key trust, shared-key-safe remove, startup + on-demand refresh, `last_error`; see §9)

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
- [x] Parser unit tests with fixtures (`core/src/test/kotlin/madoyomi/core/repo/`, text fixtures in `core/src/test/resources/repo/`; protobuf stored as base64 of the gzipped bytes)
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

## 9. Decision record

Repo manager (Stage 5). The rest of the phase's decisions are added at the end of the phase.

| Decision | Why |
|---|---|
| Anime repos persist only in the DB (`EXTENSIONSTORE`, `type = ANIME`). Upstream's `extensionStores` setting stays a manga-only mirror. | No new setting and no generated-settings changes; the DB is the source of truth for both kinds, and the GraphQL `extensionStores` query reads it. |
| A pkg offered by two repos of the same kind keeps upstream's rule: one `EXTENSION` row, highest version code wins (ties: the older repo). | `pkg_name` is unique; trust is per signature, not per repo, so this doesn't affect trust. |
| A signing key that changes on refresh is not applied; the refresh fails (`last_error`) until the user re-adds the repo. | Same as Mihon. A compromised repo can't swap keys silently. |
| Adding checks every pkg against the repo type (`.animeextension.` vs `.extension.`) and rejects a mismatch (`REPO_TYPE_MISMATCH`). A refresh drops mismatched entries with a warning. | A wrong-type repo is a user mistake, best reported at once; one bad entry later shouldn't freeze a repo. |
| `ANIME_SOURCE` rows come from the listing at install (Stage 6), not at refresh. | Same as manga `SOURCE` rows today. |
| The existing `addExtensionStore`/`removeExtensionStore`/`fetchExtensions` mutations go through `RepoService` (manga). Stage 7 adds `type`/`format`. | They are the only entry points, and they must follow the trust model and the last-good cache. |
| A refresh never grants trust. Repos added before Stage 5 stay untrusted (`signing_key_trusted = false`) until they are re-added. | Trust is only ever granted by a user action. |
| Removing a repo revokes per signature: the row of a key shared with another trusted repo moves to that repo, and USER trust is never touched. | `TRUSTED_SIGNATURE.hash` is the primary key (one row per key). |

## 10. Findings log

**2026-11-03: repo indexes (Stage 5).** No `protoc` was available, so a throwaway test walked the protobuf wire format and decoded with the DTOs.
- **yuzono `index.pb`** (`https://github.com/yuzono/anime-repo/raw/repo/index.pb`, reached from `repo.json`'s `index_v2`):
  - **It uses Aniyomi's schema, not Mihon's.** The store, contact and resources messages are the same, but in `Extension` field 7 is the content warning (1/2/3), **field 8 is a bool `is_torrent`**, and **field 9 is the repeated `Source`** (Mihon has the sources at field 8). Source fields: 1 id, 2 name, 3 language, 4 home URL, 5 mirror URLs (16 sources have some).
  - Upstream's Mihon DTO throws on it, so Suwayomi as it stands can't add yuzono with `AUTO`. Madoyomi has `AnimeNetworkExtensionStore` for it.
  - **The torrent flag is in the protobuf:** field 8 is set on exactly the 7 extensions with `"torrent": 1` in `index.min.json` (hentaitorrent, nyaatorrent, ptorrent, stremio, subsplease, torrentio, torrentioanime). So `is_torrent` comes from the index in both formats. Stage 6 can still cross-check it against the manifest (`tachiyomi.animeextension.torrent`).
  - 247 extensions, 301 sources, gzipped (15,970 bytes, 86,172 raw). The **same 247 pkgs and the same version codes as `index.min.json`** (Phase 0 also saw 247).
  - Content warnings: 175 SAFE, 5 MIXED, 67 NSFW. Libs: `14` ×197, `16` ×50.
  - Names have no `Aniyomi: ` prefix, which the JSON has. APK/icon URLs are on `cdn.jsdelivr.net` (the JSON's are relative to the raw GitHub repo).
  - Signing key `cbec121a…c45905`, the same as `repo.json`; badge `Yū`; Discord in the contact.
- **yuzono `index.min.json`:**
  - `torrent` is an int 0/1.
  - Source ids are JSON strings, which kotlinx decodes as `Long`.
  - `nsfw` is 0/1. As upstream does, 1 maps to MIXED, so the JSON can't tell MIXED from NSFW; the protobuf can.
- **Keiyoushi `index.pb`:**
  - 1452 extensions, 2365 sources (137 with mirror URLs), all 1452 with a `jar_url` (field 501).
  - Gzipped 106,214 bytes / 727,528 raw. Key `9add655a…4da2`.
  - Its `repo.json` has `index_v2` → `index.pb` and no `shortName`.
- **Live check** (`RepoServiceLiveTest`, `MADOYOMI_LIVE_REPO_TESTS=1`):
  - yuzono `AUTO` → `index.pb`, 247 listings / 7 torrent;
  - yuzono `JSON` → `repo.json` (legacy), 247 / 7;
  - Keiyoushi `index.min.json` `AUTO` → `index.pb`, 1452.

  Each repo is trusted as a STORE key and refreshes cleanly.
- kotlinx-protobuf can fail on truncated input with `IndexOutOfBoundsException`, not only `SerializationException`, so the parser treats any runtime exception as "not this format".
