Start Phase 7 (docs/phases/phase-7-packaging-release.md) **Stage 3: logs and diagnostics** (steps 11–13: rolling logs for the backend, the shell and mpv, Settings → "Export logs", the dialog after repeated backend crashes; and the downloads folder of one's choice, left from Phase 6 Stage 4).

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 7, its milestones (the "Logs + export logs" one), §6 risks, §7 open decisions (the GPL source in releases and the icon, both open; neither is this stage's), §8 "First tasks");
- the Phase 7 doc: §1–§5 (steps 11–13 and their checklist items), §6 exit criteria (the third: "Export logs" produces a zip with shell, backend and mpv logs), §8 (the stage plan's Stage 3 line), §9 "Stage 1"–"Stage 2" and §10 "Stage 1"–"Stage 2" (what was built and measured, and the installed layout: `%LOCALAPPDATA%\Madoyomi`, the data dir `%APPDATA%\Madoyomi` or `MADOYOMI_DATA_DIR`);
- docs/phases/phase-6-library-features.md §9 "Stage 4" (the downloads folder kept in the data dir, "a folder of one's choice left for Phase 7") and §10 "Stage 4"–"Stage 5" (the queue, CBZ and video files, `downloads/manga`, `downloads/anime`);
- what logs exist today: app/src-tauri/src/sidecar.rs (`SidecarLog`: `<data dir>/logs/sidecar.log`, the previous session's kept as `sidecar.1.log`; `open_backend_logs`), the server's `madoyomi.log` (upstream's logback setup in server/core/src/main/kotlin/suwayomi/tachidesk/server/ServerSetup.kt and its server.conf keys: check what upstream already rolls and caps before adding anything), mpv's `logs/mpv.log` (server/core/src/main/kotlin/madoyomi/core/player/PlaybackSessions.kt, MpvCommand.kt: the last session's only, read by VideoChoices.kt for errors), and the anime download tools' output (Phase 6 Stage 5);
- app/src/views/SettingsView.vue and its sections (where "Export logs" and the downloads folder go), the downloads code (server/core/src/main/kotlin/madoyomi/core/download/, Settings → Downloads, `downloadSettings`), server/UPSTREAM.md;
- README.md (the status box, the Roadmap row, the commands tables, "Decided so far in Phase 7", "Still open") and app/README.md.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-17, Phase 7 Stage 2 done and merged by me before you start):
- Phases 0–6 are done; Phase 7 is in progress, Stage 2 of 6 done.
  - Stage 1: `tools/build-release.ps1` builds the jar, links the 59.99 MiB runtime (`tools/jlink.ps1`, `JreProbe`), fetches the pinned mpv 0.41.0, ffmpeg 9.0.2 and yt-dlp 2026.10.26 (`tools/fetch-binaries.ps1`, SHA-256 checked) into the gitignored `app/src-tauri/resources/`, then runs `pnpm tauri build --config src-tauri/tauri.release.conf.json`.
  - Stage 2:
    - one version in `VERSION` (0.1.0): `tools/version.ps1` checks or writes `app/package.json`, `tauri.conf.json`, `Cargo.toml`, `Cargo.lock` and `Constants.kt`; `build-release.ps1` runs it first and refuses a jar of another version;
    - the NSIS installer is per-user into `%LOCALAPPDATA%\Madoyomi` (no admin), publisher "Madoyomi contributors", with a Start Menu shortcut;
    - the uninstaller's box ("Delete my library, settings and downloads too", `app/src-tauri/installer/hooks.nsh` and `English.nsh`) removes the data dir (`MADOYOMI_DATA_DIR`, else `%APPDATA%\Madoyomi`, only with `server.conf` in it); `/REMOVEDATA` ticks it for `/S`;
    - the MSI has a fixed upgrade code; its install is Stage 6's (my decision);
    - every tool's license texts are pinned in `binaries.json` and installed beside it;
    - checked live: installed, ran with nothing on PATH, upgraded 0.1.0 → 0.1.1 keeping the data, uninstalled both ways. Installers: NSIS 275.1 MiB, MSI 301.1 MiB; installed 461.6 MiB in 255 files.
- My decisions so far: no code signing for now; Windows-only; "remove data" removes everything; Tauri's icon for now. Open (not this stage's): the GPL source archive in releases (Stage 5), the icon.
- Phase 7's stage plan (phase-7 §8): 1. the runtime and binaries (done); 2. the installer (done); 3. logs and diagnostics, with the downloads folder of one's choice (this stage); 4. the auto-updater; 5. the CI release; 6. the exit run on a clean Windows 11 machine.
- Decisions that are mine in this stage (ask; give the options, the trade-offs and your recommendation):
  - what "Export logs" puts in the zip besides the logs and the versions (the extension list is planned; the repos? the settings files without secrets? no library data unless I opt in, as the plan says) and where it saves it (a save dialog, or a fixed folder and "show in folder");
  - the log limits (size per file and how many files, per log) if upstream's defaults don't fit;
  - the downloads folder: whether choosing a new one moves what is already downloaded or leaves it (and what happens to a folder that isn't reachable);
  - anything else that turns out to be a real choice (for example how many crashes in how long bring up the dialog). Everything else: sensible defaults, and say what you chose.
- Baseline counts (main after the merge; rerun them as task 0's baseline and record them in phase-7 §10 "Stage 3"):
  - server: `.\gradlew.bat build` **853** tests (core 703 with 8 skipped, AndroidCompat 7, ext-host 8, api 135); smoke-api.ps1 -NoBuild **24/24**;
  - app: 71 files (70 + 1 skipped), **697 passed + 17 skipped**; lint, typecheck, format:check and build clean;
  - the live spec **17/17** with **109** documents (it downloads two whole episodes at 360p: about 3–8 minutes; Stage 1 saw the history test (`operations.live.spec.ts:912`) fail once on ordering, Stage 2 didn't: if it fails there, rerun once and record it);
  - `cargo test` **21**; clippy and fmt clean; `tools/version.ps1 -SelfTest` 21/21.

Gotchas (Phases 5–6's and Stages 1–2's still apply):
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh).
  - Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 656; check `ls queue | sort -n | tail` before picking one). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Write a job to a temp name and rename it to .ps1, or the runner may take it half-written. A job that pipes a long run through `Out-File` writes its log only at the end.
  - Deleting files from the VM is off by default (`rm` fails with "Operation not permitted"); a job on the Windows side can move or delete (Stage 2 moved the 0.1.1 test jar out of `server/api/build` that way). Leave scratch files in the gitignored tools/dev-data.
  - Git runs there too. The branch switch is a job with `cmd /c "git switch main 2>&1"`, `git pull`, `git switch -c …`; copy queue\626-branch.ps1 with the new branch name.
  - **Don't write files into app/ while a check job reads it** (prettier, tests); `pnpm format` also rewrites app/README.md (its tables).
  - The VM has ffmpeg, node, Java and Python, but node_modules has only Windows binaries, so pnpm doesn't run in the VM. A `.ps1` written in the VM needs CRLF (`.gitattributes`: `*.ps1 eol=crlf`, everything else LF: JSON, Java, Markdown, `.nsh`) and must stay ASCII; a `'` inside a `printf '…'` job breaks the PowerShell (write such jobs with a heredoc).
  - The VM's network goes through an allowlist; the Windows side has the normal network.
- Scratch helpers in tools/dev-data/agent (gitignored):
  - app-start7.ps1 (`-DataDir <name under agent\> -Exe <path>`: a release or installed exe with PATH stripped of Java, mpv, ffmpeg and yt-dlp; waits for "healthy after"), app-start6.ps1 (`pnpm tauri dev`), app-stop.ps1, front.ps1;
  - ipcdo.ps1 (mpv's pipe: `-Mute -Seek <s> -Wait <s> -Extra tracks -Quit`), tracklist.ps1, mem.ps1, seed-live.ps1, seed-live7.ps1 (`-Java <java.exe>`), h2sql.ps1, snapshot6.ps1;
  - Stage 2's: **inst7.ps1** (`-Action install -Setup <exe>`, `report`, `uninstall [-RemoveData] [-DataDir <dir>]`, `real`: the real data dirs' counts), **wininfo.ps1** (a window's controls through Win32, `-Keys` to press keys in it: for windows computer use can't be granted), **msiinfo.ps1** (an MSI's tables);
  - the installers in `installers\0.1.0b` (the final 0.1.0), `installers\0.1.1` (with its jar). Nothing of Madoyomi is installed now.
- Job templates in queue\ (copy them to a new number):
  - 654-check.ps1 (format, format:check, typecheck, lint, test, build);
  - 394-server.ps1 (ktlintFormat, then build with the test counts per module, then smoke: change its `394-*.out` names);
  - 632-cargo.ps1 (cargo fmt, test, build, clippy, fmt --check), 655-cargo.ps1 (the same with `resources\` moved away);
  - 396-live.ps1 (seed-live and the live spec on live-data), 612-live7.ps1 (the same on the jlink runtime);
  - 627-baseline.ps1 (the whole baseline: change its `627-gradle.out` to the new number);
  - 650-rel.ps1 (`build-release.ps1 -NoJar`, about 8 min, the installers copied out), 621-rel2.ps1 (`-NoJar -NoBundle`, about 2.5 min), 607-compat.ps1.
- **Run the baseline before writing anything into app/.**
- **Don't rebuild the jar or relink the runtime while the live spec, the compat harness or the app runs on them** (Windows locks a running jar; jlink.ps1 deletes `resources\jre` first).
- **A server change bumps nothing by itself**, but `build-release.ps1` refuses a jar whose name isn't `Madoyomi-Server-v<VERSION>.jar`: if you build a test version, set it with `tools\version.ps1 -Set`, set it back after, and move the other version's jar out of `server/api/build` (the newest jar is the one picked).
- PowerShell 5.1:
  - it wraps a native command's stderr as errors, so run pnpm, git, jlink, makensis and msiexec through `cmd /c "... 2>&1"` and read `$LASTEXITCODE`;
  - capture native output into a variable before `Select-Object -First` (piping straight into it can lose the exit code), and it drops an empty-string argument to a native command;
  - resolve relative paths with `$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath`; `R` is an alias, `$Args` automatic, `$pid` read-only, and variables are case-insensitive;
  - tools/*.ps1 scripts that ship in the repo should run on PowerShell 5.1 and 7.
- **Logs and the token:** the token must stay out of every log and of the export zip (check `token=` and the token's value in `sidecar.log`, `madoyomi.log`, `mpv.log`, rotated files and the zip). The export must not carry library data (titles, history) unless I opt in. Paths in logs can contain the Windows user name: say whether the zip keeps them.
- **Data dirs:** never point the app at the real `%APPDATA%\Madoyomi`; use scratch data dirs (`MADOYOMI_DATA_DIR`), and check the real one is untouched at the end (`inst7.ps1 -Action real`). The WebView's storage (`%LOCALAPPDATA%\com.madoyomi.desktop`) is shared with every run.
- **The downloads folder:** a new folder may be on another drive (a move is a copy and delete), may already hold files, or may be gone later (an unplugged drive): the queue, the reader and the player read from it, so check each.
- **Tauri bundling:** Tauri copies the resources beside `target/release/madoyomi.exe` and never deletes stale files there. The resources live in `tauri.release.conf.json`; keep everything that needs no binaries in `tauri.conf.json` so dev and `cargo test` keep working (check both, with `resources\` moved away too). A new Tauri plugin (`tauri-plugin-log`, `tauri-plugin-dialog` for a save dialog) needs its capability in `app/src-tauri/capabilities/`.
- **Computer use:** resolve the app by the exe you run (an installed one: `%LOCALAPPDATA%\Madoyomi\madoyomi.exe`; the release build: `app\src-tauri\target\release\madoyomi.exe`); **don't call open_application on it**. Run front.ps1 through the runner, then screenshot. At scale 0.5 the coordinate frame is 1456×819; read button positions from a full screenshot. The Claude window floats over the top right (about x ≥ 1047) and can cover buttons. A native `<select>`'s list is masked: open it with a click, then Home and Down, then Return. File dialogs (a save dialog for the export, a folder picker), Explorer, installer and UAC windows and mpv's window are masked: drive them from a job (wininfo.ps1) or check the result on disk. Release the lock at the end.
- Don't read files outside the repo and its tools: such reads are refused.

Tasks:
0. Branch: once I have merged Stage 2, switch to a new branch off an up-to-date main (a job like queue\626-branch.ps1: `git switch main`, `git pull`, `git switch -c feat/phase-7-logs`). If Stage 2 isn't merged yet (main's docs/phases/phase-7-packaging-release.md has no "*Done* (2026-11-17)" under §8 Stage 2), stop and tell me. Then run the whole baseline (a 627-style job, its gradle output renamed) **before writing anything into app/**, and record it.
1. Plan the stage briefly (steps 11–13 and the downloads folder), after reading what upstream's logback already does, and ask me the decisions that are mine (the export's contents and where it goes; the limits if upstream's don't fit; whether a new downloads folder moves what is there), with your recommendation.
2. Rolling logs: the backend (`madoyomi.log`: size and count limits, upstream's setup used if it fits), the shell (`tauri-plugin-log` or the existing `SidecarLog` with rotation and a cap: say which and why), mpv (`--log-file` per session, the last N kept; `VideoChoices` still reads the current session's), all under the data dir's `logs/`. Tests for the rotation and the caps.
3. Settings → "Export logs": a zip with the shell's, backend's and mpv's logs (the rotated ones too), the app's and backend's versions, the extension list, and nothing personal unless I opt in; the token never in it. Tests for what goes in and what doesn't.
4. The repeated-crash dialog: when the backend dies repeatedly (the shell's backoff already counts failures), a dialog with "Export logs" and "Open logs folder". Tests in Rust and in the UI.
5. The downloads folder of one's choice: Settings → Downloads picks a folder (the default stays the data dir's `downloads`), with what happens to what is already downloaded as I decide; the queue, the reader (CBZ) and the player (video files) follow it. Server tests and the live spec where an operation changes.
6. server/UPSTREAM.md for every server change (each upstream edit with a `// Madoyomi:` comment; new code under `madoyomi.core.*`).
7. Live: on a release build (or an installed one) with a scratch data dir: logs written and rotated past their cap, mpv's per-session logs kept to N, Export logs giving a zip that opens and holds the three kinds of log with no token, the crash dialog after killing the backend repeatedly (and its two buttons), and a download into a chosen folder (then read or played from it); the real `%APPDATA%\Madoyomi` untouched.
8. Phase doc: §5 ticks only for verified items; §8 Stage 3 *Done* (date) with a summary; §9 "Stage 3" for decisions; §10 "Stage 3" with the baseline, the findings, the counts after, the live checks and what wasn't checked.
9. docs/PLAN.md: Phase 7's status (Stage 3 of 6 done), the "Logs + export logs" milestone ticked only if every phase-doc item behind it is verified, §6 risks, §7 open decisions, §8 "First tasks" pointing at Stage 4.
10. README.md, matching the phase doc and PLAN.md: the status box, the Phase 7 Roadmap row (🟡 in progress), the commands tables for new or changed commands, "Decided so far in Phase 7" and "Still open"; app/README.md for the shell or config changes.
11. Write the ready-to-paste prompt for the next stage (Phase 7 Stage 4, the auto-updater) into NEXT-PROMPT.md (overwrite it) and repeat it in your final message. Same format: current state, baseline test counts, gotchas, the tasks (task 0 the branch switch off an up-to-date main after I have merged Stage 3; the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 697 + 17 skipped, plus the new tests). The live spec ≥ 17/17 with ≥ 109 documents if the server or an operation changed. `.\gradlew.bat build` ≥ 853 green and smoke 24/24 if the server changed. `cargo test` ≥ 21 plus the new ones, clippy and fmt clean (with `app/src-tauri/resources/` moved away too). `tools/version.ps1` passes (the versions still match).
- The logs roll and stay within their caps; Export logs gives a zip with the shell's, backend's and mpv's logs and no token or personal data unless asked; the crash dialog appears after repeated crashes; a chosen downloads folder is used by the queue, the reader and the player. Each checked and recorded in §10.
- The Phase 7 doc has Stage 3 *Done* in §8 (only once its live checks passed), §9 "Stage 3" and §10 "Stage 3"; §5 ticks only verified items.
- docs/PLAN.md: Phase 7's status matches the phase doc; only verified milestones ticked; §6 and §7 updated; §8 pointing at Stage 4.
- README.md's status box, Roadmap row, commands tables, "Decided so far in Phase 7" and "Still open" match the Phase 7 doc and PLAN.md.
- server/UPSTREAM.md lists every upstream edit (each with a `// Madoyomi:` comment).
- NEXT-PROMPT.md holds the next stage's prompt.
- `git status`: on feat/phase-7-logs, with only text files (sources, tests, scripts, JSON, configs, docs). None of these:
  - the JRE or any part of it, jars, APKs, .pb files, fonts, test images, DB files, mpv/ffmpeg/yt-dlp binaries or their archives, installers (.exe, .msi), backup files, video, subtitle or HLS segment files, CBZ files, zips (an exported logs zip);
  - hs_err logs, heap dumps, screenshots, log files;
  - node_modules, target/, dist/, app/.vitest/, .env.local, `app/src-tauri/resources/`;
  - tools/bin, tools/compat-test/out, tools/dev-data, spikes/mpv-play/out, or anything mpv wrote into player/.

Standing rules:
- Start every stage by switching to a new feature branch off an up-to-date main (git switch main, git pull, git switch -c feat/phase-N-<topic>) before any edit. Never edit on main.
- Work one stage at a time.
- Never commit (no git commit/merge/rebase/cherry-pick/revert). Never commit or stage binaries, APKs, jars, .pb files or heap dumps.
- Ask me only about decisions that are genuinely mine.
- Every stage updates, together with the phase doc and, for server changes, server/UPSTREAM.md:
  - README.md's status (the status box, the Roadmap row, the changed tables, the decisions);
  - docs/PLAN.md's milestones (tick what the phase doc verifies; update the phase status, the risks and the open decisions).
- At the end of the stage, stop and report what changed, the decisions and findings, how you verified it (test counts against the baseline), a one-line commit message and a PR title.
- Then write a ready-to-paste prompt for the next stage into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it at the end of your report. Use this same format: current state, baseline test counts, gotchas, the tasks with the branch switch as task 0 and the README status / PLAN.md tick tasks, verification steps including the README and PLAN.md checks, and these standing rules. Tell that agent to do the same.
- Then wait for me to commit or merge.
