# Upstream: Suwayomi-Server

`server/` is a fork of [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) (MPL-2.0).

| | |
|---|---|
| Upstream repo | `https://github.com/Suwayomi/Suwayomi-Server.git`, branch `master` |
| Imported commit | `7b061be09c9b62a89a63addd11ac91c40fc2510f` ("Improve Files Reliability (#2509)", 2026-11-01) |
| Imported on | 2026-11-03 |
| Upstream build version | `v2.4.1` in a 1-commit shallow clone. Upstream derives it from `git rev-list --count HEAD` (`buildSrc/src/main/kotlin/Constants.kt`), so the commit SHA is the reliable reference, not this version. |

## How it was imported

The upstream tree at the commit above was copied with `git archive` (tracked files only, no build output), **unmodified**. The user committed it on its own as the baseline, before any Madoyomi change. Every later Madoyomi change is a diff on top of that commit.

Left out of the import:

| Path | Why |
|---|---|
| `.github/` | Upstream CI and issue templates. Madoyomi has its own (Phase 7). |
| `renovate.json`, `CHANGELOG-TEMPLATE.md` | Upstream project tooling |

Binary assets that are **in the tree but not committed** (gitignored in the root `.gitignore`). `tools/fetch-upstream-assets.ps1` restores them from upstream at the pinned commit and checks their SHA-256:

| Path (upstream) | Used by |
|---|---|
| `AndroidCompat/src/main/resources/font/NotoSans/*.ttf` (8 files, ~40 MB) | `android.graphics.Typeface` stub (runtime, for extensions). Only `NotoSans`, `NotoSerif`, `NotoSansMono`, `NotoSansSymbols2` and `NotoEmoji` are referenced. `NotoColorEmoji-Regular` (24 MB) and the two Italic fonts are not. |
| `server/src/test/resources/{dice.jxl, fox.profile0.8bpc.yuv420.avif, sample1.heif}`, at `core/src/test/resources/` in Madoyomi | Upstream image-decoding tests |

Run `.\tools\fetch-upstream-assets.ps1` once after cloning, before the first Gradle build.

## Module layout

Upstream is one application module (`server`) with `i18n`, `server-config` and `server-config-generate` nested inside it. Madoyomi splits it (Phase 1 Stage 3):

```
server/
  AndroidCompat/, AndroidCompat/Config/            upstream, same place
  i18n/, server-config/, server-config-generate/   upstream, lifted out of server/
  ext-host/    upstream network + util (eu.kanade.tachiyomi.network/util), the dex2jar bytecode fixer,
               the extension classloader and a few helpers; new madoyomi.exthost.*. No DB/GraphQL/Javalin
  ext-manga/   upstream Mihon source API (eu.kanade.tachiyomi.source, minus local/)
  ext-anime/   Aniyomi source API (eu.kanade.tachiyomi.animesource), ported, Apache-2.0 (see its NOTICE.md)
  core/        the rest of upstream's server module: DB, migrations, manga domain, settings, GraphQL,
               Javalin, startup. A library (no application/shadow plugins)
  api/         madoyomi.api.* (entry point, sidecar HTTP) and the shadowJar -> api/build/Madoyomi-Server-<ver>.jar
```

Dependencies: `api → core → {ext-manga, ext-anime} → ext-host → {AndroidCompat, server-config}`.

GraphQL and Javalin stay in `core`: about 30 non-GraphQL upstream files (migrations, settings, `DBManager`, `UserType`, sync, manga impl) import GraphQL types, so they can't be split out without a deep refactor. `PackageTools` (the dex2jar wrapper and extension source loader) also stays in `core`, because it injects `ApplicationDirs` from `ServerSetup.kt`.

### Moved paths

Upstream path (relative to the upstream repo root) → Madoyomi path (relative to `server/`). Package names did not change.

