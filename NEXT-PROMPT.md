Start Phase 7 (docs/phases/phase-7-packaging-release.md) **Stage 4: the auto-updater** (steps 8–10: the updater keypair with the private key only as a CI secret, `latest.json`, the check on start with a setting to turn it off, the dialog with the release notes, and a clean stop of the backend and mpv before the installer runs).

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 7, its milestones (the "Auto-update N → N+1 keeps user data" one), §6 risks, §7 open decisions (the GPL source in releases and the icon, both open; neither is this stage's), §8 "First tasks");
- the Phase 7 doc: §1–§5 (steps 8–10 and their checklist items), §6 exit criteria (the second: N → N+1 through the auto-updater keeping the user data; it is Stage 6's on the clean machine, but this stage proves it from a local feed), §8 (the stage plan's Stage 4 line), §9 "Stage 1"–"Stage 3" and §10 "Stage 1"–"Stage 3" (what was built and measured: the release build, the per-user NSIS installer into `%LOCALAPPDATA%\Madoyomi`, the MSI per-machine, `VERSION` and `tools/version.ps1`, the uninstaller's hook and `/REMOVEDATA`, the logs, Export logs and the crash dialog);
- app/src-tauri/src/sidecar.rs (`Backend::stop`: stdin closed, `STOP_GRACE`, the Job Object; `RunEvent::Exit` in lib.rs), app/src-tauri/src/diagnostics.rs (Stage 3's commands, `tauri-plugin-dialog` used from Rust), app/src-tauri/tauri.conf.json and tauri.release.conf.json, app/src-tauri/installer/hooks.nsh (never removes data in update mode), app/src-tauri/capabilities/default.json;
- tools/build-release.ps1, tools/version.ps1, tools/binaries.json (how a release is built and versioned);
- the server's playback shutdown (server/core/src/main/kotlin/madoyomi/core/player/PlaybackSessions.kt: the shutdown hook that quits mpv) and the download queue (`DownloadQueue.stop`, what a restart re-queues);
- app/src/views/SettingsView.vue (where an "Updates" switch for the app would go; Settings → Updates today is the library update check, so name it apart), app/src/stores/backend.ts, app/src/components/CrashDialog.vue (a dialog pattern over the app);
- README.md (the status box, the Roadmap row, the commands tables, "Decided so far in Phase 7", "Still open") and app/README.md.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-20, Phase 7 Stage 3 done and merged by me before you start):
- Phases 0–6 are done; Phase 7 is in progress, Stage 3 of 6 done.
  - Stage 1: `tools/build-release.ps1` builds the jar, links the 60 MiB runtime, fetches the pinned mpv 0.41.0, ffmpeg 9.0.2 and yt-dlp 2026.10.26 into the gitignored `app/src-tauri/resources/`, then `pnpm tauri build --config src-tauri/tauri.release.conf.json`.
  - Stage 2: one version in `VERSION` (0.1.0, `tools/version.ps1`); the NSIS installer per-user into `%LOCALAPPDATA%\Madoyomi` (no admin), "Madoyomi contributors", the uninstaller's "Delete my library, settings and downloads too" (never during an update); the MSI per-machine with a fixed upgrade code; no code signing (SmartScreen warns on each version).
  - Stage 3:
    - logs: the backend's `madoyomi.log` is upstream's logback (10 MB, daily, 31 days, 100 MB, in server.conf); the shell's `sidecar.log` rolls at each start and past 10 MB, 3 kept, with the UI's `[ui]` errors (`log_from_ui`); mpv one log per session, 10 kept;
    - Settings → Backend → Export logs (and the crash dialog, the failure screen): the shell's save dialog, then a zip with every log, `about.json`, the backend's `diagnostics` report (or `extensions.txt` without the backend); the token, any `token=` value, the profile folder and `USERNAME=` taken out; the library only when "Include my library list" is ticked;
    - the crash dialog after 3 crashes in 10 minutes (`Status.recentCrashes`, `crashCount`, `crashAlert`), and the failure screen with Export logs and Open logs folder when the restarts stop;
    - the downloads folder of one's choice (`downloads.json` `folder`; asked each time: move or start empty; a folder that is gone waits: `heldBecause`, `DOWNLOADS_UNREACHABLE`; two upstream edits in server/UPSTREAM.md).
- My decisions so far: no code signing for now; Windows-only; "remove data" removes everything; Tauri's icon for now; Export logs' contents and save dialog; the downloads folder asked each time; the crash dialog at 3 in 10 minutes and on give-up. Open (not this stage's): the GPL source archive in releases (Stage 5), the icon.
- Phase 7's stage plan (phase-7 §8): 1. the runtime and binaries (done); 2. the installer (done); 3. logs and diagnostics (done); 4. the auto-updater (this stage); 5. the CI release; 6. the exit run on a clean Windows 11 machine.
- Decisions that are mine in this stage (ask; give the options, the trade-offs and your recommendation):
  - where the updater feed lives before CI exists (GitHub Releases' `latest.json` URL now, with the local feed only for the test; or something else) and whether pre-releases are offered;
  - the check: on every start, or at most once a day; on by default or off; and what the setting is called and where it goes (Settings → General, apart from the library's Updates);
  - the dialog: install now / later / skip this version; whether "later" asks again at the next start; whether an update may install while an episode plays or a download runs (stop them, wait, or ask);
  - which installer the updater uses (Tauri's updater bundles the NSIS one for a per-user install; the MSI per-machine needs admin) and what happens to an MSI install;
  - where the private key and its password are kept until Stage 5's CI exists (never in the repo), and who holds the backup.
  Everything else: sensible defaults, and say what you chose.
- Baseline counts (main after the merge; rerun them as task 0's baseline and record them in phase-7 §10 "Stage 4"):
  - server: `.\gradlew.bat build` **867** tests (core 710 with 8 skipped, AndroidCompat 7, ext-host 8, api 142); smoke-api.ps1 -NoBuild **24/24**;
  - app: 74 files (73 + 1 skipped), **716 passed + 19 skipped**; lint, typecheck, format:check and build clean;
  - the live spec **19/19** with **112** documents (it downloads two whole episodes at 360p and moves the live data dir's downloads folder out and back: about 5–11 minutes; Stages 1 and 3 saw the history test (`operations.live.spec.ts:912`) fail once on ordering: if it fails there, rerun once and record it);
  - `cargo test` **29**; clippy and fmt clean (with and without `app/src-tauri/resources/`); `tools/version.ps1` passes and `-SelfTest` passes.

Gotchas (Phases 5–6's and Stages 1–3's still apply):
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh).
  - Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 706; check `ls queue | sort -n | tail` before picking one). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Write a job to a temp name and rename it to .ps1, or the runner may take it half-written. A job that pipes a long run through `Out-File` writes its log only at the end.
  - Deleting files from the VM is off by default (`rm` fails with "Operation not permitted"); a job on the Windows side can move or delete. Leave scratch files in the gitignored tools/dev-data.
  - Git runs there too. The branch switch is a job with `cmd /c "git switch main 2>&1"`, `git pull`, `git switch -c …`; copy queue\667-branch.ps1 with the new branch name.
  - **Don't write files into app/ while a check job reads it** (prettier, tests); `pnpm format` also rewrites app/README.md (its tables).
  - The VM has ffmpeg, node, Java and Python, but node_modules has only Windows binaries, so pnpm doesn't run in the VM. A `.ps1` written in the VM needs CRLF (`.gitattributes`: `*.ps1 eol=crlf`, everything else LF) and must stay ASCII; a `'` inside a `printf '…'` job breaks the PowerShell (write such jobs with a heredoc).
  - The VM's network goes through an allowlist; the Windows side has the normal network.
  - The desktop can go away for hours (Stage 3: the runner idled about four hours and the app's backend was ended with `0x40010004`); check the heartbeat and restart what you were running.
- Scratch helpers in tools/dev-data/agent (gitignored):
  - app-start7.ps1 (`-DataDir <name under agent\> -Exe <path>`: a release or installed exe with PATH stripped of Java, mpv, ffmpeg and yt-dlp; waits for "healthy after"), app-start6.ps1 (`pnpm tauri dev`), app-stop.ps1, front.ps1;
  - ipcdo.ps1 (mpv's pipe: `-Mute -Seek <s> -Wait <s> -Extra tracks -Quit`), tracklist.ps1, mem.ps1, seed-live.ps1, seed-live7.ps1 (`-Java <java.exe>`), h2sql.ps1, snapshot6.ps1;
  - Stage 2's: inst7.ps1 (`-Action install -Setup <exe>`, `report`, `uninstall [-RemoveData] [-DataDir <dir>]`, `real`: the real data dirs' counts), wininfo.ps1, msiinfo.ps1; the installers in `installers\0.1.0b`, `installers\0.1.1` (with its jar);
  - Stage 3's: **procenv.ps1** (`-ProcessId <pid> [-Name MADOYOMI_TOKEN] [-SearchDir <dir>]`: a variable from another process's environment, and the files holding its value: the token check), **dlg.ps1** (a common file dialog by title), **s3/seed.ps1** (a fresh data dir with both repos, AnimeGG and MangaPill, on the release runtime and jar), **s3/kill.ps1** (`-Times N [-NoWait]`: kill the app's backend), **s3/zipcheck.ps1**; the data dir `p7-logs`.
- Job templates in queue\ (copy them to a new number):
  - 654-check.ps1 (format, format:check, typecheck, lint, test, build);
  - 394-server.ps1 (ktlintFormat, then build with the test counts per module, then smoke: change its `394-*.out` names);
  - 674-cargo.ps1 (cargo fmt, test, build, clippy, fmt --check, with and then without `resources\`);
  - 396-live.ps1 (seed-live and the live spec on live-data), 612-live7.ps1 (the same on the jlink runtime);
  - 627-baseline.ps1 (the whole baseline: change its `627-gradle.out` to the new number);
  - 650-rel.ps1 (`build-release.ps1 -NoJar`, about 8 min, the installers copied out), 621-rel2.ps1 (`-NoJar -NoBundle`, about 4–5 min), 607-compat.ps1.
- **Run the baseline before writing anything into app/.**
- **Don't rebuild the jar or relink the runtime while the live spec, the compat harness or the app runs on them** (Windows locks a running jar; jlink.ps1 deletes `resources\jre` first; `build-release.ps1 -NoJar` still relinks the runtime).
- **Building N and N+1:** the updater test needs two versions. `build-release.ps1` refuses a jar whose name isn't `Madoyomi-Server-v<VERSION>.jar`: set a test version with `tools\version.ps1 -Set`, build its jar, set it back after (no diff left), and move the other version's jar out of `server/api/build` (the newest jar is the one picked). Keep the installers of both outside the repo's tracked files (tools/dev-data).
- **The updater's signing key:** `tauri signer generate`; the private key and its password never go into the repo, a log, a job's output or a zip (check `git status`, the job logs and Stage 3's exports). For the local test keep them in tools/dev-data (gitignored) and say so; the public key goes into `tauri.conf.json` (`plugins.updater.pubkey`).
- **Tauri's updater:** it needs `bundle.createUpdaterArtifacts` and the `updater` plugin with its capability (`updater:default`), and `tauri-plugin-process` for the relaunch if the UI does it. On Windows it runs the NSIS installer in `passive` mode by default (`plugins.updater.windows.installMode`); the app must stop the backend and mpv first (the installer replaces files of a running exe and jar). Check that hooks.nsh's update mode keeps the data dir and that the uninstall-before-install path isn't taken in an update.
- **A local feed:** a `latest.json` and the installer served from a loopback HTTP server on the Windows side (Tauri's updater needs HTTPS unless `dangerousInsecureTransportProtocol` is set for the test build only: never in the shipped config).
- **Data dirs:** never point the app at the real `%APPDATA%\Madoyomi` (the user deleted it before Stage 3; check it stays absent, `inst7.ps1 -Action real`); use scratch data dirs (`MADOYOMI_DATA_DIR`). An installer test installs per-user (NSIS) and uninstalls at the end, and never starts the installed app without `MADOYOMI_DATA_DIR`. The WebView's storage (`%LOCALAPPDATA%\com.madoyomi.desktop`) is shared with every run.
- **Logs and the token:** the token must stay out of every log and export (procenv.ps1 checks the backend's real value).
- **Tauri bundling:** Tauri copies the resources beside `target/release/madoyomi.exe` and never deletes stale files there. The resources live in `tauri.release.conf.json`; keep everything that needs no binaries in `tauri.conf.json` so dev and `cargo test` keep working (check both, with `resources\` moved away too). A new plugin needs its capability in `app/src-tauri/capabilities/`.
- **Computer use:** resolve the app by the exe's name (`madoyomi.exe`) while it runs (resolving by path or by "Madoyomi" fails); don't call open_application on it. Run front.ps1 through the runner, then screenshot. At scale 0.5 the coordinate frame is 1456×819. The Claude window floats over the bottom right. The app's own dialogs (save, folder picker) show, but typing is allowed only in a dialog's "File name" box (a folder picker's Folder box refuses: click through folders instead). Explorer, installer, UAC and mpv's windows are masked: drive them from a job (wininfo.ps1, dlg.ps1) or check the result on disk. An Explorer window opened by the app comes to the front and blocks clicks: close it from a job (`Shell.Application` windows' `Quit`). Release the lock at the end.
- Don't read files outside the repo and its tools: such reads are refused.

Tasks:
0. Branch: once I have merged Stage 3, switch to a new branch off an up-to-date main (a job like queue\667-branch.ps1: `git switch main`, `git pull`, `git switch -c feat/phase-7-updater`). If Stage 3 isn't merged yet (main's docs/phases/phase-7-packaging-release.md has no "*Done* (2026-11-20)" under §8 Stage 3), stop and tell me. Then run the whole baseline (a 627-style job, its gradle output renamed) **before writing anything into app/**, and record it.
1. Plan the stage briefly (steps 8–10), after reading Tauri 2's updater docs (the plugin, `latest.json`'s format, `createUpdaterArtifacts`, the Windows install modes, the signer), and ask me the decisions that are mine (the feed, the check's timing and default, the dialog's choices and what it does with playback and downloads, the installer it uses and MSI installs, where the key lives), with your recommendation.
2. The keypair: generate it, put the public key into the config, keep the private key and its password out of the repo (where, as I decide), and document how Stage 5's CI gets them (a secret's name).
3. The updater in the shell and the UI: `tauri-plugin-updater` (and `tauri-plugin-process` if needed) with their capabilities; the check (on start, as I decide) with its setting; the dialog with the version and the release notes, install now or later (as I decide), the download's progress; errors shown, not swallowed (an offline check is quiet). Tests in Rust and in the UI.
4. A clean stop before the install: the backend stopped (stdin closed, its grace, then killed), mpv quit, running downloads paused or re-queued as the queue does on a restart, then the installer; tests for the order.
5. `build-release.ps1`: the updater artifacts (the signed NSIS `.exe` and its `.sig`) and a `latest.json` for a version, with its notes; the commands tables for any new switch.
6. Live: build N (0.1.0) and N+1 (a test version, set back after), install N per-user with a scratch data dir, serve N+1 from a local feed, update through the dialog while the app runs (with an episode playing or a download running, as I decide), and check the app comes back as N+1 with the library, history, downloads and settings kept, nothing left running, the logs saying what happened, and the real `%APPDATA%\Madoyomi` untouched; uninstall at the end.
7. server/UPSTREAM.md for any server change (each upstream edit with a `// Madoyomi:` comment; new code under `madoyomi.core.*`).
8. Phase doc: §5 ticks only for verified items; §8 Stage 4 *Done* (date) with a summary; §9 "Stage 4" for decisions; §10 "Stage 4" with the baseline, the findings, the counts after, the live checks and what wasn't checked.
9. docs/PLAN.md: Phase 7's status (Stage 4 of 6 done), the "Auto-update N → N+1 keeps user data" milestone ticked only if every phase-doc item behind it is verified (the clean machine is Stage 6's: say what is left), §6 risks, §7 open decisions, §8 "First tasks" pointing at Stage 5.
10. README.md, matching the phase doc and PLAN.md: the status box, the Phase 7 Roadmap row (🟡 in progress), the commands tables for new or changed commands, "Decided so far in Phase 7" and "Still open"; app/README.md for the shell or config changes.
11. Write the ready-to-paste prompt for the next stage (Phase 7 Stage 5, the CI release) into NEXT-PROMPT.md (overwrite it) and repeat it in your final message. Same format: current state, baseline test counts, gotchas, the tasks (task 0 the branch switch off an up-to-date main after I have merged Stage 4; the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 716 + 19 skipped, plus the new tests). The live spec ≥ 19/19 with ≥ 112 documents if the server or an operation changed. `.\gradlew.bat build` ≥ 867 green and smoke 24/24 if the server changed. `cargo test` ≥ 29 plus the new ones, clippy and fmt clean (with `app/src-tauri/resources/` moved away too). `tools/version.ps1` passes (the versions match, back at 0.1.0 after the N+1 build).
- The update from N to N+1 works from the local feed with the data kept; the backend and mpv stop before the installer runs; the check's setting is honoured; the private key is in no tracked file, log or zip. Each checked and recorded in §10.
- The Phase 7 doc has Stage 4 *Done* in §8 (only once its live checks passed), §9 "Stage 4" and §10 "Stage 4"; §5 ticks only verified items.
- docs/PLAN.md: Phase 7's status matches the phase doc; only verified milestones ticked; §6 and §7 updated; §8 pointing at Stage 5.
- README.md's status box, Roadmap row, commands tables, "Decided so far in Phase 7" and "Still open" match the Phase 7 doc and PLAN.md.
- server/UPSTREAM.md lists every upstream edit (each with a `// Madoyomi:` comment).
- NEXT-PROMPT.md holds the next stage's prompt.
- `git status`: on feat/phase-7-updater, with only text files (sources, tests, scripts, JSON, configs, docs). None of these:
  - the JRE or any part of it, jars, APKs, .pb files, fonts, test images, DB files, mpv/ffmpeg/yt-dlp binaries or their archives, installers (.exe, .msi), `.sig` files, the updater's private key or its password, `latest.json` built for the test, backup files, video, subtitle or HLS segment files, CBZ files, zips;
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
