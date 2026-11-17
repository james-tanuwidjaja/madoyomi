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
- [ ] NSIS per-user installer
- [ ] MSI build + upgrade path tested
- [ ] Version sync across package.json / tauri.conf.json / Gradle
- [ ] Code-signing decision documented

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
- [ ] Bundled binary licenses in `THIRD_PARTY.md` and installer

## 6. Exit criteria

- [ ] A clean Windows 11 machine with no Java installs Madoyomi from the NSIS installer and can browse, play and read without any extra setup.
- [ ] Installing version N and then publishing N+1 updates the app in place through the auto-updater, keeping user data.
- [ ] "Export logs" produces a zip with shell, backend and mpv logs.

## 7. Risks & notes

- jlink may drop a module some extension needs at runtime → run the Phase 2 compat harness against the jlink JRE.
- Unsigned installers trigger SmartScreen warnings; document it for early users.
- GPL obligations for bundled mpv/ffmpeg builds: ship licenses and source links.

## 8. Decisions closed here

- Code signing approach.
- Windows-only, or keep macOS/Linux builds possible (PLAN.md §7): decide whether CI also builds other platforms.

### Stage plan (2026-11-17)

Agreed with the user at the end of Phase 6 (phase-6 §9 "Stage 7"): six stages, the bundle first. Each stage ends in something verifiable and a commit:
1. **The runtime and binaries** (steps 1–3): `tools/jlink.ps1` (the modules from `jdeps` plus the ones extensions need, ≤ 60 MB, the Phase 2 compat harness run on it), `tools/binaries.json` with pinned mpv, ffmpeg and yt-dlp builds and their SHA-256, `tools/fetch-binaries.ps1` checking them, `bundle.resources`/`externalBin` in `tauri.conf.json`, and the production paths in `sidecar.rs` (the bundled JRE, jar and tools passed to the backend, as `MADOYOMI_MPV`/`MADOYOMI_FFMPEG`/`MADOYOMI_YTDLP` are in dev). Verified by the compat harness on the jlink JRE, `cargo test`, and a `pnpm tauri build` that runs on the dev desktop with nothing on PATH (no Java, mpv, ffmpeg or yt-dlp).
   - *Done* (2026-11-17). `tools/jlink.ps1` links jdeps' modules plus four that extensions reach at runtime (`jdk.crypto.ec`, `jdk.localedata`, `jdk.charsets`, `jdk.zipfs`) and leaves out six that jdeps finds in library code Madoyomi never runs, each with its reason: **59.99 MiB, 22 modules**. `tools/lib/JreProbe.java` checks every new runtime: JS through the QuickJs stub, dates in seven locales, charsets, zipfs, TLS, imaging, prefs, JDBC and JNDI. On that runtime the compat harness gave anime 18/20 (as recorded) and manga 8/10 (kagane's site went behind Cloudflare since the recorded run, and it fails the same on the JDK); smoke gave 24/24 and the live spec 17/17. `tools/binaries.json` pins mpv 0.41.0 (the mpv project's build), ffmpeg 9.0.2 (gyan.dev essentials) and yt-dlp 2026.10.26 with SHA-256 checked against GitHub's digests and the publishers' sums, and `tools/fetch-binaries.ps1` fails on a mismatch. The resources are in `tauri.release.conf.json` (merged by `tools/build-release.ps1`), so dev and `cargo test` don't need them; `sidecar.rs` drops the resource dir's `\\?\` prefix and has tests for java and the bundled jar (21 tests). A release build run with nothing on PATH (no Java, mpv, ffmpeg or yt-dlp) on a fresh data dir was healthy in 7.9 s: two repos added, KickAssAnime and MangaPill installed, Kingdom read, an episode played in the bundled mpv, an HLS episode downloaded by the bundled ffmpeg (357 MB) and played from its file, and Settings → Player and → Downloads showed the bundled tools. The bundle is about 461 MiB beside a 4.8 MB exe; the installers are 275 MiB (NSIS) and 301 MiB (MSI), not installed yet (Stage 2).
2. **The installer** (steps 4–7, 16): NSIS per-user (no admin, a Start Menu shortcut, uninstall keeping the data unless asked), MSI with a fixed upgrade code, one version for `package.json`, `tauri.conf.json`, `Cargo.toml` and Gradle (a sync script), the icon, product name and publisher, the bundled binaries' licenses in `THIRD_PARTY.md` and the installer. Closes the code-signing decision and the Windows-only one (PLAN.md §7), both the user's. Verified by installing, upgrading and uninstalling on the dev desktop and a clean VM if one is at hand.
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