| Upstream | Madoyomi |
|---|---|
| `server/build.gradle.kts` | `core/build.gradle.kts`; its `application` and `shadowJar` blocks are in `api/build.gradle.kts` |
| `server/i18n/**`, `server/server-config/**`, `server/server-config-generate/**` | `i18n/**`, `server-config/**`, `server-config-generate/**` |
| `server/src/main/kotlin/eu/kanade/tachiyomi/network/**` | `ext-host/src/main/kotlin/eu/kanade/tachiyomi/network/**` |
| `server/src/main/kotlin/eu/kanade/tachiyomi/util/**` | `ext-host/src/main/kotlin/eu/kanade/tachiyomi/util/**` |
| `server/src/main/kotlin/suwayomi/tachidesk/manga/impl/util/lang/{RxCoroutineBridge,JsonObject}.kt` | `ext-host/src/main/kotlin/suwayomi/tachidesk/manga/impl/util/lang/` |
| `server/src/main/kotlin/suwayomi/tachidesk/manga/impl/util/{BytecodeEditor,ChildFirstURLClassLoader}.kt` | `ext-host/src/main/kotlin/suwayomi/tachidesk/manga/impl/util/` |
| `server/src/main/kotlin/suwayomi/tachidesk/server/util/SocksUtils.kt` | `ext-host/src/main/kotlin/suwayomi/tachidesk/server/util/` |
| `server/src/main/kotlin/eu/kanade/tachiyomi/source/**` except `local/` | `ext-manga/src/main/kotlin/eu/kanade/tachiyomi/source/**` |
| everything else under `server/` (`src/main/**`, `src/test/**`, `src/main/jte/**`, …) | `core/` (same relative path) |

