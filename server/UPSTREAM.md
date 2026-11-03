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
| `server/src/test/resources/{dice.jxl, fox.profile0.8bpc.yuv420.avif, sample1.heif}` | Upstream image-decoding tests |

Run `.\tools\fetch-upstream-assets.ps1` once after cloning, before the first Gradle build.

## Syncing with upstream

1. Fetch upstream and diff the imported commit against the new upstream commit: `git diff 7b061be..<new> -- . ':(exclude).github'`.
2. Apply that diff onto `server/`, remapping any paths Madoyomi moved (see "Madoyomi changes" below), and resolve conflicts.
3. Update the commit SHA here and in `tools/fetch-upstream-assets.ps1` (plus any new or changed asset hashes).

## Madoyomi changes

Rules that keep merges manageable:
- Upstream packages (`suwayomi.tachidesk.*`, `eu.kanade.tachiyomi.*`, `xyz.nulldev.*`, `android.*`, …) keep their names. **New Madoyomi code goes under `madoyomi.*`.**
- Upstream files keep their MPL-2.0 headers. Files ported from Aniyomi or Mihon keep Apache-2.0 headers (see the root `NOTICE` and `THIRD_PARTY.md`).
- Prefer disabling an upstream feature (default off, route not registered) over deleting it.
- Every Madoyomi edit inside an upstream file carries a `// Madoyomi:` comment, usually with the upstream value, so `git grep "Madoyomi:" -- server` lists them all.
- Feature switches live in one place: `madoyomi.core.MadoyomiFeatures`.

Moved paths: none yet (the module split is Phase 1 Stage 3).

### Build

| File | Change |
|---|---|
| `gradle.properties` (new, tracked) | `org.gradle.jvmargs=-Xmx5120m` and `kotlin.compiler.execution.strategy=in-process`, the same as upstream CI's `.github/runner-files/ci-gradle.properties`. Without them `:server:compileKotlin` fails with `OutOfMemoryError`. |
| `.gitignore` | `!/gradle.properties`, so the root file above is tracked (upstream ignores it) |
| `settings.gradle.kts` | `rootProject.name = "Madoyomi-Server"` (app name, jar name, `BuildConfig.NAME`); no `ProductName` env override |
| `buildSrc/src/main/kotlin/Constants.kt` | `MainClass = "madoyomi.api.MainKt"`; fixed version `v0.1.0` (must stay numeric `vX.Y.Z`, `AppInfo` derives the version code from it) |
| `server/build.gradle.kts` | BuildConfig `GITHUB`/`DISCORD`, jar vendor, test system properties use `madoyomi.config.`; **deleted** the `downloadWebUI` and `runElectron` tasks |

### Rebrand (user-visible names only)

| File | Change |
|---|---|
| `AndroidCompat/Config/.../ApplicationRootDir.kt` | `CONFIG_PREFIX = "madoyomi.config"` (so `-Dmadoyomi.config.server.*`); default data dir `%APPDATA%\Madoyomi` on Windows, AppDirs `Madoyomi` elsewhere |
| `AndroidCompat/Config/.../Logging.kt` | log file `logs/madoyomi.log`, appender name; `debugLogsEnabled` reads the prefixed property |
| `server/.../server/ServerSetup.kt` | temp dir `<tmp>/Madoyomi`; startup log line uses `BuildConfig.NAME` |
| `server/.../server/util/AppExit.kt` | shutdown log line |
| `server/.../server/database/DBManager.kt` | Hikari pool name |
| `server/.../graphql/server/TemporaryFileStorage.kt`, `eu/kanade/tachiyomi/network/NetworkHelper.kt` | temp dir names |
| `server/.../global/impl/util/Jwt.kt`, `server-config/.../ServerConfig.kt` (`jwtAudience`) | JWT issuer/audience `madoyomi-server[-api]` |
| `server/.../server/user/UserType.kt`, `server/.../server/JavalinSetup.kt`, `server/.../graphql/controller/GraphQLController.kt` | cookie names `madoyomi-server-token`, `madoyomi-basic-csrf` |
| `server/.../manga/impl/backup/proto/models/Backup.kt` | backup file prefix `app.madoyomi` (the `.tachibk` extension stays, for Mihon compatibility) |
| Tracker clients: `AnilistInterceptor`, `KitsuInterceptor`, `MangaUpdatesInterceptor`, `MyAnimeListInterceptor`, `ShikimoriInterceptor`, `BangumiInterceptor`, `MangaBakaApi` | User-Agent says Madoyomi. OAuth redirect URLs are unchanged (trackers are Phase 8). |
| Tests `UserContextTest`, `ProtoBackupExportTest` | follow the cookie name and backup prefix |

