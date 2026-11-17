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

These are dependencies, not copied code. Gradle resolves them and bundles them into the server jar.

| Library | License | Used for |
|---|---|---|
| [apksig](https://android.googlesource.com/platform/tools/apksig/) (`com.android.tools.build:apksig`) | Apache-2.0 | APK signature verification for extension trust (`server/ext-host`, `madoyomi.exthost.ApkInspector`) |
| [dex2jar](https://github.com/ThexXTURBOXx/dex2jar) | Apache-2.0 | Converting extension APKs to JVM bytecode |
| [OkHttp](https://github.com/square/okhttp) | Apache-2.0 | HTTP client that extensions expect |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 2.3.1 | BSD-3-Clause | `animesource.model.HttpServer` in `server/ext-anime` |

The full dependency set is in `server/gradle/libs.versions.toml` and the module `build.gradle.kts` files. Run `.\gradlew.bat :api:dependencies --configuration runtimeClasspath` in `server/` for the resolved tree. The licenses of the bundled jars are inside them (`META-INF/`).

The Tauri app's Rust and npm dependencies are listed in `app/src-tauri/Cargo.lock` and `app/pnpm-lock.yaml`.

## Bundled with release builds (Phase 7)

Release builds (`tools/build-release.ps1`) bundle these programs and runtime beside the app. They are not in git: `tools/fetch-binaries.ps1` downloads the versions pinned in [`tools/binaries.json`](tools/binaries.json) and checks their SHA-256, and `tools/jlink.ps1` links the runtime from the JDK. Each runs as a separate program (mpv, ffmpeg, yt-dlp) or is the JVM the backend runs on. Their license files and the source links in the installer come in Phase 7 Stage 2.

| Component | Version (pinned in Stage 1) | License | Source |
|---|---|---|---|
| Java runtime (jlink of Eclipse Temurin JDK 21) | 21.0.13+9, 22 modules | GPL-2.0 with the Classpath Exception | [adoptium/temurin21-binaries](https://github.com/adoptium/temurin21-binaries), [openjdk/jdk21u](https://github.com/openjdk/jdk21u) |
| [mpv](https://mpv.io) (the mpv project's own Windows build, `x86_64-w64-mingw32`) | 0.41.0 | GPL-2.0-or-later; the build ships FFmpeg, libass, libplacebo and others as DLLs | [mpv-player/mpv v0.41.0](https://github.com/mpv-player/mpv/tree/v0.41.0) |
| [FFmpeg](https://ffmpeg.org) (gyan.dev's essentials build, `ffmpeg.exe` only) | 9.0.2 | GPL-3.0-or-later (built with `--enable-gpl --enable-version3`) | [FFmpeg n9.0.2](https://github.com/FFmpeg/FFmpeg/tree/n9.0.2); the build's configuration in its `README.txt` |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) (`yt-dlp.exe`) | 2026.10.26 | Unlicense; the PyInstaller exe bundles Python (PSF-2.0) and other libraries | [yt-dlp 2026.10.26](https://github.com/yt-dlp/yt-dlp/tree/2026.10.26) |

## Not committed

Upstream's Noto fonts (SIL Open Font License 1.1) and test images are restored by `tools/fetch-upstream-assets.ps1` and are not in git (see [server/UPSTREAM.md](server/UPSTREAM.md)).
