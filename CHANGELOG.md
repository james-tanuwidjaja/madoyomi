# Changelog

Each release's notes. The release workflow takes the section of the version it builds (`## X.Y.Z`) for the GitHub release and for `latest.json`, so the app's update dialog shows it: keep each section short and in plain words. A release fails without its section (`node tools/release-notes.mjs X.Y.Z` checks it). See [RELEASING.md](RELEASING.md).

## 0.1.0

The first release of Madoyomi, a Windows app for anime and manga that runs Aniyomi and Mihon extensions.

- Add your own extension repos, then browse, search and install anime and manga extensions.
- A library for each kind, with categories, update checks and history.
- Read manga in the built-in reader; play anime in mpv, with progress saved.
- Download chapters and episodes to read and watch offline.
- Backups, Export logs, and updates of the app from Settings.
