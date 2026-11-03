# Third-party code and components

Madoyomi is licensed under [MPL-2.0](LICENSE). It includes or depends on the third-party code below. The short attribution summary is in [`NOTICE`](NOTICE).

## Code in this repository

These are source files copied or forked into the tree. Each keeps its original license header.

| Component | License | Where | Upstream |
|---|---|---|---|
| Suwayomi-Server | MPL-2.0 | `server/` (all modules); per-file headers kept | [Suwayomi/Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) @ `7b061be`; changes listed in [server/UPSTREAM.md](server/UPSTREAM.md) |
| Tachiyomi / Mihon source API and helpers (`eu.kanade.tachiyomi.*`) | Apache-2.0 | `server/ext-host` (`network`, `util`), `server/ext-manga` (`source`), `server/core` (`App*`, `source.local`) | [mihonapp/mihon](https://github.com/mihonapp/mihon), via Suwayomi-Server. License text: `server/*/src/main/kotlin/eu/kanade/tachiyomi/LICENSE` |
| Aniyomi source API (`eu.kanade.tachiyomi.animesource.*`, `mihon.core.common.extensions`) | Apache-2.0 | `server/ext-anime` | [aniyomiorg/aniyomi](https://github.com/aniyomiorg/aniyomi) @ `9741444`; changes in [server/ext-anime/NOTICE.md](server/ext-anime/NOTICE.md) |
| `RxCoroutineBridge` | MPL-2.0 header (Suwayomi); the code comes from TachiyomiSY (Apache-2.0) | `server/ext-host/.../suwayomi/tachidesk/manga/impl/util/lang/RxCoroutineBridge.kt` | [jobobby04/TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), via Suwayomi-Server |

## Notable libraries

These are dependencies, not copied code. Gradle resolves them and bundles them into the server jar (apksig only from Stage 6; until then it is a compile-only dependency of `AndroidCompat`).

| Library | License | Used for |
|---|---|---|
| [apksig](https://android.googlesource.com/platform/tools/apksig/) (`com.android.tools.build:apksig`) | Apache-2.0 | APK signature verification (extension trust, Phase 1 Stage 6) |
| [dex2jar](https://github.com/ThexXTURBOXx/dex2jar) | Apache-2.0 | Converting extension APKs to JVM bytecode |
| [OkHttp](https://github.com/square/okhttp) | Apache-2.0 | HTTP client that extensions expect |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 2.3.1 | BSD-3-Clause | `animesource.model.HttpServer` in `server/ext-anime` |

The full dependency set is in `server/gradle/libs.versions.toml` and the module `build.gradle.kts` files. Run `.\gradlew.bat :api:dependencies --configuration runtimeClasspath` in `server/` for the resolved tree. The licenses of the bundled jars are inside them (`META-INF/`).

The Tauri app's Rust and npm dependencies are listed in `app/src-tauri/Cargo.lock` and `app/pnpm-lock.yaml`.

## Not committed

Upstream's Noto fonts (SIL Open Font License 1.1) and test images are restored by `tools/fetch-upstream-assets.ps1` and are not in git (see [server/UPSTREAM.md](server/UPSTREAM.md)).
