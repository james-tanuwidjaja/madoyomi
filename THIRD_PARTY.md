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

Release builds (`tools/build-release.ps1`) bundle these programs and the runtime beside the app; the installers put them in the install folder. They are not in git: `tools/fetch-binaries.ps1` downloads the versions pinned in [`tools/binaries.json`](tools/binaries.json) and checks their SHA-256, and `tools/jlink.ps1` links the runtime from the JDK. Each runs as a separate program (mpv, ffmpeg, yt-dlp) or is the JVM the backend runs on; Madoyomi starts them and talks to them over pipes and the command line, and links none of them.

Every bundled program carries its license texts in its own folder, pinned by SHA-256 in `binaries.json` like the programs (Phase 7 Stage 2). Madoyomi's own `LICENSE`, `NOTICE` and this file are at the top of the install folder.

| Component | Version | License | License texts in the install folder | Source |
|---|---|---|---|---|
| Java runtime (jlink of Eclipse Temurin JDK 21) | 21.0.13+9, 22 modules | GPL-2.0 with the Classpath Exception | `jre/legal/<module>/` (each module's `LICENSE`, `ASSEMBLY_EXCEPTION`, `ADDITIONAL_LICENSE_INFO` and third-party notices), `jre/release` | [adoptium/temurin21-binaries](https://github.com/adoptium/temurin21-binaries) `jdk-21.0.13+9`, [openjdk/jdk21u](https://github.com/openjdk/jdk21u) |
| [mpv](https://mpv.io) (the mpv project's own Windows build, `x86_64-w64-mingw32`, built 2025-12-21 from the v0.41.0 tag's commit `41f6a64`) | 0.41.0 | GPL-2.0-or-later (this build links FFmpeg built with `--enable-gpl`) | `mpv/Copyright`, `mpv/LICENSE.GPL`, `mpv/LICENSE.LGPL`; its libraries' in `mpv/licenses/` (below) | [mpv-player/mpv v0.41.0](https://github.com/mpv-player/mpv/tree/v0.41.0); how the build was made: [`ci/build-mingw64.sh`](https://github.com/mpv-player/mpv/blob/v0.41.0/ci/build-mingw64.sh) and [`.github/workflows/build.yml`](https://github.com/mpv-player/mpv/blob/v0.41.0/.github/workflows/build.yml) at the tag |
| [FFmpeg](https://ffmpeg.org) (gyan.dev's essentials build, `ffmpeg.exe` only) | 9.0.2 | GPL-3.0-or-later (built with `--enable-gpl --enable-version3`) | `ffmpeg/LICENSE` (the build's GPL-3.0 text), `ffmpeg/README.txt` (the build's configuration and every library in it, with versions) | [FFmpeg n9.0.2](https://github.com/FFmpeg/FFmpeg/tree/n9.0.2); the libraries' versions in the build's `README.txt`; the build: [gyan.dev](https://www.gyan.dev/ffmpeg/builds/), [GyanD/codexffmpeg 9.0.2](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2) |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) (`yt-dlp.exe`) | 2026.10.26 | Unlicense; the PyInstaller exe bundles Python (PSF-2.0) and other libraries | `yt-dlp/LICENSE`, `yt-dlp/THIRD_PARTY_LICENSES.txt` (what the exe bundles) | [yt-dlp 2026.10.26](https://github.com/yt-dlp/yt-dlp/tree/2026.10.26) |

### The libraries inside mpv's build

mpv's Windows build ships these as DLLs beside `mpv.exe` (LuaJIT is linked into it). Its build script clones most of them at their newest commit on the build day, so the versions are the ones the build reports (`mpv --version`) or the ones the script pins; the license texts come from the tag named here. They are in `mpv/licenses/` (GPL-2.0 and LGPL-2.1 texts at the top of `mpv/`).

| Library (DLL) | Version in the build | License | License text | Source |
|---|---|---|---|---|
| FFmpeg (`avcodec-62`, `avdevice-62`, `avfilter-11`, `avformat-62`, `avutil-60`, `swresample-6`, `swscale-9`) | git snapshot `1fcb201` of 2025-12-21 (libavcodec 62.23.102) | GPL-2.0-or-later (built with `--enable-gpl`) | `FFmpeg-LICENSE.md` (from `n8.0`), `../LICENSE.GPL` | [FFmpeg/FFmpeg](https://github.com/FFmpeg/FFmpeg) at `1fcb201` |
| libass (`libass-9.dll`) | git snapshot of 2025-12-21 | ISC | `libass-COPYING.txt` (from `0.17.4`) | [libass/libass](https://github.com/libass/libass) |
| dav1d (`libdav1d.dll`) | git snapshot of 2025-12-21 | BSD-2-Clause | `dav1d-COPYING.txt` (from `1.5.2`) | [videolan/dav1d](https://code.videolan.org/videolan/dav1d) |
| FreeType (`libfreetype-6.dll`) | 2.14.1 | FreeType License (FTL) or GPL-2.0 | `FreeType-LICENSE.txt`, `FreeType-FTL.txt` | [freetype 2.14.1](https://download.savannah.gnu.org/releases/freetype/) |
| FriBidi (`libfribidi-0.dll`) | 1.0.16 | LGPL-2.1-or-later | `fribidi-COPYING.txt` | [fribidi/fribidi v1.0.16](https://github.com/fribidi/fribidi/tree/v1.0.16) |
| HarfBuzz (`libharfbuzz-0.dll`) | 12.2.0 | MIT ("Old MIT") | `harfbuzz-COPYING.txt` | [harfbuzz/harfbuzz 12.2.0](https://github.com/harfbuzz/harfbuzz/tree/12.2.0) |
| GNU libiconv (`libiconv-2.dll`) | 1.18 | LGPL-2.1-or-later | `../LICENSE.LGPL` | [libiconv 1.18](https://ftp.gnu.org/gnu/libiconv/) |
| Little CMS (`liblcms2.dll`) | git snapshot of 2025-12-21 | MIT | `lcms2-LICENSE.txt` (from `lcms2.17`) | [mm2/Little-CMS](https://github.com/mm2/Little-CMS) |
| libplacebo (`libplacebo-358.dll`) | v7.358.0 (git snapshot of 2025-12-21) | LGPL-2.1-or-later | `libplacebo-LICENSE.txt` (from `v7.351.0`) | [videolan/libplacebo](https://code.videolan.org/videolan/libplacebo) |
| shaderc (`libshaderc_shared.dll`) | git snapshot of 2025-12-21 | Apache-2.0 | `shaderc-LICENSE.txt` (from `v2025.4`) | [google/shaderc](https://github.com/google/shaderc) |
| SPIRV-Cross (`libspirv-cross-c-shared.dll`) | git snapshot of 2025-12-21 | Apache-2.0 | `SPIRV-Cross-LICENSE.txt` (from `vulkan-sdk-1.4.328.0`) | [KhronosGroup/SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross) |
| Vulkan loader (`vulkan-1.dll`) | git snapshot of 2025-12-21 | Apache-2.0 | `Vulkan-Loader-LICENSE.txt` (from `v1.4.335`) | [KhronosGroup/Vulkan-Loader](https://github.com/KhronosGroup/Vulkan-Loader) |
| zlib (`zlib1.dll`) | 1.3.1 | Zlib | `zlib-LICENSE.txt` | [madler/zlib v1.3.1](https://github.com/madler/zlib/tree/v1.3.1) |
| LuaJIT (linked into `mpv.exe`) | git snapshot of 2025-12-21 | MIT | `LuaJIT-COPYRIGHT.txt` (from `v2.1`) | [LuaJIT/LuaJIT](https://github.com/LuaJIT/LuaJIT) |
| GCC runtime (`libgcc_s_seh-1.dll`, `libstdc++-6.dll`, `libssp-0.dll`) | Ubuntu's `g++-mingw-w64` | GPL-3.0 with the GCC Runtime Library Exception | `GCC-COPYING.RUNTIME.txt`, `GCC-COPYING3.txt` (from `releases/gcc-13.3.0`) | [gcc-mirror/gcc](https://github.com/gcc-mirror/gcc) |
| winpthreads (`libwinpthread-1.dll`) | Ubuntu's `mingw-w64` | MIT and ZPL-2.1 | `winpthreads-COPYING.txt` (from `v11.0.1`) | [mingw-w64](https://www.mingw-w64.org/), [mingw-w64-libraries/winpthreads](https://github.com/mingw-w64/mingw-w64/tree/v11.0.1/mingw-w64-libraries/winpthreads) |

**Corresponding source (GPL).** mpv, FFmpeg (both builds) and the GCC runtime are GPL; libiconv, FriBidi and libplacebo are LGPL. Madoyomi redistributes these binaries unmodified, and the tables link the source of each at the version in the build. mpv's build script fetches several libraries at their newest commit, so for those the source link is the project, not an exact tag. Each release links the sources instead of attaching an archive of them (decided in Phase 7 Stage 5, `docs/phases/phase-7-packaging-release.md` §9): its text names each bundled program with its source and points here, at the release's tag. Madoyomi's own source, with the Suwayomi-Server fork (MPL-2.0), is the release's tag in this repo (public from the first published release on).

## Not committed

Upstream's Noto fonts (SIL Open Font License 1.1) and test images are restored by `tools/fetch-upstream-assets.ps1` and are not in git (see [server/UPSTREAM.md](server/UPSTREAM.md)).
