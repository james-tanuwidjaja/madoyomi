# Madoyomi

Windows 11 desktop app for anime + manga that runs existing Aniyomi/Mihon extensions unchanged.
Tauri 2 + Vue 3 frontend (`app/`), Kotlin/JVM backend sidecar (`server/`, planned), mpv for playback (`player/`, planned).

- Overall plan, architecture and phase summaries: [docs/PLAN.md](docs/PLAN.md)
- Detailed steps and checklists per phase: [docs/phases/](docs/phases/)

## Rules

1. **Read the plan first.** Before doing anything else in this workspace, read [docs/PLAN.md](docs/PLAN.md). When the task touches a specific phase, also read the matching `docs/phases/phase-N-*.md` and keep its checklist up to date (tick items only when they are actually done and verified).
2. **Never commit.** Do not run `git commit` (including `--amend`) or any other command that creates commits in this workspace (e.g. `git merge`, `git rebase`, `git cherry-pick`, `git revert`, `git stash` + commit flows). Leave all changes unstaged or staged for the user to review and commit themselves. A deny rule in `.claude/settings.json` enforces this for `git commit`. Never commit or stage binaries, APKs, jars, `.pb` files or heap dumps.

## Standing rules for every stage

Work is split into phases (`docs/phases/`), and each phase into stages (its §8 "Stage plan"). Every stage runs in a new session.

3. **Branch first.** Before any edit, switch to a new feature branch off an up-to-date main: `git switch main`, `git pull`, `git switch -c feat/phase-N-<topic>`. Never edit on main.
4. **One stage at a time.** Plan the stage briefly, then implement it. Ask the user only about decisions that are genuinely theirs.
5. **Keep the status docs in sync.** Every stage updates, together:
   - the phase doc: §5 checklist ticks (only verified items), the §8 stage plan, the §9 decision record, the §10 findings log;
   - `docs/PLAN.md`: tick a milestone only when every phase-doc item behind it is verified; update the phase status (§4, §8 "First tasks"), §6 risks and §7 open decisions;
   - `README.md`: the status box, the Roadmap row, the commands tables for new or changed commands, and the "Decided so far in Phase N" list. It must match the phase doc and PLAN.md;
   - `server/UPSTREAM.md` for any server change (every upstream edit gets a `// Madoyomi:` comment).
6. **Report at the end of the stage, then stop.** Report what changed, the decisions and findings, and how it was verified (test counts against the baseline). Add a one-line commit message and a PR title.
7. **Hand off.** Write a ready-to-paste prompt for the next stage into `NEXT-PROMPT.md` in the repo root (overwrite it), and repeat it at the end of the report. Use the same format: current state, baseline test counts, gotchas, the tasks (task 0 is the branch switch; include the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules. Then wait for the user to commit or merge.