`server/src/main/kotlin/eu/kanade/tachiyomi/LICENSE` (Tachiyomi's Apache-2.0 text) stays in `core` and is copied next to the moved code in `ext-host`, `ext-manga` and `ext-anime`.

## Syncing with upstream

1. Fetch upstream and diff the imported commit against the new upstream commit: `git diff 7b061be..<new> -- . ':(exclude).github'`.
2. Rewrite the paths in that diff with the "Moved paths" table (most specific row first), apply it onto `server/` (for example `git apply --3way --directory=server`), and resolve conflicts. New upstream files under `server/src/main/kotlin/eu/kanade/tachiyomi/{network,util,source}` belong in `ext-host`/`ext-manga` unless they import `core` code.
3. Update the commit SHA here and in `tools/fetch-upstream-assets.ps1` (plus any new or changed asset hashes and their `Local` paths).

## Madoyomi changes

Rules that keep merges manageable:
- Upstream packages (`suwayomi.tachidesk.*`, `eu.kanade.tachiyomi.*`, `xyz.nulldev.*`, `android.*`, …) keep their names. **New Madoyomi code goes under `madoyomi.*`.**
- Upstream files keep their MPL-2.0 headers. Files ported from Aniyomi or Mihon keep Apache-2.0 headers (see the root `NOTICE` and `THIRD_PARTY.md`).
- Prefer disabling an upstream feature (default off, route not registered) over deleting it.
- Every Madoyomi edit inside an upstream file carries a `// Madoyomi:` comment, usually with the upstream value, so `git grep "Madoyomi:" -- server` lists them all.
- Feature switches live in one place: `madoyomi.core.MadoyomiFeatures`.
- Code in a lower module never imports a higher one (`ext-host` doesn't know `core`). Where upstream did, a small hook in `madoyomi.*` replaces the import (see "Module split" below).

Paths below are Madoyomi paths, relative to `server/`.

### Build

| File | Change |
|---|---|
| `gradle.properties` (new, tracked) | `org.gradle.jvmargs=-Xmx5120m` and `kotlin.compiler.execution.strategy=in-process`, the same as upstream CI's `.github/runner-files/ci-gradle.properties`. Without them `:core:compileKotlin` fails with `OutOfMemoryError`. |
| `.gitignore` | `!/gradle.properties`, so the root file above is tracked (upstream ignores it); `core/out` and `core/src/main/resources/WebUI.zip` (upstream: `server/...`) |
| `settings.gradle.kts` | `rootProject.name = "Madoyomi-Server"` (app name, jar name, `BuildConfig.NAME`); no `ProductName` env override; the module list of the split |
| `buildSrc/src/main/kotlin/Constants.kt` | `MainClass = "madoyomi.api.MainKt"`; fixed version `v0.1.0` (must stay numeric `vX.Y.Z`, `AppInfo` derives the version code from it) |
| `core/build.gradle.kts` | BuildConfig `GITHUB`/`DISCORD`, test system properties use `madoyomi.config.`; **deleted** the `downloadWebUI` and `runElectron` tasks. Module split: no `application`/shadow plugins (moved to `api/build.gradle.kts`, jar vendor `Madoyomi`), depends on `ext-host`/`ext-manga`/`ext-anime`, project paths `:i18n`, `:server-config`, `:server-config-generate` |
| `server-config-generate/build.gradle.kts` | `generateSettings` targets `:core` (upstream `:server`), with `evaluationDependsOn(":core")` since the two are siblings now; `projects.serverConfig` |
| `ext-host/`, `ext-manga/`, `ext-anime/`, `api/` `build.gradle.kts` (new) | the new modules |

### Rebrand (user-visible names only)

| File | Change |
|---|---|
| `AndroidCompat/Config/.../ApplicationRootDir.kt` | `CONFIG_PREFIX = "madoyomi.config"` (so `-Dmadoyomi.config.server.*`); default data dir `%APPDATA%\Madoyomi` on Windows, AppDirs `Madoyomi` elsewhere |
| `AndroidCompat/Config/.../Logging.kt` | log file `logs/madoyomi.log`, appender name; `debugLogsEnabled` reads the prefixed property |
| `core/.../server/ServerSetup.kt` | temp dir `<tmp>/Madoyomi`; startup log line uses `BuildConfig.NAME` |
| `core/.../server/util/AppExit.kt` | shutdown log line |
| `core/.../server/database/DBManager.kt` | Hikari pool name |
| `core/.../graphql/server/TemporaryFileStorage.kt`, `ext-host/.../eu/kanade/tachiyomi/network/NetworkHelper.kt` | temp dir names |
| `core/.../global/impl/util/Jwt.kt`, `server-config/.../ServerConfig.kt` (`jwtAudience`) | JWT issuer/audience `madoyomi-server[-api]` |
| `core/.../server/user/UserType.kt`, `core/.../server/JavalinSetup.kt`, `core/.../graphql/controller/GraphQLController.kt` | cookie names `madoyomi-server-token`, `madoyomi-basic-csrf` |
| `core/.../manga/impl/backup/proto/models/Backup.kt` | backup file prefix `app.madoyomi` (the `.tachibk` extension stays, for Mihon compatibility) |
| Tracker clients in `core`: `AnilistInterceptor`, `KitsuInterceptor`, `MangaUpdatesInterceptor`, `MyAnimeListInterceptor`, `ShikimoriInterceptor`, `BangumiInterceptor`, `MangaBakaApi` | User-Agent says Madoyomi. OAuth redirect URLs are unchanged (trackers are Phase 8). |
| Tests `UserContextTest`, `ProtoBackupExportTest` (in `core`) | follow the cookie name and backup prefix |

Deliberately **not** rebranded: package names (rule above); i18n strings (they only appear in OPDS, the login page and the KCEF WebView, all disabled); tray, SyncYomi and KOReader device names (disabled features); `docs/`, `scripts/`, `README.md` and `CONTRIBUTING.md` (upstream documentation and packaging, unused by Madoyomi; their `:server:` task names refer to upstream's layout).

### Sidecar defaults and disabled features

| File | Change |
|---|---|
| `server-config/.../ServerConfig.kt` | defaults: `ip` `127.0.0.1`, `port` `0` (min lowered to 0), `initialOpenInBrowserEnabled`, `systemTrayEnabled`, `webUIEnabled` and `kcefEnabled` all `false` (KCEF code stays for Phase 2) |
| `core/.../server/JavalinSetup.kt` | connector always binds `127.0.0.1` and is exposed as `JavalinSetup.connector`; startup log shows the bound port; CORS allows only the Tauri origins (`MadoyomiFeatures.ALLOWED_ORIGINS`), on every path; OPDS routes not registered; `javalinSetup(configure)` hook, called before upstream's `defineCore()`, and it returns the app; the `api/` "unmatched → 404" `after` handler leaves a 401 alone (otherwise token rejections would turn into 404s) |
| `core/.../server/ServerSetup.kt` | SyncYomi scheduling off |
| `core/.../manga/impl/update/Updater.kt` | no SyncYomi sync before library updates |
| `core/.../manga/controller/MangaController.kt`, `core/.../graphql/mutations/ChapterMutation.kt` | no KOReader progress pushes |
| `core/.../global/impl/AppUpdate.kt` | server update check returns nothing (never calls Suwayomi's GitHub) |
| `core/.../server/util/WebInterfaceManager.kt` | WebUI update check/download is a no-op while the WebUI is disabled |
| `core/.../server/util/AppMutex.kt` | single-instance probe off (Tauri owns the lifecycle) |
| `core/.../server/Migration.kt` | legacy Java-Preferences import skipped: it would otherwise import and **delete** an old Suwayomi install's registry node |

### Module split

Upstream code that would have referenced a higher module, or an `internal` symbol of another module, after the split. These were the only cross-module references the moved code had.

| File | Change |
|---|---|
| `ext-host/.../eu/kanade/tachiyomi/network/NetworkHelper.kt` | calls `madoyomi.exthost.SourceRegistry.unregisterAllSources()` when the default User-Agent changes (upstream: `GetSource.unregisterAllSources()`, which is in `core`) |
| `core/.../manga/impl/util/source/GetSource.kt` | registers `unregisterAllSources` with `SourceRegistry` in its `init` block (the source cache only exists once `GetSource` is initialised) |
| `ext-host/.../suwayomi/tachidesk/server/util/SocksUtils.kt` | `buildSocksProxyUrl()` is public (upstream: `internal`), because `core`'s `CEFManager` and `SocksProxyTest` call it across the module boundary |
| `ext-host/.../eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt` | `CFClearance.withRequestProxy` is public (upstream: `internal`), for `SocksProxyTest` in `core` |

`awaitSingle` (`RxCoroutineBridge.kt`), `JsonObject.EMPTY` (`JsonObject.kt`) and `SocksUtils.kt` moved down to `ext-host` with their packages unchanged, so their callers in `ext-manga` and `core` did not change.

### Domain & DB (Phase 1 Stage 4)

Repos and extensions of both kinds share upstream's `EXTENSIONSTORE` and `EXTENSION` tables, told apart by a new `type` column (see "Database migrations" below). Upstream's code is manga-only, so every query of it that reads a *set* of these rows, or acts on a row the client names, now also requires `type = MANGA`. Lookups that reach `EXTENSION` through `SOURCE` (manga-only by construction), inserts (the column defaults to `MANGA`) and `removeExtensionStore`/`ExtensionStoreService.upsert` (keyed by the unique `index_url`) are unchanged. Since Stage 5 `removeExtensionStore` goes through `RepoService` (see "Repo manager" below); Stage 7 adds `type` to the GraphQL inputs.

| File | Change |
|---|---|
| `core/.../manga/model/table/ExtensionTable.kt` | new columns `type`, `signatureHash`, `isTorrent`, `isLoaded` |
| `core/.../manga/model/table/ExtensionStoreTable.kt` | new columns `type`, `format`, `lastRefreshedAt`, `lastError`, `signingKeyTrusted` |
| `core/.../manga/impl/extension/ExtensionsList.kt` | `type = MANGA` on `extensionTableAsDataClass` and on the installed set of `updateExtensionDatabase` (which drives its updates, obsolete marking and deletes) |
| `core/.../manga/impl/extension/ExtensionStoreService.kt` | `type = MANGA` on the store sets of `getAndRefresh`, `syncDbToPrefs` and `syncPrefsToDb`, and on the delete in `syncPrefsToDb` (the `extensionStores` setting lists manga stores only) |
| `core/.../manga/impl/extension/Extension.kt` | `type = MANGA` on the `installExtension(pkgName)` and `uninstallExtension` lookups |
| `core/.../manga/impl/util/source/GetSource.kt` | `type = MANGA` on the extension lookup, so the manga loader can never load another kind of jar |
| `core/.../graphql/queries/ExtensionQuery.kt` | `extensions`: `type = MANGA` in the base query's `adjustWhere` (covers nodes, `totalCount` and page info) |
| `core/.../graphql/queries/ExtensionStoreQuery.kt` | `extensionStores`: `type = MANGA` on the base query |
| `core/.../graphql/dataLoaders/ExtensionDataLoader.kt` | `ExtensionDataLoader` (`extension(pkgName)`): `type = MANGA` |
| `core/.../graphql/dataLoaders/ExtensionStoreDataLoader.kt` | `ExtensionStoreDataLoader` (`extensionStore(indexUrl)`): `type = MANGA` |
| `core/.../graphql/mutations/ExtensionMutation.kt` | `type = MANGA` on the `updateExtension(s)` lookup and the two `fetchExtensions` payload lists |

### Repo manager (Phase 1 Stage 5)

The repo manager lives in `madoyomi.core.repo` (see "New Madoyomi code"). Upstream's store code now delegates to it, so both kinds of repo share one parser, one format-detection path and one trust model.

| File | Change |
|---|---|
| `core/.../manga/impl/extension/ExtensionStoreService.kt` | `fetch` and `getExtensions` delegate to `RepoIndexFetcher` (format `AUTO`, or `JSON` for a legacy store's `getExtensions`, which reproduces upstream's "read `index.min.json`, don't follow `index_v2`"). `syncPrefsToDb` adds new URLs through `RepoService.add` (so a repo typed into the config file is trusted like any added repo). The old inline parsing is gone; `getAndRefresh` and `upsert` are unchanged but no longer called by Madoyomi code. |
| `core/.../manga/impl/extension/ExtensionsList.kt` | `fetchExtensions` calls `RepoService.refreshAll(MANGA)` (last-good cache). `updateExtensionDatabase` is `internal` (upstream: `private`), takes `kind` (the set of rows it syncs; new rows get `type = kind`) and `prune` (without it nothing counts as missing), and writes `is_torrent` |
| `core/.../manga/impl/extension/github/NetworkLegacyExtension.kt` | `torrent: Int = 0` (Aniyomi's legacy index; upstream's `ignoreUnknownKeys` dropped it) |
| `core/.../manga/model/dataclass/ExtensionInfo.kt` | `isTorrent: Boolean = false`, carried into `EXTENSION.is_torrent` |
| `core/.../graphql/mutations/ExtensionStoreMutation.kt` | `addExtensionStore` → `RepoService.add(indexUrl, MANGA)`; `removeExtensionStore` → `RepoService.remove(indexUrl)`. Inputs and payloads are unchanged (Stage 7 adds `type`/`format`) |
| `core/.../server/ServerSetup.kt` | after the startup `syncPrefsToDb()`, `RepoService.refreshOnStartup()` refreshes every repo of both kinds once, in the background |

How the repo manager behaves:
- **Formats.**
  - `AUTO` is upstream's behaviour: gzip sniff, then the first byte (`[` legacy `index.min.json` → its `repo.json`, `{` `repo.json` or JSON v2, otherwise protobuf). It follows `index_v2`, at most 3 hops.
  - `JSON` never follows `index_v2`, and `PROTOBUF` decodes the URL as protobuf without sniffing.
  - The format is stored per repo (`EXTENSIONSTORE.format`), and refreshes use it.
- **Two protobuf schemas.** Mihon's (`NetworkExtensionStore`, upstream DTO) is for manga. Aniyomi's (`AnimeNetworkExtensionStore`) is for anime: field 8 is a bool `is_torrent` and the sources moved to field 9, so upstream's DTO cannot read yuzono's `index.pb`. The parser tries the repo kind's schema first and then the other one, so a repo added as the wrong kind fails the package check with `REPO_TYPE_MISMATCH`.
- **Add** (`add(indexUrl, kind, format)`):
  - The stored URL is where the index resolved to.
  - Every listed pkg must match the kind (`.animeextension.` / `.extension.`).
  - The repo's signing key is trusted: `signing_key_trusted = true`, plus a `TRUSTED_SIGNATURE` row (origin STORE) unless that hash already has one.
  - Re-adding with a new key revokes the old key, unless another repo still vouches for it.
- **Remove:**
  - The FK cascade drops the repo's trust row. `TRUSTED_SIGNATURE.hash` is the primary key, so a key shared with another trusted repo gets its row re-pointed at that repo, and USER trust is never touched.
  - The repo's uninstalled listings are deleted.
  - Installed extensions of the repo (or signed with its key) whose hash is no longer trusted get `is_loaded = false` and are unloaded (`ExtensionUnloader`: sources out of `GetSource`'s cache, class loader closed). Until Stage 6 adds the trust gate to `GetSource`, a manga extension unloaded this way can still be reloaded lazily.
- **Refresh** (`refreshAll(kind?)`, on demand through `fetchExtensions` and at startup):
  - A failed repo keeps its rows and gets `last_error`; a row it last listed is only replaced by a newer version.
  - Pruning (upstream's delete/obsolete of missing entries) only runs when every repo of that kind fetched.
  - A changed signing key is not applied (as in Mihon): the refresh fails until the user re-adds the repo.
  - A repo whose URL changed (e.g. it gained `index_v2`) is updated in place, so its id, trust, type and format survive (upstream deletes and re-inserts it).
  - Listings of the wrong kind are dropped with a warning.
  - A refresh with no repos of a kind does nothing.
- **Persistence.** Anime repos live only in `EXTENSIONSTORE`. Manga repos are also mirrored to the upstream `extensionStores` setting.
- **Duplicates.** A pkg offered by two repos of the same kind keeps upstream's rule: one row, the highest version code wins.
- No migration: every column used here exists since `M0069`.

### New Madoyomi code

| Path | What |
|---|---|
| `core/src/main/kotlin/madoyomi/core/MadoyomiFeatures.kt` | feature switches + loopback/CORS constants referenced by upstream files |
| `core/src/main/kotlin/madoyomi/core/MadoyomiBuildInfo.kt` | public `name`/`version` from the generated `BuildConfig`, which upstream's buildconfig plugin makes `internal` to `core` (used by `api`'s `/health`) |
| `ext-host/src/main/kotlin/madoyomi/exthost/SourceRegistry.kt` | callback hook that lets `ext-host` ask `core`'s source cache to drop its sources |
| `api/src/main/kotlin/madoyomi/api/Main.kt` | entry point `madoyomi.api.MainKt` |
| `api/src/main/kotlin/madoyomi/api/SidecarHttp.kt` | `GET /health`, the token filter and the WebSocket upgrade check |
| `api/src/main/kotlin/madoyomi/api/SidecarToken.kt` | token from `MADOYOMI_TOKEN`, constant-time compare |
| `ext-anime/src/main/kotlin/eu/kanade/tachiyomi/animesource/**`, `ext-anime/src/main/kotlin/mihon/core/common/extensions/JsonObject.kt` | Aniyomi source API, ported from the Phase 0 spike (Apache-2.0, see `ext-anime/NOTICE.md`). Phase 1 only compiles it. |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionKind.kt` | `ExtensionKind { MANGA, ANIME }`, stored by name in `EXTENSIONSTORE.type` and `EXTENSION.type` |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexFormat.kt` | `RepoIndexFormat { AUTO, JSON, PROTOBUF }` (`EXTENSIONSTORE.format`) |
| `core/src/main/kotlin/madoyomi/core/extension/TrustedSignatureTable.kt` | `TRUSTED_SIGNATURE` table + `SignatureTrustOrigin { USER, STORE }` |
| `core/src/main/kotlin/madoyomi/core/anime/model/table/*.kt` | `AnimeSourceTable`, `AnimeTable`, `EpisodeTable`, `AnimeHistoryTable`, `AnimeCategoryTable` |
| `core/src/main/kotlin/madoyomi/core/database/MadoyomiMigration.kt` | marker interface for Madoyomi migrations (see "Database migrations") |
| `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0069_MadoyomiExtensionKinds.kt`, `M0070_AnimeTables.kt` | Madoyomi migrations. New files, but in upstream's migration package, because the runner only scans that package. |
| `core/src/test/kotlin/madoyomi/core/database/MigrationsFromEmptyDbTest.kt` | the migration chain on empty and pre-Madoyomi H2 databases, re-runs, schema and Exposed-table checks |
| `core/src/main/kotlin/madoyomi/core/repo/ExtensionListing.kt`, `RepoStoreMeta.kt` | the common listing model (`ExtensionListing`, `ListingSource`), repo metadata, `FetchedRepo`, signing-key normalisation, the pkg/kind check |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexParser.kt` | `RepoIndexParser` + `ParsedIndex` (v2 store, legacy `repo.json`, legacy `index.min.json`), gzip sniff (64 MiB cap) |
| `core/src/main/kotlin/madoyomi/core/repo/JsonIndexParser.kt`, `ProtobufIndexParser.kt` | the two parsers over upstream's DTOs |
| `core/src/main/kotlin/madoyomi/core/repo/AnimeNetworkExtensionStore.kt` | Aniyomi's `index.pb` schema (reverse-engineered, see the Phase 1 doc §10) |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexFetcher.kt` | format detection/override and redirects over a `RepoHttp` (the default uses `NetworkHelper`'s client) |
| `core/src/main/kotlin/madoyomi/core/repo/RepoService.kt` | repo add/remove/refresh, trust and the last-good cache (see "Repo manager" above) |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionUnloader.kt` | drops a loaded extension's sources and class loader |
| `core/src/main/kotlin/madoyomi/core/error/MadoyomiException.kt` | `MadoyomiException(code, message, details)` + `ErrorCode` (Stage 7 maps it to GraphQL errors) |
| `core/src/test/kotlin/madoyomi/core/repo/*`, `core/src/test/resources/repo/*` | parser, fetcher and service tests. Fixtures are text: trimmed real yuzono `index.min.json`/`repo.json`, and trimmed yuzono and Keiyoushi `index.pb` stored as base64 of the gzipped bytes. `RepoServiceLiveTest` hits the real repos and only runs with `MADOYOMI_LIVE_REPO_TESTS=1` |

## Database migrations

How upstream's runner (`com.github.Suwayomi:exposed-migrations`, called from `DBManager.databaseUp`) works, and what follows from it:
- It instantiates every top-level class in `suwayomi.tachidesk.server.database.migration`, which must all be migrations named `M<number>_<Name>`. So Madoyomi migrations live in that upstream package, and nothing else may be put there.
- The numbers must be exactly `1..N`, with no gaps.
- It records each migration it ran in `MIGRATIONS` and on later starts runs only the numbers **above the highest recorded one**. Running it twice is a no-op.

Rules for Madoyomi migrations:
1. Upstream owns `M0001`–`M0068` (at `7b061be`). Madoyomi's start at `M0069` and are always the highest-numbered ones.
2. Each carries the `// Madoyomi migration:` marker comment and implements `madoyomi.core.database.MadoyomiMigration`.
3. Each is idempotent: `ADD COLUMN IF NOT EXISTS`, `SchemaUtils.create` (skips existing tables), `CREATE … IF NOT EXISTS`, and data changes guarded the same way. Use portable SQL (H2 and PostgreSQL), as upstream does. Only H2 is tested.

`MigrationsFromEmptyDbTest` enforces rules 1 and 3.

| Migration | Adds |
|---|---|
| `M0069_MadoyomiExtensionKinds` | `EXTENSIONSTORE`: `TYPE` (`MANGA`/`ANIME`, default `MANGA`), `FORMAT` (`AUTO`/`JSON`/`PROTOBUF`, default `AUTO`), `LAST_REFRESHED_AT` (epoch s, default 0), `LAST_ERROR` (nullable), `SIGNING_KEY_TRUSTED` (default false). `EXTENSION`: `TYPE` (default `MANGA`), `SIGNATURE_HASH` (nullable), `IS_TORRENT`, `IS_LOADED` (default false). New `TRUSTED_SIGNATURE`: `HASH` (PK, hex SHA-256), `ORIGIN` (`USER`/`STORE`), `STORE_ID` (nullable FK → `EXTENSIONSTORE`, `ON DELETE CASCADE`: removing a repo revokes its key), `ADDED_AT`. Existing rows become manga, `AUTO`, untrusted. |
| `M0070_AnimeTables` | `ANIME_SOURCE` (id, name, lang, extension FK, content_warning, base_url), `ANIME` (url, title, artist, author, description, genre, status, thumbnail_url, in_library, initialized, source, added_at, last_fetched_at), `EPISODE` (url, name, episode_number, date_upload, scanlator, watched, bookmark, last_second_seen, total_seconds, source_order, fetched_at, anime FK `CASCADE`), and Phase 6 stubs `ANIME_HISTORY` (episode FK `CASCADE`, unique; last_seen) and `ANIME_CATEGORY` (category FK, anime FK, both `CASCADE`; unique pair) |

Sources are typed by table (manga in `SOURCE`, anime in `ANIME_SOURCE`), and repos and extensions by the `type` column. The reasoning is in the Phase 1 doc (`docs/phases/phase-1-backend-foundation.md`, step 6).

### Numbering collisions on an upstream sync

When the upstream diff adds migrations of its own, say `k` of them, numbered `M0069`…`M00(68+k)`:
1. Upstream's new migrations keep their numbers.
2. Rename ours up by `k`, keeping their order and names (`git mv M0069_MadoyomiExtensionKinds.kt M00(69+k)_MadoyomiExtensionKinds.kt` and the class name, and so on). Update the numbers in this file, in `MigrationsFromEmptyDbTest` (`FIRST_MADOYOMI_VERSION`) and in the "Upgrading a pre-Madoyomi database" test.
3. Databases that already ran ours have them recorded at the old numbers. The runner would treat upstream's new migrations as already done and skip them. So the first sync that renumbers must also add a reconcile step to `DBManager.databaseUp`, just before `runMigrations` (a `// Madoyomi:` edit). It goes through `MIGRATIONS` in version order and finds the first row whose `NAME` is the name of a `MadoyomiMigration` but whose `VERSION` no longer matches that migration's number. It deletes that row and every row after it. The runner then applies upstream's new migrations and re-runs ours, which is safe because they are idempotent. Because it only matches Madoyomi migration names, upstream rows are never re-run.
4. Until Madoyomi has users with real data, renumbering and deleting the dev data dirs (`tools/dev-data`) is enough. Step 3 can wait until it is needed.

### Startup contract (what the Tauri shell relies on)

- `java -jar api/build/Madoyomi-Server-v0.1.0.jar [--data-dir <path>]` (built by `.\gradlew.bat :api:shadowJar`). Unknown arguments exit with code 2.
- Env `MADOYOMI_TOKEN`: the API token. If unset, a random one is used and the API is effectively locked.
- Binds `127.0.0.1` on a random free port and prints exactly one line `MADOYOMI_PORT=<port>` on stdout once listening (other stdout lines are logs).
- Every request needs `Authorization: Bearer <token>`, except `GET /health` and CORS preflights. `GET` requests and WebSocket upgrades may pass `?token=<token>` instead. A rejected request gets `401 {"code":"UNAUTHORIZED","message":…}`.
- Upstream `authMode` is forced to `NONE` (every request is the built-in admin), so the token is the only gate.
- `GET /health` → `{"status":"ok","name","version","pid","uptimeMs"}`.
- CORS: `http://localhost:1420`, `http://tauri.localhost`, `https://tauri.localhost`.
- Exits (shutdown hooks run) when stdin closes.

`tools/run-server.ps1` builds the jar and runs it this way with a dev data dir (`tools/dev-data/`).
