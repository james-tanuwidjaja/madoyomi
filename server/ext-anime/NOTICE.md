# ext-anime: third-party code

This module holds the Aniyomi extension API (`eu.kanade.tachiyomi.animesource.*`) that anime extension APKs are compiled against. Madoyomi implements it so it can load those APKs unchanged.

## Provenance

| Path (under `src/main/kotlin/`) | Upstream | License |
|---|---|---|
| `eu/kanade/tachiyomi/animesource/**` (26 files) | [aniyomiorg/aniyomi](https://github.com/aniyomiorg/aniyomi) @ `9741444` (2026-09-14), `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/animesource/` | Apache License 2.0 |
| `mihon/core/common/extensions/JsonObject.kt` | the same Aniyomi commit, `core/common` module | Apache License 2.0 |

The license text is in [`src/main/kotlin/eu/kanade/tachiyomi/LICENSE`](src/main/kotlin/eu/kanade/tachiyomi/LICENSE). Each file carries an Apache-2.0 header naming its origin. The code was first ported for the Phase 0 spike (`spikes/anime-harness`) and copied here in Phase 1.

## Changes from upstream

1. `PreferenceScreen.kt`: a plain `typealias` to AndroidCompat's `androidx.preference.PreferenceScreen`. Upstream uses `expect`/`actual`, because `source-api` is a multiplatform module.
2. The `@Stable` annotations (Jetpack Compose) are removed, since Compose isn't on the classpath.
3. `logcat` calls are replaced with `System.err`.
4. `awaitSingle` is imported from `suwayomi.tachidesk.manga.impl.util.lang` (Suwayomi's `RxCoroutineBridge.kt`, which lives in `server/ext-host` since the module split).
5. All files: license header added, plus formatting by ktlint, which runs on every build.

`JsonObject.kt` has no other changes.

## Dependencies

`animesource/model/HttpServer.kt` (extension lib 17) uses [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 2.3.1 (BSD-3-Clause), declared in `build.gradle.kts`.

See also the repository root [`NOTICE`](../../NOTICE) and [`THIRD_PARTY.md`](../../THIRD_PARTY.md).
