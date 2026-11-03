# Suwayomi-Server: research notes (Phase 0 / S1)

Source read: `Suwayomi/Suwayomi-Server` `master` @ `7b061be` (2026-11-01), shallow clone in `tools/upstream/Suwayomi-Server` (gitignored).
Latest release at the time: `v2.4.2366` (`Suwayomi-Server-v2.4.2366.jar`).
License: MPL-2.0 (per-file headers).

> Status: these notes come from **reading the source**. The run-it-and-browse part of S1 (build, add the repo, install, read) has **not** been done yet: see the S1 checklist in [phase-0-spikes.md](../phases/phase-0-spikes.md).

## Build & toolchain

| Item | Value |
|---|---|
| Build | Gradle 9.8.2 wrapper, Kotlin 2.4.21, `jvmTarget = 21` |
| Modules | `server`, `server:i18n`, `server:server-config`, `server:server-config-generate`, `AndroidCompat`, `AndroidCompat:Config` |
| Fat jar | `./gradlew :server:shadowJar` (shadow 9.6.1) → `server/build/Suwayomi-Server-*.jar` |
| Key libs | OkHttp **5.5.1** ("major version locked by Tachiyomi extensions"), Javalin **7.2.4**, Jackson 3, Exposed 1.4, graphql-kotlin 11, Koin 4 (via `injekt-koin`), dex2jar 2.4.38, GraalJS polyglot, JCEF/KCEF |

## How extensions are loaded

Files under `server/src/main/kotlin/suwayomi/tachidesk/manga/impl/`.

1. **Install** (`extension/Extension.kt` `installExtension`): if the repo listing has a **`jarUrl`** (Keiyoushi field 501), it downloads the prebuilt jar and **skips dex2jar**. Otherwise it downloads the APK, reads the manifest with `apk-parser` (`PackageTools.getPackageInfo`) and checks the extension feature, lib version (`LIB_VERSION_MIN 1.3` .. `LIB_VERSION_MAX 1.6`) and signature.
2. **dex2jar** (`util/PackageTools.dex2jar`): runs the dex2jar *library* in-process (`MultiDexFileReader` → `Dex2jar…to(jar)`), not the CLI. It then runs `BytecodeEditor.fixAndroidClasses(jar)`, which **rewrites bytecode references** to some JDK classes (e.g. `java/text/SimpleDateFormat`) to replacements under `xyz/nulldev/androidcompat/replace/…`, so that Android-behaviour differences are patched without touching the extension.
3. **Signature**: SHA-256 of the first signing cert (`getSignatureHash`), compared with the store's `signingKey`. APK v2 signers are not read yet (apk-parser issue #72).
4. **Classloader** (`util/ChildFirstURLClassLoader.kt`): one `URLClassLoader` per jar, cached in `PackageTools.jarLoaderMap`. Despite the name it asks the **system** classloader first, then the jar, then the parent. So the server's copies of OkHttp, Jsoup, kotlinx.serialization and Injekt always win, and the extension only supplies its own classes. Access is serialized per jar with a coroutine `Mutex` (`blockJarUsageWhile`).
5. **Instantiation**: `Class.forName(className).getDeclaredConstructor().newInstance()`. The result is either a `Source` or a `SourceFactory` (`createSources()`).

Meta-data keys used: `tachiyomi.extension.class`, `tachiyomi.extension.factory`, `tachiyomi.extension.nsfw`, `tachiyomix.name`, `tachiyomix.extensionLib`, `tachiyomix.contentWarning`. Anime APKs use the same scheme under `tachiyomi.animeextension.*`, plus `tachiyomi.animeextension.torrent` (see [anime-ext-classes.md](anime-ext-classes.md)).

## Android stubs (`AndroidCompat/`)

- About 330 Java/Kotlin files that re-implement the parts of the Android SDK extensions touch: `android.app/content/database/graphics/net/os/preference/text/util/view/webkit/widget`, `androidx.preference`, `androidx.core.net`, `dalvik.system`, `org.json`, `libcore`, and QuickJS/Duktape shims (`app.cash.quickjs`, `com.squareup.duktape`, backed by GraalJS).
- Under them sits `com.github.Suwayomi:android-jar:1.0.0` (the stock `android.jar` API surface). AndroidCompat's real implementations shadow it.
- `xyz.nulldev.androidcompat.*` holds the glue: `AndroidCompatInitializer`/`AndroidCompatModule` (DI), `pm` (fake PackageManager), `io` (SharedPreferences backed by files), `webkit` (WebView provider), `replace` (bytecode-replacement targets).
- Everything is in **one module** shared by all extensions, with nothing manga-specific in it. So Aniyomi extensions can reuse it as-is. The S3 class list shows they need the same Android packages (`android.app.Application`, `SharedPreferences`, `androidx.preference.*`, `android.webkit.*`, `android.widget.Toast`).

## Tachiyomi runtime API (`server/src/main/kotlin/eu/kanade/tachiyomi/`)

