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

_The list of modified upstream files and moved paths is kept here as Phase 1 progresses._
