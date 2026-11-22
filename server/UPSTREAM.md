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
| `.github/` | Upstream CI and issue templates. Madoyomi has its own. |
| `renovate.json`, `CHANGELOG-TEMPLATE.md` | Upstream project tooling |

Binary assets that are **in the tree but not committed** (gitignored in the root `.gitignore`). `tools/fetch-upstream-assets.ps1` restores them from upstream at the pinned commit and checks their SHA-256:

| Path (upstream) | Used by |
|---|---|
| `AndroidCompat/src/main/resources/font/NotoSans/*.ttf` (8 files, ~40 MB) | `android.graphics.Typeface` stub (runtime, for extensions). Only `NotoSans`, `NotoSerif`, `NotoSansMono`, `NotoSansSymbols2` and `NotoEmoji` are referenced. `NotoColorEmoji-Regular` (24 MB) and the two Italic fonts are not. |
| `server/src/test/resources/{dice.jxl, fox.profile0.8bpc.yuv420.avif, sample1.heif}`, at `core/src/test/resources/` in Madoyomi | Upstream image-decoding tests |

Run `.\tools\fetch-upstream-assets.ps1` once after cloning, before the first Gradle build.

## Module layout

Upstream is one application module (`server`) with `i18n`, `server-config` and `server-config-generate` nested inside it. Madoyomi splits it:

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
| `build.gradle.kts` | the android-jar repository is `raw/6830765fb39a28e31cd40111c853f5bf88be3f06/` (upstream: `raw/android-jar/`, the branch's head). Upstream rewrites `com.github.Suwayomi:android-jar:1.0.0` in place on that branch whenever its AndroidCompat implements another class (2026-11-01 `android.net.Network*`, 2026-11-07 `RenderProcessGoneDetail`); this fork, at `7b061be`, still takes `RenderProcessGoneDetail` from the stubs, so a fresh checkout (the CI) failed `:AndroidCompat:compileKotlin` while the dev desktop's Gradle cache held the older jar. `6830765` is byte for byte the jar the fork was built and tested with. On an upstream sync, move the pin to the branch commit that matches the synced AndroidCompat and update the SHA-256 below |
| `AndroidCompat/build.gradle.kts` | `strippedAndroidStubs` checks the android-jar's SHA-256 (`androidStubsSha256`, `b91eab7d…`) before stripping it, so a changed artifact fails the build at once with both hashes |
| `core/src/test/.../server/util/SocksProxyTest.kt` | `@AfterAll` waits (up to 5 s) until `ServerSetup`'s asynchronous subscriber has applied the restored proxy settings to the JVM's `socksProxyHost`/`Port`/`Version`, then clears them (and the default `Authenticator`) when the proxy was off. Before, the next test class could still see one of this class's states: `CefTest`'s download through OkHttp failed with `IOException: bad host` (the baseline, the local build), which in CI would stop a release |
| `settings.gradle.kts` | `rootProject.name = "Madoyomi-Server"` (app name, jar name, `BuildConfig.NAME`); no `ProductName` env override; the module list of the split |
| `buildSrc/src/main/kotlin/Constants.kt` | `MainClass = "madoyomi.api.MainKt"`; fixed version `vX.Y.Z`, written from the repo's `VERSION` by `tools/version.ps1` (`v0.1.2` now); it must stay numeric, `AppInfo` derives the version code from it |
| `core/build.gradle.kts` | BuildConfig `GITHUB`/`DISCORD`, test system properties use `madoyomi.config.`; **deleted** the `downloadWebUI` and `runElectron` tasks. Module split: no `application`/shadow plugins (moved to `api/build.gradle.kts`, jar vendor `Madoyomi`), depends on `ext-host`/`ext-manga`/`ext-anime`, project paths `:i18n`, `:server-config`, `:server-config-generate` |
| `server-config-generate/build.gradle.kts` | `generateSettings` targets `:core` (upstream `:server`), with `evaluationDependsOn(":core")` since the two are siblings now; `projects.serverConfig` |
| `ext-host/`, `ext-manga/`, `ext-anime/`, `api/` `build.gradle.kts` (new) | the new modules. `ext-host` depends on apksig (`implementation`, so it is bundled into the server jar) and has its own JUnit tests. `api` compiles against graphql-kotlin, Exposed and `ext-host` (its GraphQL additions), and its tests reuse `core`'s test classes (`testImplementation` of `core`'s test source set output, hence `evaluationDependsOn(":core")`), with their data root in `api/build/tmp/TestDesk` |

### Rebrand (user-visible names only)

| File | Change |
|---|---|
| `AndroidCompat/Config/.../ApplicationRootDir.kt` | `CONFIG_PREFIX = "madoyomi.config"` (so `-Dmadoyomi.config.server.*`); default data dir `%APPDATA%\Madoyomi` on Windows, AppDirs `Madoyomi` elsewhere |
| `AndroidCompat/Config/.../Logging.kt` | log file `logs/madoyomi.log`, appender name; `debugLogsEnabled` reads the prefixed property |
| `core/.../server/ServerSetup.kt` | temp dir `<tmp>/Madoyomi`; startup log line uses `BuildConfig.NAME`. The two id-keyed caches are in the data dir instead (see "Id-keyed caches in the data dir") |
| `core/.../server/util/AppExit.kt` | shutdown log line |
| `core/.../server/database/DBManager.kt` | Hikari pool name |
| `core/.../graphql/server/TemporaryFileStorage.kt`, `ext-host/.../eu/kanade/tachiyomi/network/NetworkHelper.kt` | temp dir names |
| `core/.../global/impl/util/Jwt.kt`, `server-config/.../ServerConfig.kt` (`jwtAudience`) | JWT issuer/audience `madoyomi-server[-api]` |
| `core/.../server/user/UserType.kt`, `core/.../server/JavalinSetup.kt`, `core/.../graphql/controller/GraphQLController.kt` | cookie names `madoyomi-server-token`, `madoyomi-basic-csrf` |
| `core/.../manga/impl/backup/proto/models/Backup.kt` | backup file prefix `app.madoyomi` (the `.tachibk` extension stays, for Mihon compatibility) |
| Tracker clients in `core`: `AnilistInterceptor`, `KitsuInterceptor`, `MangaUpdatesInterceptor`, `MyAnimeListInterceptor`, `ShikimoriInterceptor`, `BangumiInterceptor`, `MangaBakaApi` | User-Agent says Madoyomi. OAuth redirect URLs are unchanged (trackers come later). |
| Tests `UserContextTest`, `ProtoBackupExportTest` (in `core`) | follow the cookie name and backup prefix |

