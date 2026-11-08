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
  api/         madoyomi.api.* (entry point, sidecar HTTP, Madoyomi's GraphQL operations) and the shadowJar
               -> api/build/Madoyomi-Server-<ver>.jar
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
| `ext-host/`, `ext-manga/`, `ext-anime/`, `api/` `build.gradle.kts` (new) | the new modules. Since Stage 6 `ext-host` depends on apksig (`implementation`, so it is bundled into the server jar) and has its own JUnit tests. Since Stage 7 `api` compiles against graphql-kotlin, Exposed and `ext-host` (its GraphQL additions), and its tests reuse `core`'s test classes (`testImplementation` of `core`'s test source set output, hence `evaluationDependsOn(":core")`), with their data root in `api/build/tmp/TestDesk` |

### Rebrand (user-visible names only)

| File | Change |
|---|---|
| `AndroidCompat/Config/.../ApplicationRootDir.kt` | `CONFIG_PREFIX = "madoyomi.config"` (so `-Dmadoyomi.config.server.*`); default data dir `%APPDATA%\Madoyomi` on Windows, AppDirs `Madoyomi` elsewhere |
| `AndroidCompat/Config/.../Logging.kt` | log file `logs/madoyomi.log`, appender name; `debugLogsEnabled` reads the prefixed property |
| `core/.../server/ServerSetup.kt` | temp dir `<tmp>/Madoyomi`; startup log line uses `BuildConfig.NAME`. Since the Phase 3 exit run the two id-keyed caches are in the data dir instead (see "Id-keyed caches in the data dir") |
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

Repos and extensions of both kinds share upstream's `EXTENSIONSTORE` and `EXTENSION` tables, told apart by a new `type` column (see "Database migrations" below). Upstream's code is manga-only, so every query of it that reads a *set* of these rows, or acts on a row the client names, now also requires `type = MANGA`. Lookups that reach `EXTENSION` through `SOURCE` (manga-only by construction), inserts (the column defaults to `MANGA`) and `removeExtensionStore`/`ExtensionStoreService.upsert` (keyed by the unique `index_url`) are unchanged. Since Stage 5 `removeExtensionStore` goes through `RepoService` (see "Repo manager" below).

**Since Stage 7 the GraphQL rows of this table are superseded** (see "API (Phase 1 Stage 7)"): the GraphQL API is type-aware, so `extensions`, `extensionStores`, the extension mutations and `fetchExtensions` take a `type` instead of assuming manga, and the two data loaders are upstream's code again. The non-GraphQL rows (`ExtensionsList`, `ExtensionStoreService`, `Extension`, `GetSource`) still apply.

| File | Change |
|---|---|
| `core/.../manga/model/table/ExtensionTable.kt` | new columns `type`, `signatureHash`, `isTorrent`, `isLoaded` |
| `core/.../manga/model/table/ExtensionStoreTable.kt` | new columns `type`, `format`, `lastRefreshedAt`, `lastError`, `signingKeyTrusted` |
| `core/.../manga/impl/extension/ExtensionsList.kt` | `type = MANGA` on `extensionTableAsDataClass` and on the installed set of `updateExtensionDatabase` (which drives its updates, obsolete marking and deletes) |
| `core/.../manga/impl/extension/ExtensionStoreService.kt` | `type = MANGA` on the store sets of `getAndRefresh`, `syncDbToPrefs` and `syncPrefsToDb`, and on the delete in `syncPrefsToDb` (the `extensionStores` setting lists manga stores only) |
| `core/.../manga/impl/extension/Extension.kt` | `type = MANGA` on the `installExtension(pkgName)` and `uninstallExtension` lookups (since Stage 6 both delegate to `ExtensionLifecycle` with `ExtensionKind.MANGA`, see "Extension lifecycle" below) |
| `core/.../manga/impl/util/source/GetSource.kt` | `type = MANGA` on the extension lookup, so the manga loader can never load another kind of jar |
| `core/.../graphql/queries/ExtensionQuery.kt` | `extensions`: `type = MANGA` in the base query's `adjustWhere` (covers nodes, `totalCount` and page info). *Stage 7: removed; `condition: {type}` instead* |
| `core/.../graphql/queries/ExtensionStoreQuery.kt` | `extensionStores`: `type = MANGA` on the base query. *Stage 7: removed; `condition: {type}` instead* |
| `core/.../graphql/dataLoaders/ExtensionDataLoader.kt` | `ExtensionDataLoader` (`extension(pkgName)`): `type = MANGA`. *Stage 7: reverted, the file is upstream's again (`pkg_name` is unique, so both kinds resolve)* |
| `core/.../graphql/dataLoaders/ExtensionStoreDataLoader.kt` | `ExtensionStoreDataLoader` (`extensionStore(indexUrl)`): `type = MANGA`. *Stage 7: reverted, the file is upstream's again (`index_url` is unique)* |
| `core/.../graphql/mutations/ExtensionMutation.kt` | `type = MANGA` on the `updateExtension(s)` lookup and the two `fetchExtensions` payload lists. *Stage 7: replaced by the type-aware version* |

### Repo manager (Phase 1 Stage 5)

The repo manager lives in `madoyomi.core.repo` (see "New Madoyomi code"). Upstream's store code now delegates to it, so both kinds of repo share one parser, one format-detection path and one trust model.

| File | Change |
|---|---|
| `core/.../manga/impl/extension/ExtensionStoreService.kt` | `fetch` and `getExtensions` delegate to `RepoIndexFetcher` (format `AUTO`, or `JSON` for a legacy store's `getExtensions`, which reproduces upstream's "read `index.min.json`, don't follow `index_v2`"). `syncPrefsToDb` adds new URLs through `RepoService.add` (so a repo typed into the config file is trusted like any added repo). The old inline parsing is gone; `getAndRefresh` and `upsert` are unchanged but no longer called by Madoyomi code. |
| `core/.../manga/impl/extension/ExtensionsList.kt` | `fetchExtensions` calls `RepoService.refreshAll(MANGA)` (last-good cache). `updateExtensionDatabase` is `internal` (upstream: `private`), takes `kind` (the set of rows it syncs; new rows get `type = kind`) and `prune` (without it nothing counts as missing), and writes `is_torrent` |
| `core/.../manga/impl/extension/github/NetworkLegacyExtension.kt` | `torrent: Int = 0` (Aniyomi's legacy index; upstream's `ignoreUnknownKeys` dropped it) |
| `core/.../manga/model/dataclass/ExtensionInfo.kt` | `isTorrent: Boolean = false`, carried into `EXTENSION.is_torrent` |
| `core/.../graphql/mutations/ExtensionStoreMutation.kt` | `addExtensionStore` → `RepoService.add(indexUrl, MANGA)`; `removeExtensionStore` → `RepoService.remove(indexUrl)`. Inputs and payloads are unchanged (Stage 7 adds `type`/`format`, see "API") |
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
  - Since Stage 6, installed extensions signed with the trusted key are then loaded (`onTrusted` → `ExtensionLifecycle.reconcileTrust`), e.g. ones installed while the repo was untrusted.
