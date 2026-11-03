# Madoyomi

Windows 11 desktop app for anime + manga that runs existing Aniyomi/Mihon extensions unchanged.
Tauri 2 + Vue 3 frontend (`app/`), Kotlin/JVM backend sidecar (`server/`, planned), mpv for playback (`player/`, planned).

- Overall plan, architecture and phase summaries: [docs/PLAN.md](docs/PLAN.md)
- Detailed steps and checklists per phase: [docs/phases/](docs/phases/)

## Rules

1. **Read the plan first.** Before doing anything else in this workspace, read [docs/PLAN.md](docs/PLAN.md). When the task touches a specific phase, also read the matching `docs/phases/phase-N-*.md` and keep its checklist up to date (tick items only when they are actually done and verified).
2. **Never commit.** Do not run `git commit` (including `--amend`) or any other command that creates commits in this workspace (e.g. `git merge`, `git rebase`, `git cherry-pick`, `git revert`, `git stash` + commit flows). Leave all changes unstaged or staged for the user to review and commit themselves. A deny rule in `.claude/settings.json` enforces this for `git commit`.
