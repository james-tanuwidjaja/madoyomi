# Phase 7 — Packaging & release

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 6](phase-6-library-features.md) · Next: [Phase 8](phase-8-later.md)

## 1. Goal

Ship Madoyomi as a normal Windows app: one installer that includes the Tauri shell, the backend jar, a minimal `jlink` JRE, mpv (and ffmpeg/yt-dlp if Phase 6 needs them), with auto-update, local logs, an "export logs" button, and a repeatable CI release build. No Java install required.

## 2. Depends on / inputs

- Phases 1–6 functionally complete.
- `tauri.conf.json` already targets NSIS + MSI (scaffold).
- Third-party binary licenses reviewed (mpv GPL/LGPL builds, ffmpeg, yt-dlp).

## 3. Deliverables

- `tools/jlink.ps1`: builds the minimal runtime from `jdeps` output into `app/src-tauri/resources/jre/`.
- `tools/fetch-binaries.ps1`: downloads pinned mpv/ffmpeg/yt-dlp builds with checksums.
- `tools/build-release.ps1`: server jar → jlink → binaries → `pnpm tauri build`.
- Tauri config: `bundle.resources` (jar, JRE, player config) and/or `externalBin` (mpv, ffmpeg); production sidecar path resolution in `sidecar.rs`.
- Updater: `tauri-plugin-updater`, signing keypair (private key kept out of the repo), `latest.json` manifest hosted on GitHub Releases.
- Logging: rotating log files for the shell, backend and mpv under `%APPDATA%/Madoyomi/logs`; a Settings → "Export logs" zip.
- `.github/workflows/release.yml`: Windows build, artifact upload, draft release.
- `RELEASING.md`: the release checklist.

## 4. Detailed steps

### Runtime & binaries
1. Run `jdeps --print-module-deps` on the server jar, then `jlink --add-modules … --strip-debug --no-header-files --no-man-pages --compress=zip-6`. Target ≤ 60 MB. Add modules that extensions need at runtime (e.g. `java.net.http`, `jdk.crypto.ec`, `java.scripting` if some use JS).
2. Pin mpv/ffmpeg/yt-dlp versions + SHA-256 in `tools/binaries.json`; the fetch script verifies the hashes.
3. Production `sidecar.rs`: resolve `resources/jre/bin/java.exe` + `resources/server.jar`; pass the mpv/ffmpeg paths to the backend through args/env.

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
- [ ] `tools/jlink.ps1` produces JRE ≤ 60 MB
- [ ] Extension-required JDK modules included (verified with compat harness on the jlink JRE)
- [ ] `tools/fetch-binaries.ps1` with pinned versions + checksums
- [ ] Production sidecar path resolution

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