Deliberately **not** rebranded: package names (rule above); i18n strings (they only appear in OPDS, the login page and the KCEF WebView, all disabled); tray, SyncYomi and KOReader device names (disabled features); `docs/` and `scripts/` (upstream documentation and packaging, unused by Madoyomi).

### Sidecar defaults and disabled features

| File | Change |
|---|---|
| `server-config/.../ServerConfig.kt` | defaults: `ip` `127.0.0.1`, `port` `0` (min lowered to 0), `initialOpenInBrowserEnabled`, `systemTrayEnabled`, `webUIEnabled` and `kcefEnabled` all `false` (KCEF code stays for Phase 2) |
| `server/.../server/JavalinSetup.kt` | connector always binds `127.0.0.1` and is exposed as `JavalinSetup.connector`; startup log shows the bound port; CORS allows only the Tauri origins (`MadoyomiFeatures.ALLOWED_ORIGINS`), on every path; OPDS routes not registered; `javalinSetup(configure)` hook, called before upstream's `defineCore()`, and it returns the app; the `api/` "unmatched → 404" `after` handler leaves a 401 alone (otherwise token rejections would turn into 404s) |
| `server/.../server/ServerSetup.kt` | SyncYomi scheduling off |
| `server/.../manga/impl/update/Updater.kt` | no SyncYomi sync before library updates |
| `server/.../manga/controller/MangaController.kt`, `server/.../graphql/mutations/ChapterMutation.kt` | no KOReader progress pushes |
| `server/.../global/impl/AppUpdate.kt` | server update check returns nothing (never calls Suwayomi's GitHub) |
| `server/.../server/util/WebInterfaceManager.kt` | WebUI update check/download is a no-op while the WebUI is disabled |
| `server/.../server/util/AppMutex.kt` | single-instance probe off (Tauri owns the lifecycle) |
| `server/.../server/Migration.kt` | legacy Java-Preferences import skipped: it would otherwise import and **delete** an old Suwayomi install's registry node |

### New Madoyomi code (in the `server` module until Stage 3)

| Path (under `server/src/main/kotlin/`) | What |
|---|---|
| `madoyomi/core/MadoyomiFeatures.kt` | feature switches + loopback/CORS constants referenced by upstream files (stays in `core`) |
| `madoyomi/api/Main.kt` | entry point `madoyomi.api.MainKt` (moves to `api`) |
| `madoyomi/api/SidecarHttp.kt` | `GET /health`, the token filter and the WebSocket upgrade check |
| `madoyomi/api/SidecarToken.kt` | token from `MADOYOMI_TOKEN`, constant-time compare |

### Startup contract (what the Tauri shell relies on)

- `java -jar Madoyomi-Server-v0.1.0.jar [--data-dir <path>]`. Unknown arguments exit with code 2.
- Env `MADOYOMI_TOKEN`: the API token. If unset, a random one is used and the API is effectively locked.
- Binds `127.0.0.1` on a random free port and prints exactly one line `MADOYOMI_PORT=<port>` on stdout once listening (other stdout lines are logs).
- Every request needs `Authorization: Bearer <token>`, except `GET /health` and CORS preflights. `GET` requests and WebSocket upgrades may pass `?token=<token>` instead. A rejected request gets `401 {"code":"UNAUTHORIZED","message":…}`.
- Upstream `authMode` is forced to `NONE` (every request is the built-in admin), so the token is the only gate.
- `GET /health` → `{"status":"ok","name","version","pid","uptimeMs"}`.
- CORS: `http://localhost:1420`, `http://tauri.localhost`, `https://tauri.localhost`.
- Exits (shutdown hooks run) when stdin closes.

`tools/run-server.ps1` builds the jar and runs it this way with a dev data dir (`tools/dev-data/`).