- **Remove:**
  - The FK cascade drops the repo's trust row. `TRUSTED_SIGNATURE.hash` is the primary key, so a key shared with another trusted repo gets its row re-pointed at that repo, and USER trust is never touched.
  - The repo's uninstalled listings are deleted.
  - Installed extensions of the repo (or signed with its key) whose hash is no longer trusted get `is_loaded = false` and are unloaded (`ExtensionUnloader`: sources out of `GetSource`'s cache, class loader closed, load permit revoked). Since Stage 6 the trust gate keeps `GetSource` from reloading them.
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

### Extension lifecycle (Phase 1 Stage 6)

Install, update, uninstall and trust of both kinds live in `madoyomi.core.extension.ExtensionLifecycle` (see "New Madoyomi code"). Upstream's entry points delegate to it, and one trust gate sits in front of the only place extension code is loaded.

| File | Change |
|---|---|
| `core/.../manga/impl/util/PackageTools.kt` | `loadExtensionSourcesWithLockHeld` (the only `Class.forName` + `newInstance` of extension code, reached from `GetSource` and from install) first calls `ExtensionLoadGate.check(jar)`, which throws `UNTRUSTED` unless the jar has a permit whose hash is in `TRUSTED_SIGNATURE` |
| `core/.../manga/impl/util/source/GetSource.kt` | the jar path comes from `ExtensionFiles.installedJar(row)` (Madoyomi layout for verified installs, upstream's flat path otherwise), and the row's `signature_hash` is recorded as the jar's permit before loading. An untrusted or unverified jar is refused, so `getSourceOrNull` returns null (and `SourceType`/`getSourceOrStub` behave as for any failing source). The unused `Path` import went |
| `core/.../manga/impl/extension/Extension.kt` | `installExtension(pkgName, isUpdate)` → `ExtensionLifecycle.install`/`update(pkgName, MANGA)` (upstream: download `jarUrl` or `apkUrl`, then `installExtension(isUpdate) { ExtensionPackage… }`); `installExternalExtension` → `installExternal` (APKs only); `uninstallExtension` is `suspend` (upstream: blocking) and → `uninstall(pkgName, MANGA)`; `updateExtension` no longer requires the in-memory `ExtensionsList.updateMap` entry (the update re-fetches the index), and still clears it. `setupJar`, `extractAssetsFromApk`, `extractAndCacheApkIcon`, `tryToDeleteFile` and `removeDeletionOnStartupRequest` are `internal` (upstream: `private`), for `ExtensionLifecycle`. The generic `installExtension(isExternal, isUpdate, fetchPackage)`, `ExtensionPackage` and the download helpers stay but are no longer called (and their loader is gated) |

How it behaves:
- **Install** (`install(pkg, kind)`):
  - The listing is re-read from the repo the row came from (`RepoIndexFetcher`, the store's stored URL and format): it is the authority for the expected package and version code, and it carries the sources, which the DB doesn't store.
  - Only the APK is downloaded; Keiyoushi's prebuilt `jarUrl` is ignored.
  - `ApkInspector` (ext-host) verifies it with apksig (v1/v2/v3/v3.1/v4; `SIGNATURE_INVALID` otherwise), and its feature, package and version code must match the repo's kind and listing (`PACKAGE_MISMATCH`). An extensions-lib outside `ExtensionKind.libRange` is only a warning; so is a torrent flag that disagrees with the manifest (the manifest wins).
  - `signature_hash` = SHA-256 of the first signer certificate, lowercase hex (the format of a repo's `signingKeyFingerprint`).
  - dex2jar (upstream `PackageTools.dex2jar`, with `BytecodeEditor`), then `Dex2jarRepair` (ext-host, see below), then upstream's asset and icon extraction.
  - Files: `<data>/extensions/{manga|anime}/{pkg}/{pkg}-v{versionName}.jar`. Icons stay in upstream's cache `<data>/extensions/icon/<pkg>.*`, which upstream's icon endpoint serves.
  - **Trusted manga:** loaded and recorded by upstream's `setupJar` (live sources reconcile `SOURCE`), plus `signature_hash` and `is_loaded = true`.
  - **Untrusted extensions (and, in Phase 1, every anime extension):** never instantiated. The row is written the way `setupJar` would, plus `signature_hash`, `is_torrent`, `is_loaded = false`, and the source rows come from the listing (`SOURCE` for manga, `ANIME_SOURCE` for anime). Since Phase 2 a trusted anime extension is loaded (see "Anime sources (Phase 2)").
  - Progress: `ExtensionLifecycle.progress` emits `DOWNLOADING → VERIFYING → CONVERTING → INSTALLED | FAILED` (with the error code), for the Stage 7 subscription.
- **Sideloads** (`installExternal`): APKs only (a jar has no signature: `SIGNATURE_INVALID`); the kind comes from the APK's feature; no listing, so no source rows until trusted.
- **Trust** is `signature_hash ∈ TRUSTED_SIGNATURE` (`SignatureTrust`). `trustSignature`/`trustExtension` add a USER row (or turn the repo's STORE row into USER, so removing the repo no longer revokes it) and load the installed manga extensions signed with it (`reconcileTrust`: `setupJar`, `is_loaded = true`). `untrustSignature` withdraws USER trust only (a key a repo vouches for stays trusted) and unloads what lost trust. Anime was trusted but not loaded in Phase 1; since Phase 2 it is loaded the same way (`AnimeExtensionLoader`).
- **Update** (`update(pkg, kind)`): the repo must list a higher version code (upstream's `hasUpdate`, set by the refresh, is the detection). It re-runs install. If the installed version has a hash and the new APK is signed by another one that isn't trusted, the update is refused with `SIGNATURE_CHANGED` before anything is written, and the installed version stays (loaded, if it was). The old jar is deleted with upstream's delete-on-next-start fallback.
- **Uninstall:** unload (sources out of `GetSource`, `jarLoaderMap.remove(jar)?.close()`, permit revoked), delete the `SOURCE`/`ANIME_SOURCE` rows, upstream's row rule (delete if obsolete or without `apkUrl`, else back to a plain listing) plus `signature_hash = null`, `is_loaded = false`, then the jar (Madoyomi or upstream layout) via `tryToDeleteFile`.
- **Upstream-layout installs** (no `signature_hash`: dev data, anything installed by upstream or before Stage 6) are never loaded. Uninstall and reinstall them, or update them when the repo lists a newer version.
- **dex2jar repair.** Keiyoushi's APKs are R8-optimised in two ways that dex2jar 2.4.38 translates into bytecode the JVM rejects (MangaPill v1.6.1: `VerifyError` in 6 of 30 classes). `Dex2jarRepair` (ext-host) fixes both after dex2jar:
  - *Constructor elision:* some classes lose their no-op constructor, and the dex does `new-instance LT;` + `invoke-direct LS;-><init>` with S an ancestor of T. ART accepts it, the JVM doesn't, and dex2jar makes it `new S`. The pass reads the dex, rewrites those sites back to `new T`/`T.<init>`, and gives T (and any class between T and S) the missing `super`-only constructor. Unmappable sites are skipped and reported as install warnings.
  - *Frames:* dex2jar computes stack map frames without the APK's own class hierarchy, so a merge of two of its classes (an `a` or a `b`, both extending `c`) is typed `java/lang/Object` and a later use as `c` fails. The pass recomputes the frames of every class with a hierarchy read from the jar and the host classpath.
  - The yuzono APKs need no constructor repair; their frames are recomputed too, and every class of both kinds' test jars passes the verifier.
- No migration: every column used here exists since `M0069`/`M0070`.

### API (Phase 1 Stage 7)

The GraphQL API (`/api/graphql`, subscriptions over `graphql-transport-ws` on the same path) is type-aware for both kinds, has the trust and anime-source operations, and every error carries a stable code. The mapping from the PLAN.md §5 REST sketch is step 17 of the Phase 1 doc. Upstream's operations were extended in place; the new ones live in `api` (`madoyomi.api.graphql`) and reach the schema through one hook.

| File | Change |
|---|---|
| `core/.../graphql/server/TachideskGraphQLSchema.kt` | `supportedPackages` + `"madoyomi"`; the query, mutation and subscription lists get `+ MadoyomiSchemaContributions.queries()/mutations()/subscriptions()`, the top-level objects of every `madoyomi.core.graphql.GraphQLSchemaContribution` found with `ServiceLoader` (`api` registers `madoyomi.api.graphql.MadoyomiSchema` in `META-INF/services`) |
| `core/.../graphql/server/TachideskGraphQLServer.kt` | `getGraphQLObject()` is public (upstream: private), so `api`'s tests run the production configuration; it uses `MadoyomiGraphQLErrors.exceptionHandler` (upstream's handler, which appended the stack trace to the message, stays but is unused) and `.instrumentation(MadoyomiGraphQLErrors.instrumentation)` |
| `core/.../server/JavalinSetup.kt` | `defineExceptionHandlers`: every upstream handler keeps its status and logging but writes the `{code, message, details}` JSON body (`RestErrors.body`; upstream: a text body or none); new handlers for `MadoyomiException` (its code and status) and a fallback for `Exception` (`ErrorClassifier`, 500 `INTERNAL` for anything unknown, stack trace in the log only); `sourceFailed` writes `SOURCE_FAILED` |
| `core/src/test/.../server/JavalinExceptionHandlersTest.kt` | expects the JSON error body `{"code":"SOURCE_FAILED","message":"HTTP error 404","details":null}` (upstream: the text `HTTP error 404`) |
| `core/.../graphql/mutations/SourceMutation.kt` | `fetchSourceManga`: `SourceAccess.require(sourceId)` (upstream: `GetSource.getSourceOrNull(sourceId)!!`, a null pointer for a missing or untrusted source) |
| `core/.../graphql/types/ExtensionType.kt` | new fields `type`, `signatureHash`, `isLoaded`, `isTorrent` (from the row) and the resolver `isTrusted()` (`SignatureTrust.isTrusted(signatureHash)`, only queried when asked for) |
| `core/.../graphql/types/ExtensionStoreType.kt` | new fields `type`, `format`, `signingKeyTrusted`, `lastRefreshedAt`, `lastError` |
| `core/.../graphql/queries/ExtensionQuery.kt` | `extensions`: no `type = MANGA` (back to upstream's `adjustWhere`); `ExtensionCondition.type` (omitted: both kinds) |
| `core/.../graphql/queries/ExtensionStoreQuery.kt` | `extensionStores`: no `type = MANGA` (back to upstream's `selectAll()`); `ExtensionStoreCondition.type` |
| `core/.../graphql/dataLoaders/ExtensionDataLoader.kt`, `ExtensionStoreDataLoader.kt` | the Stage 4 `type = MANGA` filters reverted: both files are identical to upstream again |
| `core/.../graphql/mutations/ExtensionStoreMutation.kt` | `AddExtensionStoreInput` gains `type` (null: `MANGA`) and `format` (null: `AUTO`), passed to `RepoService.add`; `removeExtensionStore`'s payload is the store as read before removal (upstream re-builds it field by field) |
| `core/.../graphql/mutations/ExtensionMutation.kt` | both kinds: the `updateExtensions` lookup has no type filter and each action goes to `ExtensionActions` by the row's type (manga → upstream's `Extension.*`, anime → `ExtensionLifecycle`); single-id `updateExtension` goes through `updateOne`, which reports what it can't do (`NOT_FOUND` for an unknown id, the lifecycle's `INSTALL_FAILED` for "already installed", "no update", "not installed") while the batch `updateExtensions` keeps upstream's skip semantics; `FetchExtensionsInput.type` → `RepoService.refreshAll(type)` (omitted: both kinds) and the payload lists that kind |

New operations (`api`, `madoyomi.api.graphql`):
- Queries: `trustedSignatures` (`hash`, `origin`, `addedAt`, `extensionStore`, `extensions`); `animeSources(condition: {id, name, lang, extensionPkgName})` → `AnimeSourceNodeList` of `AnimeSourceType` (`id`, `name`, `lang`, `contentWarning`, `baseUrl`, `extension`).
- Mutations (`INSTALL_EXTENSIONS` permission): `trustExtension(input: {pkgName})` (either kind), `trustSignature(input: {hash})`, `untrustSignature(input: {hash})` (its `isTrusted` says whether a repo still vouches for the key).
- Subscriptions: `extensionInstallProgress(pkgName?)` (`ExtensionLifecycle.progress`: `pkgName`, `type`, `state`, `errorCode`, `message`) and `extensionStoreRefreshed(type?)` (`RepoService.refreshed`, one event per refreshed repo: `indexUrl`, `type`, `ok`, `error`, `extensionCount`, `extensionStore`). Nothing is replayed, so a client subscribes before it starts the operation.

The error model:
- `ErrorCode` (with an HTTP status each): Stage 5/6's codes plus `SOURCE_FAILED` (a source's HTTP error or unreachable host), `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN` and `INTERNAL`.
- `ErrorClassifier` maps any exception, for GraphQL and REST alike: a `MadoyomiException` anywhere in the cause chain (after unwrapping `CompletionException`/`ExecutionException`/`InvocationTargetException`) keeps its code and details; upstream's `UnauthorizedException`/`ForbiddenException`, `HttpException`, unreachable-host `IOException`s and `IllegalArgumentException` get their codes; upstream's `StubSource.SourceNotInstalledException` (what `getSourceOrStub` callers throw) is diagnosed by `SourceAccess` (`NOT_FOUND`, or `UNTRUSTED` with the hash); anything else is `INTERNAL`, with the exception's message (never a stack trace) and the stack trace in the log.
- GraphQL: `errors[].extensions = {code, details}`; graphql-java's own errors (syntax, validation) get `BAD_REQUEST` from the instrumentation.
- REST and binary endpoints: the JSON body `{code, message, details}` (the token filter's 401 included).

### Exit run (Phase 1 Stage 8)

The exit run (`tools/smoke-api.ps1`) found one upstream bug, fixed here (Phase 1 doc §10).

| File | Change |
|---|---|
| `core/.../manga/impl/util/storage/ImageResponse.kt` | `getCachedImageResponse` maps the cached file's extension back to a MIME type (`ImageUtil.ImageType`, then `MimeUtils`, then upstream's `"image/<ext>"`). Upstream returns `"image/<ext>"`, so every cached JPEG page or thumbnail (saved as `.jpg`) was served as the unregistered `image/jpg`, while the first, uncached fetch was `image/jpeg`. Test: `core/src/test/kotlin/madoyomi/core/storage/CachedImageTypeTest.kt` |

### Anime sources (Phase 2)

Trusted anime extensions are loaded since Phase 2 Stage 1, by the same loader and behind the same trust gate as manga.

| File | Change |
|---|---|
| `core/.../manga/impl/util/PackageTools.kt` | `loadExtensionSourcesWithLockHeld` creates the class loader before its `try` and catches `Throwable` (upstream: `Exception`). On a failure it closes a class loader it created but didn't keep in `jarLoaderMap`, and it logs `LinkageError`s as well as exceptions. Upstream leaked the loader whenever `Class.forName` failed, and the leaked loader kept the jar open, which on Windows locks it against uninstall and update. |
| `ext-host/.../manga/impl/util/ChildFirstURLClassLoader.kt` (Stage 2) | `loadClass`: a class found nowhere (system loader, jar, parent) is reported to `madoyomi.exthost.ExtensionClassLoadHook` with the loader's jar before the `ClassNotFoundException` propagates (upstream: rethrown silently). The compat log listens there. |
| `ext-host/.../eu/kanade/tachiyomi/network/Requests.kt` (Stage 2) | the suspend helpers of Aniyomi's extensions-lib 16, `OkHttpClient.get(String\|HttpUrl, headers, cache)` and `OkHttpClient.post(url, headers, body, cache)` (`newCall(GET/POST(…)).awaitSuccess()`, from Aniyomi's `core/common/.../network/Requests.kt`), plus their `OkHttpClient`/`Response` imports. Suwayomi's copy lacks them, and the compat scan found lib-16 anime extensions calling `get$default` (anikoto, kotokai, aniwave) |
| `AndroidCompat/src/main/java/androidx/preference/ListPreference.java` (Stage 2) | `getValue`/`setValue`/`setValueIndex` read and persist the value through the screen's `SharedPreferences` (`getSharedPreferences()` + `getKey()`), falling back to a value kept in the object and then to `defaultValue` (upstream: `"Stub!"`); new `getEntry()` (Android API) |
| `AndroidCompat/.../androidx/preference/MultiSelectListPreference.java` (Stage 2) | `getValues`/`setValues` the same way (`getStringSet`/`putStringSet`, copies), `findIndexOfValue` like `ListPreference`'s (upstream: all three `"Stub!"`) |
| `AndroidCompat/.../androidx/preference/TwoStatePreference.java` (Stage 2) | `isChecked`/`setChecked` the same way (`getBoolean`/`putBoolean`; upstream: `"Stub!"`) |
| `AndroidCompat/.../androidx/preference/Preference.java` (Stage 2) | `setOnPreferenceClickListener` stores the listener (upstream: throws `"Stub!"`); new `getOnPreferenceClickListener()`. Nothing clicks a preference in Madoyomi, so it is never called |
| `AndroidCompat/build.gradle.kts` (Stage 2) | the android-jar (`libs.android.stubs`) goes through `strippedAndroidStubs`, a copy without the classes in `madoyomiAndroidOverrides` (now `android/os/LocaleList.class`), used as `implementation(files(…))` (upstream: `implementation(libs.android.stubs)`). AndroidCompat implements those classes itself, so there is one copy of each on every classpath and in the server jar; upstream's android-jar is already stripped of the classes upstream implements |
| `AndroidCompat/src/main/java/android/os/LocaleList.java` (Stage 2, new file in an Android package) | `android.os.LocaleList` on `java.util.Locale`, android-jar's public API (every Keiyoushi extension of the compat scan calls `getDefault`/`get`/`size`) |
| `core/.../graphql/server/TachideskDataLoaderRegistryFactory.kt` (Stage 4) | the registry also gets `MadoyomiSchemaContributions.dataLoaders()`, the data loaders of Madoyomi's GraphQL types (`api` registers the anime ones), after upstream's list |
| `core/.../graphql/types/ExtensionType.kt` (Stage 4) | new resolver `compatIssues()` (`ExtensionCompatLog.issues(pkgName)` as `madoyomi.core.graphql.ExtensionCompatIssueType`), next to Phase 1's `isTrusted()` |
| `ext-host/.../manga/impl/util/ChildFirstURLClassLoader.kt` (Stage 6) | every loader has a unique name, `madoyomi-extension-<n>` (upstream: unnamed), which stack frames carry (`StackTraceElement.getClassLoaderName`). `ExtensionThreads` uses it to tell which threads run an extension's code: R8's short class names (`a`, `w2`, `v7`) repeat across extensions, so a frame's class name alone matched other extensions' threads |
| `core/.../eu/kanade/tachiyomi/AppModule.kt` (Stage 6) | `Json` is a Koin `factory` (upstream: `single`): every injection gets its own instance. A `Json` keeps a schema per serial descriptor it decoded with (kotlinx-serialization's `DescriptorSchemaCache`, strongly held), and extensions inject it, so the shared instance kept the descriptors (and so the class loaders) of every extension after uninstall. The heap dump of Stage 6 showed it holding 4 of 5 loaders |
| `AndroidCompat/.../io/sharedprefs/JavaSharedPreferences.kt` (Stage 6) | change listeners are held weakly (a synchronized `WeakHashMap`), as Android's `SharedPreferencesImpl` does, and `notify` calls a copy of the listeners. Upstream kept a strong map of listener -> lambda (the lambda capturing the listener), so every listener an extension registered kept its class loader after uninstall: these objects live as long as the process |
| `AndroidCompat/build.gradle.kts` (Stage 6) | the test task sets `madoyomi.config.server.rootDir` to `build/tmp/TestDesk`, as core's does, so the new SharedPreferences test doesn't write to the user's data dir |
| `core/.../manga/impl/Search.kt` (Stage 6) | `filterListCache` is `internal` (upstream: `private`), so `ExtensionUnloader` can drop a manga source's cached filter list on unload; `Source.preferenceScreenMap` (already public) is dropped the same way. Both hold the extension's objects (and so its class loader) for good |
| `core/build.gradle.kts`, `api/build.gradle.kts` (Stage 6) | the tests run with `--add-opens` for `java.base/java.lang` and `java.base/java.util.concurrent` (to `ALL-UNNAMED`), and the server jar's manifest has `Add-Opens: java.base/java.lang java.base/java.util.concurrent` (see "Startup contract"). `ExtensionThreads` reads a thread's `Runnable`, a pool worker's pool and a thread's inherited access-control context |

How it behaves:
- **Load points.** A trusted anime extension is instantiated in three cases:
  - at install or update (`ExtensionLifecycle.record`). The new code is instantiated *before* the old version is unloaded, so a failure keeps the installed version as it was;
  - when its key becomes trusted (`reconcileLocked`);
  - on first use (`AnimeGetSource.getSource`), e.g. a Phase 1 install that was never loaded.
- **What a load does.** The class goes through `PackageTools.loadExtensionSources`/`blockJarUsageWhile`, so `ExtensionLoadGate` applies. The result is one `AnimeSource`, or every source of an `AnimeSourceFactory`. `ANIME_SOURCE` is then reconciled with the live sources (`baseUrl` from `AnimeHttpSource.baseUrl`; ids the code no longer provides are deleted), and `is_loaded = true` is set together with upstream's extension-language rule.
- **Failures.** Anything other than the gate's `UNTRUSTED` becomes `LOAD_FAILED`, with `details.cause` and, for a `LinkageError`, `details.missing`. This covers a missing class, a non-source class, a throwing constructor and a `LinkageError`. At install it fails the install (progress `FAILED`, code `LOAD_FAILED`) and deletes the new jar.
- **Unload.** `ExtensionUnloader` drops an anime extension's sources from `AnimeGetSource` and closes its class loader, as for manga. Since Stage 6 it first stops the threads the extension left running (`ExtensionThreads`), so nothing keeps the loader: an uninstalled extension's classes are unloaded at the next full GC.
- **Diagnosis.** `AnimeSourceAccess` says why a source isn't available: `NOT_FOUND` (with a hint when the id is a manga source), `UNTRUSTED` with the hash, or the loader's `LOAD_FAILED`.
- **Compat log (Stage 2).** `ExtensionCompatLog` (core, in memory, per package, at most 200 distinct issues) collects what an extension needs from the host and doesn't get:
  - at install, the static `ExtensionLinkCheck` of the converted jar (both kinds). Its reachable issues become `InstallResult.warnings` (`link check: 7 Android stubs that throw "Stub!" (…)`); issues used only by an `android.app.Activity` subclass (the deep-link `UrlActivity`, which Madoyomi never starts) are left out. The log of a package starts over when a new version installs, and is dropped at uninstall;
  - at runtime, the extension class loader's misses (`ExtensionClassLoadHook`, `CLASS_NOT_FOUND`; known `Class.forName` probes such as `*BeanInfo`, `*$serializer` and bundled-OkHttp platform checks are dropped), and the `NoClassDefFoundError`, `NoSuchMethodError`/`NoSuchFieldError`, `AbstractMethodError` and `RuntimeException("Stub!")` that reach the host (`ExtensionIncompatibility`).
  - Every source call goes through `AnimeSourceCalls` (from Stage 3), which records such an error and throws `EXTENSION_INCOMPATIBLE` (501) with `details.pkgName`, `sourceId`, `operation`, `kind`, `missing`. The loader records them too (the error stays `LOAD_FAILED`), and `ErrorClassifier` maps one that reaches GraphQL or REST another way (upstream's manga code) to `EXTENSION_INCOMPATIBLE` without a package.
  - Each distinct runtime issue is logged once as `[compat] <pkg>: <kind> <target> (<operation>)`. The core API is `ExtensionCompatLog.issues(pkg)`/`all()`/`staticReport(pkg)`; GraphQL has it as `extension.compatIssues` (Stage 4).
- **Services (Stage 3).** New Madoyomi code only, in `madoyomi.core.anime`; no upstream file changed. Every source call goes through `AnimeSourceCalls`. GraphQL wraps them since Stage 4.
  - `AnimeBrowse.fetch(sourceId, POPULAR|LATEST|SEARCH, page, query, filters)` upserts the page into `ANIME` by `(source, url)` (`AnimeEntries.upsert`, upstream's `MangaList.insertOrUpdate` plus a dedupe and a retry on a unique-key race) and returns the ids.
  - `AnimeDetails.fetch(animeId, fetchDetails, fetchChildren)` writes the details. It then reconciles `EPISODE` (upstream's chapter reconcile: by url, `source_order` oldest = 1, watch state kept and carried over by episode number, an empty list changes nothing) or, for `fetch_type = Seasons`, links the season rows (`parent_id`, `season_source_order`).
  - `AnimeVideos.fetchEpisodeVideos(episodeId)`, `fetchHosterVideos` and `resolveVideo` follow the Aniyomi app's `EpisodeLoader`/`HosterLoader`. Nothing is persisted; the last fetch per episode is kept in memory for `resolveVideo`.
  - `AnimeFilters` and `AnimeSourcePreferences` map to upstream's GraphQL `Filter`/`FilterChange` and `Preference`/`SourcePreferenceChange` (`graphql/types/SourceType.kt`, `graphql/mutations/SourceMutation.kt`), used as they are. A preference change runs the listener first (Android's order), saves to the source's `SharedPreferences` and drops the cached source.
  - The behaviour and the deviations from upstream are in the Phase 2 doc, §9 "Stage 3".
- **API (Stage 4).** Madoyomi's GraphQL in `api` wraps the services, in the shapes of upstream's manga operations. The mapping from the PLAN.md §5 sketch is step 16 of the Phase 2 doc, and the decisions are in its §9 "Stage 4".
  - Queries: `anime(id)`, `animes(condition, filter, order, paging)`, `episodes(…)`, and on `AnimeSourceType` `iconUrl`, `supportsLatest`/`isConfigurable` (null while the source can't be used), `filters`, `preferences`, `anime`.
  - Mutations: `fetchSourceAnime`, `fetchAnimeAndEpisodes` (data plus the source's error), `fetchEpisodeVideos`, `fetchHosterVideos`, `resolveEpisodeVideo`, and `updateAnimeSourcePreference` (`MANAGE_SOURCE_PREFERENCES`). Phase 3 Stage 5 added `updateAnime`/`updateAnimes` (see "Library flag (Phase 3)" below).
  - `ExtensionType.compatIssues` (the upstream edit above).
  - The types `AnimeType`/`EpisodeType` (upstream's `MangaType`/`ChapterType` shapes) resolve their relations through data loaders registered with the hook above.
  - REST `GET /api/v1/anime/{animeId}/thumbnail` (`madoyomi.api.AnimeHttp`, registered in `Main`'s `javalinSetup` hook): `AnimeThumbnails`, upstream's manga thumbnail flow for anime (the source's client and headers, a details refresh when the URL is gone, upstream's thumbnail cache as `anime-<id>.<ext>`).
- **Compat harness fixes (Stage 5).** Madoyomi code only; no upstream file changed. The findings are in the Phase 2 doc §10.
  - `AnimeSourceCalls.call` runs every suspend source call on its own dispatcher: up to 64 daemon threads with 128 MB stacks (`anime-source-N`). `java.util.regex` recurses once per repetition of a group, but Android's ICU regex doesn't, so anizone's `(?:[^'\\]|\\.)*` over a 41K-character page overflowed a default 1 MB stack. The blocking variant (filters, preferences, headers) still runs on the caller's thread.
  - `ErrorClassifier` maps upstream `CloudflareInterceptor`'s `IOException("Cloudflare bypass currently disabled")`, and its private `CloudflareBypassException`, anywhere in the cause chain, to `SOURCE_FAILED` with `details.reason = "CLOUDFLARE"`. It was `INTERNAL` before. The message is matched as a constant (`ErrorClassifier.CLOUDFLARE_BLOCKED`), so the upstream file stays untouched.
  - When `AnimeGetSource` drops sources (uninstall, update, preference change, User-Agent change), `AnimeVideos.dropStale()` now forgets the last fetches of the dropped instances. A kept fetch held its source, and so the uninstalled extension's class loader.
- **Fix round (Stage 6).** The class loaders kept after uninstall, found with a heap dump (`tools/compat-test/lib/HprofLoaderRoots.java`; Phase 2 doc §10), and the two R8 patterns of the manga sample.
  - `ExtensionThreads` (core): on unload, before the class loader is closed, `ExtensionUnloader` stops the threads the extension left running. A thread belongs to it when any of these comes from the extension's loader: its class, its `Runnable`, its context class loader, one of its stack frames (by the loader's name), or, for a JDK pool worker, the pool's thread factory.
    - Such a pool is shut down (`shutdownNow`). Every other thread is interrupted. One still alive after 500 ms gets the sockets its `Runnable` holds closed (server and client sockets, found through the extension's own objects), as its own `stop()` would do. Nothing is forced, and a thread that keeps running is logged.
    - missav, onetwothreeanime and reanime each bundle an R8-renamed NanoHTTPD whose listener thread blocks in `accept()`, which an interrupt doesn't end. streamingcommunity's own pool (`streamingunity-subs`) idles in `take()`, which an interrupt doesn't end either.
    - Every live thread also loses its inherited access-control context, when no SecurityManager is installed (JDK 21 installs none unless asked). A thread keeps the context of the stack that created it, so an OkHttp or coroutine worker started inside a source call kept that extension's protection domain, and so its class loader.
    - Reading those fields needs `java.lang` and `java.util.concurrent` open, which the manifest's `Add-Opens` does. Without it the stop is interrupt-only.
  - `AnimeSourceCalls`' pool threads are created inside `AccessController.doPrivileged`, with the host's context class loader. A new thread inherits the access-control context of the stack that creates it. The pool grows inside source calls, so a pool thread kept the protection domain, and so the class loader, of the extension that was running then. The threads it started in turn inherited it (kickassanime was kept by a reanime server thread that a pool thread had started).
  - `AnimeGetSource.register` takes the `unloadCount` a lazy load saw before it started. If an uninstall or update unloaded the extension meanwhile, nothing is registered, and `AnimeExtensionLoader.loadInstalled` drops the class loader it created, then fails with `NOT_FOUND`. Install and trust loads run under the lifecycle lock and pass no count.
  - `AnimeVideos.fetchEpisodeVideos` keeps a fetch only while its source is still the live instance (an unload during the call has already run `dropStale`).
  - `ExtensionUnloader` drops a manga source's `Source.preferenceScreenMap` and `Search.filterListCache` entries.
  - `ExtensionUnloader` evicts the shared OkHttp pool's idle connections: a pooled HTTP/2 connection's TLS context captured the handshake's access-control context, for up to the 5-minute keep-alive.
  - Injekt's `Json` is a factory (the `AppModule` edit above): the shared instance's schema cache held every extension's serial descriptors.
  - Result (Phase 2 doc §10): after uninstalling the top 20, 1 extension class loader is alive (15 before), held by a connection reanime itself keeps open.
  - `Dex2jarRepair`:
    - a `new T` that dex2jar left unfolded before an `invokespecial S.<init>` (mangafire, comix, mangakakalot) gets its call rewritten to `T.<init>`;
    - a constructor whose super call skips its direct superclass `U` (R8 removed `U`'s no-op constructor; mangadex's 8 serialization DTOs) calls `U.<init>`, and `U` gets the constructor back.
    - The compat scan's manga sample verifies with 0 errors.

### Library flag (Phase 3)

Madoyomi code only; no upstream file changed. The UI's details page and Library (Phase 3 doc §9 "Stage 5") need to add an anime to the library, which only upstream's manga side could do (`updateManga`).
- `updateAnime(input: {id, patch: {inLibrary}})` → `{clientMutationId, anime}` and `updateAnimes(input: {ids, patch})` → `{clientMutationId, animes}` (in the given order), in `madoyomi.api.graphql.AnimeMutation`, in the shape of upstream's `updateManga`/`updateMangas` (`@RequireAuth`, as upstream's).
- How it behaves:
  - `inLibrary: true` sets `ANIME.in_library` and `added_at` (`inLibraryAt`) to now. `false` clears the flag and keeps `added_at`, as upstream keeps `inLibraryAt`.
  - Every id must exist: an unknown one is `NOT_FOUND` (`AnimeEntries.animeRow`), and nothing is written. Upstream's `.first()` would be `INTERNAL`.
  - Not ported:
    - upstream's queueing of uninitialized entries for a library update. Anime library updates are Phase 6, and the UI refreshes an uninitialized entry when it is opened;
    - `Library.handleMangaThumbnail`. Anime thumbnails stay in the thumbnail cache, a Phase 6 point (downloads).
- A browse already leaves an in-library row as it is (`AnimeEntries.upsert`); `AnimeLibraryApiTest` checks that too.

### Id-keyed caches in the data dir (Phase 3 exit run)

Found in the Phase 3 exit run (Phase 3 doc §10, "Stage 7"). The decision was the user's.

| File | Change |
|---|---|
| `core/.../server/ServerSetup.kt` (`ApplicationDirs`) | `tempThumbnailCacheRoot` is `<dataRoot>/cache/thumbnails` and `tempMangaCacheRoot` is `<dataRoot>/cache/manga`. Upstream has `<tmp>/Madoyomi/thumbnails` and `<tmp>/Madoyomi/manga-cache`. |

Why:
- Both caches name their files by database id: `<mangaId>.<ext>`, Madoyomi's `anime-<id>.<ext>`, and chapter page dirs by manga and chapter id.
- In the shared temp dir, a fresh data dir served the covers an older data dir had cached for the same ids. The exit run's anime #1 showed another database's One Piece cover.
- A user would hit this after resetting the data dir, after a reinstall, or with two data dirs.
- `<dataRoot>/cache` is upstream's existing `cacheDir`. Upstream's WebView-data mutation (`WebviewMutation`, `deleteRecursively`) clears that dir, and these caches with it. That is harmless for caches, and the UI doesn't call it.

Not changed:
- Everything else in the temp dir stays where it is: extension downloads in random dirs, `webUI-serve` (off), and temporary uploads. None of it is keyed by a database id.
- Old files in `%TEMP%\Madoyomi\thumbnails` and `manga-cache` are left alone; Windows' temp cleanup may remove them.

Tested in `core/src/test/kotlin/madoyomi/core/storage/IdKeyedCacheDirsTest.kt`.

### Playback (Phase 4)

Madoyomi code only; no upstream file changed. The backend owns mpv (Phase 4 doc §9 "Stage 1"; the contract with mpv is [player/IPC.md](../player/IPC.md)).
- `madoyomi.core.player` (core):
  - `MpvLocator` finds `mpv.exe`: an explicit path (the player settings, Stage 5), the system property `madoyomi.player.mpv`, the env `MADOYOMI_MPV`, then `PATH`. A missing one is `PLAYER_NOT_FOUND`;
  - `PlayerDirs` gives mpv's config dir: `MADOYOMI_PLAYER_DIR` if it is a directory, else `<data dir>/player`;
  - `MpvCommand` builds the command line. The source's values (headers in Phase 0 S4's forms, the title, subtitle and audio URLs, `Video.mpvArgs` minus the reserved options) go into a per-session `--include` file in mpv's fixed-length quoting, not onto the command line, because Java doesn't escape a `"` inside a Windows argument;
  - `PlaybackSessions` runs one mpv at a time. `play` resolves the video through `AnimeVideos.resolveVideo` and replaces the running session; `stop`, `current`. mpv's output goes to `<data dir>/logs/mpv.log`, and it gets the environment without `MADOYOMI_TOKEN`. A shutdown hook stops mpv. Each session writes `<data dir>/cache/player/<id>.session` (pid, executable, start time), and `reapOrphans` (called once from `Main`) ends a live process matching all three, then clears the dir.
- `ErrorCode`: `PLAYER_NOT_FOUND` (503) and `PLAYER_FAILED` (500).
- GraphQL (`madoyomi.api.graphql.PlaybackGraphQL`):
  - `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})` → `{clientMutationId, session}`;
  - `stopPlayback(input: {})` → `{clientMutationId, session}` (null if nothing played);
  - `playbackSession` (the running session, else the last one);
  - `PlaybackSessionType`: `id`, `episodeId`, `animeId`, `title`, `videoTitle`, `state` (`PlaybackState`: `RUNNING`, `EXITED`, `STOPPED`), `startedAt`, `endedAt`, `exitCode`.
- Tests: `core/src/test/kotlin/madoyomi/core/player/{MpvCommandTest,MpvLocatorTest}.kt`; `api/src/test/kotlin/madoyomi/api/PlaybackApiTest.kt`, with a fake mpv in `api/src/test/java/madoyomi/api/FakeMpv.java` (Java with no dependencies, so its launch stays short).

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
| `core/src/main/kotlin/madoyomi/core/anime/model/table/*.kt` | `AnimeSourceTable`, `AnimeTable`, `EpisodeTable`, `AnimeHistoryTable`, `AnimeCategoryTable` (Stage 3: the M0071 columns and unique indexes) |
| `core/src/main/kotlin/madoyomi/core/database/MadoyomiMigration.kt` | marker interface for Madoyomi migrations (see "Database migrations") |
| `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0069_MadoyomiExtensionKinds.kt`, `M0070_AnimeTables.kt`, `M0071_AnimeBrowse.kt` | Madoyomi migrations. New files, but in upstream's migration package, because the runner only scans that package. |
| `core/src/test/kotlin/madoyomi/core/database/MigrationsFromEmptyDbTest.kt` | the migration chain on empty and pre-Madoyomi H2 databases, re-runs, schema and Exposed-table checks |
| `core/src/main/kotlin/madoyomi/core/repo/ExtensionListing.kt`, `RepoStoreMeta.kt` | the common listing model (`ExtensionListing`, `ListingSource`), repo metadata, `FetchedRepo`, signing-key normalisation, the pkg/kind check |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexParser.kt` | `RepoIndexParser` + `ParsedIndex` (v2 store, legacy `repo.json`, legacy `index.min.json`), gzip sniff (64 MiB cap) |
| `core/src/main/kotlin/madoyomi/core/repo/JsonIndexParser.kt`, `ProtobufIndexParser.kt` | the two parsers over upstream's DTOs |
| `core/src/main/kotlin/madoyomi/core/repo/AnimeNetworkExtensionStore.kt` | Aniyomi's `index.pb` schema (reverse-engineered, see the Phase 1 doc §10) |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexFetcher.kt` | format detection/override and redirects over a `RepoHttp` (the default uses `NetworkHelper`'s client) |
| `core/src/main/kotlin/madoyomi/core/repo/RepoService.kt` | repo add/remove/refresh, trust and the last-good cache (see "Repo manager" above) |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionUnloader.kt` | drops a loaded extension's sources and class loader (and, since Stage 6, its load permit; jar path from `ExtensionFiles`). Since Phase 2 Stage 6 it also stops the extension's threads (`ExtensionThreads`), evicts the shared OkHttp pool's idle connections, and drops a manga source's cached preference screen and filter list |
| `core/src/main/kotlin/madoyomi/core/error/MadoyomiException.kt` | `MadoyomiException(code, message, details)` + `ErrorCode`. Stage 6 added `SIGNATURE_INVALID`, `SIGNATURE_CHANGED`, `UNTRUSTED`, `PACKAGE_MISMATCH`, `INSTALL_FAILED`; Stage 7 an HTTP status per code and `SOURCE_FAILED`, `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL` (see "API") |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionKind.kt` (Stage 6 additions) | per kind: `feature`, `classKey`, `nsfwKey`, `torrentKey`, `labelPrefix`, `libRange` (manga 1.3–1.6, anime 12–16), `dirName`; shared `NAME_KEY`/`LIB_KEY`/`CONTENT_WARNING_KEY`; `fromFeatures` |
| `ext-host/src/main/kotlin/madoyomi/exthost/ApkInspector.kt` | manifest (apk-parser, typed values such as Keiyoushi's float `extensionLib` decoded) + apksig verification and signer SHA-256 |
| `ext-host/src/main/kotlin/madoyomi/exthost/Dex2jarRepair.kt` | the dex2jar post-pass: R8 constructor elision and frames recomputed with the APK's class hierarchy (see "Extension lifecycle") |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionLifecycle.kt`, `ExtensionInstallProgress.kt` | install/update/sideload/uninstall/trust, the progress flow and `InstallResult` |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionFiles.kt` | the jar layout (Madoyomi and upstream) and the icon dir |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionLoadGate.kt` | the trust gate: per-jar permits (the verified hash) checked against `TRUSTED_SIGNATURE` at load time |
| `core/src/main/kotlin/madoyomi/core/extension/SignatureTrust.kt` | `TRUSTED_SIGNATURE` reads, USER trust/untrust, and the shared-key re-homing (moved from `RepoService`) |
| `core/src/main/kotlin/madoyomi/core/error/ErrorClassifier.kt`, `RestErrors.kt` | the error model shared by GraphQL and REST (see "API") |
| `core/src/main/kotlin/madoyomi/core/graphql/GraphQLSchemaContribution.kt` | the schema hook: `GraphQLSchemaContribution` + `MadoyomiSchemaContributions` (ServiceLoader; Koin would start too late, after schema generation) |
| `core/src/main/kotlin/madoyomi/core/graphql/MadoyomiGraphQLErrors.kt` | the GraphQL `DataFetcherExceptionHandler` and the instrumentation that gives graphql-java's own errors a code |
| `core/src/main/kotlin/madoyomi/core/extension/SourceAccess.kt` | why a manga source can't be used (`NOT_FOUND`, `UNTRUSTED`, `INTERNAL`), which upstream's `GetSource.getSourceOrNull` swallows |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionActions.kt` | install/update/uninstall by kind for the GraphQL mutations |
| `core/.../madoyomi/core/repo/RepoService.kt`, `extension/ExtensionLifecycle.kt` (Stage 7 additions) | `RepoService.refreshed` (one `RepoRefreshResult` per refreshed repo, for `extensionStoreRefreshed`); `refreshedSubscribers`/`progressSubscribers` (subscriber counts, so tests can wait for a subscription); `ExtensionLoadGate.clear()` is public for `api`'s tests |
| `api/src/main/kotlin/madoyomi/api/graphql/*`, `api/src/main/resources/META-INF/services/madoyomi.core.graphql.GraphQLSchemaContribution` | Madoyomi's GraphQL operations and types (see "API") and their registration |
| `api/src/test/kotlin/madoyomi/api/*` | `ApiGraphQLTest` (the production `GraphQL` object, a scratch H2 per test, a loopback `HttpServer` serving `core`'s `TestRepo` fixtures and the real APKs to the production network stack), `ExtensionApiIntegrationTest` (repos, install, trust, update, uninstall and both subscriptions for both kinds), `ErrorModelTest`, `SidecarHttpTest` (token gate, `/health`, WebSocket upgrade, REST error bodies on a bare Javalin) |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeGetSource.kt`, `AnimeExtensionLoader.kt`, `AnimeSourceAccess.kt` (Phase 2) | the anime source cache (registered with `SourceRegistry`), instantiation and `ANIME_SOURCE` reconcile, and why a source can't be used (see "Anime sources") |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionLinkCheck.kt` (Phase 2) | static link check of an extension jar: every class, method and field it uses from outside itself is resolved over the host's class files (read as bytes, nothing loaded), reporting missing classes, missing members and android-jar `"Stub!"` bodies. Tests: `core/src/test/kotlin/madoyomi/core/extension/ExtensionLinkCheckTest.kt` |
| `core/.../madoyomi/core/error/MadoyomiException.kt` (Phase 2 addition) | `ErrorCode.LOAD_FAILED` (500); Stage 2: `EXTENSION_INCOMPATIBLE` (501) |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionCompatLog.kt`, `ExtensionCompat.kt` (Phase 2 Stage 2) | the compat log and `ExtensionIncompatibility` (which `Throwable`s mean an API gap); the install-time link check and its warnings (see "Anime sources") |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeSourceCalls.kt` (Phase 2 Stage 2) | the wrapper around every anime source call: API gaps become `EXTENSION_INCOMPATIBLE` and go into the compat log |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionClassLoadHook.kt` (Phase 2 Stage 2) | the callback `ChildFirstURLClassLoader` reports classes found nowhere to (`core`'s compat log registers itself) |
| `ext-host/.../madoyomi/exthost/ExtensionLinkCheck.kt` (Stage 2 addition) | `Report.activityClasses` (the jar's `android.app.Activity` subclasses), `onlyFromActivities(issue)` and `reachable` |
| `tools/compat-test/select-top.ps1`, `scan.ps1`, `lib/Protobuf.ps1`, `top20.txt`, `manga-sample.txt`; `core/src/test/kotlin/madoyomi/core/extension/CompatScanTest.kt`; `docs/research/compat-scan.md` (Phase 2 Stage 2) | the top-20/manga-sample selection (jsDelivr hits; GitHub release-asset downloads for Keiyoushi; a PowerShell `index.pb` reader), the static scan over them (`MADOYOMI_COMPAT_SCAN=1`) and its generated report. APKs go to the gitignored `tools/compat-test/apks/` |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeEntries.kt`, `AnimeBrowse.kt`, `AnimeDetails.kt`, `AnimeVideos.kt`, `AnimeFilters.kt`, `AnimeSourcePreferences.kt` (Phase 2 Stage 3) | the anime services: row ↔ model mapping and the browse upsert, browse, details with episodes or seasons, hosters and videos, filter and preference mapping (see "Anime sources") |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeThumbnails.kt`, `core/src/main/kotlin/madoyomi/core/graphql/ExtensionCompatIssueType.kt` (Phase 2 Stage 4) | anime thumbnails (fetch, refresh, cache), and the GraphQL shape of a compat issue. Stage 4 also added `GraphQLSchemaContribution.dataLoaders`, `MadoyomiGraphQLErrors.errorOf` (a classified error for partial results), the GraphQL names of `ExtensionCompatLog`'s enums, and the thumbnail cache drop in `AnimeEntries.writeDetails` |
| `api/src/main/kotlin/madoyomi/api/AnimeHttp.kt`, `api/src/main/kotlin/madoyomi/api/graphql/{AnimeTypes,AnimeVideoTypes,AnimeQuery,EpisodeQuery,AnimeMutation,AnimeDataLoaders}.kt` (Phase 2 Stage 4) | the anime REST route and GraphQL operations, types and data loaders (see "Anime sources"). `api` now depends on `ext-anime` |
| `api/src/test/kotlin/madoyomi/api/Anime*Test.kt` (Phase 2 Stage 4) | `AnimeApiTest` (fake sources with installed `EXTENSION`/`ANIME_SOURCE` rows), `AnimeBrowseApiTest`, `AnimeDetailsApiTest`, `AnimeVideosApiTest`, `AnimeExtensionApiTest` (AnimePahe installed from the loopback repo: preferences, compat issues; kickassanime's filters), `AnimeThumbnailTest` (a bare Javalin and a loopback image host) |
| `tools/compat-test/run-compat.ps1`, `tools/compat-test/triage.txt`, `docs/research/anime-compat.md` (Phase 2 Stage 5) | the compat harness: the top 20 on the real sidecar jar (install → popular → details + episodes → videos → resolve), process health per extension (`jcmd`/`jstat`, JVM aborts, uninstall leak check), the hand-written triage, and the generated report. Raw results and server logs go to the gitignored `tools/compat-test/out/` |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionThreads.kt` (Phase 2 Stage 6) | stops the threads an extension left running when it is unloaded: shuts down its own thread pools, interrupts the rest, closes the sockets of one still blocked, and logs what keeps running. It also clears every live thread's inherited access-control context (see "Anime sources", "Fix round") |
| `tools/compat-test/lib/HprofLoaderRoots.java`, `run-compat.ps1` additions (Phase 2 Stage 6) | a dependency-free HPROF reader (JDK 21 single-file program) that prints the shortest strong GC-root path to each live `ChildFirstURLClassLoader`. The harness takes a heap dump when extension loaders are still alive after the uninstall and an idle wait (`-SettleSec`, `-NoHeapDump`), and puts the nearest holder of each in the report. The harness also gained a preference round-trip and a filtered search per extension, and `-Kind Manga` (the Keiyoushi sample through upstream's manga API into `docs/research/manga-compat.md`) |
| `core/src/test/kotlin/madoyomi/core/extension/{ExtensionThreadsTest,Dex2jarRepairCompatTest,GcAssert}.kt`, `core/src/test/kotlin/madoyomi/core/anime/ParsedAnimeHttpSourceTest.kt`, `api/src/test/kotlin/madoyomi/api/AnimeRoundTripApiTest.kt`, `AndroidCompat/src/test/kotlin/.../JavaSharedPreferencesTest.kt` (Phase 2 Stage 6) | Stage 6's tests. `ParsedAnimeHttpSourceTest` runs a Jsoup-selector source (no top-20 extension extends `ParsedAnimeHttpSource`) through browse, details and videos against a loopback site. `ExtensionThreadsTest` compiles "extension" classes at test time with `javax.tools` and loads them through a `ChildFirstURLClassLoader`. `Dex2jarRepairCompatTest` covers the four manga APKs. `AnimeRoundTripApiTest` round-trips filters and preferences of real extensions through GraphQL, offline. Leak tests are in `ExtensionLifecycleTest` (an uninstalled anime and manga extension's class loader is collected), `AnimeVideosTest` and `AnimeExtensionLoaderTest` |
| `tools/lib/MadoyomiServer.ps1` (Phase 2 Stage 4) | the PowerShell helpers that start the jar as the Tauri shell does and drive its API (steps, GraphQL, WebSocket, install with progress), used by `tools/smoke-api.ps1` |
| `api/src/main/kotlin/madoyomi/api/graphql/AnimeMutation.kt` (Phase 3 Stage 5 additions), `api/src/test/kotlin/madoyomi/api/AnimeLibraryApiTest.kt` | `updateAnime`/`updateAnimes`, the library flag for anime (see "Library flag (Phase 3)"), and their tests: add, remove, the batch, an unknown id, and a browse leaving an in-library entry as it is |
| `core/src/main/kotlin/madoyomi/core/player/{MpvLocator,PlayerDirs,MpvCommand,PlaybackSessions}.kt`, `api/src/main/kotlin/madoyomi/api/graphql/PlaybackGraphQL.kt` (Phase 4 Stage 1) | the mpv player: finding mpv and its config dir, the command line and session config file, one playback session at a time with orphan handling, and its GraphQL (see "Playback (Phase 4)"). `Main` calls `PlaybackSessions.reapOrphans()` after `applicationSetup()` |
| `core/src/test/kotlin/madoyomi/core/player/*`, `api/src/test/kotlin/madoyomi/api/PlaybackApiTest.kt`, `api/src/test/java/madoyomi/api/FakeMpv.java` (Phase 4 Stage 1) | the player's tests (see "Playback (Phase 4)") |
| `core/src/test/kotlin/madoyomi/core/storage/IdKeyedCacheDirsTest.kt` (Phase 3 exit run) | the thumbnail and chapter-page caches are in the data dir, and two data dirs share neither (see "Id-keyed caches in the data dir") |
| `core/src/test/kotlin/madoyomi/core/anime/*` (Stage 3 additions) | `AnimeBrowseTest`, `AnimeDetailsTest`, `AnimeVideosTest` (scripted fake sources and a scratch H2 per class, `AnimeTestSources.kt`), `AnimeFiltersTest` (every filter of kickassanime, aniwave and hanime from `tools/compat-test/apks/`), `AnimeSourcePreferencesTest` (AnimePahe), and `AnimeServicesLiveTest` (real sites, only with `MADOYOMI_LIVE_SOURCE_TESTS=1`). Tests skip when their APK is missing. Stage 3 also made every madoyomi scratch-database test set its database as the explicit default and restore the previous explicit one (an order dependency, Phase 2 doc §10) |
| `ext-host/src/test/kotlin/madoyomi/exthost/*`, `core/src/test/kotlin/madoyomi/core/extension/*` | `ApkInspectorTest`, `Dex2jarRepairTest`, `ExtensionLifecycleTest`, `SignatureTrustTest` on the real APKs in the gitignored `tools/apks/` (fetched by `tools/fetch-test-apks.ps1`; tests skip without them). `ExtensionLifecycleLiveTest` installs from the real repos and only runs with `MADOYOMI_LIVE_REPO_TESTS=1` |
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
| `M0071_AnimeBrowse` (Phase 2 Stage 3) | `ANIME`: `BACKGROUND_URL` (nullable), `UPDATE_STRATEGY` (`ALWAYS_UPDATE`/`ONLY_FETCH_ONCE`, default `ALWAYS_UPDATE`), `FETCH_TYPE` (`Episodes`/`Seasons`, default `Episodes`), `SEASON_NUMBER` (default -1), `MEMO` (JSON text, default `{}`), `PARENT_ID` (nullable FK → `ANIME`, `ON DELETE SET NULL`, declared inline in its `ADD COLUMN IF NOT EXISTS`), `SEASON_SOURCE_ORDER` (default 0). `EPISODE`: `FILLERMARK` (default false), `SUMMARY`, `PREVIEW_URL` (nullable), `MEMO` (default `{}`). Then duplicates are deleted (lowest id kept, as upstream's M0039/M0062) and `CREATE UNIQUE INDEX IF NOT EXISTS` adds `UX_ANIME_SOURCE_URL` on `ANIME (SOURCE, URL)` and `UX_EPISODE_URL_ANIME` on `EPISODE (URL, ANIME)` (portable and idempotent; `ADD CONSTRAINT IF NOT EXISTS` is H2-only) |

Sources are typed by table (manga in `SOURCE`, anime in `ANIME_SOURCE`), and repos and extensions by the `type` column. The reasoning is in the Phase 1 doc (`docs/phases/phase-1-backend-foundation.md`, step 6).

### Numbering collisions on an upstream sync

When the upstream diff adds migrations of its own, say `k` of them, numbered `M0069`…`M00(68+k)`:
1. Upstream's new migrations keep their numbers.
2. Rename ours up by `k`, keeping their order and names (`git mv M0069_MadoyomiExtensionKinds.kt M00(69+k)_MadoyomiExtensionKinds.kt` and the class name, and so on). Update the numbers in this file, in `MigrationsFromEmptyDbTest` (`FIRST_MADOYOMI_VERSION`) and in the "Upgrading a pre-Madoyomi database" test.
3. Databases that already ran ours have them recorded at the old numbers. The runner would treat upstream's new migrations as already done and skip them. So the first sync that renumbers must also add a reconcile step to `DBManager.databaseUp`, just before `runMigrations` (a `// Madoyomi:` edit). It goes through `MIGRATIONS` in version order and finds the first row whose `NAME` is the name of a `MadoyomiMigration` but whose `VERSION` no longer matches that migration's number. It deletes that row and every row after it. The runner then applies upstream's new migrations and re-runs ours, which is safe because they are idempotent. Because it only matches Madoyomi migration names, upstream rows are never re-run.
4. Until Madoyomi has users with real data, renumbering and deleting the dev data dirs (`tools/dev-data`) is enough. Step 3 can wait until it is needed.

### Startup contract (what the Tauri shell relies on)

- `java -jar api/build/Madoyomi-Server-v0.1.0.jar [--data-dir <path>]` (built by `.\gradlew.bat :api:shadowJar`). Unknown arguments exit with code 2.
- The jar's manifest has `Add-Opens: java.base/java.lang java.base/java.util.concurrent`, which `java -jar` applies (Phase 2 Stage 6: uninstall stops the threads an extension left running). A launch that doesn't use `-jar` (a jlink launcher, `-cp`) must pass `--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED` itself. Without them, uninstall can only interrupt those threads, and a local server blocked in `accept()` keeps the extension's classes until the process ends.
- Env `MADOYOMI_TOKEN`: the API token. If unset, a random one is used and the API is effectively locked.
- Env `MADOYOMI_MPV` (optional, Phase 4): the `mpv.exe` to play with; without it the backend uses `mpv.exe` from `PATH`. Env `MADOYOMI_PLAYER_DIR` (optional): mpv's config dir; without it `<data dir>/player`. mpv is started as the JVM's child, without `MADOYOMI_TOKEN` in its environment, and at startup the backend ends an mpv that an earlier (crashed) backend on the same data dir left running.
- Binds `127.0.0.1` on a random free port and prints exactly one line `MADOYOMI_PORT=<port>` on stdout once listening (other stdout lines are logs).
- Every request needs `Authorization: Bearer <token>`, except `GET /health` and CORS preflights. `GET` requests and WebSocket upgrades may pass `?token=<token>` instead. A rejected request gets `401 {"code":"UNAUTHORIZED","message":…,"details":null}`.
- Binary REST routes: upstream's (`/api/v1/manga/{id}/thumbnail`, pages, extension icons) and `GET /api/v1/anime/{animeId}/thumbnail` (Phase 2).
- The API is GraphQL at `POST /api/graphql`; subscriptions use `graphql-transport-ws` on `ws://127.0.0.1:<port>/api/graphql?token=<token>` (the server negotiates no subprotocol, so a client must not require one to be echoed). GraphQL errors carry `extensions: {code, details}`; REST/binary errors are the body `{code, message, details}` (see "API (Phase 1 Stage 7)").
- Upstream `authMode` is forced to `NONE` (every request is the built-in admin), so the token is the only gate.
- `GET /health` → `{"status":"ok","name","version","pid","uptimeMs"}`.
- CORS: `http://localhost:1420`, `http://tauri.localhost`, `https://tauri.localhost`.
- Exits (shutdown hooks run) when stdin closes.

`tools/run-server.ps1` builds the jar and runs it this way with a dev data dir (`tools/dev-data/`).
