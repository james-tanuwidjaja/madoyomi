Continue Phase 3 (docs/phases/phase-3-frontend-mvp.md), Stage 3: Repos and Extensions with TrustDialog (steps 11-12, the "Repos screen" and "Extensions screen (available/installed/updates) + TrustDialog" checklist items). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 3 doc, all of it: §4 steps, §5 checklist, §6 exit criteria, §8 stage plan, §9 decisions (Stages 1 and 2), §10 findings (Stages 1 and 2);
- server/UPSTREAM.md: "Startup contract", "Repo manager (Phase 1 Stage 5)", "Extension lifecycle (Phase 1 Stage 6)" and "API (Phase 1 Stage 7)" for the repo, extension and trust operations and the error codes;
- the Phase 1 doc's §4 step 17 (the API mapping) and its §9 on trust;
- README.md (status box, Roadmap, decisions, the NOTE on pnpm tauri dev);
- app/README.md (structure, routes, the live operation check).
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-2 done; Phase 3 Stages 1 and 2 done; Stage 2 on branch feat/phase-3-api):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar. Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md. Stages 1 and 2 changed nothing in server/.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>, with no subprotocol.
  - REST only for binaries: thumbnails, pages, extension icons. GETs may pass ?token=.
  - Errors carry {code, details}. Repo and extension codes: REPO_FETCH_FAILED, REPO_FORMAT_MISMATCH, REPO_TYPE_MISMATCH, SIGNATURE_INVALID, SIGNATURE_CHANGED, UNTRUSTED, PACKAGE_MISMATCH, INSTALL_FAILED, LOAD_FAILED, EXTENSION_INCOMPATIBLE, NOT_FOUND and others.
  - Repos: extensionStores, addExtensionStore(input: {indexUrl, type, format}) (type null: MANGA; format null: AUTO), removeExtensionStore, fetchExtensions(input: {type}).
  - Extensions: extensions(condition: {type}); updateExtension(input: {id: pkgName, patch: {install|update|uninstall}}), which reports what it can't do (NOT_FOUND, INSTALL_FAILED). ExtensionType has type, signatureHash, isTrusted, isLoaded, isTorrent, hasUpdate, isObsolete, contentWarning, compatIssues.
  - Trust: trustExtension(input: {pkgName}), trustSignature/untrustSignature(input: {hash}), trustedSignatures. Adding a repo trusts its signing key (Mihon model); any other certificate needs explicit trust, and untrusted extensions are never loaded.
  - Subscriptions: extensionInstallProgress (DOWNLOADING, VERIFYING, CONVERTING, INSTALLED, FAILED with errorCode/message) and extensionStoreRefreshed. Nothing is replayed.
- The shell (app/src-tauri/src/sidecar.rs, Stage 1):
  - It runs the newest server jar with a per-session token and --data-dir %APPDATA%\Madoyomi (MADOYOMI_DATA_DIR overrides it) and restarts it after a crash.
  - Commands get_backend_status, get_backend_info, restart_backend, open_backend_logs; event backend-status.
  - The port changes after every restart.
