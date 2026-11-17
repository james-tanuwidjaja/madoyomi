# Phase 7 — Packaging & release

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 6](phase-6-library-features.md) · Next: [Phase 8](phase-8-later.md)

## 1. Goal

Ship Madoyomi as a normal Windows app: one installer that includes the Tauri shell, the backend jar, a minimal `jlink` JRE, mpv (and ffmpeg/yt-dlp if Phase 6 needs them), with auto-update, local logs, an "export logs" button, and a repeatable CI release build. No Java install required.

## 2. Depends on / inputs

- Phases 1–6 functionally complete.
- `tauri.conf.json` already targets NSIS + MSI (scaffold).
- Third-party binary licenses reviewed (mpv GPL/LGPL builds, ffmpeg, yt-dlp).

## 3. Deliverables

- `tools/jlink.ps1`: builds the minimal runtime from `jdeps` output into `app/src-tauri/resources/jre/` (Stage 1: with `tools/lib/JreProbe.java` run on it).
- `tools/fetch-binaries.ps1`: downloads pinned mpv/ffmpeg/yt-dlp builds with checksums (`tools/binaries.json`).
- `tools/build-release.ps1`: server jar → jlink → binaries → `pnpm tauri build`.
- Tauri config: `bundle.resources` (jar, JRE, player config, mpv, ffmpeg, yt-dlp; Stage 1: in `app/src-tauri/tauri.release.conf.json`, passed with `--config`); production sidecar path resolution in `sidecar.rs`.
- Updater: `tauri-plugin-updater`, signing keypair (private key kept out of the repo), `latest.json` manifest hosted on GitHub Releases.
- Logging: rotating log files for the shell, backend and mpv under `%APPDATA%/Madoyomi/logs`; a Settings → "Export logs" zip.
- `.github/workflows/release.yml`: Windows build, artifact upload, draft release.
- `RELEASING.md`: the release checklist.
- Stage 2: `VERSION` (the one version) and `tools/version.ps1` (checks or writes its copies); the installer settings in `tauri.conf.json` (`bundle.publisher`, `bundle.windows.nsis`, `bundle.windows.wix.upgradeCode`), `app/src-tauri/installer/hooks.nsh` (the uninstaller's "remove data") and `installer/English.nsh` (its label); the license texts pinned in `tools/binaries.json`.

## 4. Detailed steps

### Runtime & binaries
1. Run `jdeps --print-module-deps` on the server jar, then `jlink --add-modules … --strip-debug --no-header-files --no-man-pages --compress=zip-6`. Target ≤ 60 MB. Add modules that extensions need at runtime (e.g. `java.net.http`, `jdk.crypto.ec`, `java.scripting` if some use JS).
2. Pin mpv/ffmpeg/yt-dlp versions + SHA-256 in `tools/binaries.json`; the fetch script verifies the hashes.
3. Production `sidecar.rs`: resolve `<resources>/jre/bin/java.exe` + `<resources>/server/Madoyomi-Server.jar`; pass the mpv/ffmpeg/yt-dlp paths to the backend through env (`MADOYOMI_MPV`, `MADOYOMI_FFMPEG`, `MADOYOMI_YTDLP`, `MADOYOMI_PLAYER_DIR`).

### Installer
4. NSIS: per-user install by default (no admin), Start Menu shortcut, uninstall keeps user data unless the user ticks "remove data".
5. MSI for users who want it; check that upgrades work (same upgrade code).
6. App icon, product name, publisher, version taken from one source (`package.json` / `tauri.conf.json` / Gradle version kept in sync by the build script).
7. Code signing: decide (none / self-signed / certificate) and document the SmartScreen impact.

### Auto-update
8. Generate the updater keypair; store the private key as a CI secret only.
9. Check for updates on start (setting to disable) and show an "update available" dialog with release notes; the backend is updated as part of the bundle.
10. Stop the backend and mpv cleanly before the installer runs.

### Logs & diagnostics
11. Backend: logback with rolling files (size + count limits). Shell: `tauri-plugin-log`. mpv: `--log-file` per session (keep last N).
12. "Export logs": zip the logs + app/backend versions + extension list (no personal library data unless opted in).
13. Crash handling: if the backend dies repeatedly, show a dialog with "Export logs" and "Open logs folder".

### CI & release
14. GitHub Actions on `windows-latest`: setup JDK + Node/pnpm + Rust, cache Gradle/pnpm/cargo, run tests, build the release, upload the installers + `latest.json` to a draft release.
15. `RELEASING.md`: bump version, changelog, tag, CI build, smoke test on a clean Windows VM, publish.

### Licensing
16. Add licenses for bundled binaries to `THIRD_PARTY.md`; include license files in the installer.

## 5. Checklist

### Runtime & binaries
- [x] `tools/jlink.ps1` produces JRE ≤ 60 MB (Stage 1: 59.99 MiB, 22 modules, §10)
- [x] Extension-required JDK modules included (verified with compat harness on the jlink JRE) (Stage 1: anime 18/20 as recorded; manga 8/10, the one new failure kagane's Cloudflare, the same on the JDK; smoke 24/24 and the live spec 17/17 on it; `JreProbe` on every link, §10)
- [x] `tools/fetch-binaries.ps1` with pinned versions + checksums (Stage 1: `tools/binaries.json`, mpv 0.41.0, ffmpeg 9.0.2, yt-dlp 2026.10.26; a wrong hash fails, §10)
- [x] Production sidecar path resolution (Stage 1: a release build run with nothing on PATH played, read and downloaded with the bundled runtime, jar, mpv, ffmpeg and yt-dlp; `cargo test` 21, §10)

### Installer
- [x] NSIS per-user installer (Stage 2: installed without admin into `%LOCALAPPDATA%\Madoyomi`, silently and through its UI, with a Start Menu shortcut; the installed app ran with nothing on PATH; upgraded 0.1.0 → 0.1.1 silently and through the UI with the data kept; uninstalled keeping the data, and with "remove data" on a scratch data dir only, §10)
- [x] MSI build + upgrade path tested (Stage 2: built with the fixed upgrade code, and 0.1.0's and 0.1.1's tables read; then, by the user on the dev desktop, 0.1.0 installed per-machine into `C:\Program Files\Madoyomi` and upgraded to 0.1.1, which found 0.1.0 by the upgrade code and removed it: one entry left, the library, downloads and history kept; then uninstalled: the folder, shortcuts and entry gone, the data dir kept, §10 "Stage 2 follow-up")
- [x] Version sync across package.json / tauri.conf.json / Gradle (Stage 2: `VERSION` and `tools/version.ps1`, also `Cargo.toml`/`Cargo.lock`; `build-release.ps1` fails on a mismatch and on a jar of another version; a self-test, §10)
- [x] Code-signing decision documented (Stage 2: none for now, the user's; the SmartScreen impact in §9)

### Auto-update
- [ ] Updater keypair; private key only in CI secrets
- [ ] Update check + dialog + disable setting
- [ ] Clean shutdown of backend/mpv before update

### Logs & diagnostics
- [ ] Rolling logs for shell, backend, mpv
- [ ] "Export logs" zip
- [ ] Repeated-crash dialog

### CI & release
- [ ] `release.yml` builds and uploads a draft release
- [ ] `RELEASING.md` written
- [ ] Smoke test on a clean Windows VM

### Licensing
- [x] Bundled binary licenses in `THIRD_PARTY.md` and installer (Stage 2: every bundled tool's license texts, and those of the libraries in mpv's DLLs, pinned by SHA-256 and installed beside the tools; Madoyomi's `LICENSE`, `NOTICE` and `THIRD_PARTY.md` at the top of the install folder; `THIRD_PARTY.md` lists each with its source. Whether releases also attach the GPL sources is open until Stage 5, §10)

## 6. Exit criteria

- [ ] A clean Windows 11 machine with no Java installs Madoyomi from the NSIS installer and can browse, play and read without any extra setup.
- [ ] Installing version N and then publishing N+1 updates the app in place through the auto-updater, keeping user data.
- [ ] "Export logs" produces a zip with shell, backend and mpv logs.

## 7. Risks & notes

- jlink may drop a module some extension needs at runtime → run the Phase 2 compat harness against the jlink JRE.
- Unsigned installers trigger SmartScreen warnings; document it for early users. Stage 2: no signing for now (the user's decision, §9); every new version warns until it has reputation, so the release notes (Stage 5) say "More info → Run anyway".
- GPL obligations for bundled mpv/ffmpeg builds: ship licenses and source links. Stage 2: the license texts ship beside each tool and `THIRD_PARTY.md` links each source at the build's version; mpv's build clones several libraries at their newest commit, so for those the link is the project. Whether each release attaches the corresponding source is open until Stage 5 (§10).

## 8. Decisions closed here

- Code signing approach. **Closed in Stage 2: none for now** (the user's decision, §9): the installers are unsigned, SmartScreen warns on each new version, and signing (SignPath Foundation once the repo is public, or a certificate) can come later without changing the build.
- Windows-only, or keep macOS/Linux builds possible (PLAN.md §7): decide whether CI also builds other platforms. **Closed in Stage 2: Windows-only** (the user's decision, §9): CI builds and tests Windows only; the code stays portable where it already is, with no promise for other platforms.

### Stage plan (2026-11-17)

Agreed with the user at the end of Phase 6 (phase-6 §9 "Stage 7"): six stages, the bundle first. Each stage ends in something verifiable and a commit:
1. **The runtime and binaries** (steps 1–3): `tools/jlink.ps1` (the modules from `jdeps` plus the ones extensions need, ≤ 60 MB, the Phase 2 compat harness run on it), `tools/binaries.json` with pinned mpv, ffmpeg and yt-dlp builds and their SHA-256, `tools/fetch-binaries.ps1` checking them, `bundle.resources`/`externalBin` in `tauri.conf.json`, and the production paths in `sidecar.rs` (the bundled JRE, jar and tools passed to the backend, as `MADOYOMI_MPV`/`MADOYOMI_FFMPEG`/`MADOYOMI_YTDLP` are in dev). Verified by the compat harness on the jlink JRE, `cargo test`, and a `pnpm tauri build` that runs on the dev desktop with nothing on PATH (no Java, mpv, ffmpeg or yt-dlp).
   - *Done* (2026-11-17). `tools/jlink.ps1` links jdeps' modules plus four that extensions reach at runtime (`jdk.crypto.ec`, `jdk.localedata`, `jdk.charsets`, `jdk.zipfs`) and leaves out six that jdeps finds in library code Madoyomi never runs, each with its reason: **59.99 MiB, 22 modules**. `tools/lib/JreProbe.java` checks every new runtime: JS through the QuickJs stub, dates in seven locales, charsets, zipfs, TLS, imaging, prefs, JDBC and JNDI. On that runtime the compat harness gave anime 18/20 (as recorded) and manga 8/10 (kagane's site went behind Cloudflare since the recorded run, and it fails the same on the JDK); smoke gave 24/24 and the live spec 17/17. `tools/binaries.json` pins mpv 0.41.0 (the mpv project's build), ffmpeg 9.0.2 (gyan.dev essentials) and yt-dlp 2026.10.26 with SHA-256 checked against GitHub's digests and the publishers' sums, and `tools/fetch-binaries.ps1` fails on a mismatch. The resources are in `tauri.release.conf.json` (merged by `tools/build-release.ps1`), so dev and `cargo test` don't need them; `sidecar.rs` drops the resource dir's `\\?\` prefix and has tests for java and the bundled jar (21 tests). A release build run with nothing on PATH (no Java, mpv, ffmpeg or yt-dlp) on a fresh data dir was healthy in 7.9 s: two repos added, KickAssAnime and MangaPill installed, Kingdom read, an episode played in the bundled mpv, an HLS episode downloaded by the bundled ffmpeg (357 MB) and played from its file, and Settings → Player and → Downloads showed the bundled tools. The bundle is about 461 MiB beside a 4.8 MB exe; the installers are 275 MiB (NSIS) and 301 MiB (MSI), not installed yet (Stage 2).
2. **The installer** (steps 4–7, 16): NSIS per-user (no admin, a Start Menu shortcut, uninstall keeping the data unless asked), MSI with a fixed upgrade code, one version for `package.json`, `tauri.conf.json`, `Cargo.toml` and Gradle (a sync script), the icon, product name and publisher, the bundled binaries' licenses in `THIRD_PARTY.md` and the installer. Closes the code-signing decision and the Windows-only one (PLAN.md §7), both the user's. Verified by installing, upgrading and uninstalling on the dev desktop and a clean VM if one is at hand.
   - *Done* (2026-11-17). One version: `VERSION` at the repo root, and `tools/version.ps1` checks (or with `-Set`/`-Sync` writes) its copies in `package.json`, `tauri.conf.json`, `Cargo.toml`, `Cargo.lock` and the server's `Constants.kt` (`vX.Y.Z`), refusing anything but a numeric X.Y.Z within the MSI's limits; `build-release.ps1` runs it first and also refuses a jar of another version. The installers: publisher "Madoyomi contributors" (the user's), per-user NSIS into `%LOCALAPPDATA%\Madoyomi`, the MSI's upgrade code pinned. Tauri's "delete the application data" box only removed the WebView's storage, so `installer/hooks.nsh` makes it remove the data dir too (`MADOYOMI_DATA_DIR`, else `%APPDATA%\Madoyomi`, only with Madoyomi's `server.conf` in it, never during an update; `/REMOVEDATA` for a silent uninstall), and `installer/English.nsh` labels it "Delete my library, settings and downloads too". Licenses: the license texts of mpv, the 15 libraries in its build, ffmpeg and yt-dlp are pinned by SHA-256 in `binaries.json` and installed beside each tool, with Madoyomi's `LICENSE`, `NOTICE` and `THIRD_PARTY.md` at the top. Decided by the user: no signing for now, Windows-only, the MSI kept but installed and upgraded in Stage 6, "remove data" removes everything, and Tauri's icon kept for now. Checked live on the dev desktop, with the user's real data dir untouched throughout: installed per-user without admin (silently and through its UI); the installed app ran with nothing on PATH on a scratch data dir (repos, MangaPill and AnimeGG installed, Kingdom read, One Piece played in the bundled mpv); upgraded 0.1.0 → 0.1.1 silently and through the UI with the library and history kept; uninstalled keeping the data, and with "remove data" on a scratch copy only (a folder without `server.conf` kept). The NSIS installer is 275.1 MiB, the MSI 301.1 MiB, and the install 461.6 MiB in 255 files (§10). After the stage the user installed the MSI (0.1.0) on the dev desktop, upgraded it to 0.1.1 (one entry, the data kept) and uninstalled it (the data kept) (§10, "Stage 2 follow-up").
3. **Logs and diagnostics** (steps 11–13): rolling logs for the backend (logback, size and count limits), the shell (`tauri-plugin-log`) and mpv (`--log-file` per session, the last N kept) in the data dir's `logs`, Settings → "Export logs" (a zip with the logs, the versions and the extension list, no library data unless asked), and the dialog after repeated backend crashes (Export logs, Open logs folder). Also the downloads folder of one's choice, left from Phase 6 (phase-6 §9 "Stage 4"), moving what is downloaded. Verified by tests and live.
4. **The auto-updater** (steps 8–10): `tauri-plugin-updater` with a keypair (the private key only as a CI secret), `latest.json`, the check on start (a setting to turn it off), the dialog with the release notes, and a clean stop of the backend and mpv before the installer runs. Verified by updating N → N+1 from a local feed with the user data kept.
5. **The CI release** (steps 14–15): `.github/workflows/release.yml` on `windows-latest` (JDK, Node/pnpm, Rust, the caches, the tests, the release build, the installers and `latest.json` uploaded to a draft release) and `RELEASING.md`. Verified by a run that makes a draft release.
6. **The exit run**: every §6 criterion on a clean Windows 11 machine without Java (install from the NSIS installer, browse, play and read with no setup; N → N+1 through the updater keeping the data; Export logs with the shell's, backend's and mpv's logs), and a fix round.

## 9. Decision record

### Stage 1 (2026-11-17)

Decided in the stage (defaults, not asked: neither license nor size made the builds a real choice, see below):
- **The runtime: jdeps' modules plus four, minus six.** Added, because extensions or ServiceLoader reach them at runtime and jdeps can't see that: `jdk.crypto.ec` (TLS with ECDHE/ECDSA), `jdk.localedata` (dates in other languages), `jdk.charsets` (Shift_JIS, EUC-KR, GBK, …), `jdk.zipfs`. Left out, though jdeps lists them, because only code paths Madoyomi never takes reach them: `jdk.jdi` and `jdk.attach` (javassist's HotSwapper), `java.instrument` (debug agents), `java.security.jgss` (PostgreSQL's GSS login, Jetty's SPNEGO), `java.compiler` (H2's Java functions, jte's compiler) and `jdk.jfr` (Truffle's JFR events, which only its optimizing JVMCI runtime emits; on a stock JDK GraalJS runs on the fallback runtime, and Truffle's own warning says so). `lib/jvm.lib` (a static link library) is left out too. Every reason is in `tools/jlink.ps1`; a module goes back when a check proves it is reached (`-ExtraModules` tries one). Without the six the runtime was 63.8 MB; with them out it is 59.99 MiB, within the 60 MB target. All locales are kept (`--include-locales` would have saved more, at the cost of dates in the languages left out).
- **A probe on every runtime:** `tools/lib/JreProbe.java`, compiled with the JDK's javac against the jar and run on the new runtime by `jlink.ps1`, which fails if a check fails. It costs about 10 s.
- **mpv: the mpv project's own 0.41.0 Windows build** (`mpv-v0.41.0-x86_64-w64-mingw32.zip`, from the v0.41.0 tag's commit), not shinchiro's weekly builds (which the dev desktop runs): a release tag with a fixed URL and GitHub's digest, a zip that .NET unpacks on Windows PowerShell 5.1 (shinchiro's are `.7z`, which needs 7-Zip or a recent `tar`), GPL like any mpv build with FFmpeg. It ships as `mpv.exe`, `mpv.com` and 23 DLLs (101 MB unpacked); the register scripts are left out.
- **ffmpeg: gyan.dev's 9.0.2 essentials build, `ffmpeg.exe` only** (100.5 MB): a versioned release with a fixed GitHub URL and gyan.dev's own checksum, GPL-3.0. BtbN's builds (LGPL ones too) are rolling (`latest`), so they can't be pinned; the LGPL variant wasn't smaller and would gain nothing for a separate program. `ffprobe` and `ffplay` are left out: the backend runs `ffmpeg` only, and yt-dlp gets `--ffmpeg-location`.
- **yt-dlp 2026.10.26, `yt-dlp.exe`** (22.5 MB), checked against the release's `SHA2-256SUMS`.
- **The pins:** each SHA-256 is the download's, matched with GitHub's digest of the release asset and, where the publisher has one, its own sums (gyan.dev's `.sha256`, yt-dlp's `SHA2-256SUMS`); `sha256From` in `binaries.json` says which. A mismatch fails the script and deletes the download.
- **The bundle's resources live in `app/src-tauri/tauri.release.conf.json`,** passed with `--config` by `tools/build-release.ps1`, not in `tauri.conf.json`: Tauri's build script checks that every resource exists, so with them in the main config `pnpm tauri dev`, `cargo test` and clippy would fail on any checkout without the binaries. A plain `pnpm tauri build` still works but bundles nothing (the README says so).
- **Layout beside the exe** (Tauri's resource dir): `jre/`, `server/Madoyomi-Server.jar`, `mpv/`, `ffmpeg/ffmpeg.exe`, `yt-dlp/yt-dlp.exe`, `player/mpv.conf` and `player/input.conf` (not `IPC.md` or the `.gitignore`), as `sidecar.rs` already looked; no `externalBin` (Tauri's sidecar naming adds a target triple, and mpv needs its DLLs beside it anyway).
- **`sidecar.rs`:** the resource dir's `\\?\` prefix is dropped (a verbatim UNC path is kept), so the paths the backend and mpv get are plain; `java_from` split out of `resolve_java` for tests. A release build never falls back to the repo; without its `jre/` it tries `java` from PATH, as before.
- **`build-release.ps1` builds the installers by default** (`-NoBundle` for the exe and its resources only); what they install is Stage 2's.

### Stage 2 (2026-11-17)

Asked and decided by the user:
- **Code signing: none for now.** The installers are unsigned. SmartScreen shows "Windows protected your PC" on each new version (More info → Run anyway) until it has reputation; since 2024 an EV certificate no longer skips that, and OV, EV and Azure Artifact Signing all build reputation the same way, while a self-signed certificate counts as unsigned. SignPath Foundation (free for open source: a public repo with an OSI license, and SignPath Foundation named as the publisher) is the first option to revisit once the repo is public; Stage 5's workflow leaves a place for a signing step. The SmartScreen steps go into the release notes and `RELEASING.md` (Stage 5).
- **Windows-only.** CI (Stage 5) builds and tests Windows only. Code that is already portable stays so (the job object is `cfg(windows)`), with no promise or bundling for other platforms (PLAN.md §7).
- **The MSI is kept, its install tested in Stage 6.** Tauri's MSI installs per-machine (Program Files, UAC), and the agent's job runner isn't elevated. Stage 2 builds it with the fixed upgrade code and reads its tables; installing, upgrading and uninstalling it are Stage 6's, on the clean machine. NSIS is the installer the release leads with. (The user then installed, upgraded and uninstalled it on the dev desktop the same day, §10 "Stage 2 follow-up"; the clean machine stays Stage 6's.)
- **"Remove data" removes everything:** the whole data dir (the library, settings, extensions, cache, logs and the downloads inside it) and the WebView's storage, when the uninstaller's box is ticked; never during an update.
- **The publisher is "Madoyomi contributors"** (as in `NOTICE`): in Add/Remove Programs, the exe's details and the MSI's Manufacturer, and the registry key `HKCU\Software\Madoyomi contributors\Madoyomi` (the install folder, for the next install).
- **The icon stays Tauri's default for now** (open, for a later stage).

Decided in the stage (defaults, not asked):
- **One version in `VERSION`** (repo root, `X.Y.Z`), with `tools/version.ps1` as the only writer: no switch checks, `-Sync` writes the copies, `-Set X.Y.Z` both, `-SelfTest` runs on a temporary copy. It accepts only a numeric X.Y.Z with major and minor ≤ 255 and the third part ≤ 65535 (the MSI's limits; the server also derives an integer version code from it). The copies: `app/package.json`, `tauri.conf.json` (the installers' and the exe's version), `Cargo.toml` and `Cargo.lock`, and `Constants.kt` (`v` prefixed; the jar's name and `BuildConfig`). Only the version text changes in each file.
- **`build-release.ps1` checks the version first**, and refuses a jar whose name doesn't carry it (`Madoyomi-Server-vX.Y.Z.jar`), so `-NoJar` can't bundle a jar of another version; it lists only this version's installers.
- **The NSIS installer is per-user** (`installMode: currentUser`, Tauri's default made explicit) into Tauri's default `%LOCALAPPDATA%\Madoyomi` (not `Programs\Madoyomi`: that is what the template uses), with a Start Menu shortcut straight in Programs (no folder) and Tauri's "Create desktop shortcut" box on the finish page (ticked by default; a silent install creates it, `/NS` doesn't). No license page: the MPL needs no acceptance, so the license files are installed instead. WebView2 stays Tauri's default (its bootstrapper, if missing; Windows 11 has it), and so do downgrades (allowed).
- **"Remove data" in `installer/hooks.nsh`:** Tauri's box on its own deletes only `%APPDATA%\com.madoyomi.desktop` and `%LOCALAPPDATA%\com.madoyomi.desktop` (the WebView's storage); the hook removes the data dir the app uses, `MADOYOMI_DATA_DIR` when set (an absolute path), else `%APPDATA%\Madoyomi`, only when Madoyomi's `server.conf` is in it, and never in update mode. `uninstall.exe /S /REMOVEDATA` ticks it for a silent uninstall. Honouring `MADOYOMI_DATA_DIR` keeps the uninstaller in step with the app (and kept the tests off the real data).
- **The box's label in `installer/English.nsh`:** "Delete my library, settings and downloads too" instead of "Delete the application data". A custom language file replaces Tauri's, so it is a copy of Tauri 2.12's 27 English strings with that one changed; after a Tauri update it is compared with the generated `target/release/nsis/x64/English.nsh` (makensis warns about a missing string).
- **The MSI's upgrade code is pinned** to the one Tauri derived for the Stage 1 build (`3f047ae9-ce0d-5d68-b160-749f913bed03`), so it no longer depends on the product name and the first MSI upgrades too.
- **The exe's details** come from `bundle.publisher` and `bundle.copyright` ("Copyright (C) Madoyomi contributors. Licensed under the MPL-2.0."), with a short and a long description.
- **License texts per tool, pinned:** `binaries.json`'s `licenses` (each with its URL at the upstream tag, its SHA-256 and what it covers), fetched and checked by `fetch-binaries.ps1` like the downloads (a mismatch fails and deletes it) and copied into the tool's folder; a changed list or hash redoes the folder (the stamp keeps the layout). mpv: its `Copyright`, `LICENSE.GPL` and `LICENSE.LGPL` at the top, and the texts of FFmpeg, libass, dav1d, FreeType (2), FriBidi, HarfBuzz, Little CMS, libplacebo, shaderc, SPIRV-Cross, the Vulkan loader, zlib, LuaJIT, the GCC runtime (2) and winpthreads in `mpv/licenses/` (libiconv's LGPL-2.1 is `LICENSE.LGPL`). ffmpeg: its `LICENSE` and `README.txt` (the build's libraries and versions) from the pinned zip. yt-dlp: its `LICENSE` and `THIRD_PARTY_LICENSES.txt`. The runtime's `legal/` was already in `jre/`. `THIRD_PARTY.md` lists each with its version and source.
- **`LICENSE`, `NOTICE` and `THIRD_PARTY.md` at the top of the install folder** (resources in `tauri.release.conf.json`; the ffmpeg and yt-dlp folders are now bundled whole, with their license files).

## 10. Findings log

### Stage 1 (2026-11-17)

- **Baseline before the stage** (main at `7b376f0`, Phase 6's exit run merged as #45; branch `feat/phase-7-runtime`, job 595, before any edit):
  - server: `.\gradlew.bat build` green on the first run with **853** tests (core 703 with 8 skipped, AndroidCompat 7, ext-host 8, api 135); `tools/smoke-api.ps1 -NoBuild` 24/24;
  - app: 71 files (70 + 1 skipped), **697 passed + 17 skipped**; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: **16/17 on the first run**: the history test found another entry first in the history list (`operations.live.spec.ts:912`); the rerun (job 602) passed **17/17 with 109 documents**, so it is a flaky ordering, not reproduced since (both later runs passed);
  - `cargo test` 18; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **jdeps on the server jar** (62–68 s on the 172 MiB shadow jar) lists 18 modules. A jlink of all of them plus the runtime four was **63.8 MB** (30 modules with the transitive ones). Which library references each extra module was read from `jdeps -verbose:package` and a scan of the jar's classes (§9). Without the six and `jvm.lib`: **59.99 MiB, 22 modules**, linked in 8 s. The largest parts: `lib/modules` 41.5 MB, `bin/server/jvm.dll` 14 MB.
- **`JreProbe` on the runtime:** all checks pass (JS through the QuickJs stub on GraalJS, `javax.script`, dates in Spanish, Brazilian Portuguese, French, Russian, Japanese, Indonesian and Turkish, 9 legacy charsets, a zip file system, TLS 1.3 with ECDHE_ECDSA suites and SunEC, a PNG round trip headless, preferences, `HttpClient` and the management beans, H2's driver and JNDI). Truffle logs that it uses its fallback runtime ("Version check failed": its optimizing runtime wants JDK 25), on the JDK as on the jlink runtime.
- **Found: upstream's Duktape stub has no engine on any runtime.** `com.squareup.duktape.Duktape` asks `javax.script` for `rhino`, which the jar doesn't register, so `evaluate` throws a `NullPointerException`. It does the same on the full JDK, so it is not the runtime's doing. Extensions that use Duktape instead of QuickJs would fail on it; none of the measured ones did. Not fixed here (a server change); noted for a later stage or Phase 8.
- **The compat harness on the jlink runtime** (`-Java app\src-tauri\resources\jre\bin\java.exe`, reports in the gitignored `tools/compat-test/out/*-jlink.md`; the committed reports stay the JDK's of 2026-11-06):
  - anime top 20: **18 of 20**, the same as `docs/research/anime-compat.md` (AnimePahe behind Cloudflare, HentaiHaven's video host down); 8.2 min; started in 8.7 s (10.5 s recorded on the JDK); no JVM abort; 1 extension loader kept after the uninstall, by streamwish's OkHttp TLS read, as recorded;
  - manga sample: **8 of 10** against 9 recorded. comix (needs a WebView) as recorded; **kagane** fails at Popular with `CLOUDFLARE`: its site answers with a Cloudflare challenge (the harness's own probe from PowerShell gets HTTP 403 too). Rerun alone on the full JDK (job 611): the same failure, so it is the site, not the runtime. 2 loaders kept after the uninstall, by kagane's pending OkHttp read (0 recorded);
  - smoke on the jlink runtime: **24/24**;
  - the live spec on the jlink runtime (`live-data`, job 612): **17/17 with 109 documents**; KickAssAnime's HLS episode through ffmpeg in 153 s, AnimeGG's direct file in 36 s.
- **The binaries** (job 598, then `fetch-binaries.ps1`, job 608): mpv's zip holds a second zip (`mpv-git-2025-12-21-41f6a64-x86_64.zip`, GitHub Actions' wrapping), so `binaries.json` has `inner`; mpv reports `v0.41.0-dev-g41f6a6450`, and `41f6a64` is the v0.41.0 tag's commit (checked through GitHub's API). The download hashes matched GitHub's digests, gyan.dev's `ffmpeg-release-essentials.zip.sha256` (release 9.0.2) and yt-dlp's `SHA2-256SUMS`. A second run skips each tool (its stamp); a manifest with a wrong hash failed with "SHA-256 mismatch … (download deleted)". The stamps first sat inside the bundled folders, and mpv's was bundled; they moved to `resources/.stamps/` (the stale copy in `target/release/mpv` stays until a clean build, since Tauri doesn't delete files there).
- **The release build** (`tools/build-release.ps1`, job 613): 10.5 min (the release Rust build 4 min, NSIS and MSI the rest). The resources landed beside `target/release/madoyomi.exe` where `sidecar.rs` looks: `jre` 60.0 MiB, `server` 172.2 MiB, `mpv` 101.0 MiB, `ffmpeg` 100.5 MiB, `yt-dlp` 22.5 MiB, `player` 2 files; with the 4.8 MB exe about **461 MiB**. Installers: NSIS 275.0 MiB, MSI 301.0 MiB (built, not installed: Stage 2). A rerun with `-NoJar -NoBundle` took 142 s.
- **Live: the release build with nothing on PATH** (`app-start7.ps1`: PATH stripped of every Java, mpv, ffmpeg and yt-dlp entry and none of the five commands found, the `MADOYOMI_*` tool overrides and `JAVA_HOME` unset; fresh data dir `tools/dev-data/agent/p7-release`), through the UI (computer use):

  | Check | Result |
  |---|---|
  | start | healthy after **7.9 s**; `sidecar.log`: `target\release\jre\bin\java.exe -jar target\release\server\Madoyomi-Server.jar`, `player: mpv target\release\mpv\mpv.exe, config target\release\player`, `downloads: ffmpeg target\release\ffmpeg\ffmpeg.exe, yt-dlp target\release\yt-dlp\yt-dlp.exe`; the backend process ran from the bundled `jre` |
  | Settings → Downloads | ffmpeg "9.0.2-essentials_build-www.gyan.dev … `target\release\ffmpeg\ffmpeg.exe` (from MADOYOMI_FFMPEG)"; yt-dlp "2026.10.26 · `target\release\yt-dlp\yt-dlp.exe` (from MADOYOMI_YTDLP)" |
  | Settings → Player | mpv "v0.41.0-dev-g41f6a6450 … `target\release\mpv\mpv.exe` (from MADOYOMI_MPV)" |
  | repos | yuzono (248 extensions) and Keiyoushi (1692) added, signing keys trusted |
  | extensions | KickAssAnime 14.64 and MangaPill 1.6.1 installed |
  | read | MangaPill → Kingdom → Start reading: Chapter 1, 1/62, pages turned |
  | play | "Oshi no Ko" Ep. 10 from the picker (VidStreaming 1080p, the source's pick): the bundled `mpv.exe` (process path), over its pipe 1080p, playing at 30 s, 6 tracks (`sid=3 aid=1`); quit over IPC, no mpv left |
  | HLS download | Ep. 11 through the bundled `ffmpeg.exe` (process path; the log "ffmpeg HLS download from hls.krussdomi.com"): **357 MB in about 5.5 min**, with 4 subtitle files; Downloaded in the list |
  | the download played | Play in Downloads: the bundled mpv on `…\p7-release\downloads\anime\KickAssAnime (EN)\_Oshi no Ko_\Japanese_Ep. 11 - Idol.mkv`, 1080p, 6 tracks |
  | close | the window closed: "backend stopped (exit code 0)", no mpv or madoyomi left |
  | the token | no `token=` in `sidecar.log`, `madoyomi.log` or `mpv.log` |

- **Counts after the stage:**
  - server: no change, so not rebuilt for tests: the baseline's **853** stand; smoke **24/24** on the JDK (baseline) and on the jlink runtime;
  - app: 71 files (70 + 1 skipped), **697 passed + 17 skipped**, unchanged (no UI change); `pnpm format`, `pnpm format:check`, `pnpm typecheck`, `pnpm lint` and `pnpm build` clean (job 622);
  - the live spec: **17/17 with 109 documents** on the JDK (job 602) and on the jlink runtime (job 612);
  - `cargo test` **21** (3 new: java's resolution, the bundled jar, the `\\?\` prefix); `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean (jobs 610, 623).
- **The tooling.** As in Phases 5–6, the agent's shell was the Linux VM and the Windows side ran through the job runner (jobs 593–623). New scratch in the gitignored `tools/dev-data/agent`: `app-start7.ps1` (the release exe with nothing on PATH), `seed-live7.ps1` (`seed-live.ps1` with `-Java`), `binaries-bad.json`; the data dir `p7-release`. `tools/bin/downloads` (gitignored) caches the three downloads.
- **Not checked, and why:**
  - installing, upgrading or uninstalling the NSIS/MSI installers, and the installed size under Program Files or `%LOCALAPPDATA%`: Stage 2 (here the size is the bundle beside the exe);
  - a clean machine without Java: Stage 6 (here PATH was stripped on the dev desktop; the JDKs are still installed, but nothing could find them);
  - yt-dlp taking over from ffmpeg: as in Phase 6, ffmpeg never failed; yt-dlp's `--version` ran in `fetch-binaries.ps1` and Settings;
  - an extension that needs Duktape, `java.net.http` or a non-English date format end to end on the jlink runtime: none of the measured ones does; the probe covers the runtime's side;
  - mpv's window itself (masked to the agent): checked over its IPC pipe and the process path.

### Stage 2 (2026-11-17)

- **Baseline before the stage** (main at `a7d840e`, Stage 1 merged as #46; branch `feat/phase-7-installer`, job 627, before any edit):
  - server: `.\gradlew.bat build` green with **853** tests (core 703 with 8 skipped, AndroidCompat 7, ext-host 8, api 135); smoke 24/24;
  - app: 71 files (70 + 1 skipped), **697 passed + 17 skipped**; lint, typecheck, format:check and build clean;
  - the live spec: **17/17 with 109 documents** on the first run (the history ordering flake of Stage 1 didn't come back);
  - `cargo test` **21**; build, clippy and fmt clean.
- **What Tauri's NSIS template does** (Tauri 2.12, read from the generated `installer.nsi`), and what that meant:
  - the per-user default folder is `%LOCALAPPDATA%\<productName>` (`%LOCALAPPDATA%\Madoyomi`), not `Programs\Madoyomi`; the shell's resource dir is that folder, and the data dir stays `%APPDATA%\Madoyomi` (Roaming), so they don't meet;
  - the uninstaller's "Delete the application data" box removes only `%APPDATA%\<identifier>` and `%LOCALAPPDATA%\<identifier>` (`com.madoyomi.desktop`: the WebView's storage, about 300 MB on the dev desktop) and the install-location key: the library would have stayed. Hence `installer/hooks.nsh` (§9). A silent uninstall can't tick the box, hence `/REMOVEDATA`. Without the box, `HKCU\Software\Madoyomi contributors\Madoyomi` (the folder for the next install) stays too;
  - a silent install also creates a desktop shortcut (the finish page's box is skipped); `/NS` skips both shortcuts;
  - a silent install over an older version copies over it without uninstalling it first (files the new version no longer ships would stay); the UI install offers "Uninstall before installing" (the default), which runs the old uninstaller in its own window (its confirm page with the box, then Close) and goes on;
  - the uninstaller copies itself to `%TEMP%\~nsuN.tmp\Un.exe` and returns at once, so a script waits for the install folder's `uninstall.exe` to go;
  - downgrades are allowed (Tauri's default), so 0.1.0 installed over 0.1.1 silently.
- **mpv's build and its libraries** (read from `ci/build-mingw64.sh` and `.github/workflows/build.yml` at v0.41.0, and from the bundled `mpv --version`): the build ships 23 DLLs; it builds FFmpeg with `--enable-gpl` (a git snapshot, `git-2025-12-21-1fcb201`, libavcodec 62.23.102), libplacebo v7.358.0, and clones FFmpeg, libass, dav1d, Little CMS, shaderc, SPIRV-Cross, the Vulkan loader, libplacebo and LuaJIT at their newest commit on the build day; libiconv 1.18, zlib 1.3.1, FreeType 2.14.1, FriBidi 1.0.16 and HarfBuzz 12.2.0 are pinned by the script; the GCC runtime and winpthreads come from Ubuntu's mingw-w64 toolchain. Most of these licenses (ISC, BSD, MIT, Apache-2.0, FTL, Zlib) ask for their notice to travel with the binary, so their texts ship in `mpv/licenses/`, taken from the tag named in `binaries.json` (the license files don't change between those commits). **Open for Stage 5:** whether each release also attaches an archive of the GPL/LGPL corresponding source (mpv's tag and the snapshots it built, FFmpeg n9.0.2 and gyan.dev's build's libraries), or links the sources as now (`THIRD_PARTY.md`); for the libraries mpv cloned at HEAD, the exact commits are only known for FFmpeg (`1fcb201`).
- **The license texts** (20 for mpv, 2 for yt-dlp, 2 from ffmpeg's zip; 519 KB together, mpv's 197 KB) fetched from `raw.githubusercontent.com` at the tags, pinned in `binaries.json`: a second run skips each tool (the stamp, now with the layout); a manifest with a wrong license hash fails with "SHA-256 mismatch … (download deleted)" (job 656), and the right one passes again.
- **The version script** (jobs 629, 634): the check lists the five copies (all 0.1.0); `-SelfTest` passes 21 checks (a set writes every copy and nothing else, the server keeps its `v`, only madoyomi's `Cargo.lock` entry changes, a mismatch fails and is named, a sync repairs it, 9 bad versions refused); on a copy with `Constants.kt` at `v0.1.0-dev` the check exits 1 naming it, `-Sync` repairs it, and `-Set 0.1.0-dev` is refused. `-Set 0.1.1` and back to `-Set 0.1.0` left no diff in the repo.
- **The builds:** 0.1.0 with `-NoJar` in 462 s (job 633), 0.1.1 with its own jar in 869 s (job 635; the jar 4:45 of it), and the final 0.1.0 with the label in 468 s (job 650), no makensis warning. The NSIS installer is **275.1 MiB** and the MSI **301.1 MiB** (as in Stage 1: the license texts add under 1 MB). The generated `installer.nsi` includes `hooks.nsh` and the custom `English.nsh`, with `MANUFACTURER "Madoyomi contributors"`.
- **The MSI, read without installing it** (job 652, the Windows Installer API): 0.1.0 and 0.1.1 both have `UpgradeCode {3F047AE9-CE0D-5D68-B160-749F913BED03}`, their own `ProductCode`, `ProductVersion` 0.1.0 and 0.1.1, Manufacturer "Madoyomi contributors", `ALLUSERS=1` (per-machine), and an `Upgrade` row from version 0 (inclusive) up, so 0.1.1 would replace 0.1.0; 254 files, the license texts among them. Not installed (Stage 6, §9).
- **Live: the NSIS installer on the dev desktop.** The job runner runs at medium integrity (no admin). The user's real `%APPDATA%\Madoyomi` (138 files, its database last written 2026-11-11) was the same after every step; the app always ran on scratch data dirs under `tools/dev-data/agent`; the WebView's real storage was backed up before the "remove data" runs and restored after (1643 files).

  | Check | Result |
  |---|---|
  | silent install 0.1.0 (`/S`) | exit 0 in 37 s, no UAC prompt; `%LOCALAPPDATA%\Madoyomi`: **255 files, 461.6 MiB** (`jre` 60.0, `server` 172.2, `mpv` 101.2 with its 20 license texts, `ffmpeg` 100.6, `yt-dlp` 22.7 MiB, `player` 2 files, `LICENSE`, `NOTICE`, `THIRD_PARTY.md`, the 5.0 MB exe and `uninstall.exe`); Add/Remove Programs: Madoyomi 0.1.0, publisher Madoyomi contributors, 472,639 KB; the exe's details 0.1.0, Madoyomi contributors, the copyright; Start Menu `Programs\Madoyomi.lnk` → the exe; a desktop shortcut too (silent) |
  | the installed app, nothing on PATH | `app-start7.ps1` (no java, mpv, ffmpeg or yt-dlp found; data dir `p7-inst`): healthy in **8.6 s** with "Madoyomi-Server v0.1.0", java from `…\Local\Madoyomi\jre`, the jar, mpv, ffmpeg and yt-dlp from the install folder (`sidecar.log`) |
  | repos, extensions | Keiyoushi (1692) and yuzono (248) added, signing keys trusted; MangaPill and AnimeGG installed |
  | read | MangaPill → Kingdom → added to the library → Start reading: Chapter 1, 1/62, pages turned; "Continue: Chapter 1" after |
  | play | AnimeGG → One Piece → added → Ep. 1180, 1080p (the source's pick): the installed `mpv.exe` (process path), over its pipe 1080p, playing at 16 s; quit over IPC |
  | close | "backend stopped (exit code 0)", no mpv or madoyomi left; no `token=` in `sidecar.log`, `madoyomi.log` or `mpv.log` |
  | silent upgrade to 0.1.1 | exit 0 in 31 s; 0.1.1 in Add/Remove Programs and the exe; the data dir unchanged (97 files, the database's time the same); the app healthy in 10.3 s with "Madoyomi-Server v0.1.1", the sidebar "v0.1.1"; Library: One Piece; History: Kingdom, Chapter 1, page 4 of 62 |
  | silent uninstall, keeping the data | the folder, the uninstall entry and both shortcuts gone; the data dir kept (98 files), the WebView's storage kept |
  | "remove data", guard | `/S /REMOVEDATA` with `MADOYOMI_DATA_DIR` on a folder without `server.conf`: the folder kept, the WebView's storage removed (Tauri's part, so the box was ticked) |
  | "remove data" | `/S /REMOVEDATA` on a copy of the scratch data dir (98 files): the copy removed, the original kept; the install-location key removed |
  | UI install 0.1.0 | its pages could be seen and clicked: Welcome, the location (`C:\Users\…\AppData\Local\Madoyomi`, 461.6 MB, no UAC), progress, the finish page with "Run Madoyomi" and "Create desktop shortcut" (both unticked: no app started, no desktop shortcut; the Start Menu one made) |
  | UI upgrade to 0.1.1 | "Already Installed: an older version … Uninstall before installing" (the default) → the old uninstaller's window (box unticked, read through Win32 since computer use can't be granted it) → Uninstall, Close → the location page → installed |
  | the final build | installed silently over 0.1.1 (a downgrade, allowed) in 25 s, healthy in 6.7 s; the uninstaller's confirm page shows "Delete my library, settings and downloads too", unticked; Esc cancelled it (nothing removed); the last silent uninstall kept the data. Nothing of Madoyomi is left installed |

- **Counts after the stage:**
  - server: no change (`Constants.kt` went to 0.1.1 for the upgrade build and back, with no diff), so not rebuilt for tests: the baseline's **853** and smoke **24/24** stand;
  - app: 71 files (70 + 1 skipped), **697 passed + 17 skipped**; `pnpm format`, `format:check`, `typecheck`, `lint` and `build` clean (job 654);
  - the live spec: no server or operation change, so the baseline's **17/17 with 109 documents** stands;
  - `cargo test` **21**, `cargo build`, `clippy --all-targets` and `fmt --check` clean with the installer settings (job 632) and with `app/src-tauri/resources/` moved away (job 655);
  - `tools/version.ps1 -SelfTest` 21/21.
- **The tooling.** As in Stage 1, the agent's shell was the Linux VM and the Windows side ran through the job runner (jobs 626–656). New scratch in the gitignored `tools/dev-data/agent`: `inst7.ps1` (silent install, report and uninstall, and the real data dirs' counts), `wininfo.ps1` (a window's controls through Win32, and keys sent to it: the uninstaller's window can't be granted to computer use), `msiinfo.ps1` (an MSI's tables), `installers/` (the 0.1.0, 0.1.1 and final installers, and the 0.1.1 jar moved out of `server/api/build`), `webview-backup/`, the data dirs `p7-inst` and `p7-guard`, `vtest/`, `fetch-test/`.
- **Not checked, and why:**
  - installing, upgrading and uninstalling the MSI: the user's decision, Stage 6 (it needs admin; its tables were read instead). The user then installed, upgraded and uninstalled it (the follow-up below);
  - a clean machine without Java: Stage 6 (here PATH was stripped on the dev desktop);
  - ticking the uninstaller's box with the mouse: its window can't be granted to computer use; the label and state were read through Win32, and the box's effect was checked with `/REMOVEDATA`, which sets the same flag;
  - the "remove data" box on the real `%APPDATA%\Madoyomi`: never, by design; the hook's path for it (no `MADOYOMI_DATA_DIR`) is the same code with the default folder;
  - SmartScreen on a downloaded installer: the installers were built locally (no Mark of the Web), so SmartScreen didn't look at them; Stage 6 downloads one from the draft release;
  - the WebView2 bootstrapper (Windows 11 has the runtime): Stage 6's clean machine.

### Stage 2 follow-up (2026-11-17): the MSI, by the user

After Stage 2 was merged (#47), the user installed, upgraded and then uninstalled the MSI on the dev desktop from a normal PowerShell, consenting to the UAC prompt each time (the agent's job runner can't elevate); the agent read the Windows Installer logs and the result. Recorded on `feat/phase-7-msi-check` (#48) and, for the uninstall, `feat/phase-7-msi-uninstall` (docs only, no code change, so no counts were rerun: Stage 2's stand).

| Check | Result |
|---|---|
| install 0.1.0 (`msiexec /i Madoyomi_0.1.0_x64_en-US.msi /l*v install-010.log`, the build of job 633) | the wizard, one UAC consent ("Elevated credential consent provided"), "Installation completed successfully", status 0; `INSTALLDIR` `C:\Program Files\Madoyomi\`, `ALLUSERS=1`; the folder holds `ffmpeg`, `jre`, `mpv`, `player`, `server`, `yt-dlp`, `LICENSE`, `NOTICE`, `THIRD_PARTY.md`, `madoyomi.exe` and an "Uninstall Madoyomi" shortcut (Tauri's WiX template) |
| the app from Program Files | ran on the user's own data dir (`%APPDATA%\Madoyomi`): AnimeGG's Overlord added to the library, Ep. 1 downloaded and watched partway; the sidebar `v0.1.0`, Madoyomi-Server v0.1.0. Program Files is read-only for a normal user, and nothing failed: nothing writes into the install folder |
| upgrade to 0.1.1 (`msiexec /i Madoyomi_0.1.1_x64_en-US.msi /l*v upgrade-011.log`, job 635's build) | `FindRelatedProducts` set `WIX_UPGRADE_DETECTED` to 0.1.0's product code `{6514DA6A-…}`, `RemoveExistingProducts` removed it, status 0; afterwards one entry in the uninstall registry, `{576ADBCA-…}` Madoyomi 0.1.1, publisher Madoyomi contributors, `C:\Program Files\Madoyomi\` (job 658); the exe 0.1.1, 255 files (the 254 of the MSI and the uninstall shortcut) |
| the data after the upgrade | Overlord in the library, its Ep. 1 still Downloaded with "12:58 left", the history kept; the sidebar `v0.1.1`, Madoyomi-Server v0.1.1; `sidecar.log`: healthy in 7.1 s with java, the jar, mpv, ffmpeg and yt-dlp from `C:\Program Files\Madoyomi`; no `token=` in the logs |
| shortcuts, PATH | the Start Menu `C:\ProgramData\…\Programs\Madoyomi\Madoyomi.lnk` (all users, in a folder, unlike NSIS's) and `C:\Users\Public\Desktop\Madoyomi.lnk`; PATH unchanged, machine and user (the WiX template's "Environment" feature only holds the exe's component, named `Path`) |
| uninstall (`msiexec /x Madoyomi_0.1.1_x64_en-US.msi /l*v uninstall.log`) | one UAC consent, "Removal completed successfully", status 0 (product `{576ADBCA-…}` 0.1.1); afterwards (job 663) `C:\Program Files\Madoyomi`, the Start Menu folder, the public desktop shortcut and the uninstall entry gone; the data dir `%APPDATA%\Madoyomi` kept (267 files, its database untouched by the uninstall), and so is the WebView's storage. The one Madoyomi registry key left, `HKCU\Software\Madoyomi contributors\Madoyomi` (the NSIS install folder, `%LOCALAPPDATA%\Madoyomi`, and its language), is the NSIS tests' (Stage 2: kept unless "remove data" is ticked), not the MSI's |

- **Findings:** the MSI's layout and run match the NSIS install's (§10 "Stage 2"), apart from where it goes (Program Files, for all users, with UAC) and its shortcuts (a Start Menu folder, the public desktop). It has no "remove data" choice: its uninstall keeps the data dir by design (Tauri's WiX template has no such dialog).
- **Not checked:** a WiX ↔ NSIS switch (Tauri's NSIS installer finds a WiX install and uninstalls it first, behind UAC), and the clean machine (Stage 6). Nothing of Madoyomi is installed on the dev desktop now; the user's data dir stays.