- `source/*` (manga `Source`, `HttpSource`, models) and `network/*` (`NetworkHelper`, `Requests.kt` GET/POST/PUT/DELETE, `OkHttpExtensions.kt` `await`/`awaitSuccess`/`asObservableSuccess`, cookie jars, interceptors including `CloudflareInterceptor`, `RateLimitInterceptor`, `UserAgentInterceptor`).
- **The `eu.kanade.tachiyomi.network` API matches what anime extensions call** (checked against `aniyomiorg/extensions-lib` tag 16 and the Aniyomi app's `core/common`): same `GET(String|HttpUrl, Headers, CacheControl)`, `POST(...)`, `Call.await()`/`awaitSuccess()`, `NetworkHelper.client`/`cloudflareClient`. One JVM can serve both extension types on the same network layer.
- DI: extensions call `Injekt.get<NetworkHelper>()`, `Injekt.get<Application>()`, `Injekt.get<Json>()`. Suwayomi provides the Injekt API on top of **Koin** (`com.github.null2264:injekt-koin`).

## DB

- **Exposed** ORM, with **H2** as the default (`jdbc:h2:<dataRoot>/database`) and **PostgreSQL** as an option (`DatabaseType`). See `server/database/DBManager.kt`.
- Migrations: 69 numbered Kotlin migrations in `server/database/migration/` (`M0001…`).
- **History:** Suwayomi once had anime support. `M0004–M0006_AnimeTablesBatch*` created `Anime`, `Episode`, `AnimeExtension` and `AnimeSource`, and `M0019_RemoveAnime` dropped them. Their old anime code (git history before M0019) is worth a look before writing ours. We will need *new* migrations rather than reviving theirs.

## API

- Javalin 7 (`server/JavalinSetup.kt`), with CORS via `bundledPlugins.enableCors` (reflects the client origin on `api/*`).
- **GraphQL is the primary API** (`graphql/`, ~14.5k lines: queries, mutations, subscriptions, data loaders) at `/api/graphql`. It covers extensions and extension stores (`ExtensionStoreMutation.addExtensionStore`), sources, manga, chapters, categories, downloads, updates, backup, settings, trackers and WebView.
- **REST v1 is now only for binaries**: `GET /api/v1/extension/icon/{pkgName}`, `manga/{id}/thumbnail`, `manga/{id}/chapter/{idx}/page/{n}`, `chapter/{id}/download`, `track/{id}/thumbnail`, `about`. OPDS is served separately.
- Subscriptions (downloads, updates) go over GraphQL WebSocket.

## Repo index support

- `extension/ExtensionStoreService.fetch` handles all three formats: legacy `index.min.json` (it then looks for `repo.json`), `repo.json` with `index_v2` → protobuf, and protobuf `index.pb` (Mihon `NetworkExtensionStore`, plus Keiyoushi's `jarUrl` field 501). JSON vs protobuf is chosen by sniffing the first byte (`{`), and **gzip is detected by its magic bytes** (Keiyoushi serves gzip without `Content-Encoding`).
- So **Keiyoushi's `index.pb` works natively**. Its `index.min.json` is now a two-entry "Outdated App / Update to Mihon 0.20.1+" stub. Schema: [index-pb.proto](index-pb.proto).

## WebView / Cloudflare

- `CloudflareInterceptor` detects a challenge and calls **FlareSolverr** (an external service, configurable) to obtain `cf_clearance` cookies and the matching User-Agent. The code is ported from `vvanglro/cf-clearance`.
- An in-process browser is available through **KCEF/JCEF** (`server/util/CEFManager.kt`, `global/impl/KcefWebView.kt`). It downloads a Chromium build on demand (`kcefEnabled` setting) and backs `android.webkit.WebView` for extensions that drive a WebView themselves. That matters for anime: AnimePahe (lib 16) references `android.webkit.WebView`/`WebViewClient`/`CookieManager`.

## What Madoyomi can reuse

| Part | Reuse? | Notes |
|---|---|---|
| `AndroidCompat/` | **Yes, as-is** | Not specific to manga or anime |
| `PackageTools` / `BytecodeEditor` / `ChildFirstURLClassLoader` | **Yes** | Needs a `type` parameter for the anime meta-data keys and lib-version range |
| `eu.kanade.tachiyomi.network` | **Yes, shared** | Same API for both extension kinds |
| `eu.kanade.tachiyomi.source` (manga) | Yes | Anime adds a parallel `eu.kanade.tachiyomi.animesource` (port from the Aniyomi app's `source-api`) |
| Extension store service (`index.pb`, JSON) | **Yes** | The anime index (yuzono `index.min.json`) is the legacy JSON format this service already parses (plus `torrent`) |
| DB layer (Exposed + H2) | Yes | Add anime tables as new migrations |
| GraphQL API | Probably extend | REST is too thin to build on; see the decision record |
| WebUI bundling, tray, launcher, OPDS, KOReader sync | Disable | Not needed in a Tauri shell |

## Open questions for the S1 run

- How long does a clean `shadowJar` build take, and does it work on Windows with only `tools/bin/jdk-21`?
- Do the three test extensions (HTML, JSON API, Cloudflare) browse, open details, list chapters and return pages?
- How big is the startup cost (KCEF download, H2 init) when started as a sidecar with a throwaway data dir?