- The UI (Stage 2; app/README.md has the structure):
  - src/api/client.ts: `api.gql` / `api.gqlResult` throw or return `ApiError {code, message, details, path, status, kind}`. Client codes: NETWORK, BACKEND_UNAVAILABLE, BAD_RESPONSE. `api.assetUrl(path)` adds ?token= to server-relative paths only (use it for extension icons).
  - src/api/types.ts: hand-written types. src/api/operations/: documents + typed calls (extensions.ts has EXTENSION_STORES_QUERY, EXTENSIONS_QUERY, REFRESH_REPOS_MUTATION and the fragments). Add the new operations there, and their exports must end in _QUERY/_MUTATION/_SUBSCRIPTION, so the live spec validates them.
  - src/api/ws.ts + src/api/realtime.ts: one app-wide subscription to each event. stores/events.ts has `installProgress[pkgName]` (the latest step), `recent[]` and `wsState`. Install terminal states invalidate that kind's extensions and sources; repo refreshes invalidate the repos and that kind's extensions; a reconnect invalidates all three. Views read progress from the store, and nothing subscribes per view.
  - src/composables/: useExtensionStores, useExtensions(kind), useRefreshRepos, useSources, useSourceFilters, useSourceBrowse (infinite), useAnime/useEpisodes, useManga/useChapters; queryKeys.ts. Queries retry only NETWORK/BACKEND_UNAVAILABLE; mutations never retry.
  - The shell (components/AppShell.vue): sidebar (Library, Browse, Extensions, Downloads, Settings), the anime/manga switch (stores/mediaKind.ts, persisted, a Reka ToggleGroup), and the footer with the server URL and the live-updates dot. Native title bar.
  - Routes: every step-10 route plus /downloads and not-found. Views use components/PageLayout.vue (title, subtitle, actions slot), PagePlaceholder.vue and ErrorNotice.vue (shows code + message, Retry).
  - views/ReposView.vue already lists the repos (with "Refresh all" and the live events), and views/ExtensionsView.vue is a placeholder with a Repos button. Replace both with the real screens.
  - Design tokens: src/styles/tokens.css; .btn/.btn-accent in main.css. Components use tokens only, never raw colours.
- Decided (Phase 3 §9): Reka UI primitives with our own styles (use its Dialog for TrustDialog and Tabs for the extension tabs), @tanstack/vue-virtual for long lists (Stage 4; the Keiyoushi repo lists 1469 extensions, so consider virtualizing the extension list now, or record why not), dark + light themes, a Fluent-like look.

Baseline:
- server: `.\gradlew.bat build` green with 662 tests, 0 failures: core 604 (8 skipped), AndroidCompat 7, ext-host 8, api 43. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. Rebuild only if server/ changes.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass.
- app: `pnpm test` gives 10 files, 76 passed + 5 skipped. That's client 15, ws 13, realtime 6, hooks 4, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6; the 5 skipped are the live spec. `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 5/5.
- app/src-tauri: `cargo test` 14 tests; `cargo clippy --all-targets` clean.
- Record all of these at the start as this stage's baseline.

Gotchas:
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 6 min).
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat.
  - `.\gradlew.bat :api:shadowJar` builds only the jar.
