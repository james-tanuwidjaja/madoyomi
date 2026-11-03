# Phase 6 — Library features

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 5](phase-5-manga-reader.md) · Next: [Phase 7](phase-7-packaging-release.md)

## 1. Goal

Make Madoyomi something people can live in day to day: organize the library with categories, get notified about new episodes/chapters, see history, use unread/unwatched badges, download content for offline use, and back up and restore everything. Importing Aniyomi/Mihon backups is a stretch goal.

## 2. Depends on / inputs

- Phases 3–5: library, playback progress (`episode_progress`) and reading progress (`chapter_progress`).
- `yt-dlp` and/or `ffmpeg` available in `tools/bin/` for anime downloads (bundled in Phase 7).

## 3. Deliverables

- Categories: tables, API, UI (create/rename/reorder/delete, assign entries, per-category sort).
- Update checker: scheduled job, per-category inclusion, "new" badges, WS `library.updates` event, optional Windows toast.
- History: anime and manga history list with "continue" actions and delete/clear.
- Badges: unread/unwatched counts on library cards, downloaded indicator.
- Download manager `server/core/downloads/`: queue, workers, pause/resume/retry/reorder, storage layout.
- Backup/restore: `.madoyomi-backup` (gzipped protobuf or JSON), manual + scheduled auto-backup, restore with merge.
- Stretch: import Aniyomi (`.tachibk`) / Mihon backup files.

## 4. Detailed steps

### Categories
1. Tables `category(id, type, name, order, flags)` and `entry_category`. A "Default" category always exists.
2. API: CRUD + reorder + bulk assign. UI: category tabs in Library, management dialog, multi-select assign.

### Update checker
3. Job scheduler in the backend (interval setting: off / 6h / 12h / 24h) plus a manual "update library" button.
4. For each library entry in included categories: fetch the episode/chapter list, diff it against the DB, insert new items, and mark the entry as having updates.
5. Respect source rate limits and run with limited parallelism per source; skip entries marked "completed" (setting).
6. Emit `library.updates` with counts; show an "Updates" view (new items, newest first) and an optional Windows notification through Tauri.

### History & badges
7. History is written from progress events (watched/read). The view groups entries by date and has a "resume" button.
8. Unread/unwatched counts come from a cheap aggregate query (or are cached when progress changes).

### Downloads
9. Queue model: `download(id, type, entryId, itemId, status, progress, error, order)`. Survives restarts.
10. **Manga:** fetch all pages through the image pipeline and write them to `downloads/manga/{source}/{title}/{chapter}.cbz` (with `ComicInfo.xml`).
11. **Anime:** resolve the video right before download (pick the preferred quality). For direct files, stream them with headers; for HLS, use `ffmpeg -headers … -i m3u8 -c copy` or `yt-dlp --add-header`. Save to `downloads/anime/{source}/{title}/{episode}.mkv/.mp4`, with subtitle files next to it.
12. Workers: configurable concurrency (global + per source), pause/resume/cancel/retry, reorder; progress over WS.
13. The player and reader prefer local files when they exist (mpv plays the file; the reader reads the CBZ).
14. UI: Downloads screen (queue + completed), download buttons on episode/chapter lists (single, range, "next N unread").

### Backup & restore
15. Define the backup schema: library entries, categories, progress, history, repos, extension list (not APKs), trusted certs, source preferences, app settings.
16. Create a backup manually and on a schedule (keep the last N). Restore merges or replaces, then reinstalls the listed extensions after a trust prompt.
17. Stretch: parse Aniyomi/Mihon backup protobufs and map them to our schema (sources matched by source ID).

### Tests
18. Update diff logic, download worker state machine, CBZ writer, backup round-trip (backup → wipe → restore → same state).

## 5. Checklist

### Categories
- [ ] Category tables + API
- [ ] Library category tabs + management UI
- [ ] Bulk assign

### Updates
- [ ] Scheduled + manual update checker
- [ ] Per-category inclusion, rate-limit aware
- [ ] Updates view + WS event
- [ ] Optional Windows toast notification

### History & badges
- [ ] History recorded from progress
- [ ] History view with resume / delete / clear
- [ ] Unread/unwatched badges on library cards

### Downloads
- [ ] Persistent download queue
- [ ] Manga → CBZ (+ `ComicInfo.xml`)
- [ ] Anime direct-file download with headers
- [ ] Anime HLS download via ffmpeg/yt-dlp
- [ ] Subtitles saved alongside
- [ ] Pause/resume/cancel/retry/reorder + concurrency limits
- [ ] Player/reader use local files
- [ ] Downloads screen + download actions in lists

### Backup & restore
- [ ] Backup schema defined
- [ ] Manual + scheduled backup (keep last N)
- [ ] Restore (merge/replace) + extension reinstall with trust
- [ ] Stretch: Aniyomi/Mihon backup import

### Tests
- [ ] Update diff tests
- [ ] Download worker tests
- [ ] Backup round-trip test

## 6. Exit criteria

- [ ] New episodes/chapters appear in the Updates view after a scheduled check, with badges in the library.
- [ ] A downloaded anime episode (HLS source) and a downloaded manga chapter (CBZ) both open offline.
- [ ] Backup → fresh install → restore gives back the same library, categories, progress and settings.

## 7. Risks & notes

- Update checks can hammer sources. Enforce per-source limits and stagger requests.
- HLS with expiring/encrypted segments may fail in ffmpeg. Fall back to yt-dlp and report clearly.
- Backup must never include secrets in plain text where avoidable (tracker tokens come in Phase 8).

## 8. Decisions closed here

- Download tool for anime: ffmpeg, yt-dlp, or both (and which one is the default).
- Backup file format (protobuf vs. JSON) and extension.
