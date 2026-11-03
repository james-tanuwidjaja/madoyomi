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