- From the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio (it sets $script:base and $script:token; GqlData runs a query).
- Seeding a data dir (Stage 2's recipe): write a scratchpad .ps1 that does these steps, run it as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> *> <log>`, and then start `pnpm tauri dev` with MADOYOMI_DATA_DIR=<that dir>:
  1. dot-source tools/lib/MadoyomiServer.ps1;
  2. `Start-MadoyomiServer <java> (Resolve-MadoyomiJar <repo> '' -NoBuild) <scratch data dir>`;
  3. add repos with GqlData (or not, for a fresh-data-dir test);
  4. optionally set $env:MADOYOMI_LIVE_URL=$script:base and $env:MADOYOMI_LIVE_TOKEN=$script:token, and run `cmd /c "pnpm vitest run src/api/__tests__/operations.live.spec.ts 2>&1"` from app/;
  5. Stop-MadoyomiServer.
  One server per data dir: H2 locks it.
- Live app checks:
  - Run `pnpm tauri dev` in the background (Bash tool, output to a scratchpad log), then wait on <dataDir>/logs/sidecar.log for "healthy after".
  - Several unrelated java processes are usually running: kill only the pid from sidecar.log ("pid N listening").
  - Close the app cleanly with (Get-Process madoyomi).CloseMainWindow().
- Screenshots and clicks WORK (Stage 2). Recreate a scratchpad win.ps1 that:
  - calls SetProcessDPIAware, finds the madoyomi process's MainWindowHandle, ShowWindow(9), SetForegroundWindow, then GetWindowRect;
  - `-Action shot -Out x.png` takes System.Drawing CopyFromScreen of that rect;
  - `-Action click -X -Y` does SetCursorPos(rect.L+X, rect.T+Y) + mouse_event(2) / mouse_event(4).
  Coordinates are window-relative physical pixels, read off the screenshot (the window is 1296x839 with its frame; the sidebar items sit at x≈80, Library y≈153, Browse 191, Extensions 229; the page's actions button at about x≈1210, y≈72). Typing into a text field would need SendKeys or keybd_event (not tried yet). Don't commit screenshots.
- Stage 2 findings to handle here:
  - A repo's stored URL can differ from the one added: yuzono's .../index.min.json is stored as https://github.com/yuzono/anime-repo/raw/repo/index.pb, so match repos by what the server returns.
  - Check how "type auto-detected" can work: addExtensionStore takes `type` (null means MANGA), and REPO_TYPE_MISMATCH exists. Read RepoService.add and decide whether the UI detects the type (e.g. a JSON index is anime, protobuf could be either), tries one and falls back on REPO_TYPE_MISMATCH, or needs a server change. A server change goes into server/UPSTREAM.md and needs the full build + smoke.
  - Upstream's "Local source" (manga, id 0) shows as Untrusted, because its fake extension has no signature. Leave it out of the extension lists, or treat it as built in.
- Subscribe-before-start isn't needed per view: realtime.ts already follows every install from app start. Read progress from useEventsStore().installProgress[pkgName].
- graphql-js refuses upstream's schema (two input types without fields), so the live spec builds it with assumeValid: true. Keep that.
- TypeScript 6 loads no @types by default: in app code there's no `process` (see how operations.live.spec.ts reads env) and no Array.prototype.at (lib ES2020).
- PowerShell:
  - variables are case-insensitive (a local can be a parameter);
  - a function's pipeline output is its return value;
  - PS 5.1 has no `??`;
  - with $ErrorActionPreference='Stop', any native stderr line aborts the script;
  - keep .ps1 files ASCII;
  - write files with [IO.File]::WriteAllText + UTF8Encoding($false) or the Write tool.
- Shell edits: backslashes inside perl -e / sed replacements get mangled. Bash heredocs with many quotes can fail to parse. Use the Write/Edit tools for such content.
- A vitest `--reporter=json` run without a valid --outputFile writes app/.vitest/: delete it, never commit it.
- The token must stay out of logs and URLs, except where the contract needs ?token= (img src, WebSocket). Never print it in test output or docs.
- Adding npm packages needs network access; pnpm-lock.yaml changes are expected and are text.
- Python and gh are not installed.

Tasks (Stage 3):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-3-extensions`. Never edit on main. (If feat/phase-3-api isn't merged yet, stop and tell me.)
1. API: add the repo and extension operations to src/api/operations/ (add/remove repo, update/install/uninstall extension, trustExtension, trustSignature if needed) and their types. Extend the live spec if new documents need checking (they're validated automatically if their export names end in _QUERY/_MUTATION). Add the composables (mutations that invalidate the right keys; install progress from the events store).
2. Repos screen (step 11): list (name, kind badge, stored URL, extension count, last refresh, last error, whether its signing key is trusted). Add by URL with the type auto-detected and an override (see the gotcha), with the server's error codes shown readably (REPO_FETCH_FAILED, REPO_FORMAT_MISMATCH, REPO_TYPE_MISMATCH). Remove (with a confirmation). Refresh one or all.
3. Extensions screen (step 12):
   - tabs Available / Installed / Updates (Reka Tabs), for the current kind;
   - search, a language filter, an NSFW toggle (contentWarning);
   - install / update / uninstall with live progress from the events store, and errors (INSTALL_FAILED, SIGNATURE_*, LOAD_FAILED, EXTENSION_INCOMPATIBLE) shown;
   - an installed but untrusted extension offers Trust through TrustDialog;
   - show compatIssues if any (a count or a details popover);
   - long lists stay fast (virtualize or record why not).
4. TrustDialog (Reka Dialog): the package name, extension name, version, repo, the signing-certificate hash (monospace, copyable), and a clear warning that extensions run with full access to the machine. Trust calls trustExtension and refreshes the lists, and Cancel does nothing. Focus trap and Escape come from Reka.
5. Tests: component tests for the Repos screen (add, error display, remove), the Extensions screen (tab filtering, search, the install-progress display from the events store) and TrustDialog (shows hash and warning; trust calls the mutation; cancel doesn't). Keep the route smoke test passing.
6. Live check in `pnpm tauri dev` on a FRESH data dir (MADOYOMI_DATA_DIR=<new scratchpad dir>), by clicks and screenshots:
   - add yuzono (https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json) and Keiyoushi (https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb) in the UI, if typing can be driven; otherwise seed them with the recipe and record why;
   - install one anime and one manga extension (e.g. AnimeGG and MangaPill, as in the smoke test) and watch the progress;
   - trust through TrustDialog if one comes up untrusted (repo keys are trusted on add, so an untrusted case may need trustSignature/untrustSignature, or record how it was shown);
   - uninstall one;
   - after a backend kill, the Extensions list and progress still work.
7. Phase doc: §5 ticks (only verified items), §9 decisions, §10 findings. Update the §8 stage plan if the plan changes.
8. docs/PLAN.md: tick the Phase 3 milestone "Repos, Extensions (with trust prompt), Browse, Source, Details, Library, Settings screens" only if every phase-doc item behind it is verified. It won't be after this stage, so leave it unticked and say which part is done. Keep Phase 3 "in progress" in §4 and §8 "First tasks" with the stage number. Update §6 risks and §7 open decisions if this stage decides anything (the mpv window question stays open).
9. README.md status: the warning box (what works now), the Roadmap row for Phase 3, the NOTE about pnpm tauri dev if behaviour changes, the commands tables for any new or changed command, and the "Decided so far in Phase 3" list. Make it match the phase doc and PLAN.md. Also update app/README.md (structure, routes table).
10. At the end, write the ready-to-paste prompt for the next stage (Stage 4: Browse and Source, with FilterRenderer) into NEXT-PROMPT.md in the repo root (overwrite it) and repeat it in your final message.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 76 tests plus the new ones (the live spec skipped); the live spec passes 5/5 (or more) against a seeded server; `cargo build`, `cargo test` (at least 14) and `cargo clippy --all-targets` pass.
- `pnpm tauri dev` on a fresh data dir: repos added, one extension of each kind installed with live progress, the trust prompt shown, an uninstall, and all of it still working after a backend kill without reloading the window.
- If anything in server/ changed: `.\gradlew.bat build` is still green (at least 662 tests), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change.
- docs/PLAN.md: every Phase 3 milestone is ticked if and only if its phase-doc items are verified, and the phase status in §4/§8 matches the phase doc.
- README.md's status box, Roadmap row and decisions match the Phase 3 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on branch feat/phase-3-extensions, only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files;
  - hs_err logs, heap dumps, screenshots;
  - node_modules, target/, dist/, app/.vitest/, .env.local;
  - tools/compat-test/out, tools/dev-data.

Standing rules:
- Start every stage by switching to a new feature branch off an up-to-date main (git switch main, git pull, git switch -c feat/phase-3-<topic>) before any edit. Never edit on main.
- Work one stage at a time.
- Never commit (no git commit/merge/rebase/cherry-pick/revert). Never commit or stage binaries, APKs, jars, .pb files or heap dumps.
- Ask me only about decisions that are genuinely mine.
- Every stage updates README.md's status (status box, Roadmap row, changed tables, decisions) and docs/PLAN.md's milestones (tick what the phase doc verifies, update phase status, risks and open decisions), together with the phase doc and, for server changes, server/UPSTREAM.md.
- At the end of the stage, stop and report what changed, the decisions and findings, how you verified it (test counts against the baseline), a one-line commit message and a PR title.
- Then write a ready-to-paste prompt for the next stage into NEXT-PROMPT.md in the repo root (overwrite it) and repeat it at the end of your report. Use this same format: current state, baseline test counts, gotchas, the tasks with the branch switch as task 0 and the README status / PLAN.md tick tasks, verification steps including the README and PLAN.md checks, and these standing rules. Tell that agent to do the same.
- Then wait for me to commit or merge.
