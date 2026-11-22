# Instructions for AI coding agents

These are the ground rules for any AI assistant working in this repository (Claude, GitHub Copilot, Codex, Cursor, Gemini and others). They apply on top of [CONTRIBUTING.md](CONTRIBUTING.md), which is the source for how to build and test: read it before changing anything. The person you work for still reviews and owns every change, so when a rule here conflicts with what they ask, tell them instead of silently picking one.

## The project in brief

Madoyomi is a Windows 11 desktop app for anime and manga that runs existing Aniyomi and Mihon extensions unchanged.

- `app/`: the desktop shell (Tauri 2, Rust, in `app/src-tauri/`) and the UI (Vue 3, TypeScript, Pinia, TanStack Query, Vite). Details: [app/README.md](app/README.md).
- `server/`: the backend, a Kotlin/JVM fork of Suwayomi-Server (Gradle multi-module). Every change to upstream code is listed in [server/UPSTREAM.md](server/UPSTREAM.md).
- `player/`: mpv's config and the backend's IPC contract with mpv ([player/IPC.md](player/IPC.md)).
- `tools/`: PowerShell and Node scripts for development, releases and the compatibility tests.
- `spikes/`: early throwaway experiments; don't build on them.

## Hard rules

1. **No extension repos or content.** Madoyomi ships no repos or extensions and never links to or provides them. Don't add repo URLs, extension download links or links to copyrighted content in code, tests, docs, comments, commit messages or pull request text. Tests use local fixtures or the URLs a script already takes as a parameter.
2. **No binaries in git:** no APKs, `.dex` files, jars (except the Gradle wrapper's), `.pb` files, tool binaries, fonts, test images, installers, logs, zips, heap dumps or databases. Generated folders (`app/src-tauri/resources/`, `tools/bin/`, `tools/compat-test/out/`, `tools/dev-data/`, build outputs) stay untracked.
3. **Upstream code:** an edit to a file that came from Suwayomi-Server, Mihon or Aniyomi gets a `// Madoyomi:` comment at the edit and an entry in [server/UPSTREAM.md](server/UPSTREAM.md). New code goes under the `madoyomi.*` packages; upstream package names stay as they are. Keep the license headers of upstream files.
4. **Security-sensitive code stays strict.** Don't weaken extension signature verification, the trust prompt, the backend's API token, the loopback-only binding or the app updater's signature check to make something work. Never add, print or log a signing key, token or password. Security problems go to [SECURITY.md](SECURITY.md), not to a public issue.
5. **Releases are the maintainer's.** Don't change `VERSION` or its copies, create tags, publish releases or touch the repo's secrets. Change `.github/workflows/` only when asked to.

## What to update with a change

- **[CHANGELOG.md](CHANGELOG.md):** a change a user would notice gets one line under `## Unreleased`, written for users ("Downloads no longer get stuck after a restart", not "Fix race in DownloadQueue"). The app's update dialog shows these lines as they are. Code cleanups, tests and docs don't need a line. Never rename `## Unreleased` to a version yourself.
- **Docs:** a new or changed command, setting or behaviour updates the doc that covers it: [README.md](README.md) (users, short), [CONTRIBUTING.md](CONTRIBUTING.md) (developers), [app/README.md](app/README.md) (the app's structure), [server/UPSTREAM.md](server/UPSTREAM.md) (upstream edits), [player/IPC.md](player/IPC.md) (the mpv contract).
- **Tests:** a fix or a feature comes with a test where it can have one (Vitest in `app/`, `cargo test` in `app/src-tauri/`, JUnit in `server/`).

## Before you say you're done

Run the checks for what you changed (the commands are in CONTRIBUTING.md, "Before you open a pull request"):

- `app/`: `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm test`, `pnpm build`.
- `app/src-tauri/`: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test`.
- `server/`: `.\gradlew.bat build` with JDK 21 (ktlint and every test).

Report which checks you ran and their results. If you couldn't run one, say so; don't claim it passed.

## Style

- Follow the existing formatters and linters: Prettier and ESLint (`app/`), `cargo fmt` and Clippy (`app/src-tauri/`), ktlint (`server/`).
- PowerShell scripts are ASCII with CRLF line endings (see `.gitattributes`) and must run on Windows PowerShell 5.1 as well as PowerShell 7. Don't name a function after a built-in alias (`del`, `ls`, `cat`, ...), and remember that PowerShell variable names are case-insensitive.
- Comments say what the code does and why, in plain words. Don't label code with planning terms such as "Phase 3" or "Stage 2". To date a change, name the release ("since 0.1.2").
- Keep changes focused: no drive-by reformatting or renames outside the task.

## Git and pull requests

- Work on a branch off an up-to-date `main`; `main` only takes pull requests.
- Fill in the pull request template ([.github/pull_request_template.md](.github/pull_request_template.md)) and tick only what is true.
- Don't commit or push unless the person you work for asks you to.