Deliberately **not** rebranded: package names (rule above); i18n strings (they only appear in OPDS, the login page and the KCEF WebView, all disabled); tray, SyncYomi and KOReader device names (disabled features); `docs/`, `scripts/`, `README.md` and `CONTRIBUTING.md` (upstream documentation and packaging, unused by Madoyomi; their `:server:` task names refer to upstream's layout).

### Sidecar defaults and disabled features

| File | Change |
|---|---|
| `server-config/.../ServerConfig.kt` | defaults: `ip` `127.0.0.1`, `port` `0` (min lowered to 0), `initialOpenInBrowserEnabled`, `systemTrayEnabled`, `webUIEnabled` and `kcefEnabled` all `false` (KCEF code stays for a later WebView) |
| `core/.../server/JavalinSetup.kt` | connector always binds `127.0.0.1` and is exposed as `JavalinSetup.connector`; startup log shows the bound port; CORS allows only the Tauri origins (`MadoyomiFeatures.ALLOWED_ORIGINS`), on every path; OPDS routes not registered; `javalinSetup(configure)` hook, called before upstream's `defineCore()`, and it returns the app; the `api/` "unmatched → 404" `after` handler leaves a 401 alone (otherwise token rejections would turn into 404s) |
| `core/.../server/ServerSetup.kt` | SyncYomi scheduling off |
| `core/.../manga/impl/update/Updater.kt` | no SyncYomi sync before library updates; No scheduled update of its own either (`scheduleUpdateTask` returns early behind `MadoyomiFeatures.upstreamLibraryUpdateScheduleEnabled`; see "Library updates") |
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

### Domain & DB

Repos and extensions of both kinds share upstream's `EXTENSIONSTORE` and `EXTENSION` tables, told apart by a new `type` column (see "Database migrations" below). Upstream's code is manga-only, so every query of it that reads a *set* of these rows, or acts on a row the client names, now also requires `type = MANGA`. Lookups that reach `EXTENSION` through `SOURCE` (manga-only by construction), inserts (the column defaults to `MANGA`) and `removeExtensionStore`/`ExtensionStoreService.upsert` (keyed by the unique `index_url`) are unchanged. `removeExtensionStore` goes through `RepoService` (see "Repo manager" below).

**The GraphQL rows of this table are superseded** (see "API"): the GraphQL API is type-aware, so `extensions`, `extensionStores`, the extension mutations and `fetchExtensions` take a `type` instead of assuming manga, and the two data loaders are upstream's code again. The non-GraphQL rows (`ExtensionsList`, `ExtensionStoreService`, `Extension`, `GetSource`) still apply.

| File | Change |
|---|---|
| `core/.../manga/model/table/ExtensionTable.kt` | new columns `type`, `signatureHash`, `isTorrent`, `isLoaded` |
| `core/.../manga/model/table/ExtensionStoreTable.kt` | new columns `type`, `format`, `lastRefreshedAt`, `lastError`, `signingKeyTrusted` |
| `core/.../manga/impl/extension/ExtensionsList.kt` | `type = MANGA` on `extensionTableAsDataClass` and on the installed set of `updateExtensionDatabase` (which drives its updates, obsolete marking and deletes) |
| `core/.../manga/impl/extension/ExtensionStoreService.kt` | `type = MANGA` on the store sets of `getAndRefresh`, `syncDbToPrefs` and `syncPrefsToDb`, and on the delete in `syncPrefsToDb` (the `extensionStores` setting lists manga stores only) |
| `core/.../manga/impl/extension/Extension.kt` | `type = MANGA` on the `installExtension(pkgName)` and `uninstallExtension` lookups (both delegate to `ExtensionLifecycle` with `ExtensionKind.MANGA`, see "Extension lifecycle" below) |
| `core/.../manga/impl/util/source/GetSource.kt` | `type = MANGA` on the extension lookup, so the manga loader can never load another kind of jar |
| `core/.../graphql/queries/ExtensionQuery.kt` | `extensions`: `type = MANGA` in the base query's `adjustWhere` (covers nodes, `totalCount` and page info). *Later removed; `condition: {type}` instead* |
| `core/.../graphql/queries/ExtensionStoreQuery.kt` | `extensionStores`: `type = MANGA` on the base query. *Later removed; `condition: {type}` instead* |
| `core/.../graphql/dataLoaders/ExtensionDataLoader.kt` | `ExtensionDataLoader` (`extension(pkgName)`): `type = MANGA`. *Later reverted, the file is upstream's again (`pkg_name` is unique, so both kinds resolve)* |
| `core/.../graphql/dataLoaders/ExtensionStoreDataLoader.kt` | `ExtensionStoreDataLoader` (`extensionStore(indexUrl)`): `type = MANGA`. *Later reverted, the file is upstream's again (`index_url` is unique)* |
| `core/.../graphql/mutations/ExtensionMutation.kt` | `type = MANGA` on the `updateExtension(s)` lookup and the two `fetchExtensions` payload lists. *Later replaced by the type-aware version* |

### Repo manager

The repo manager lives in `madoyomi.core.repo` (see "New Madoyomi code"). Upstream's store code now delegates to it, so both kinds of repo share one parser, one format-detection path and one trust model.

| File | Change |
|---|---|
| `core/.../manga/impl/extension/ExtensionStoreService.kt` | `fetch` and `getExtensions` delegate to `RepoIndexFetcher` (format `AUTO`, or `JSON` for a legacy store's `getExtensions`, which reproduces upstream's "read `index.min.json`, don't follow `index_v2`"). `syncPrefsToDb` adds new URLs through `RepoService.add` (so a repo typed into the config file is trusted like any added repo). The old inline parsing is gone; `getAndRefresh` and `upsert` are unchanged but no longer called by Madoyomi code. |
| `core/.../manga/impl/extension/ExtensionsList.kt` | `fetchExtensions` calls `RepoService.refreshAll(MANGA)` (last-good cache). `updateExtensionDatabase` is `internal` (upstream: `private`), takes `kind` (the set of rows it syncs; new rows get `type = kind`) and `prune` (without it nothing counts as missing), and writes `is_torrent` |
| `core/.../manga/impl/extension/github/NetworkLegacyExtension.kt` | `torrent: Int = 0` (Aniyomi's legacy index; upstream's `ignoreUnknownKeys` dropped it) |
| `core/.../manga/model/dataclass/ExtensionInfo.kt` | `isTorrent: Boolean = false`, carried into `EXTENSION.is_torrent` |
| `core/.../graphql/mutations/ExtensionStoreMutation.kt` | `addExtensionStore` → `RepoService.add(indexUrl, MANGA)`; `removeExtensionStore` → `RepoService.remove(indexUrl)`. Inputs and payloads are unchanged (the API adds `type`/`format`, see "API") |
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
  - Installed extensions signed with the trusted key are then loaded (`onTrusted` → `ExtensionLifecycle.reconcileTrust`), e.g. ones installed while the repo was untrusted.
- **Remove:**
  - The FK cascade drops the repo's trust row. `TRUSTED_SIGNATURE.hash` is the primary key, so a key shared with another trusted repo gets its row re-pointed at that repo, and USER trust is never touched.
  - The repo's uninstalled listings are deleted.
  - Installed extensions of the repo (or signed with its key) whose hash is no longer trusted get `is_loaded = false` and are unloaded (`ExtensionUnloader`: sources out of `GetSource`'s cache, class loader closed, load permit revoked). The trust gate keeps `GetSource` from reloading them.
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

### Extension lifecycle

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
  - **Untrusted extensions:** never instantiated. The row is written the way `setupJar` would, plus `signature_hash`, `is_torrent`, `is_loaded = false`, and the source rows come from the listing (`SOURCE` for manga, `ANIME_SOURCE` for anime). A trusted anime extension is loaded (see "Anime sources").
  - Progress: `ExtensionLifecycle.progress` emits `DOWNLOADING → VERIFYING → CONVERTING → INSTALLED | FAILED` (with the error code), for the `extensionInstallProgress` subscription.
- **Sideloads** (`installExternal`): APKs only (a jar has no signature: `SIGNATURE_INVALID`); the kind comes from the APK's feature; no listing, so no source rows until trusted.
- **Trust** is `signature_hash ∈ TRUSTED_SIGNATURE` (`SignatureTrust`). `trustSignature`/`trustExtension` add a USER row (or turn the repo's STORE row into USER, so removing the repo no longer revokes it) and load the installed manga extensions signed with it (`reconcileTrust`: `setupJar`, `is_loaded = true`). `untrustSignature` withdraws USER trust only (a key a repo vouches for stays trusted) and unloads what lost trust. Anime extensions are loaded the same way (`AnimeExtensionLoader`).
- **Update** (`update(pkg, kind)`): the repo must list a higher version code (upstream's `hasUpdate`, set by the refresh, is the detection). It re-runs install. If the installed version has a hash and the new APK is signed by another one that isn't trusted, the update is refused with `SIGNATURE_CHANGED` before anything is written, and the installed version stays (loaded, if it was). The old jar is deleted with upstream's delete-on-next-start fallback.
- **Uninstall:** unload (sources out of `GetSource`, `jarLoaderMap.remove(jar)?.close()`, permit revoked), delete the `SOURCE`/`ANIME_SOURCE` rows, upstream's row rule (delete if obsolete or without `apkUrl`, else back to a plain listing) plus `signature_hash = null`, `is_loaded = false`, then the jar (Madoyomi or upstream layout) via `tryToDeleteFile`.
- **Upstream-layout installs** (no `signature_hash`: dev data, anything installed by upstream or by an early build) are never loaded. Uninstall and reinstall them, or update them when the repo lists a newer version.
- **dex2jar repair.** Keiyoushi's APKs are R8-optimised in two ways that dex2jar 2.4.38 translates into bytecode the JVM rejects (MangaPill v1.6.1: `VerifyError` in 6 of 30 classes). `Dex2jarRepair` (ext-host) fixes both after dex2jar:
  - *Constructor elision:* some classes lose their no-op constructor, and the dex does `new-instance LT;` + `invoke-direct LS;-><init>` with S an ancestor of T. ART accepts it, the JVM doesn't, and dex2jar makes it `new S`. The pass reads the dex, rewrites those sites back to `new T`/`T.<init>`, and gives T (and any class between T and S) the missing `super`-only constructor. Unmappable sites are skipped and reported as install warnings.
  - *Frames:* dex2jar computes stack map frames without the APK's own class hierarchy, so a merge of two of its classes (an `a` or a `b`, both extending `c`) is typed `java/lang/Object` and a later use as `c` fails. The pass recomputes the frames of every class with a hierarchy read from the jar and the host classpath.
  - The yuzono APKs need no constructor repair; their frames are recomputed too, and every class of both kinds' test jars passes the verifier.
- No migration: every column used here exists since `M0069`/`M0070`.

### API

The GraphQL API (`/api/graphql`, subscriptions over `graphql-transport-ws` on the same path) is type-aware for both kinds, has the trust and anime-source operations, and every error carries a stable code. Upstream's operations were extended in place; the new ones live in `api` (`madoyomi.api.graphql`) and reach the schema through one hook.

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
| `core/.../graphql/dataLoaders/ExtensionDataLoader.kt`, `ExtensionStoreDataLoader.kt` | the earlier `type = MANGA` filters reverted: both files are identical to upstream again |
| `core/.../graphql/mutations/ExtensionStoreMutation.kt` | `AddExtensionStoreInput` gains `type` (null: `MANGA`) and `format` (null: `AUTO`), passed to `RepoService.add`; `removeExtensionStore`'s payload is the store as read before removal (upstream re-builds it field by field) |
| `core/.../graphql/mutations/ExtensionMutation.kt` | both kinds: the `updateExtensions` lookup has no type filter and each action goes to `ExtensionActions` by the row's type (manga → upstream's `Extension.*`, anime → `ExtensionLifecycle`); single-id `updateExtension` goes through `updateOne`, which reports what it can't do (`NOT_FOUND` for an unknown id, the lifecycle's `INSTALL_FAILED` for "already installed", "no update", "not installed") while the batch `updateExtensions` keeps upstream's skip semantics; `FetchExtensionsInput.type` → `RepoService.refreshAll(type)` (omitted: both kinds) and the payload lists that kind |

New operations (`api`, `madoyomi.api.graphql`):
- Queries: `trustedSignatures` (`hash`, `origin`, `addedAt`, `extensionStore`, `extensions`); `animeSources(condition: {id, name, lang, extensionPkgName})` → `AnimeSourceNodeList` of `AnimeSourceType` (`id`, `name`, `lang`, `contentWarning`, `baseUrl`, `extension`).
- Mutations (`INSTALL_EXTENSIONS` permission): `trustExtension(input: {pkgName})` (either kind), `trustSignature(input: {hash})`, `untrustSignature(input: {hash})` (its `isTrusted` says whether a repo still vouches for the key).
- Subscriptions: `extensionInstallProgress(pkgName?)` (`ExtensionLifecycle.progress`: `pkgName`, `type`, `state`, `errorCode`, `message`) and `extensionStoreRefreshed(type?)` (`RepoService.refreshed`, one event per refreshed repo: `indexUrl`, `type`, `ok`, `error`, `extensionCount`, `extensionStore`). Nothing is replayed, so a client subscribes before it starts the operation.

The error model:
- `ErrorCode` (with an HTTP status each): the codes plus `SOURCE_FAILED` (a source's HTTP error or unreachable host), `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN` and `INTERNAL`.
- `ErrorClassifier` maps any exception, for GraphQL and REST alike: a `MadoyomiException` anywhere in the cause chain (after unwrapping `CompletionException`/`ExecutionException`/`InvocationTargetException`) keeps its code and details; upstream's `UnauthorizedException`/`ForbiddenException`, `HttpException`, unreachable-host `IOException`s and `IllegalArgumentException` get their codes; upstream's `StubSource.SourceNotInstalledException` (what `getSourceOrStub` callers throw) is diagnosed by `SourceAccess` (`NOT_FOUND`, or `UNTRUSTED` with the hash); anything else is `INTERNAL`, with the exception's message (never a stack trace) and the stack trace in the log.
- GraphQL: `errors[].extensions = {code, details}`; graphql-java's own errors (syntax, validation) get `BAD_REQUEST` from the instrumentation.
- REST and binary endpoints: the JSON body `{code, message, details}` (the token filter's 401 included).

### Smoke-test fix

The API smoke test (`tools/smoke-api.ps1`) found one upstream bug, fixed here.

| File | Change |
|---|---|
| `core/.../manga/impl/util/storage/ImageResponse.kt` | `getCachedImageResponse` maps the cached file's extension back to a MIME type (`ImageUtil.ImageType`, then `MimeUtils`, then upstream's `"image/<ext>"`). Upstream returns `"image/<ext>"`, so every cached JPEG page or thumbnail (saved as `.jpg`) was served as the unregistered `image/jpg`, while the first, uncached fetch was `image/jpeg`. Test: `core/src/test/kotlin/madoyomi/core/storage/CachedImageTypeTest.kt` |

### Anime sources

Trusted anime extensions are loaded by the same loader and behind the same trust gate as manga.

| File | Change |
|---|---|
| `core/.../manga/impl/util/PackageTools.kt` | `loadExtensionSourcesWithLockHeld` creates the class loader before its `try` and catches `Throwable` (upstream: `Exception`). On a failure it closes a class loader it created but didn't keep in `jarLoaderMap`, and it logs `LinkageError`s as well as exceptions. Upstream leaked the loader whenever `Class.forName` failed, and the leaked loader kept the jar open, which on Windows locks it against uninstall and update. |
| `ext-host/.../manga/impl/util/ChildFirstURLClassLoader.kt` | `loadClass`: a class found nowhere (system loader, jar, parent) is reported to `madoyomi.exthost.ExtensionClassLoadHook` with the loader's jar before the `ClassNotFoundException` propagates (upstream: rethrown silently). The compat log listens there. |
| `ext-host/.../eu/kanade/tachiyomi/network/Requests.kt` | the suspend helpers of Aniyomi's extensions-lib 16, `OkHttpClient.get(String\|HttpUrl, headers, cache)` and `OkHttpClient.post(url, headers, body, cache)` (`newCall(GET/POST(…)).awaitSuccess()`, from Aniyomi's `core/common/.../network/Requests.kt`), plus their `OkHttpClient`/`Response` imports. Suwayomi's copy lacks them, and the compat scan found lib-16 anime extensions calling `get$default` (anikoto, kotokai, aniwave) |
| `AndroidCompat/src/main/java/androidx/preference/ListPreference.java` | `getValue`/`setValue`/`setValueIndex` read and persist the value through the screen's `SharedPreferences` (`getSharedPreferences()` + `getKey()`), falling back to a value kept in the object and then to `defaultValue` (upstream: `"Stub!"`); new `getEntry()` (Android API) |
| `AndroidCompat/.../androidx/preference/MultiSelectListPreference.java` | `getValues`/`setValues` the same way (`getStringSet`/`putStringSet`, copies), `findIndexOfValue` like `ListPreference`'s (upstream: all three `"Stub!"`) |
| `AndroidCompat/.../androidx/preference/TwoStatePreference.java` | `isChecked`/`setChecked` the same way (`getBoolean`/`putBoolean`; upstream: `"Stub!"`) |
| `AndroidCompat/.../androidx/preference/Preference.java` | `setOnPreferenceClickListener` stores the listener (upstream: throws `"Stub!"`); new `getOnPreferenceClickListener()`. Nothing clicks a preference in Madoyomi, so it is never called |
| `AndroidCompat/build.gradle.kts` | the android-jar (`libs.android.stubs`) goes through `strippedAndroidStubs`, a copy without the classes in `madoyomiAndroidOverrides` (now `android/os/LocaleList.class`), used as `implementation(files(…))` (upstream: `implementation(libs.android.stubs)`). AndroidCompat implements those classes itself, so there is one copy of each on every classpath and in the server jar; upstream's android-jar is already stripped of the classes upstream implements |
| `AndroidCompat/src/main/java/android/os/LocaleList.java` (new file in an Android package) | `android.os.LocaleList` on `java.util.Locale`, android-jar's public API (every Keiyoushi extension of the compat scan calls `getDefault`/`get`/`size`) |
| `core/.../graphql/server/TachideskDataLoaderRegistryFactory.kt` | the registry also gets `MadoyomiSchemaContributions.dataLoaders()`, the data loaders of Madoyomi's GraphQL types (`api` registers the anime ones), after upstream's list |
| `core/.../graphql/types/ExtensionType.kt` | new resolver `compatIssues()` (`ExtensionCompatLog.issues(pkgName)` as `madoyomi.core.graphql.ExtensionCompatIssueType`), next to `isTrusted()` |
| `ext-host/.../manga/impl/util/ChildFirstURLClassLoader.kt` | every loader has a unique name, `madoyomi-extension-<n>` (upstream: unnamed), which stack frames carry (`StackTraceElement.getClassLoaderName`). `ExtensionThreads` uses it to tell which threads run an extension's code: R8's short class names (`a`, `w2`, `v7`) repeat across extensions, so a frame's class name alone matched other extensions' threads |
| `core/.../eu/kanade/tachiyomi/AppModule.kt` | `Json` is a Koin `factory` (upstream: `single`): every injection gets its own instance. A `Json` keeps a schema per serial descriptor it decoded with (kotlinx-serialization's `DescriptorSchemaCache`, strongly held), and extensions inject it, so the shared instance kept the descriptors (and so the class loaders) of every extension after uninstall. A heap dump showed it holding 4 of 5 loaders |
| `AndroidCompat/.../io/sharedprefs/JavaSharedPreferences.kt` | change listeners are held weakly (a synchronized `WeakHashMap`), as Android's `SharedPreferencesImpl` does, and `notify` calls a copy of the listeners. Upstream kept a strong map of listener -> lambda (the lambda capturing the listener), so every listener an extension registered kept its class loader after uninstall: these objects live as long as the process |
| `AndroidCompat/build.gradle.kts` | the test task sets `madoyomi.config.server.rootDir` to `build/tmp/TestDesk`, as core's does, so the new SharedPreferences test doesn't write to the user's data dir |
| `core/.../manga/impl/Search.kt` | `filterListCache` is `internal` (upstream: `private`), so `ExtensionUnloader` can drop a manga source's cached filter list on unload; `Source.preferenceScreenMap` (already public) is dropped the same way. Both hold the extension's objects (and so its class loader) for good |
| `core/build.gradle.kts`, `api/build.gradle.kts` | the tests run with `--add-opens` for `java.base/java.lang` and `java.base/java.util.concurrent` (to `ALL-UNNAMED`), and the server jar's manifest has `Add-Opens: java.base/java.lang java.base/java.util.concurrent` (see "Startup contract"). `ExtensionThreads` reads a thread's `Runnable`, a pool worker's pool and a thread's inherited access-control context |

How it behaves:
- **Load points.** A trusted anime extension is instantiated in three cases:
  - at install or update (`ExtensionLifecycle.record`). The new code is instantiated *before* the old version is unloaded, so a failure keeps the installed version as it was;
  - when its key becomes trusted (`reconcileLocked`);
  - on first use (`AnimeGetSource.getSource`), e.g. an early install that was never loaded.
- **What a load does.** The class goes through `PackageTools.loadExtensionSources`/`blockJarUsageWhile`, so `ExtensionLoadGate` applies. The result is one `AnimeSource`, or every source of an `AnimeSourceFactory`. `ANIME_SOURCE` is then reconciled with the live sources (`baseUrl` from `AnimeHttpSource.baseUrl`; ids the code no longer provides are deleted), and `is_loaded = true` is set together with upstream's extension-language rule.
- **Failures.** Anything other than the gate's `UNTRUSTED` becomes `LOAD_FAILED`, with `details.cause` and, for a `LinkageError`, `details.missing`. This covers a missing class, a non-source class, a throwing constructor and a `LinkageError`. At install it fails the install (progress `FAILED`, code `LOAD_FAILED`) and deletes the new jar.
- **Unload.** `ExtensionUnloader` drops an anime extension's sources from `AnimeGetSource` and closes its class loader, as for manga. It first stops the threads the extension left running (`ExtensionThreads`), so nothing keeps the loader: an uninstalled extension's classes are unloaded at the next full GC.
- **Diagnosis.** `AnimeSourceAccess` says why a source isn't available: `NOT_FOUND` (with a hint when the id is a manga source), `UNTRUSTED` with the hash, or the loader's `LOAD_FAILED`.
- **Compat log.** `ExtensionCompatLog` (core, in memory, per package, at most 200 distinct issues) collects what an extension needs from the host and doesn't get:
  - at install, the static `ExtensionLinkCheck` of the converted jar (both kinds). Its reachable issues become `InstallResult.warnings` (`link check: 7 Android stubs that throw "Stub!" (…)`); issues used only by an `android.app.Activity` subclass (the deep-link `UrlActivity`, which Madoyomi never starts) are left out. The log of a package starts over when a new version installs, and is dropped at uninstall;
  - at runtime, the extension class loader's misses (`ExtensionClassLoadHook`, `CLASS_NOT_FOUND`; known `Class.forName` probes such as `*BeanInfo`, `*$serializer` and bundled-OkHttp platform checks are dropped), and the `NoClassDefFoundError`, `NoSuchMethodError`/`NoSuchFieldError`, `AbstractMethodError` and `RuntimeException("Stub!")` that reach the host (`ExtensionIncompatibility`).
  - Every source call goes through `AnimeSourceCalls`, which records such an error and throws `EXTENSION_INCOMPATIBLE` (501) with `details.pkgName`, `sourceId`, `operation`, `kind`, `missing`. The loader records them too (the error stays `LOAD_FAILED`), and `ErrorClassifier` maps one that reaches GraphQL or REST another way (upstream's manga code) to `EXTENSION_INCOMPATIBLE` without a package.
  - Each distinct runtime issue is logged once as `[compat] <pkg>: <kind> <target> (<operation>)`. The core API is `ExtensionCompatLog.issues(pkg)`/`all()`/`staticReport(pkg)`; GraphQL has it as `extension.compatIssues`.
- **Services.** New Madoyomi code only, in `madoyomi.core.anime`; no upstream file changed. Every source call goes through `AnimeSourceCalls`. GraphQL wraps them.
  - `AnimeBrowse.fetch(sourceId, POPULAR|LATEST|SEARCH, page, query, filters)` upserts the page into `ANIME` by `(source, url)` (`AnimeEntries.upsert`, upstream's `MangaList.insertOrUpdate` plus a dedupe and a retry on a unique-key race) and returns the ids.
  - `AnimeDetails.fetch(animeId, fetchDetails, fetchChildren)` writes the details. It then reconciles `EPISODE` (upstream's chapter reconcile: by url, `source_order` oldest = 1, watch state kept and carried over by episode number, an empty list changes nothing) or, for `fetch_type = Seasons`, links the season rows (`parent_id`, `season_source_order`).
  - `AnimeVideos.fetchEpisodeVideos(episodeId)`, `fetchHosterVideos` and `resolveVideo` follow the Aniyomi app's `EpisodeLoader`/`HosterLoader`. Nothing is persisted; the last fetch per episode is kept in memory for `resolveVideo`.
  - `AnimeFilters` and `AnimeSourcePreferences` map to upstream's GraphQL `Filter`/`FilterChange` and `Preference`/`SourcePreferenceChange` (`graphql/types/SourceType.kt`, `graphql/mutations/SourceMutation.kt`), used as they are. A preference change runs the listener first (Android's order), saves to the source's `SharedPreferences` and drops the cached source.
- **API.** Madoyomi's GraphQL in `api` wraps the services, in the shapes of upstream's manga operations.
  - Queries: `anime(id)`, `animes(condition, filter, order, paging)`, `episodes(…)`, and on `AnimeSourceType` `iconUrl`, `supportsLatest`/`isConfigurable` (null while the source can't be used), `filters`, `preferences`, `anime`.
  - Mutations: `fetchSourceAnime`, `fetchAnimeAndEpisodes` (data plus the source's error), `fetchEpisodeVideos`, `fetchHosterVideos`, `resolveEpisodeVideo`, and `updateAnimeSourcePreference` (`MANAGE_SOURCE_PREFERENCES`), and `updateAnime`/`updateAnimes` (see "Library flag" below).
  - `ExtensionType.compatIssues` (the upstream edit above).
  - The types `AnimeType`/`EpisodeType` (upstream's `MangaType`/`ChapterType` shapes) resolve their relations through data loaders registered with the hook above.
  - REST `GET /api/v1/anime/{animeId}/thumbnail` (`madoyomi.api.AnimeHttp`, registered in `Main`'s `javalinSetup` hook): `AnimeThumbnails`, upstream's manga thumbnail flow for anime (the source's client and headers, a details refresh when the URL is gone, upstream's thumbnail cache as `anime-<id>.<ext>`).
- **Compat harness fixes.** Madoyomi code only; no upstream file changed. The harness's results are in [tools/compat-test/reports/](../tools/compat-test/reports/).
  - `AnimeSourceCalls.call` runs every suspend source call on its own dispatcher: up to 64 daemon threads with 128 MB stacks (`anime-source-N`). `java.util.regex` recurses once per repetition of a group, but Android's ICU regex doesn't, so anizone's `(?:[^'\\]|\\.)*` over a 41K-character page overflowed a default 1 MB stack. The blocking variant (filters, preferences, headers) still runs on the caller's thread.
  - `ErrorClassifier` maps upstream `CloudflareInterceptor`'s `IOException("Cloudflare bypass currently disabled")`, and its private `CloudflareBypassException`, anywhere in the cause chain, to `SOURCE_FAILED` with `details.reason = "CLOUDFLARE"`. It was `INTERNAL` before. The message is matched as a constant (`ErrorClassifier.CLOUDFLARE_BLOCKED`), so the upstream file stays untouched.
  - When `AnimeGetSource` drops sources (uninstall, update, preference change, User-Agent change), `AnimeVideos.dropStale()` now forgets the last fetches of the dropped instances. A kept fetch held its source, and so the uninstalled extension's class loader.
- **Fix round.** The class loaders kept after uninstall, found with a heap dump (`tools/compat-test/lib/HprofLoaderRoots.java`), and the two R8 patterns of the manga sample.
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
  - Result: after uninstalling the top 20, 1 extension class loader is alive (15 before), held by a connection reanime itself keeps open.
  - `Dex2jarRepair`:
    - a `new T` that dex2jar left unfolded before an `invokespecial S.<init>` (mangafire, comix, mangakakalot) gets its call rewritten to `T.<init>`;
    - a constructor whose super call skips its direct superclass `U` (R8 removed `U`'s no-op constructor; mangadex's 8 serialization DTOs) calls `U.<init>`, and `U` gets the constructor back.
    - The compat scan's manga sample verifies with 0 errors.

### Library flag

Madoyomi code only; no upstream file changed. The UI's details page and Library need to add an anime to the library, which only upstream's manga side could do (`updateManga`).
- `updateAnime(input: {id, patch: {inLibrary}})` → `{clientMutationId, anime}` and `updateAnimes(input: {ids, patch})` → `{clientMutationId, animes}` (in the given order), in `madoyomi.api.graphql.AnimeMutation`, in the shape of upstream's `updateManga`/`updateMangas` (`@RequireAuth`, as upstream's).
- How it behaves:
  - `inLibrary: true` sets `ANIME.in_library` and `added_at` (`inLibraryAt`) to now. `false` clears the flag and keeps `added_at`, as upstream keeps `inLibraryAt`.
  - Every id must exist: an unknown one is `NOT_FOUND` (`AnimeEntries.animeRow`), and nothing is written. Upstream's `.first()` would be `INTERNAL`.
  - Not ported:
    - upstream's queueing of uninitialized entries for a library update. Anime library updates are separate, and the UI refreshes an uninitialized entry when it is opened;
    - `Library.handleMangaThumbnail`. Anime thumbnails stay in the thumbnail cache.
- A browse already leaves an in-library row as it is (`AnimeEntries.upsert`); `AnimeLibraryApiTest` checks that too.

### Id-keyed caches in the data dir

Found in an exit run. The decision was the user's.

| File | Change |
|---|---|
| `core/.../server/ServerSetup.kt` (`ApplicationDirs`) | `tempThumbnailCacheRoot` is `<dataRoot>/cache/thumbnails` and `tempMangaCacheRoot` is `<dataRoot>/cache/manga`. Upstream has `<tmp>/Madoyomi/thumbnails` and `<tmp>/Madoyomi/manga-cache`. |

Why:
- Both caches name their files by database id: `<mangaId>.<ext>`, Madoyomi's `anime-<id>.<ext>`, and chapter page dirs by manga and chapter id. (Corrected in the chapter page dirs are named by the source, the manga title and the chapter name, `getChapterCachePath(mangaId, chapterId)` → `DirName.getChapterDir`; the ids only look them up. The covers are what was keyed by id.)
- In the shared temp dir, a fresh data dir served the covers an older data dir had cached for the same ids. The exit run's anime #1 showed another database's One Piece cover.
- A user would hit this after resetting the data dir, after a reinstall, or with two data dirs.
- `<dataRoot>/cache` is upstream's existing `cacheDir`. Upstream's WebView-data mutation (`WebviewMutation`, `deleteRecursively`) clears that dir, and these caches with it. That is harmless for caches, and the UI doesn't call it.

Not changed:
- Everything else in the temp dir stays where it is: extension downloads in random dirs, `webUI-serve` (off), and temporary uploads. None of it is keyed by a database id.
- Old files in `%TEMP%\Madoyomi\thumbnails` and `manga-cache` are left alone; Windows' temp cleanup may remove them.

Tested in `core/src/test/kotlin/madoyomi/core/storage/IdKeyedCacheDirsTest.kt`.

### Playback

Madoyomi code only; no upstream file changed. The backend owns mpv (the contract with mpv is [player/IPC.md](../player/IPC.md)).
- `madoyomi.core.player` (core):
  - `MpvLocator` finds `mpv.exe`: the player settings' path, the system property `madoyomi.player.mpv`, the env `MADOYOMI_MPV`, then `PATH`. A missing one is `PLAYER_NOT_FOUND`;
  - `PlayerDirs` gives mpv's config dir: `MADOYOMI_PLAYER_DIR` if it is a directory, else `<data dir>/player`;
  - `MpvCommand` builds the command line. The source's values (headers in the forms the early mpv spike found, the title, subtitle and audio URLs, `Video.mpvArgs` minus the reserved options) go into a per-session `--include` file in mpv's fixed-length quoting, not onto the command line, because Java doesn't escape a `"` inside a Windows argument;
  - `PlaybackSessions` runs one mpv at a time. `play` resolves the video through `AnimeVideos.resolveVideo` and replaces the running session; `stop`, `current`. mpv's output goes to `<data dir>/logs/mpv.log`, and it gets the environment without `MADOYOMI_TOKEN`. A shutdown hook stops mpv. Each session writes `<data dir>/cache/player/<id>.session` (pid, executable, start time), and `reapOrphans` (called once from `Main`) ends a live process matching all three, then clears the dir.
- `ErrorCode`: `PLAYER_NOT_FOUND` (503) and `PLAYER_FAILED` (500).
- GraphQL (`madoyomi.api.graphql.PlaybackGraphQL`):
  - `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})` → `{clientMutationId, session}`;
  - `stopPlayback(input: {})` → `{clientMutationId, session}` (null if nothing played);
  - `playbackSession` (the running session, else the last one);
  - `PlaybackSessionType`: `id`, `episodeId`, `animeId`, `title`, `videoTitle`, `state` (`PlaybackState`: `RUNNING`, `EXITED`, `STOPPED`), `startedAt`, `endedAt`, `exitCode`.
- Tests: `core/src/test/kotlin/madoyomi/core/player/{MpvCommandTest,MpvLocatorTest}.kt`; `api/src/test/kotlin/madoyomi/api/PlaybackApiTest.kt`, with a fake mpv in `api/src/test/java/madoyomi/api/FakeMpv.java` (Java with no dependencies, so its launch stays short).
- **IPC and progress.** Madoyomi code, plus two build-file lines (below). The contract is [player/IPC.md](../player/IPC.md) "IPC".
  - `MpvIpcClient` (core): mpv's JSON IPC on the session's named pipe, opened as an `AsynchronousFileChannel` (overlapped I/O, so a pending read never blocks a write; a Unix socket elsewhere), with connect retries, `request_id` replies, line-delimited events on a reader thread, `observe_property`, and a close when mpv closes the pipe.
  - `PlaybackTracker` (core): turns the observed `time-pos`, `duration`, `pause`, `eof-reached`, `track-list` and the `seek`/`playback-restart`/`file-loaded`/`end-file` events into saves (every 5 s while playing; at once on pause, after a seek, at the threshold, at the end), the watched mark (once per session, at 85 %), progress events (at most 1/s), and the session's outcome (ended, failed, closed).
  - `EpisodeProgress` (core): writes `EPISODE.last_second_seen`/`total_seconds` in **milliseconds** (the Aniyomi app's unit, despite the names), sets `watched` only if it isn't, and finds the next episode by `source_order`. No migration.
  - `PlaybackSessions`: connects to the pipe in the background after the start, adds the external subtitle and audio tracks over IPC (`sub-add`/`audio-add` with the source's label as title; `MpvCommand` no longer writes `sub-files-append`/`audio-files-append`), stops a session with `quit` before it would kill it, and publishes `events` (STARTED, PROGRESS, WATCHED, ENDED with the next episode, ERROR, CLOSED). `Session` gained `positionSeconds`, `durationSeconds` and `paused`; `watchedThreshold` (the setting) and `ipcConnectTimeoutMillis` are settable.
  - GraphQL: subscription `playbackEvents(episodeId)` → `PlaybackEventType` (`type`, `session`, `nextEpisodeId`, `reason`, `message`); `PlaybackSessionType` gained `positionSeconds`, `durationSeconds` and `paused`; `EpisodeType.lastSecondSeen`/`totalSeconds` are described as milliseconds.
  - Build: `gradle/libs.versions.toml` gets `jna-platform` (5.17.0), used only as `testImplementation` in `core/build.gradle.kts` (a `// Madoyomi:` line, upstream file) and `api/build.gradle.kts`. The tests' fake mpv serves a real named pipe, which Java can't create.
  - Tests: `core/src/test/kotlin/madoyomi/core/player/{MpvIpcClientTest,PlaybackTrackerTest,EpisodeProgressTest}.kt`, the fake pipe server `core/src/test/java/madoyomi/core/player/FakeMpvServer.java` (in-process, and as mpv in a process), and the progress cases of `PlaybackApiTest`. The pipe tests run on Windows only.
- **Watched marks.** Madoyomi code only; no upstream file changed, no migration. The UI's bulk "Mark watched"/"Mark unwatched" needs what upstream's manga side has as `updateChapter(s)`.
  - `updateEpisode(input: {id, patch})` → `{clientMutationId, episode}` and `updateEpisodes(input: {ids, patch})` → `{clientMutationId, episodes}` (in the given order, each id once), in `madoyomi.api.graphql.AnimeMutation`, `@RequireAuth` as upstream's. `UpdateEpisodePatch`: `isWatched`, `isBookmarked`, `lastSecondSeen` (milliseconds, a `LongString`).
  - How it behaves:
    - `isWatched: false` also sets `last_second_seen` to 0, as the Aniyomi app's `SetSeenStatus` does, unless the patch sets a position; `isWatched: true` keeps the position. `total_seconds` is never changed here;
    - a `lastSecondSeen` is clamped to 0 and to `total_seconds` when that is known (upstream clamps `lastPageRead` to the page count);
    - every id must exist: an unknown one is `NOT_FOUND` (`AnimeEntries.episodeRow`), and nothing is written. Upstream's `.first()` would be `INTERNAL`;
    - a session playing the episode isn't affected: it saves its position as before, and doesn't mark it watched again (the once-per-session rule).
  - Tests: `api/src/test/kotlin/madoyomi/api/EpisodeUpdateApiTest.kt`.
- **Retry on an expired URL.** Madoyomi code only; no upstream file changed. The contract is [player/IPC.md](../player/IPC.md) "When the video doesn't play".
  - `VideoChoices` (core, `madoyomi.core.player`): `VideoRef` (a video by its hoster's name, title and resolution), `find` (the same video in a new fetch: the hoster by name, the video by title, else by resolution, nothing else) and `next` (the video after a failed one in the source's order, wrapping, never the failed one). `MpvLog`: the last `HTTP error NNN` in mpv's log, whether an `eof` came more than 10 s before the end, and the statuses worth a refetch (401, 403, 404, 410).
  - `PlaybackSessions`: a session that fails early (no position reported) or with an expired status in mpv's log, or that mpv ends as `eof` more than 10 s early after an HTTP error (found live: mpv treats a refused range request as the end of the file), is retried once. The backend sends RETRYING, fetches the episode's videos again in the background, finds the same video, resolves it and plays it from the failed session's last position (attempt 1). Then the failed session's CLOSED. If that fails, or a retry fails again, it sends ERROR with `retried`, `httpStatus`, `failedVideo` and `nextVideo`. A `playEpisode` or `stopPlayback` meanwhile cancels the retry.
  - `AnimeVideos`: `cachedHosters` (the last fetch's hosters, without asking the source) and `hosterName`.
  - GraphQL: `PlaybackEventKind.RETRYING`; `PlaybackEventType` gained `httpStatus`, `retried`, `failedVideo` and `nextVideo` (`PlaybackVideoRefType`: `hosterName`, `videoTitle`, `resolution`).
  - Tests: `core/src/test/kotlin/madoyomi/core/player/VideoChoicesTest.kt`, and the retry cases of `PlaybackApiTest`. `FakeMpvServer` (a test file) reads its failure from the URL (`fakempv-error`, `fakempv-fail-at`, `fakempv-eof-at`, `fakempv-status`), so a fake source can hand it a URL that fails on the first fetch only.
- **Player settings.** Madoyomi code only; no upstream file changed, no migration (the settings are a file of their own, not upstream's `server.conf`).
  - `PlayerSettings` / `PlayerSettingsStore` (core, `madoyomi.core.player`): the watched threshold (0.5–0.95 in steps of 0.05), `QualityPreference` (`SOURCE`, `HIGHEST`, `P2160`…`P360`), the preferred server, the subtitle and audio language, the mpv path, in `<data dir>/player-settings.json` (kotlinx.serialization; written to a `.tmp` and moved over; an unreadable file or value gives the default). `update(patch)` validates every field first (`BAD_REQUEST`, nothing written). `fileOverride` and `reload()` are for tests.
  - `PlayerLanguages`: 21 languages (639-1 code, English and native names, 639-2 codes) and `matches(label, language)`. `TrackSelection.choose(trackList, externals, settings)`: the `sid`/`aid` to set (a track id, off, or mpv's own).
  - `VideoPreferences`: `sortHosters` and `sortVideos` (stable; the server, then the hoster's target quality), `heightOf`, `targetHeight`, `Ranked(item, sourceIndex, matchesPreference)`.
  - `AnimeVideos`: a fetch reads the settings when it starts and keeps its hosters and videos as `Ranked`, sorted by them after the source's sorts. `EpisodeHoster.sourceIndex`, `EpisodeVideo.sourceIndex` and `matchesPreference` (defaults: the index, false).
  - `PlaybackSessions`: `executable` locates mpv with the settings' path; `watchedThreshold` is the settings' (no longer a `var`), and `PlaybackTracker` takes it as `() -> Double`, read at every position. `addTracks` adds the external tracks with `auto`, waits for them (30 s at most), then sets `sid`/`aid` by `TrackSelection`; with a language set it also runs for a file without external tracks.
  - `MpvLocator`: `find` (the file and its `Origin`: `SETTINGS`, `SYSTEM_PROPERTY`, `ENV`, `PATH`), quotes taken off an explicit path, the error naming "Settings → Player" with `details.origin`, and `check(path)` (`<mpv> --version`, 10 s, an "mpv " line).
  - GraphQL (`madoyomi.api.graphql.PlayerSettingsGraphQL`): `playerSettings`, `playerLanguages`, `mpvCheck(path)` → `MpvCheckType` (`ok`, `path`, `origin`, `version`, `errorCode`, `message`), `setPlayerSettings(input: {patch: PlayerSettingsPatchInput})` → `{clientMutationId, settings}`. `EpisodeHosterType.sourceIndex`, `EpisodeVideoType.sourceIndex` and `matchesPreference`.
  - Tests: `core/src/test/kotlin/madoyomi/core/player/{PlayerSettingsTest,PlayerLanguagesTest,TrackSelectionTest,VideoPreferencesTest}.kt`, new cases in `MpvLocatorTest` and `PlaybackTrackerTest`, `api/src/test/kotlin/madoyomi/api/PlayerSettingsApiTest.kt`, and the player-settings cases of `PlaybackApiTest`. `ApiGraphQLTest` gives each test a settings file of its own. `FakeMpvServer` numbers tracks per type as mpv does, sets `sid`/`aid`, and gets tracks of its own from the URL (`fakempv-audio`, `fakempv-subs`).
- **Exit run.** Madoyomi code only; no upstream file changed.
  - `PlaybackTracker.neverPlayed`: no position reported, or mpv's `file_error` "no audio or video data played" (`NOTHING_PLAYED`). mpv reports the start position once the file is open, so an HLS stream whose segments all fail had a position; `PlaybackSessions` now retries on `neverPlayed` instead of "no position".
  - Tests: a new case in `PlaybackTrackerTest` and in `PlaybackApiTest`; `FakeMpvServer` (a test file) gets `fakempv-nothing` (the file opens, reports its start position, and ends with "no audio or video data played"). `ExtensionThreadsTest` (a Madoyomi test) counts the source pool's threads by name instead of by `poolSize`, which idle threads of earlier tests could lower meanwhile.

### Manga reader

Upstream's manga page route (`GET /api/v1/manga/{mangaId}/chapter/{chapterIndex}/page/{index}`, `MangaController.pageRetrieve` → `Page.getPageImageServe` → `Page.getPageImage`) already fetches a page through the source's `HttpSource.getImage` (the source's client: its headers, cookie jar and rate limits) and caches it in `<data dir>/cache/manga`. So it is the reader's image proxy, and Madoyomi adds what it lacks around the fetch.

| File | Change |
|---|---|
| `core/.../manga/impl/Page.kt` (`getPageImage`) | the source fetch goes through `madoyomi.core.reader.PageImages.get(...)` instead of `ImageResponse.getImageResponse(cacheSaveDir, fileName) { source.getImage(tachiyomiPage) }` (a `// Madoyomi:` comment with upstream's call). The rest of `getPageImage` (downloaded chapters, the Local source, the `imageUrl` resolution, the cache folder and file name) is upstream's. |

What `PageImages` and `PageCache` (`madoyomi.core.reader`, core) add:
- **One fetch per page at a time.** Concurrent requests for a page wait for the same download and share its result or its error. Upstream let each fetch and write the same `.tmp` file, which a prefetch and the reader would have raced on. The download runs in a scope of its own, so a caller that goes away doesn't cancel it for the others.
- **One retry.** A transient failure (an I/O error that isn't a Cloudflare challenge, HTTP 408, 425, 429, 5xx) is tried once more as it was. An HTTP 401, 403, 404 or 410 first gets the page's image URL anew, because a source's URLs may expire and upstream keeps the page list from the chapter's last `fetchChapterPages`. A page with its own URL is resolved again with `getImageUrl`, and `PAGE.image_url` is updated. Otherwise the chapter's page list is fetched again (`refreshChapterPageList`), once for all of a chapter's pages that fail together.
- **`SOURCE_FAILED` (424)** with `details.httpCode` and `details.retried` for a source's HTTP error or I/O failure. Upstream answered a non-200 that wasn't thrown as `Exception("request error! …")`, which was `INTERNAL`.
- **The cache's cap and LRU eviction** (`ReaderSettings.pageCacheLimitMb`, 1 GiB by default, 256 MiB to 10 GiB; the user's decision). A hit sets the file's modification time. Once the counted size passes the cap, a background trim removes the oldest files to 90 % of the cap, and the empty folders. Files used in the last 5 minutes stay even over the cap: they are the chapter being read and its prefetch. (Upstream's downloader staged a chapter's pages in this same folder, `ChaptersFilesProvider`; Madoyomi's queue stages them outside it, in `<downloads>/.partial`, uncounted: "Manga downloads".) A leftover `.tmp` is never a hit.
- **The prefetch** (`PageImages.prefetch`): pages `from` … `from + count - 1` (1 to 20) of a chapter through upstream's `getPageImage`, in the background, at most 2 requests at a time across chapters. A chapter whose page list was never fetched gets it first. A new prefetch of a chapter replaces the one still queued for it. Downloaded chapters and the Local source are skipped.

Not changed: upstream's `clearCachedImages` mutation (`ImageMutation`) still clears the same folder; `PageCache`'s counted size then drifts until its next scan. The cache folders are named by the source, the manga title and the scanlator and chapter name (`getChapterCachePath` → `DirName.getChapterDir`), not by ids (see "Id-keyed caches in the data dir").

GraphQL (`madoyomi.api.graphql.ReaderGraphQL`): `readerSettings` → `ReaderSettingsType` (`pageCacheLimitMb`); `setReaderSettings(input: {patch: {pageCacheLimitMb}})`, which trims the cache before it answers; `pageCache` → `PageCacheType` (`bytes`, `files`, `limitBytes`, `path`); `clearPageCache(input: {})` → `{cache}`; `prefetchChapterPages(input: {chapterId, from, count})` → `{chapterId, pageCount, queued, cached}`. The settings are `<data dir>/reader-settings.json` (`ReaderSettingsStore`, as `PlayerSettingsStore`).

Tests: `core/src/test/kotlin/madoyomi/core/reader/{PageCacheTest,ReaderSettingsTest}.kt` (19), `api/src/test/kotlin/madoyomi/api/PageImagesApiTest.kt` (7: upstream's route on a bare Javalin against a loopback image host, with a fake `HttpSource`).

**The paged reader changed nothing in `server/`.** The reader uses upstream as it is: the page route with the token in the `Authorization` header (the token filter takes it on every route; only `<img>`-style requests need `?token=`), `fetchChapterPages` on every open, and `updateChapter(s)` for the progress and the read marks (`lastPageRead` clamped to the page count, `lastReadAt` stamped with it, and the deprecated top-level `isRead`/`lastPageRead`/`lastReadAt` fields of `ChapterType` read, as the app already did). Upstream's chapter recognition reads MangaPill's "Group 2 Chapter 385" as chapter 2; the app doesn't trust the numbers for order.

### Library categories

Manga categories are upstream's as they are: `CATEGORY`, `CATEGORYMANGA`, `CATEGORYMETA`, `Category`, `CategoryManga`, `CategoryMutation` (`createCategory`, `updateCategory`, `updateCategoryOrder`, `deleteCategory`, `updateMangaCategories`, `updateMangasCategories`, `setCategoryMeta`), `CategoryQuery` and `MangaType.categories`, with one edit:

| File | Change |
|---|---|
| `core/.../graphql/mutations/MangaMutation.kt` (`updateMangas`, so `updateManga` too) | with `inLibrary: true`, the entries that weren't in the library yet go into the categories flagged `is_default` unless they are in a category already: `madoyomi.core.library.MangaNewEntryCategories.notInLibrary` before the upsert and `addedToLibrary` after it, in the same transaction (two `// Madoyomi:` comments). Upstream does this only in its REST `Library.addMangaToLibrary`, so a manga added through GraphQL (the app's "Add to library") stayed in Default whatever the dialog's "New entries" flag said. The rest of `updateMangas` (the upsert, the library update queue, the thumbnail) is upstream's. |

The user chose **one category list per kind**, so anime categories are Madoyomi's own tables and code, with upstream's rules:
- `M0072_AnimeCategories` drops M0070's never-written `ANIME_CATEGORY` stub (a FK to upstream's manga `CATEGORY`) and creates `ANIME_CATEGORY` (the list, `CATEGORY`'s columns plus `META` as JSON text) with its "Default" row, and `ANIME_CATEGORY_ANIME` (the join, as `CATEGORYMANGA`). See "Database migrations";
- `madoyomi.core.anime.AnimeCategories`: the list, create (at a position or the end), update, move by position, delete, assign (`clear`, `add`, `remove`), the categories of entries, the library entries of a category (Default: those in none), the meta. Where it differs from upstream:
  - names are trimmed, 1 to 64 characters and unique ignoring case, on a create and a rename. Upstream checks a new manga name only, case-sensitively;
  - renaming, flagging, moving or deleting Default is `BAD_REQUEST`. Upstream ignores it, or returns a null category;
  - an unknown category or entry is `NOT_FOUND`, before anything is written;
- `updateAnime(s)` with `inLibrary: true` puts an entry that was out of the library into the categories flagged `default`, unless it is in a category already: upstream's `Library.addMangaToLibrary`. A removed entry keeps its categories, as upstream's manga do.

GraphQL (`madoyomi.api.graphql.AnimeCategoryGraphQL`), in the shapes of upstream's:
- `animeCategories` and `animeCategory(id)` → `AnimeCategoryType` (`id`, `order`, `name`, `default`, `includeInUpdate`, `includeInDownload`, `isDefaultCategory`, `meta`, `animes`);
- `createAnimeCategory`, `updateAnimeCategory`, `updateAnimeCategoryOrder`, `deleteAnimeCategory`, `updateAnimeCategories` (one anime) and `updateAnimesCategories` (many), `setAnimeCategoryMeta`, `deleteAnimeCategoryMeta`;
- `AnimeType.categories` (by position, Default never listed) through `CategoriesForAnimeDataLoader`.

The Library keeps each category's sort in its meta, `madoyomi.library.sort` (`title:asc`, `added:desc`): upstream's `setCategoryMeta` for manga, `setAnimeCategoryMeta` for anime.

Tests: `api/src/test/kotlin/madoyomi/api/AnimeCategoryApiTest.kt` (9), `MigrationsFromEmptyDbTest` (the stub replaced, one Default, the categories kept on a rerun), and `api/src/test/kotlin/madoyomi/api/MangaNewEntryCategoriesApiTest.kt` (3: the flagged categories through `updateMangas` and `updateManga`, an entry in a category already, a removed entry added again).

Not changed: upstream's `includeInUpdate`/`includeInDownload` are stored for anime too, for the update checker and the downloads. Upstream's `CATEGORY.flags` (Tachiyomi's sort bitmask, for sync) isn't used: the sort is in the meta.

### History and badges

**Manga history is upstream's `CHAPTER_USER.last_read_at`**, with no table of Madoyomi's: upstream's `updateChapter(s)` stamps it whenever `lastPageRead` is saved (the reader does on opening a chapter and on every page), its data loaders read it (`lastReadChapter`) and its backup carries it (`BackupHistory`, `BackupMangaHandler`), so the restore brings it back. One edit:

| File | Change |
|---|---|
| `core/.../graphql/mutations/ChapterMutation.kt` (`updateChapters`, so `updateChapter` too) | a patch with `isRead: false` saves `lastPageRead` without stamping `last_read_at` (one `// Madoyomi:` comment). The chapter list's "Mark unread" sends `isRead: false, lastPageRead: 0`, so upstream stamped every chapter marked unread as just read and the History listed them first. A read mark alone (`isRead: true`) didn't stamp before and doesn't now (upstream's rule); a page saved with `isRead: true` or without `isRead` still stamps. Mihon's rule: marking unread doesn't touch the history. |

**Anime history is `ANIME_HISTORY`** (M0070's stub, `madoyomi.core.anime.model.table.AnimeHistoryTable`: one row per episode, unique, `last_seen` in epoch seconds as upstream's `last_read_at`; Aniyomi's `animehistory` is one row per episode too). No migration: the stub's shape is what history needs. `madoyomi.core.library.AnimeHistory`:
- `stamp` (an upsert per episode), called by `EpisodeProgress.save` (the player's saves: on load, every 5 s while playing, on close) and by `AnimeMutation.updateEpisodes` when the patch gives `lastSecondSeen` and doesn't mark unwatched. A watched mark alone, bulk or from the player, doesn't stamp: upstream's rule for `isRead`;
- `AnimeDetails.reconcileEpisodes` moves an episode's `last_seen` with its watch state to the new episode with the same number when a refresh replaces it (upstream carries `last_read_at` over the same way, `Chapter.kt`). Before, the row went with the old episode (cascade);
- `page` (one item per anime: its latest episode, newest first, limit and offset, and the resume target), `delete` (by anime), `clear`, `stats` (the library counts).

`madoyomi.core.library.MangaHistory` reads and changes upstream's column the same way: `page`, `delete` (by manga: `last_read_at` back to 0 for its chapters), `clear`. Neither touches the read or watched marks or the positions.

GraphQL (`madoyomi.api.graphql.HistoryGraphQL`):
- `animeHistory(limit, offset)` → `{ hasMore, items { anime, episode, lastSeenAt, resume } }` and `mangaHistory(limit, offset)` → `{ hasMore, items { manga, chapter, lastReadAt, resume } }` (upstream's `MangaType` and `ChapterType`, with the user's data). `resume` is the item itself, or when it is watched/read the first unwatched/unread one after it in the source's order, else the item again (Mihon's history resume opens the next chapter);
- `deleteAnimeHistory(input: {animeIds})`, `deleteMangaHistory(input: {mangaIds})`, `clearAnimeHistory`, `clearMangaHistory` → `{ removed }`;
- `AnimeType.unwatchedCount` and `AnimeType.lastSeenAt` through `AnimeStatsDataLoader` (two grouped queries for a whole list; a Seasons entry counts its seasons' episodes), the counterparts of upstream's `MangaType.unreadCount` and `lastReadChapter.lastReadAt`, which the app uses as they are.

Tests: `api/src/test/kotlin/madoyomi/api/HistoryApiTest.kt` (6: what stamps either history and what doesn't, the "Mark unread" edit, both lists with their resume targets and paging, delete and clear keeping the marks, the counts with seasons, upstream's `unreadCount`), and `AnimeDetailsTest` (the history moved with a re-listed episode).

Not changed: upstream's REST `Chapter.kt` paths that stamp `last_read_at` (the app doesn't use them); `CHAPTER_USER` has no index on `last_read_at` (an upstream table: the history query groups the user's rows by manga, fine at a desktop library's size; measure in a large library before adding one).

### Library updates

The library update check is Madoyomi's, for both kinds, in place of upstream's manga-only `Updater` (`suwayomi.tachidesk.manga.impl.update`), with one edit:

| File | Change |
|---|---|
| `core/.../manga/impl/update/Updater.kt` (`scheduleUpdateTask`) | returns right after descheduling, behind `MadoyomiFeatures.upstreamLibraryUpdateScheduleEnabled` (false; one `// Madoyomi:` comment). Upstream scheduled a manga-only check every `globalUpdateInterval` hours (12 by default) once the `Updater` was first used, which happened in the app whenever a manga was added to the library. Madoyomi's schedule (off by default, the user's decision) covers both kinds; two schedules would have hit the manga sources twice. The rest of the `Updater` stays: `addMangasToQueue` still fetches a never-initialized manga added through `updateMangas`, and upstream's `updateLibrary`, `updateLibraryManga`, `updateCategoryManga`, `updateStop`, `libraryUpdateStatus` and `libraryUpdateStatusChanged` still work for manga, but the app doesn't use them. |

`madoyomi.core.library`:
- `LibraryUpdater`: one check at a time for both kinds (a start while one runs adds the entries not queued yet; `stop`).
  - **Which entries:** upstream's `addCategoriesToUpdateQueue` rule for `includeInUpdate`. It reads upstream's `Category.getCategoryList`, `CategoryManga.getCategoryMangaList` and `getMangasCategories` for manga, and `AnimeCategories` for anime. Strategy `ONLY_FETCH_ONCE` is left out. The skip filters are Madoyomi's settings in place of upstream's per-user `excludeCompleted`, `excludeUnreadChapters` and `excludeNotStarted`, which only upstream's `Updater` read.
  - **The fetch:** upstream's `Manga.updateMangaAndChapters`, with the details only when the user's `updateMangas` is on or the manga was never initialized (upstream's rule); `AnimeDetails.fetch` for anime. A Seasons entry gets its season list, then the episodes of the seasons that have some stored.
  - **The order:** upstream's model, one channel per source with its entries one at a time, plus a stagger (`staggerMs`, 1 s) and a semaphore of `serverConfig.maxSourcesInParallel` permits.
  - **The new count:** the item ids the fetch added, fetched since the check started; none for an entry with nothing stored before. It goes into the marks.
  - **The status** (`StateFlow`): running, the trigger, the times, total/done/skipped, the entries being checked, failures with the error, what was found per entry.
- `LibraryUpdateSettings` / `LibraryUpdateSettingsStore`: `<data dir>/library-update.json`, as the player and reader settings. It holds the interval (0, 6, 12 or 24 h), skip completed (true), skip with unwatched/unread (false), skip not started (false), the toast (false), and the schedule's anchor.
- `LibraryUpdateScheduler`: upstream's `HAScheduler`, started from `madoyomi.api.Main`. It schedules one interval after the anchor, or a minute after the start when that is past, and reschedules when the interval changes. `MADOYOMI_UPDATE_INTERVAL_SECONDS` (env) or `madoyomi.updateIntervalSeconds` replaces the interval while it is on.
- `LibraryUpdateMarks` (`AnimeUpdateMarkTable`, `MangaUpdateMarkTable`, M0073): a count per library entry (per user for manga), added to by a check, cleared by `clearUpdateMarks`.
- `LibraryUpdatesFeed`: the Updates lists. Items of library entries fetched after the entry joined the library (`ANIME.ADDED_AT`, upstream's `MANGAUSER.IN_LIBRARY_AT`) and after the entry's first fetch (the minimum `fetched_at` of its items), newest first. This is upstream's `Chapter.getRecentChapters` rule plus the first-fetch one. An episode of a season counts for its library parent.
- `AnimeDetails.reconcileEpisodes` (Madoyomi code) now carries a deleted episode's `fetched_at` to the re-listed one with the same number, as upstream's `Chapter.updateChapterListDatabase` does, so it isn't new.

GraphQL (`madoyomi.api.graphql.LibraryUpdateGraphQL`), named apart from upstream's:
- `libraryCheck` → `LibraryCheckType`: `running`, `trigger` (`MANUAL`/`SCHEDULED`), `startedAt`/`finishedAt` (epoch ms), `total`, `done`, `skipped`, `checking`, `failures { entry error }`, `found { entry newCount }`, `newEpisodes`, `newChapters`, `cancelled`, `nextScheduledAt`. The entries are `{ kind id title }`;
- `startLibraryUpdate(input: {kind, categoryId})` (no kind: both; a category needs a kind, else `BAD_REQUEST`; an unknown one is `NOT_FOUND`), `stopLibraryUpdate`;
- the `libraryUpdates` subscription: the status at once, then on every change;
- `libraryUpdateSettings`, `setLibraryUpdateSettings(input: {intervalHours, skipCompleted, skipUnseen, skipNotStarted, notify})`. An interval other than 0, 6, 12 or 24 is `BAD_REQUEST`;
- `animeUpdates(limit, offset)` → `{ hasMore, items { anime, episode, fetchedAt } }` and `mangaUpdates(limit, offset)` → `{ hasMore, items { manga, chapter, fetchedAt } }` (upstream's `MangaType` and `ChapterType` with the user's data);
- `updateMarks(kind)` → `[{ entryId, newCount, markedAt }]` (library entries only) and `clearUpdateMarks(input: {kind, entryIds})` (null: all) → `{ cleared }`.

Tests: `api/src/test/kotlin/madoyomi/api/LibraryUpdateApiTest.kt` (8: the anime diff through a fake source with the counts, the mark, the feed's first-fetch rule and a re-listed episode; a Seasons entry; the manga diff through upstream's code with a fake manga source and a failure; the category rule for both kinds and one category's check; the skip settings; one entry per source with the stagger and the parallel limit; stop, adding to a running check and the subscription; the settings file, their checks and a 400 ms real schedule), and `MigrationsFromEmptyDbTest` (M0073's tables and foreign keys).

Not changed: upstream's per-user library update settings (`excludeUnreadChapters`, `excludeNotStarted`, `excludeCompleted`, `globalUpdateInterval`) stay in its config but nothing Madoyomi runs reads them. `CATEGORY.include_in_download` is used `LibraryUpdater.onNewItems` hands a manga's new chapter ids to the download queue.

### Manga downloads

The download queue is Madoyomi's, for both kinds, in place of upstream's manga-only `DownloadManager`/`Downloader` (`suwayomi.tachidesk.manga.impl.download`). Upstream's kept its queue in memory and saved it only as an unordered set of chapter ids in SharedPreferences, so a restart lost the order, the failures and a pause. It paused only as a whole, retried at once with no stagger, and staged a chapter's pages in the reader's page cache folder, which the cap trims. What Madoyomi reuses: upstream's page list for a download (`getChapterDownloadReadyById`), its CBZ path (`getChapterCbzPath`) and reader for downloaded chapters (`Page.getPageImage` → `ChapterDownloadHelper` → `ArchiveProvider`), its `ComicInfo.xml` (`createComicInfoFile`), its marks (`CHAPTER.is_downloaded`, `CHAPTER_USER.is_downloaded`/`is_download_requested`), its delete (`Chapter.deleteDownloadedChapters`) and its category rule (`Manga.isInIncludedDownloadCategory`). Three edits:

| File | Change |
|---|---|
| `core/.../server/ServerSetup.kt` (`ApplicationDirs.mangaDownloadsRoot`) | `<downloads>/manga` (upstream: `<downloads>/mangas`), beside `<downloads>/anime` (one `// Madoyomi:` comment). Upstream's reader, delete and `InitialMigration` all go through this property. |
| `core/.../manga/impl/Chapter.kt` (`downloadNewChapters`) | returns at once behind `MadoyomiFeatures.upstreamAutoDownloadEnabled` (false; one `// Madoyomi:` comment). Upstream queued new chapters into its own `DownloadManager` on every chapter list fetch (a details page opened too), when the user's `autoDownloadNewChapters` was on. Madoyomi queues them after a library update check only (`MangaDownloads.autoDownload`), so there is one queue and one rule. |
| `core/.../manga/impl/download/fileProvider/impl/ArchiveProvider.kt` (`getImageInputStream`) | **Live check:** the page's stream (`ZipEntryStream`, a `FilterInputStream`) owns its `ZipFile` and closes it on `close()` (two `// Madoyomi:` comments). Upstream opened a `ZipFile` per page and returned only the entry's stream, so the archive was unreachable at once: commons-compress's `ZipFile.finalize()` could close the channel while the page was still being sent (`ClosedChannelException`, seen once in the reader), and every page left a file handle open until GC. This is the reader's path for every downloaded chapter (`Page.getPageImage` → `ChapterDownloadHelper.getImage`). |

`madoyomi.core.downloads`:
- `DownloadTable` (M0074) and `DownloadItemState` (`QUEUED`, `DOWNLOADING`, `PAUSED`, `FAILED`, `DONE`).
- `DownloadQueue`: the queue for both kinds, in the table, so it survives a restart. A row left `DOWNLOADING` by a stop is queued again at `start()` (from `madoyomi.api.Main`).
  - **Limits:** at most `DownloadSettings.maxParallel` downloads at once (3), `perSource` of one source (1), and two starts of one source `staggerMs` (1 s) apart.
  - **States:** a failure is retried after 5 s, then 30 s, then `FAILED` with its error. `pause`/`resume`/`retry`/`cancel` take ids or a whole kind; a cancelled item's staged parts are discarded.
  - **Order:** `reorder` moves an item among its kind's unfinished ones, keeping the other kind's positions. Done rows are the completed list (`clearCompleted`; `forgetDone` when their files are deleted).
  - **Changes:** `changes` (a counter) drives the subscription.
- `ItemDownloader`, one per kind; `MangaChapterDownloader` for manga:
  - the page list through upstream's `getChapterDownloadReadyById`;
  - the pages staged in `<downloads>/.partial/manga/<chapterId>/` (outside the page cache), one at a time. A page staged by an earlier attempt is kept, and one in the reader's page cache is copied. The others go through `PageImages.get(…, counted = false)`: the source's client and rate limits, the expired-URL retry, `SOURCE_FAILED`, not counted towards the cache's cap;
  - a `.page-count` file wipes what was staged for a page list of another length;
  - then upstream's `ComicInfo.xml`, the CBZ, upstream's marks set as its downloader does, and the staging folder deleted.
- `CbzWriter`: the staged files in name order, images stored and `ComicInfo.xml` deflated, one fixed entry time, written as `<name>.cbz.part` and moved over the target.
- `MangaDownloads`:
  - **`enqueue`:** in reading order. Downloaded chapters are only marked as the user's (upstream's rule); the Local source is left out.
  - **`delete`:** cancels unfinished items, then upstream's `Chapter.deleteDownloadedChapters`.
  - **`autoDownload`:** called through `LibraryUpdater.onNewItems` with the new chapter ids of a checked manga, when the setting is on and upstream's `includeInDownload` rule lets the entry in.
- `DownloadSettings`/`DownloadSettingsStore`: `<data dir>/downloads.json` (`maxParallel` 1–10, `perSource` 1–3, `autoDownload` false).

Madoyomi code changed with it:
- `PageCache.image` and `PageImages.get` take `counted` (a download's staging isn't the cache's), and `PageCache.cached` gives a page's file;
- `LibraryUpdater` gives the new chapter ids to `onNewItems` (`newItemIds`).

GraphQL (`madoyomi.api.graphql.DownloadGraphQL`), named apart from upstream's:
- queries: `downloadQueue` → `DownloadQueueType` (`items`: the unfinished `DownloadItemType`s of both kinds in order; `mangaDone`, `animeDone`, `lastFinishedAt`), `completedDownloads(kind, limit, offset)` → `{ items, hasMore }` (the last finished first) and `downloadSettings` → `{ maxParallel, perSource, autoDownload, path }`;
- mutations: `enqueueDownloads(input: {kind, itemIds})` (see "Anime downloads" for the anime kind), `pauseDownloads`, `resumeDownloads`, `retryDownloads` and `cancelDownloads` (`input: {kind, ids}`, null ids: all of the kind), `reorderDownload(input: {id, to})`, `deleteDownloads(input: {kind, itemIds})`, `clearCompletedDownloads(input: {kind})`, `setDownloadSettings`;
- the `downloadsChanged` subscription: the queue at once, then on every change, at most every 250 ms.

Tests: `api/src/test/kotlin/madoyomi/api/DownloadQueueApiTest.kt` (9, with a fake downloader):
- the order, the global and per-source limits and the stagger;
- retries, `FAILED` and Retry; pause, resume and cancel;
- reorder; the queue across a stop and start of the workers;
- what enqueue keeps, requeues and skips (and the cascade);
- the settings; the subscription;
- the automatic downloads after a library update, by setting and category.

`api/src/test/kotlin/madoyomi/api/MangaDownloadApiTest.kt` (3, the real downloader against a loopback image host):
- the CBZ with `ComicInfo.xml` at upstream's path, staged outside the page cache, then read through upstream's reader path with the host refusing everything;
- a page in the reader's cache not fetched again, and a retry going on from the staged pages;
- delete and clear.

`core/src/test/kotlin/madoyomi/core/downloads/CbzWriterTest.kt` (2). `core/src/test/kotlin/madoyomi/core/downloads/CbzPageStreamTest.kt` (1): a 4 MB stored page read through `ArchiveProvider` across forced GC and finalization (`ClosedChannelException` on upstream's code). `MigrationsFromEmptyDbTest` checks M0074's table and foreign keys.

Not changed:
- Upstream's `DownloadManager`, `Downloader` and their GraphQL (`enqueueChapterDownloads`, `dequeueChapterDownloads`, `startDownloader`, `stopDownloader`, `clearDownloader`, `reorderChapterDownload(s)`, `downloadStatus`, `downloadChanged`, `downloadStatusChanged`) stay, but the app doesn't use them. `DownloadManager.restoreAndResumeDownloads` still runs at startup, on its own empty queue.
- Upstream's `deleteDownloadedChapter(s)` still works, but leaves Madoyomi's completed row (it shows until Clear list); the app uses `deleteDownloads`.
- `serverConfig.downloadAsCbz`, `downloadConversions`, `downloadsPath` and the per-user `autoDownloadNewChapters`/`excludeEntryWithUnreadChapters`/`autoDownloadNewChaptersLimit` aren't read by Madoyomi's queue: it always writes CBZ, without conversions, in the data dir's `downloads` (the user's decision).
- `Migration.kt`'s `InitialMigration` (`migrateMangaDownloadDir`) runs once per data dir, at its first start. It moves the folders of `downloads/` into `downloads/manga` only when that folder doesn't exist, so a fresh data dir is left alone; it is never run again for `downloads/anime`.

### Anime downloads

**No upstream file was edited**: episodes join the queue as anime's `ItemDownloader`, and everything else is Madoyomi code (`madoyomi.core.downloads`, `madoyomi.core.library.LibraryUpdater`, `madoyomi.core.player.PlaybackSessions`) plus the `M0075_AnimeDownloads` migration in upstream's migration package. The anime folder is `<downloads>/anime`, computed in `AnimeDownloads` (upstream's `ApplicationDirs` isn't touched again).

`madoyomi.core.downloads`:
- `AnimeEpisodeDownloader` (anime's `ItemDownloader`):
  - **the video**, resolved right before the download through the extension as Play does (`AnimeVideos.fetchEpisodeVideos`, sorted by the player settings' preferred server and quality, else the source's order): the first video of the first hoster that has one (a lazy hoster loaded in its turn), `resolveVideo` when it isn't `initialized`;
  - **its kind** from the first answer (the response's first KiB and `Content-Type`): an HLS playlist or a DASH manifest goes to `StreamDownloads`, anything else is a file streamed with the video's headers over the source's own OkHttp client (no call timeout, 60 s read timeout);
  - **a direct file** is staged as `<downloads>/.partial/anime/<episodeId>/video.part` with `state.json` (which video, its extension and size) and resumed with `Range: bytes=<staged>-` when the server answers 206 for the same video and size, else started over. Its extension comes from its signature (Matroska, MP4, TS), then the URL, then the type;
  - **subtitles and external audio tracks**: each subtitle track fetched with the same headers and saved beside the file (one that fails, or is itself a playlist, is left out and logged); each external audio track (KickAssAnime serves its audio apart from a video-only stream, found by the live check) downloaded as the video is and saved beside it as `<episode>.<lang>.audio.<ext>` (one that fails fails the download). `play` gives them to mpv as its external tracks;
  - progress: `BYTES` for a file (thousandths with the size), `SECONDS` for ffmpeg, `SEGMENTS` for yt-dlp.
- `StreamDownloads`: ffmpeg first (`-user_agent`, one `-headers` block with CRLF between the headers, which its HLS demuxer sends with every segment and key; `-c copy -f matroska`; `-progress pipe:1` for the time, `Duration:` from its log for the total), with the HLS demuxer's `-allowed_extensions ALL` and, where this ffmpeg has them (asked once with `-h demuxer=hls`), `-allowed_segment_extensions ALL` and `-extension_picky 0`, so segments disguised as `.jpg`/`.png` pass. When ffmpeg is missing or fails, yt-dlp (`--ignore-config --force-generic-extractor`, one `--add-header` each, `--hls-use-mpegts`, a progress template). When both fail, the error names both. Neither resumes.
- `DownloadTools`: finds ffmpeg and yt-dlp as `MpvLocator` finds mpv (the download settings' path, the system property `madoyomi.downloads.ffmpeg`/`.ytdlp`, the env `MADOYOMI_FFMPEG`/`MADOYOMI_YTDLP` that the shell sets from `tools/bin/<tool>/<tool>.exe` in dev, then PATH) and checks their version. `ProcessArgs` quotes arguments for Windows by the MSVCRT rules (Java's `ProcessBuilder` doesn't escape a `"`, and header values carry quotes); `ToolProcess` runs a tool with its output read line by line, the API token taken out of its environment, and the process tree killed on pause, cancel or stop, or after 120 s without a line.
- `AnimeDownloads`: `enqueue` (watching order; downloaded episodes left out), `place` (moves the staged files to `downloads/anime/<source (LANG)>/<title>/<scanlator_><episode>.<ext>`, the subtitles as `<episode>.<lang>.<ext>`, upstream's `SafePath.buildValidFilename`; two episodes of the same name get the id), `delete` (cancels, deletes the files and empty folders, the record and the completed row), `autoDownload` (through `LibraryUpdater.onNewItems`, upstream's `includeInDownload` rule on the anime categories), `play` (the file in `PlaybackSessions.launch`, with the subtitle files as the external tracks, no headers and no retry; `resume` reckons the start as the app does), `counts` (a Seasons entry's seasons included).
- `EpisodeDownloadTable` (M0075): the downloaded episodes, kept apart from the queue's completed list (which Clear list empties while the files stay).
- `DownloadTable` gets `progressUnit` and `totalBytes` (M0075); `ItemDownloader.download` reports a `DownloadProgress`.
- `DownloadSettings` gets `ffmpegPath` and `ytDlpPath` ("" by default: looked for).

Madoyomi code changed with it:
- `LibraryUpdater` gives an anime entry's new episode ids (a Seasons entry's, from its seasons) to `onNewItems`, which `DownloadQueue.start` points at the kind's `autoDownload`;
- `PlaybackSessions` logs a local file as such (not as "(no host)").

GraphQL (`DownloadGraphQL`):
- `enqueueDownloads` and `deleteDownloads` take the anime kind;
- `DownloadItemType.progressUnit` (`PAGES`, `BYTES`, `SECONDS`, `SEGMENTS`) and `totalBytes`;
- `downloadSettings`/`setDownloadSettings`: `ffmpegPath`, `ytDlpPath`; the query `downloadTools(ffmpegPath, ytDlpPath)` → `[DownloadToolType]` (`tool`, `ok`, `path`, `origin`, `version`, `message`);
- `playDownloadedEpisode(input: {episodeId, startSeconds, resume})` → `PlayEpisodePayload` (`NOT_FOUND` when it isn't downloaded or the file is gone);
- `EpisodeType.downloaded` and `AnimeType.downloadCount` (data loaders `EpisodeDownloadedDataLoader`, `AnimeDownloadCountDataLoader`).

Tests: `api/src/test/kotlin/madoyomi/api/AnimeDownloadApiTest.kt` (6, the real downloader against a loopback host that refuses a request without the video's headers):
- a direct file with its subtitle and its audio track beside it, the marks and the counts; resumed with a `Range` request from a staged part;
- played from its file by a fake mpv (the path after `--`, no headers), then deleted (`NOT_FOUND` after);
- an HLS stream made by ffmpeg with its segments named `.jpg`, and an audio-only HLS track, through ffmpeg with the headers (quotes, commas, semicolons) on every segment; with ffmpeg made to fail, through yt-dlp; both failing, FAILED with both reasons (these skip without ffmpeg or yt-dlp);
- the automatic downloads by setting and category.

`core/src/test/kotlin/madoyomi/core/downloads/DownloadToolsTest.kt` (5): where the tools are found, the Windows quoting, both tools' commands, yt-dlp's progress, the file and subtitle extensions and a tool's error line. `MigrationsFromEmptyDbTest` checks M0075's columns, table and foreign keys.

### Backups

Madoyomi's backups are its own `.madoyomi-backup` files (gzipped protobuf) of both kinds, in place of upstream's manga-only `.tachibk`; upstream's backup code is reused for the manga part, with one edit:

| File | Change |
|---|---|
| `core/.../manga/impl/backup/proto/ProtoBackupExport.kt` (`scheduleAutomatedBackupTask`) | returns right after descheduling, behind `MadoyomiFeatures.upstreamAutomatedBackupEnabled` (false; one `// Madoyomi:` comment). Upstream wrote a manga-only `app.madoyomi.auto.<user>_<date>.tachibk` into `<data dir>/backups` every `server.backupInterval` days (1 by default, kept `backupTTL` = 14 days) from the server's start, and again a minute after each start when the last one was older than the interval. Madoyomi's schedule (off by default, the user's decision) covers both kinds, so upstream's is off (the user's decision). Its `.tachibk` files already on disk stay and are listed (and can be restored: their manga part). Upstream's `createBackup`, `restoreBackup`, `validateBackup` and `restoreStatus` stay in the schema and work as before for manga; the app doesn't use them. |

`madoyomi.core.backup` (no new table, no migration):
- `MadoyomiBackup` and its models: upstream's `Backup` fields with their numbers (1 `backupManga`, 2 `backupCategories`, 101 `backupSources`, 9000 `meta`; upstream's `BackupManga`, `BackupCategory`, `BackupSource` as they are, so its handlers write and read them) and Madoyomi's from 20000 up, which Mihon (104–106), Aniyomi (500–506), TachiyomiSY (600s) and Suwayomi (9000–9002) don't use: `info` (format version, app version, time, automatic), `anime` (library entries and their seasons, with episodes, marks, positions, `ANIME_HISTORY`'s time and category names), `animeCategories` (with Default's flags and every category's meta), `animeSources`, `mangaCategoryFlags` (the "new entries", update and download flags that upstream's `BackupCategory` doesn't carry), `repos`, `trustedCertificates` (USER rows), `extensions` (package, kind, repo, version, signature, their source ids; not the APKs), `sourcePreferences` (each installed source's `SharedPreferences` as stored, keys starting `__PRIVATE_` or `__APP_STATE_` left out, as Mihon does) and `settingsFiles` (`player-settings.json`, `reader-settings.json`, `library-update.json`, `downloads.json`, `backup-settings.json`, their schedule anchors left out). Upstream's 9001/9002 (server and user settings) are left out: they hold the SOCKS proxy, login and database passwords in plain text, and settings Madoyomi manages itself.
- `MadoyomiBackupExport.create`: the manga part through upstream's `BackupMangaHandler`, `BackupCategoryHandler`, `BackupSourceHandler` and `BackupGlobalMetaHandler` with `BackupFlags.DEFAULT` minus the server and user settings; the rest from Madoyomi's tables and files.
- `BackupFiles`: encode/decode (a file that isn't gzip, isn't protobuf or holds no library is `BAD_REQUEST`; one without `info` but with manga is a `.tachibk`: `BackupFormat.TACHIYOMI`), the folder (upstream's `ApplicationDirs.automatedBackupRoot`, `<data dir>/backups`), the list (`.madoyomi-backup`, `.tachibk`, `.proto.gz`), `write` (`Madoyomi_<date>` or `Madoyomi_auto_<date>`, written as a `.part` then moved), `import` (a picked file, checked first, kept under its name with " (2)" when taken), `delete`, `prune` (the scheduled ones beyond the newest N).
- `BackupSettings` / `BackupSettingsStore` (`<data dir>/backup-settings.json`: the interval 0/24/168 h, keep 1–50 (7), the anchor) and `BackupScheduler` (upstream's `HAScheduler`, as `LibraryUpdateScheduler`; `MADOYOMI_BACKUP_INTERVAL_SECONDS` or `madoyomi.backupIntervalSeconds` replaces the interval while it is on), started from `madoyomi.api.Main`.
- `MadoyomiBackupReport`: what a file holds checked against this install (counts, the repos not added, the certificates not trusted, the extensions not installed and whether a repo lists them, the sources the entries need that aren't installed and which backup extension provides them).
- `MadoyomiBackupRestore`: one restore at a time in the background with a `StateFlow` status. The order: the settings files (each store reloaded, the schedules rescheduled), the repos picked (`RepoService.add`, which trusts the repo's key), the certificates picked (`SignatureTrust.trust`), the extensions picked (`ExtensionActions.install`, when an added repo lists them), the sources' preferences (through `SharedPreferences`, so a loaded extension sees them), then the library: the anime categories (by name, ignoring case) and entries (by source and URL, parents before seasons; episodes by URL: merge keeps a mark set on either side and the position of the newer watch, history the later time), then the manga part through upstream's `ProtoBackupImport.restoreLegacy` (a re-encoded upstream `Backup`, its progress followed with `getRestoreState`) and the category flags. Replace first takes out of the library the entries the backup doesn't have (both kinds), deletes the categories it doesn't have (upstream's `Category.removeCategory` for manga), resets the backup's entries' marks, positions and memberships and clears all history, so the merge that follows gives the backup's state. Upstream's manga restore sets a restored manga's categories to the backup's even in a merge (its `restoreMangaCategoryData`).

GraphQL (`madoyomi.api.graphql.BackupGraphQL`), named apart from upstream's: `madoyomiBackups`, `madoyomiBackupSettings`, `validateMadoyomiBackup(name)`, `madoyomiRestoreStatus`; `createMadoyomiBackup`, `deleteMadoyomiBackup(input: {name})`, `setMadoyomiBackupSettings(input: {intervalHours, keep})`, `restoreMadoyomiBackup(input: {name, mode, library, settings, repos, certificates, extensions})` (only the repos, certificates and extensions listed are added, trusted or installed: the app's trust prompt); the `madoyomiRestoreStatusChanged` subscription. REST (`madoyomi.api.BackupHttp`): `GET /api/v1/madoyomi/backups/{name}` (the bytes) and `POST /api/v1/madoyomi/backups?name=` (a picked file, `BAD_REQUEST` when it isn't a backup).

Tests: `api/src/test/kotlin/madoyomi/api/BackupRestoreApiTest.kt` (6): the round trip (both kinds with a Seasons entry, categories with their meta and flags, marks, positions, history, a manga's reader meta, a repo with MangaPill installed from a loopback repo, a USER certificate, settings files and a source preference, a private one left out) into a fresh database and fresh settings files, compared field by field, the extension reinstalled and its source live; merge against replace; an extension no repo lists (reported, the restore goes on); files that aren't backups and bad names (`BAD_REQUEST`, `NOT_FOUND`); the schedule's pruning and delete; a `.tachibk` made by upstream's export restoring its manga.

### Logs, diagnostics and the downloads folder

The backend's own log is upstream's as it was (`AndroidCompat/Config/.../Logging.kt`, no new edit): `<data dir>/logs/madoyomi.log`, rolled daily and past `server.maxLogFileSize` (10 MB) into `madoyomi_<date>_<n>.log.gz`, `server.maxLogFiles` days (31) kept and all of them capped at `server.maxLogFolderSize` (100 MB); `BackendLogConfigTest` checks those limits and that a change applies at once. Two upstream files changed for the downloads folder of one's choice:

| File | Change |
|---|---|
| `core/.../server/ServerSetup.kt` (`ApplicationDirs.downloadsRoot`) | `DownloadsFolder.root(dataRoot, serverConfig.downloadsPath.value)`: Madoyomi's folder (`downloads.json`'s `folder`), then upstream's `downloadsPath`, then `<data dir>/downloads` (upstream: `downloadsPath` or the default). Upstream's `downloadsPath` is a `PathSetting(mustExist = true)`, which silently falls back to the default when its folder is missing; Madoyomi's never does (an unplugged drive stays the folder: the queue waits, the reader and the player answer `DOWNLOADS_UNREACHABLE`). One `// Madoyomi:` comment |
| `core/.../server/ServerSetup.kt` (`applicationSetup`, the folders made at startup) | makes `DownloadsFolder.defaultRoot(dataRoot)`, never the chosen folder (upstream: `downloadsRoot`), so a folder that is gone isn't made again empty on the system drive or a re-lettered one. One `// Madoyomi:` comment |
| `core/.../manga/impl/chapter/ChapterForDownload.kt` (`asDownloadReady`) | a chapter marked downloaded whose CBZ isn't found while the downloads folder is gone throws `DOWNLOADS_UNREACHABLE` (`DownloadsFolder.requireReachable()`) instead of upstream's reset (its download marks cleared and its pages fetched from the source), which would have forgotten every download on an unplugged drive the first time one was opened. A file missing from a folder that is there is still upstream's reset. One `// Madoyomi:` comment |

`madoyomi.core.downloads.DownloadsFolder`: the folder, `status()` (path, the default, whether it is there, the last change), `blockReason()` (the queue's `schedule()` starts nothing while the folder moves or is gone, and looks again every 5 s), `requireReachable()`, and `change(path, mode)`: checks (an absolute path; empty or new, Explorer's `desktop.ini`/`Thumbs.db` aside; not inside the old folder nor holding it; not inside the data dir unless it is the default; nothing `QUEUED` or `DOWNLOADING`; `MOVE` only from a folder that is there; writable), then in the background `MOVE` (one drive: each top folder renamed, put back if one fails; across drives: every file copied, empty folders too, the setting switched, the old files deleted, a failed copy removing the copies and changing nothing) or `START_EMPTY` (the setting switched; `CHAPTER.is_downloaded` and `CHAPTER_USER.is_downloaded` cleared, `EPISODE_DOWNLOAD` emptied and the completed `DOWNLOAD` rows deleted; the old files stay). `AnimeDownloads.play` answers `DOWNLOADS_UNREACHABLE` before "the file is gone" when the whole folder is. `ErrorCode.DOWNLOADS_UNREACHABLE` (503, `details.path`).

`madoyomi.core.logs.LogFiles`: numbered logs (`mpv.log`, `mpv.1.log` …). `PlaybackSessions` rotates mpv's log at each launch, keeping `MPV_LOGS_KEPT` (10), and starts each with a `# Madoyomi playback <id> at <time>: <mpv>` line; mpv's output is appended after it (`Redirect.appendTo`), still at `--msg-level=all=warn`, so `VideoChoices`' `MpvLog` reads the current session's as before. (Not `--log-file`: mpv writes that at `-v -v` at least, with the source's headers and cookies.)

`madoyomi.core.diagnostics.Diagnostics`: Export logs' `diagnostics.json`: the backend's, Java's and the OS's versions, the repos, the installed extensions, the downloads folder, server.conf through upstream's `GlobalConfigManager.getRedactedConfig` (every setting upstream doesn't mark `privacySafe` shown as `[REDACTED]`), Madoyomi's five settings files, and the library's titles with their sources only with `includeLibrary`.

GraphQL: `downloadsFolder`, `setDownloadsFolder(input: {path, mode: MOVE | START_EMPTY})`, `DownloadQueueType.heldBecause`, `diagnostics(includeLibrary)` (a JSON string). Tests: `MangaDownloadApiTest` (4 more: a move on one drive and back across drives with the reader offline and a new download following it, an empty start, a folder that is gone, the checks), `DiagnosticsApiTest` (2), `PlaybackApiTest` (1 more: mpv's logs per session, 10 kept), `LogFilesTest` (4), `BackendLogConfigTest` (3).

### New Madoyomi code

| Path | What |
|---|---|
| `core/src/main/kotlin/madoyomi/core/MadoyomiFeatures.kt` | feature switches + loopback/CORS constants referenced by upstream files |
| `core/src/main/kotlin/madoyomi/core/MadoyomiBuildInfo.kt` | public `name`/`version` from the generated `BuildConfig`, which upstream's buildconfig plugin makes `internal` to `core` (used by `api`'s `/health`) |
| `ext-host/src/main/kotlin/madoyomi/exthost/SourceRegistry.kt` | callback hook that lets `ext-host` ask `core`'s source cache to drop its sources |
| `api/src/main/kotlin/madoyomi/api/Main.kt` | entry point `madoyomi.api.MainKt` |
| `api/src/main/kotlin/madoyomi/api/SidecarHttp.kt` | `GET /health`, the token filter and the WebSocket upgrade check |
| `api/src/main/kotlin/madoyomi/api/SidecarToken.kt` | token from `MADOYOMI_TOKEN`, constant-time compare |
| `ext-anime/src/main/kotlin/eu/kanade/tachiyomi/animesource/**`, `ext-anime/src/main/kotlin/mihon/core/common/extensions/JsonObject.kt` | Aniyomi source API, ported from an early spike (Apache-2.0, see `ext-anime/NOTICE.md`). |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionKind.kt` | `ExtensionKind { MANGA, ANIME }`, stored by name in `EXTENSIONSTORE.type` and `EXTENSION.type` |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexFormat.kt` | `RepoIndexFormat { AUTO, JSON, PROTOBUF }` (`EXTENSIONSTORE.format`) |
| `core/src/main/kotlin/madoyomi/core/extension/TrustedSignatureTable.kt` | `TRUSTED_SIGNATURE` table + `SignatureTrustOrigin { USER, STORE }` |
| `core/src/main/kotlin/madoyomi/core/anime/model/table/*.kt` | `AnimeSourceTable`, `AnimeTable`, `EpisodeTable`, `AnimeHistoryTable`, `AnimeCategoryTable` (the M0071 columns and unique indexes; `AnimeCategoryTable` is the category list and `AnimeCategoryAnimeTable` the join, M0072) |
| `core/src/main/kotlin/madoyomi/core/database/MadoyomiMigration.kt` | marker interface for Madoyomi migrations (see "Database migrations") |
| `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0069_MadoyomiExtensionKinds.kt`, `M0070_AnimeTables.kt`, `M0071_AnimeBrowse.kt`, `M0072_AnimeCategories.kt`, `M0073_LibraryUpdateMarks.kt`, `M0074_Downloads.kt`, `M0075_AnimeDownloads.kt` | Madoyomi migrations. New files, but in upstream's migration package, because the runner only scans that package. |
| `core/src/test/kotlin/madoyomi/core/database/MigrationsFromEmptyDbTest.kt` | the migration chain on empty and pre-Madoyomi H2 databases, re-runs, schema and Exposed-table checks |
| `core/src/main/kotlin/madoyomi/core/repo/ExtensionListing.kt`, `RepoStoreMeta.kt` | the common listing model (`ExtensionListing`, `ListingSource`), repo metadata, `FetchedRepo`, signing-key normalisation, the pkg/kind check |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexParser.kt` | `RepoIndexParser` + `ParsedIndex` (v2 store, legacy `repo.json`, legacy `index.min.json`), gzip sniff (64 MiB cap) |
| `core/src/main/kotlin/madoyomi/core/repo/JsonIndexParser.kt`, `ProtobufIndexParser.kt` | the two parsers over upstream's DTOs |
| `core/src/main/kotlin/madoyomi/core/repo/AnimeNetworkExtensionStore.kt` | Aniyomi's `index.pb` schema (reverse-engineered from the wire; the schema is [spikes/index-pb/index-pb.proto](../spikes/index-pb/index-pb.proto)) |
| `core/src/main/kotlin/madoyomi/core/repo/RepoIndexFetcher.kt` | format detection/override and redirects over a `RepoHttp` (the default uses `NetworkHelper`'s client) |
| `core/src/main/kotlin/madoyomi/core/repo/RepoService.kt` | repo add/remove/refresh, trust and the last-good cache (see "Repo manager" above) |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionUnloader.kt` | drops a loaded extension's sources and class loader (and, its load permit; jar path from `ExtensionFiles`). It also stops the extension's threads (`ExtensionThreads`), evicts the shared OkHttp pool's idle connections, and drops a manga source's cached preference screen and filter list |
| `core/src/main/kotlin/madoyomi/core/error/MadoyomiException.kt` | `MadoyomiException(code, message, details)` + `ErrorCode`, with `SIGNATURE_INVALID`, `SIGNATURE_CHANGED`, `UNTRUSTED`, `PACKAGE_MISMATCH`, `INSTALL_FAILED`, an HTTP status per code and `SOURCE_FAILED`, `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL` (see "API") |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionKind.kt` | per kind: `feature`, `classKey`, `nsfwKey`, `torrentKey`, `labelPrefix`, `libRange` (manga 1.3–1.6, anime 12–16), `dirName`; shared `NAME_KEY`/`LIB_KEY`/`CONTENT_WARNING_KEY`; `fromFeatures` |
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
| `core/.../madoyomi/core/repo/RepoService.kt`, `extension/ExtensionLifecycle.kt` | `RepoService.refreshed` (one `RepoRefreshResult` per refreshed repo, for `extensionStoreRefreshed`); `refreshedSubscribers`/`progressSubscribers` (subscriber counts, so tests can wait for a subscription); `ExtensionLoadGate.clear()` is public for `api`'s tests |
| `api/src/main/kotlin/madoyomi/api/graphql/*`, `api/src/main/resources/META-INF/services/madoyomi.core.graphql.GraphQLSchemaContribution` | Madoyomi's GraphQL operations and types (see "API") and their registration |
| `api/src/test/kotlin/madoyomi/api/*` | `ApiGraphQLTest` (the production `GraphQL` object, a scratch H2 per test, a loopback `HttpServer` serving `core`'s `TestRepo` fixtures and the real APKs to the production network stack), `ExtensionApiIntegrationTest` (repos, install, trust, update, uninstall and both subscriptions for both kinds), `ErrorModelTest`, `SidecarHttpTest` (token gate, `/health`, WebSocket upgrade, REST error bodies on a bare Javalin) |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeGetSource.kt`, `AnimeExtensionLoader.kt`, `AnimeSourceAccess.kt` | the anime source cache (registered with `SourceRegistry`), instantiation and `ANIME_SOURCE` reconcile, and why a source can't be used (see "Anime sources") |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionLinkCheck.kt` | static link check of an extension jar: every class, method and field it uses from outside itself is resolved over the host's class files (read as bytes, nothing loaded), reporting missing classes, missing members and android-jar `"Stub!"` bodies. Tests: `core/src/test/kotlin/madoyomi/core/extension/ExtensionLinkCheckTest.kt` |
| `core/.../madoyomi/core/error/MadoyomiException.kt` | `ErrorCode.LOAD_FAILED` (500); `EXTENSION_INCOMPATIBLE` (501) |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionCompatLog.kt`, `ExtensionCompat.kt` | the compat log and `ExtensionIncompatibility` (which `Throwable`s mean an API gap); the install-time link check and its warnings (see "Anime sources") |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeSourceCalls.kt` | the wrapper around every anime source call: API gaps become `EXTENSION_INCOMPATIBLE` and go into the compat log |
| `ext-host/src/main/kotlin/madoyomi/exthost/ExtensionClassLoadHook.kt` | the callback `ChildFirstURLClassLoader` reports classes found nowhere to (`core`'s compat log registers itself) |
| `ext-host/.../madoyomi/exthost/ExtensionLinkCheck.kt` | `Report.activityClasses` (the jar's `android.app.Activity` subclasses), `onlyFromActivities(issue)` and `reachable` |
| `tools/compat-test/select-top.ps1`, `scan.ps1`, `lib/Protobuf.ps1`, `top20.txt`, `manga-sample.txt`; `core/src/test/kotlin/madoyomi/core/extension/CompatScanTest.kt`; `tools/compat-test/reports/compat-scan.md` | the top-20/manga-sample selection (jsDelivr hits; GitHub release-asset downloads for Keiyoushi; a PowerShell `index.pb` reader), the static scan over them (`MADOYOMI_COMPAT_SCAN=1`) and its generated report. APKs go to the gitignored `tools/compat-test/apks/` |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeEntries.kt`, `AnimeBrowse.kt`, `AnimeDetails.kt`, `AnimeVideos.kt`, `AnimeFilters.kt`, `AnimeSourcePreferences.kt` | the anime services: row ↔ model mapping and the browse upsert, browse, details with episodes or seasons, hosters and videos, filter and preference mapping (see "Anime sources") |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeThumbnails.kt`, `core/src/main/kotlin/madoyomi/core/graphql/ExtensionCompatIssueType.kt` | anime thumbnails (fetch, refresh, cache), and the GraphQL shape of a compat issue, and `GraphQLSchemaContribution.dataLoaders`, `MadoyomiGraphQLErrors.errorOf` (a classified error for partial results), the GraphQL names of `ExtensionCompatLog`'s enums, and the thumbnail cache drop in `AnimeEntries.writeDetails` |
| `api/src/main/kotlin/madoyomi/api/AnimeHttp.kt`, `api/src/main/kotlin/madoyomi/api/graphql/{AnimeTypes,AnimeVideoTypes,AnimeQuery,EpisodeQuery,AnimeMutation,AnimeDataLoaders}.kt` | the anime REST route and GraphQL operations, types and data loaders (see "Anime sources"). `api` now depends on `ext-anime` |
| `api/src/test/kotlin/madoyomi/api/Anime*Test.kt` | `AnimeApiTest` (fake sources with installed `EXTENSION`/`ANIME_SOURCE` rows), `AnimeBrowseApiTest`, `AnimeDetailsApiTest`, `AnimeVideosApiTest`, `AnimeExtensionApiTest` (AnimePahe installed from the loopback repo: preferences, compat issues; kickassanime's filters), `AnimeThumbnailTest` (a bare Javalin and a loopback image host) |
| `tools/compat-test/run-compat.ps1`, `tools/compat-test/triage.txt`, `tools/compat-test/reports/anime-compat.md` | the compat harness: the top 20 on the real sidecar jar (install → popular → details + episodes → videos → resolve), process health per extension (`jcmd`/`jstat`, JVM aborts, uninstall leak check), the hand-written triage, and the generated report. Raw results and server logs go to the gitignored `tools/compat-test/out/` |
| `core/src/main/kotlin/madoyomi/core/extension/ExtensionThreads.kt` | stops the threads an extension left running when it is unloaded: shuts down its own thread pools, interrupts the rest, closes the sockets of one still blocked, and logs what keeps running. It also clears every live thread's inherited access-control context (see "Anime sources", "Fix round") |
| `tools/compat-test/lib/HprofLoaderRoots.java`, `run-compat.ps1` additions | a dependency-free HPROF reader (JDK 21 single-file program) that prints the shortest strong GC-root path to each live `ChildFirstURLClassLoader`. The harness takes a heap dump when extension loaders are still alive after the uninstall and an idle wait (`-SettleSec`, `-NoHeapDump`), and puts the nearest holder of each in the report. The harness also gained a preference round-trip and a filtered search per extension, and `-Kind Manga` (the Keiyoushi sample through upstream's manga API into `tools/compat-test/reports/manga-compat.md`) |
| `core/src/test/kotlin/madoyomi/core/extension/{ExtensionThreadsTest,Dex2jarRepairCompatTest,GcAssert}.kt`, `core/src/test/kotlin/madoyomi/core/anime/ParsedAnimeHttpSourceTest.kt`, `api/src/test/kotlin/madoyomi/api/AnimeRoundTripApiTest.kt`, `AndroidCompat/src/test/kotlin/.../JavaSharedPreferencesTest.kt` | the tests. `ParsedAnimeHttpSourceTest` runs a Jsoup-selector source (no top-20 extension extends `ParsedAnimeHttpSource`) through browse, details and videos against a loopback site. `ExtensionThreadsTest` compiles "extension" classes at test time with `javax.tools` and loads them through a `ChildFirstURLClassLoader`. `Dex2jarRepairCompatTest` covers the four manga APKs. `AnimeRoundTripApiTest` round-trips filters and preferences of real extensions through GraphQL, offline. Leak tests are in `ExtensionLifecycleTest` (an uninstalled anime and manga extension's class loader is collected), `AnimeVideosTest` and `AnimeExtensionLoaderTest` |
| `tools/lib/MadoyomiServer.ps1` | the PowerShell helpers that start the jar as the Tauri shell does and drive its API (steps, GraphQL, WebSocket, install with progress), used by `tools/smoke-api.ps1` |
| `api/src/main/kotlin/madoyomi/api/graphql/AnimeMutation.kt`, `api/src/test/kotlin/madoyomi/api/AnimeLibraryApiTest.kt` | `updateAnime`/`updateAnimes`, the library flag for anime (see "Library flag"), and their tests: add, remove, the batch, an unknown id, and a browse leaving an in-library entry as it is |
| `core/src/main/kotlin/madoyomi/core/player/{MpvLocator,PlayerDirs,MpvCommand,PlaybackSessions}.kt`, `api/src/main/kotlin/madoyomi/api/graphql/PlaybackGraphQL.kt` | the mpv player: finding mpv and its config dir, the command line and session config file, one playback session at a time with orphan handling, and its GraphQL (see "Playback"). `Main` calls `PlaybackSessions.reapOrphans()` after `applicationSetup()` |
| `core/src/test/kotlin/madoyomi/core/player/*`, `api/src/test/kotlin/madoyomi/api/PlaybackApiTest.kt`, `api/src/test/java/madoyomi/api/FakeMpv.java` | the player's tests (see "Playback") |
| `core/src/main/kotlin/madoyomi/core/player/{MpvIpcClient,PlaybackTracker,EpisodeProgress}.kt` | mpv's JSON IPC client, the progress and watched logic, and the `EPISODE` progress writes (see "Playback") |
| `core/src/test/kotlin/madoyomi/core/player/{MpvIpcClientTest,PlaybackTrackerTest,EpisodeProgressTest}.kt`, `core/src/test/java/madoyomi/core/player/FakeMpvServer.java` | the IPC, progress and watched tests, and a fake mpv on a real named pipe (JNA, test only) |
| `api/src/main/kotlin/madoyomi/api/graphql/AnimeMutation.kt`, `api/src/test/kotlin/madoyomi/api/EpisodeUpdateApiTest.kt` | `updateEpisode`/`updateEpisodes`, the watched and bookmark flags and the position of episodes (see "Playback", "Watched marks"), and their tests: mark and unmark, the position clamped, bookmarks, the batch, an unknown id |
| `core/src/main/kotlin/madoyomi/core/player/VideoChoices.kt`, `core/src/test/kotlin/madoyomi/core/player/VideoChoicesTest.kt` | finding a video again after a refetch, the next video to offer, and what mpv's log says (see "Playback", "Retry on an expired URL") |
| `core/src/main/kotlin/madoyomi/core/player/{PlayerSettings,PlayerLanguages,TrackSelection,VideoPreferences}.kt`, `api/src/main/kotlin/madoyomi/api/graphql/PlayerSettingsGraphQL.kt` | the player settings and their file, the track languages and the track choice, the video order by the preferred server and quality, and their GraphQL (see "Playback", "Player settings") |
| `core/src/test/kotlin/madoyomi/core/player/{PlayerSettingsTest,PlayerLanguagesTest,TrackSelectionTest,VideoPreferencesTest}.kt`, `api/src/test/kotlin/madoyomi/api/PlayerSettingsApiTest.kt` | their tests |
| `core/src/main/kotlin/madoyomi/core/anime/AnimeCategories.kt`, `core/src/main/kotlin/madoyomi/core/anime/model/table/AnimeCategoryTable.kt` (`AnimeCategoryTable`, `AnimeCategoryAnimeTable`), `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0072_AnimeCategories.kt`, `api/src/main/kotlin/madoyomi/api/graphql/AnimeCategoryGraphQL.kt`, `api/src/test/kotlin/madoyomi/api/AnimeCategoryApiTest.kt` | the anime library's categories: the tables (the stub `AnimeCategoryTable` became the list), the rules, the migration, the GraphQL and the data loader, and their tests (see "Library categories"). `AnimeMutation.updateAnimes` and `AnimeType.categories` call them |
| `core/src/main/kotlin/madoyomi/core/library/{AnimeHistory,MangaHistory}.kt`, `api/src/main/kotlin/madoyomi/api/graphql/HistoryGraphQL.kt`, `api/src/test/kotlin/madoyomi/api/HistoryApiTest.kt` | the watch history in `ANIME_HISTORY` and the reading history in upstream's `CHAPTER_USER.last_read_at`: the stamps, the lists with the resume targets, delete and clear, the library counts (`AnimeStatsDataLoader`), and their test (see "History and badges"). `EpisodeProgress.save`, `AnimeMutation.updateEpisodes`, `AnimeDetails.reconcileEpisodes` and `AnimeType` call them |
| `core/src/main/kotlin/madoyomi/core/library/{LibraryUpdater,LibraryUpdateScheduler,LibraryUpdateSettings,LibraryUpdateMarkTables,LibraryUpdatesFeed}.kt`, `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0073_LibraryUpdateMarks.kt`, `api/src/main/kotlin/madoyomi/api/graphql/LibraryUpdateGraphQL.kt`, `api/src/test/kotlin/madoyomi/api/LibraryUpdateApiTest.kt` | the library update check for both kinds, its schedule and settings, the "new" marks, the Updates feed, their GraphQL and test (see "Library updates"). `madoyomi.api.Main` starts the scheduler; `MadoyomiFeatures.upstreamLibraryUpdateScheduleEnabled` turns upstream's schedule off |
| `core/src/main/kotlin/madoyomi/core/downloads/{DownloadTable,DownloadQueue,DownloadSettings,CbzWriter,MangaDownloads}.kt`, `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0074_Downloads.kt`, `api/src/main/kotlin/madoyomi/api/graphql/DownloadGraphQL.kt`, `api/src/test/kotlin/madoyomi/api/{DownloadQueueApiTest,MangaDownloadApiTest}.kt`, `core/src/test/kotlin/madoyomi/core/downloads/CbzWriterTest.kt` | the download queue for both kinds, the manga chapter downloader, the CBZ writer, the settings, the migration, their GraphQL and tests (see "Manga downloads"). `madoyomi.api.Main` starts the queue; `MadoyomiFeatures.upstreamAutoDownloadEnabled` turns upstream's automatic downloads off |
| `core/src/main/kotlin/madoyomi/core/downloads/{AnimeEpisodeDownloader,StreamDownloads,DownloadTools,AnimeDownloads}.kt`, `core/src/main/kotlin/suwayomi/tachidesk/server/database/migration/M0075_AnimeDownloads.kt`, `api/src/test/kotlin/madoyomi/api/AnimeDownloadApiTest.kt`, `core/src/test/kotlin/madoyomi/core/downloads/DownloadToolsTest.kt` | anime downloads: the episode downloader (direct files, HLS and DASH through ffmpeg with yt-dlp as the fallback, subtitles), the tools and their quoting, the downloaded episodes and their playback, the migration and their tests (see "Anime downloads") |
| `core/src/main/kotlin/madoyomi/core/backup/{MadoyomiBackupModels,MadoyomiBackupExport,MadoyomiBackupReport,MadoyomiBackupRestore,BackupFiles,BackupSettings}.kt`, `api/src/main/kotlin/madoyomi/api/graphql/BackupGraphQL.kt`, `api/src/main/kotlin/madoyomi/api/BackupHttp.kt`, `api/src/test/kotlin/madoyomi/api/BackupRestoreApiTest.kt` | `.madoyomi-backup` files: the format, the export, the report, the restore with merge or replace, the files and the schedule, their GraphQL, REST routes and test (see "Backups"). `madoyomi.api.Main` starts the schedule; `MadoyomiFeatures.upstreamAutomatedBackupEnabled` turns upstream's scheduled backup off |
| `core/src/main/kotlin/madoyomi/core/downloads/DownloadsFolder.kt`, `core/src/main/kotlin/madoyomi/core/logs/LogFiles.kt`, `core/src/main/kotlin/madoyomi/core/diagnostics/Diagnostics.kt`, `api/src/main/kotlin/madoyomi/api/graphql/DiagnosticsQuery.kt`, `api/src/test/kotlin/madoyomi/api/DiagnosticsApiTest.kt`, `core/src/test/kotlin/madoyomi/core/logs/{LogFilesTest,BackendLogConfigTest}.kt` | the downloads folder of one's choice, numbered logs (mpv's per session), the diagnostics report for Export logs, and their tests (see "Logs, diagnostics and the downloads folder"). `DownloadSettings.folder`, `DownloadQueue.refresh()` and the hold in `schedule()`, `DownloadGraphQL`'s folder operations and `heldBecause`, and `ErrorCode.DOWNLOADS_UNREACHABLE` came with them |
| `core/src/main/kotlin/madoyomi/core/library/MangaNewEntryCategories.kt`, `api/src/test/kotlin/madoyomi/api/MangaNewEntryCategoriesApiTest.kt` | manga added to the library through upstream's GraphQL go into the categories flagged for new entries, called from upstream's `MangaMutation.updateMangas` (see "Library categories"), and its test |
| `core/src/main/kotlin/madoyomi/core/reader/{ReaderSettings,PageCache,PageImages}.kt`, `api/src/main/kotlin/madoyomi/api/graphql/ReaderGraphQL.kt` | the reader settings and their file, the chapter-page cache (single-flight, retry, errors, cap and eviction), the page fetch with the expired-URL retry and the prefetch, and their GraphQL (see "Manga reader") |
| `core/src/test/kotlin/madoyomi/core/reader/{PageCacheTest,ReaderSettingsTest}.kt`, `api/src/test/kotlin/madoyomi/api/PageImagesApiTest.kt` | their tests |
| `core/src/test/kotlin/madoyomi/core/storage/IdKeyedCacheDirsTest.kt` | the thumbnail and chapter-page caches are in the data dir, and two data dirs share neither (see "Id-keyed caches in the data dir") |
| `core/src/test/kotlin/madoyomi/core/anime/*` | `AnimeBrowseTest`, `AnimeDetailsTest`, `AnimeVideosTest` (scripted fake sources and a scratch H2 per class, `AnimeTestSources.kt`), `AnimeFiltersTest` (every filter of kickassanime, aniwave and hanime from `tools/compat-test/apks/`), `AnimeSourcePreferencesTest` (AnimePahe), and `AnimeServicesLiveTest` (real sites, only with `MADOYOMI_LIVE_SOURCE_TESTS=1`). Tests skip when their APK is missing. Every madoyomi scratch-database test set its database as the explicit default and restore the previous explicit one (an order dependency) |
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
| `M0070_AnimeTables` | `ANIME_SOURCE` (id, name, lang, extension FK, content_warning, base_url), `ANIME` (url, title, artist, author, description, genre, status, thumbnail_url, in_library, initialized, source, added_at, last_fetched_at), `EPISODE` (url, name, episode_number, date_upload, scanlator, watched, bookmark, last_second_seen, total_seconds, source_order, fetched_at, anime FK `CASCADE`), and the stubs `ANIME_HISTORY` (episode FK `CASCADE`, unique; last_seen) and `ANIME_CATEGORY` (category FK, anime FK, both `CASCADE`; unique pair) |
| `M0071_AnimeBrowse` | `ANIME`: `BACKGROUND_URL` (nullable), `UPDATE_STRATEGY` (`ALWAYS_UPDATE`/`ONLY_FETCH_ONCE`, default `ALWAYS_UPDATE`), `FETCH_TYPE` (`Episodes`/`Seasons`, default `Episodes`), `SEASON_NUMBER` (default -1), `MEMO` (JSON text, default `{}`), `PARENT_ID` (nullable FK → `ANIME`, `ON DELETE SET NULL`, declared inline in its `ADD COLUMN IF NOT EXISTS`), `SEASON_SOURCE_ORDER` (default 0). `EPISODE`: `FILLERMARK` (default false), `SUMMARY`, `PREVIEW_URL` (nullable), `MEMO` (default `{}`). Then duplicates are deleted (lowest id kept, as upstream's M0039/M0062) and `CREATE UNIQUE INDEX IF NOT EXISTS` adds `UX_ANIME_SOURCE_URL` on `ANIME (SOURCE, URL)` and `UX_EPISODE_URL_ANIME` on `EPISODE (URL, ANIME)` (portable and idempotent; `ADD CONSTRAINT IF NOT EXISTS` is H2-only) |
| `M0072_AnimeCategories` | Drops M0070's `ANIME_CATEGORY` stub when it has the stub's shape (an `ANIME` column and no `NAME`; it was never written). Creates `ANIME_CATEGORY` (`NAME` varchar 64, `SORT_ORDER` default 0, `IS_DEFAULT` and `IS_DEFAULT_CATEGORY` default false, `INCLUDE_IN_UPDATE` and `INCLUDE_IN_DOWNLOAD` default -1, `META` unlimited text default `{}`) and `ANIME_CATEGORY_ANIME` (`CATEGORY` → `ANIME_CATEGORY`, `ANIME` → `ANIME`, both `CASCADE`; unique `(CATEGORY, ANIME)`, an index on `ANIME`), then inserts the "Default" row (`IS_DEFAULT_CATEGORY`, order 0) when there is none. Idempotent: the stub check, `SchemaUtils.create` (after `currentDialectMetadata.resetCaches()`, as upstream's M0066 does, since Exposed caches the table names it saw before the drop), the guarded insert |

Sources are typed by table (manga in `SOURCE`, anime in `ANIME_SOURCE`), and repos and extensions by the `type` column.

| `M0073_LibraryUpdateMarks` | Creates `ANIME_UPDATE_MARK` (`ANIME` → `ANIME`, `CASCADE`, unique; `NEW_COUNT` default 0; `MARKED_AT` epoch s, default 0) and `MANGA_UPDATE_MARK` (`MANGA` → `MANGA` and `USER_ID` → `USERACCOUNT`, both `CASCADE`, unique `(MANGA, USER_ID)`; `NEW_COUNT`, `MARKED_AT`). Idempotent: `SchemaUtils.create` skips existing tables |
| `M0074_Downloads` | Creates `DOWNLOAD`: `KIND` (`MANGA`/`ANIME`), `MANGA` → `MANGA`, `CHAPTER` → `CHAPTER` (unique), `ANIME` → `ANIME`, `EPISODE` → `EPISODE` (unique), all four nullable and `CASCADE`; `USER_ID` (default 1), `SOURCE_ID`, `STATE` (default `QUEUED`), `POSITION`, `PROGRESS_DONE`, `PROGRESS_TOTAL`, `BYTES`, `TRIES` (default 0), `ERROR` (nullable), `ADDED_AT`, `FINISHED_AT` (epoch ms, default 0). Idempotent: `SchemaUtils.create` skips an existing table |
| `M0075_AnimeDownloads` | `DOWNLOAD`: `PROGRESS_UNIT` (`PAGES`/`BYTES`/`SECONDS`/`SEGMENTS`, default `PAGES`) and `TOTAL_BYTES` (default 0). Creates `EPISODE_DOWNLOAD`: `EPISODE` → `EPISODE` (unique) and `ANIME` → `ANIME` (indexed), both `CASCADE`; `PATH` (varchar 2048, relative to the downloads folder), `SUBTITLES` (JSON text, default `[]`), `VIDEO_TITLE`, `METHOD` (`DIRECT`/`FFMPEG`/`YT_DLP`), `BYTES`, `DOWNLOADED_AT` (epoch ms). Idempotent: `ADD COLUMN IF NOT EXISTS`, and `SchemaUtils.create` skips an existing table |

### Numbering collisions on an upstream sync

When the upstream diff adds migrations of its own, say `k` of them, numbered `M0069`…`M00(68+k)`:
1. Upstream's new migrations keep their numbers.
2. Rename ours up by `k`, keeping their order and names (`git mv M0069_MadoyomiExtensionKinds.kt M00(69+k)_MadoyomiExtensionKinds.kt` and the class name, and so on). Update the numbers in this file, in `MigrationsFromEmptyDbTest` (`FIRST_MADOYOMI_VERSION`) and in the "Upgrading a pre-Madoyomi database" test.
3. Databases that already ran ours have them recorded at the old numbers. The runner would treat upstream's new migrations as already done and skip them. So the first sync that renumbers must also add a reconcile step to `DBManager.databaseUp`, just before `runMigrations` (a `// Madoyomi:` edit). It goes through `MIGRATIONS` in version order and finds the first row whose `NAME` is the name of a `MadoyomiMigration` but whose `VERSION` no longer matches that migration's number. It deletes that row and every row after it. The runner then applies upstream's new migrations and re-runs ours, which is safe because they are idempotent. Because it only matches Madoyomi migration names, upstream rows are never re-run.
4. Until Madoyomi has users with real data, renumbering and deleting the dev data dirs (`tools/dev-data`) is enough. Step 3 can wait until it is needed.

### Startup contract (what the Tauri shell relies on)

- `java -jar api/build/Madoyomi-Server-v0.1.0.jar [--data-dir <path>]` (built by `.\gradlew.bat :api:shadowJar`). Unknown arguments exit with code 2.
- The jar's manifest has `Add-Opens: java.base/java.lang java.base/java.util.concurrent`, which `java -jar` applies (uninstall stops the threads an extension left running). A launch that doesn't use `-jar` (a jlink launcher, `-cp`) must pass `--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED` itself. Without them, uninstall can only interrupt those threads, and a local server blocked in `accept()` keeps the extension's classes until the process ends.
- Env `MADOYOMI_TOKEN`: the API token. If unset, a random one is used and the API is effectively locked.
- Env `MADOYOMI_MPV` (optional): the `mpv.exe` to play with, unless the player settings name one (`<data dir>/player-settings.json`); without either the backend uses `mpv.exe` from `PATH`. Env `MADOYOMI_PLAYER_DIR` (optional): mpv's config dir; without it `<data dir>/player`. mpv is started as the JVM's child, without `MADOYOMI_TOKEN` in its environment, and at startup the backend ends an mpv that an earlier (crashed) backend on the same data dir left running. The backend drives mpv over `\\.\pipe\madoyomi-mpv-<session>` and stops it with `quit` (mpv then exits with 0).
- Env `MADOYOMI_UPDATE_INTERVAL_SECONDS` (optional, for live checks): while the library update schedule is on, it runs this many seconds apart instead of the chosen 6, 12 or 24 hours. The schedule itself is in `<data dir>/library-update.json`.
- Binds `127.0.0.1` on a random free port and prints exactly one line `MADOYOMI_PORT=<port>` on stdout once listening (other stdout lines are logs).
- Every request needs `Authorization: Bearer <token>`, except `GET /health` and CORS preflights. `GET` requests and WebSocket upgrades may pass `?token=<token>` instead. A rejected request gets `401 {"code":"UNAUTHORIZED","message":…,"details":null}`.
- Binary REST routes: upstream's (`/api/v1/manga/{id}/thumbnail`, pages, extension icons) and `GET /api/v1/anime/{animeId}/thumbnail`.
- The API is GraphQL at `POST /api/graphql`; subscriptions use `graphql-transport-ws` on `ws://127.0.0.1:<port>/api/graphql?token=<token>` (the server negotiates no subprotocol, so a client must not require one to be echoed). GraphQL errors carry `extensions: {code, details}`; REST/binary errors are the body `{code, message, details}` (see "API").
- Upstream `authMode` is forced to `NONE` (every request is the built-in admin), so the token is the only gate.
- `GET /health` → `{"status":"ok","name","version","pid","uptimeMs"}`.
- CORS: `http://localhost:1420`, `http://tauri.localhost`, `https://tauri.localhost`.
- Exits (shutdown hooks run) when stdin closes.

`tools/run-server.ps1` builds the jar and runs it this way with a dev data dir (`tools/dev-data/`).
