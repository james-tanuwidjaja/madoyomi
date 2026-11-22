# Copilot instructions for Madoyomi

The ground rules for AI assistants in this repository are in [AGENTS.md](../AGENTS.md) at the repository root; follow them, together with [CONTRIBUTING.md](../CONTRIBUTING.md). The ones that matter most, also when reviewing a pull request:

- No links to extension repos or copyrighted content anywhere (code, tests, docs, PR text), and no binaries in git.
- An edit to upstream Suwayomi-Server, Mihon or Aniyomi code has a `// Madoyomi:` comment and an entry in `server/UPSTREAM.md`.
- A change users would notice has a plain-words line under `## Unreleased` in `CHANGELOG.md`.
- New or changed commands, settings or behaviour are reflected in README.md, CONTRIBUTING.md or app/README.md.
- Signature verification, the trust prompt, the API token and the updater's signature check are never weakened.
- `VERSION`, tags, releases and secrets are the maintainer's; no "Phase N" or "Stage N" labels in code or docs.
