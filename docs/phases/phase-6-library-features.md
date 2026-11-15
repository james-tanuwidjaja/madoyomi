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
- [x] Category tables + API
- [x] Library category tabs + management UI
- [x] Bulk assign

### Updates
- [ ] Scheduled + manual update checker
- [ ] Per-category inclusion, rate-limit aware
- [ ] Updates view + WS event
- [ ] Optional Windows toast notification

### History & badges
- [x] History recorded from progress
- [x] History view with resume / delete / clear
- [x] Unread/unwatched badges on library cards

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

- Download tool for anime: ffmpeg, yt-dlp, or both (and which one is the default). **Decided in Stage 1 (the user's): both, ffmpeg the default.** Direct files are downloaded by the backend itself with the source's headers. HLS goes through `ffmpeg -headers … -i <m3u8> -c copy` (what Aniyomi does; it also muxes the subtitles), and through yt-dlp (`--add-header`) as the fallback when ffmpeg fails, with the failure reported. Both are bundled in Phase 7 (§9).
- Backup file format (protobuf vs. JSON) and extension. **Decided in Stage 1 (the user's): gzipped protobuf, `.madoyomi-backup`.** Upstream's and Mihon's format: upstream's manga backup (`BackupManga` with its categories, history and meta) is reused and extended with anime, repos, trusted certificates and settings, keeping the field numbers compatible so the Aniyomi/Mihon import (the stretch) stays cheap. The extension keeps it apart from a Mihon/Aniyomi `.tachibk`, which those apps would partly misread (§9).
- **Categories: one list per kind** (Stage 1, the user's; not one of the doc's two decisions, but it changes what the Library shows). Each kind's Library has its own tabs, as in Aniyomi. Upstream's manga categories stay as they are; anime gets Madoyomi's own tables (M0072, which replaces the Phase 1 stub) with upstream's rules (§9).

### Stage plan (2026-11-14)

Agreed with the user at the end of Phase 5 (phase-5 §9 "Stage 6"). Each stage ends in something verifiable and a commit:
1. **Categories** (steps 1–2): the tables and the API for both kinds (create, rename, reorder, delete, assign one and many, the Default category, a sort per category), the Library's category tabs, the management dialog and bulk assign. Also settles the two decisions above. Verified by server tests (the API, the anime tables and the migration), app tests, the operations in the live spec, and live through the API and in `pnpm tauri dev`.
   - *Done* (2026-11-15):
     - the decisions (§8, §9): one category list per kind, ffmpeg with yt-dlp as the fallback, gzipped protobuf as `.madoyomi-backup`;
     - server: manga categories are upstream's tables and API. Anime categories: `M0072_AnimeCategories` (the stub `ANIME_CATEGORY` replaced by the list `ANIME_CATEGORY` with its Default row, and the join `ANIME_CATEGORY_ANIME`), `madoyomi.core.anime.AnimeCategories` (upstream's rules, names checked ignoring case on a rename too, Default protected), and `AnimeCategoryGraphQL` (`animeCategories`, `animeCategory`, `createAnimeCategory`, `updateAnimeCategory`, `updateAnimeCategoryOrder`, `deleteAnimeCategory`, `updateAnimeCategories`, `updateAnimesCategories`, `setAnimeCategoryMeta`, `deleteAnimeCategoryMeta`, `AnimeType.categories`). `updateAnime(s)` puts a newly added entry into the categories flagged for new entries. One upstream edit: upstream's GraphQL `updateManga(s)` didn't do that for manga (only its REST `Library.addMangaToLibrary` did), so the dialog's "New entries" flag did nothing for a manga added from its details page; `madoyomi.core.library.MangaNewEntryCategories`, called from `MangaMutation.updateMangas` (server/UPSTREAM.md "Library categories (Phase 6)");
     - tests: 13 new on the server (`AnimeCategoryApiTest` 9, `MangaNewEntryCategoriesApiTest` 3, a `MigrationsFromEmptyDbTest` case), 807 in all;
     - app: `api/operations/categories.ts` (14 documents) and the library's category ids and bulk removal (2 more: 69 in all), `useCategories` (the list and every change, written into the cache), the pure `utils/categories.ts`, `stores/libraryCategory.ts` (the open tab per kind); the Library's tabs with counts, a sort per tab kept in the category's meta, Select (click, Shift+click, Select all) with Set categories and Remove from library; `CategoriesDialog` (create, rename, move, the new-entries flag, delete with a confirm); `CategoryPicker` (tri-state boxes, a new category made in place); the details pages' Categories button (`EntryCategoriesButton`). 27 new tests, 605 passed + 12 skipped; the live spec 12/12 with 69 documents, with the new-entry flag checked for each kind;
     - live through the API and in `pnpm tauri dev`, after an app restart too (§10).
2. **History and badges** (steps 7–8): history written from the progress (watched and read), a History view grouped by date with resume, delete and clear; unread/unwatched counts on the library cards (a cheap aggregate). Verified by server tests (the history writes and the counts), app tests, and live after watching an episode and reading a chapter.
   - *Done* (2026-11-15):
     - the decision (§9): the History view follows the anime/manga switch, as every other section;
     - server: manga history is upstream's `CHAPTER_USER.last_read_at` (stamped by `updateChapter(s)` with a page, carried by upstream's backup), with one upstream edit: "Mark unread" (`isRead: false` with page 0) no longer stamps it, so chapters marked unread don't show as just read (`ChapterMutation.updateChapters`). Anime history is `ANIME_HISTORY` (M0070's stub, one row per episode; no migration), stamped by the player's saves (`EpisodeProgress.save`) and by `updateEpisode(s)` given a position, and moved with a re-listed episode on a refresh. `madoyomi.core.library.AnimeHistory`/`MangaHistory` and `HistoryGraphQL`: `animeHistory`/`mangaHistory` (one item per entry, its latest episode or chapter, newest first, paged, with a `resume` target: the item, or the first unfinished one after it when it is finished), `deleteAnimeHistory`/`deleteMangaHistory` (by entry) and `clearAnimeHistory`/`clearMangaHistory`, which keep the marks and positions; `AnimeType.unwatchedCount` and `lastSeenAt` (`AnimeStatsDataLoader`, a Seasons entry counting its seasons), the counterparts of upstream's `unreadCount` and `lastReadChapter.lastReadAt`;
     - tests: 6 new on the server (`HistoryApiTest`) and one more case in `AnimeDetailsTest`, 813 in all;
     - app: the History route and sidebar entry (`HistoryView`, `HistoryRow`, `useHistory`, `api/operations/history.ts` with 6 documents, `utils/history.ts`): grouped by day (Today, Yesterday, the date), the episode or chapter with its time and how far it got, Resume (a chapter in the reader at its page, an episode through its details page's picker with its resume point, `?play=`), "Next: …" when it is finished, Remove, Clear all with a second click, 50 at a time with Show more; the library cards' unwatched/unread badge and the sorts "Unwatched/Unread" and "Last watched/read" (also as a category's sort); the counts and the History fetched again after a mark, a reader save or a playback session. 20 new tests, 625 passed + 13 skipped; the live spec 13/13 with 75 documents;
     - live through the API (the live spec) and in `pnpm tauri dev` after watching an episode and reading chapters (§10).
3. **The update checker** (steps 3–6): scheduled (off / 6 h / 12 h / 24 h) and manual, per category (`includeInUpdate`), with limited parallelism per source and completed entries skipped (a setting); new items inserted and the entries marked; the Updates view (newest first), the WS `library.updates` event, and an optional Windows toast through Tauri. Verified by update-diff tests, a fake source's new chapters and episodes through the API, and live with a scheduled check.
4. **Manga downloads** (steps 9–10, 12–14 for manga): the persistent queue (`download`), workers with pause, resume, cancel, retry, reorder and concurrency limits, chapters as CBZ with `ComicInfo.xml` under `downloads/manga/{source}/{title}/`, staged outside the page cache's folder (phase-5 §10, Stage 1 "Not checked"), the reader reading the CBZ, the Downloads screen and the chapter list's download actions (single, range, "next N unread"). Verified by worker state-machine and CBZ tests, and live: a chapter downloaded, then read with the network off.
5. **Anime downloads** (step 11, 12–14 for anime): the video resolved right before the download at the preferred quality; direct files streamed with their headers; HLS through ffmpeg with yt-dlp as the fallback (§8); subtitles next to the file; mpv playing the local file. Verified by tests on a loopback host (direct, HLS, the fallback) and live: an HLS episode downloaded and played offline.
6. **Backup and restore** (steps 15–17): the `.madoyomi-backup` schema (gzipped protobuf, §8), manual and scheduled backups (keep the last N), restore with merge or replace, the extensions reinstalled after a trust prompt; the Aniyomi/Mihon import as the stretch. Verified by a backup round-trip test (backup → wipe → restore → the same state) and live on a fresh data dir.
7. **The exit run**: every §6 criterion from a fresh data dir, and a fix round.

## 9. Decision record

### Stage 1 (2026-11-14)

Decided by the user:
- **The stage plan** (§8, agreed at the end of Phase 5).
- **Categories: one list per kind.** Each kind's Library has its own category tabs, as in Aniyomi (which keeps anime and manga categories apart), so an Aniyomi import maps them one to one. Upstream's manga categories stay as they are. Anime gets Madoyomi's own tables, a migration replacing the Phase 1 stub, and its own GraphQL, a mirror of upstream's rules. The alternative was one shared list: the stub (`ANIME_CATEGORY`, pointing at upstream's `CATEGORY`) as it was and fewer server lines, but a category made for anime would show, empty, in the manga Library too, and an Aniyomi import would have to merge two lists.
- **The anime download tool (§8): both, ffmpeg the default.** HLS goes through `ffmpeg -headers … -c copy`, which Aniyomi uses and which also muxes subtitles into MKV. yt-dlp (`--add-header`) is the fallback when ffmpeg fails, for expiring or unusual segments (§7's risk), and the failure is reported. Direct files need neither: the backend downloads them with the source's headers. Cost: two binaries in Phase 7 (yt-dlp.exe about 18 MB) and two code paths to test. The alternatives were ffmpeg only (one dependency, with no fallback), or yt-dlp as the default (parallel fragments with retries per fragment, but it needs ffmpeg anyway to merge and mux, updates often and starts slower).
- **The backup format (§8): gzipped protobuf, extension `.madoyomi-backup`.** This is upstream's and Mihon's format. Upstream's manga backup code (`BackupManga` with its categories, history and meta, which already carries the per-manga reader settings, phase-5 §9 "Stage 5") is reused and extended with anime, repos, trusted certificates and settings. Keeping the field numbers compatible makes the Aniyomi/Mihon import cheap. The alternatives were gzipped JSON (readable and easy to diff, but larger, and the import would still need a protobuf reader) and the `.tachibk` extension (Mihon/Aniyomi would partly misread the Madoyomi-only fields).

Decided in the stage (defaults, not asked):
- **Anime categories mirror upstream's.** `ANIME_CATEGORY` has `CATEGORY`'s columns (name, `sort_order`, `is_default` "new entries go here", `is_default_category`, the include-in-update and include-in-download flags) plus `META` as JSON text in place of upstream's `CATEGORYMETA` table. `ANIME_CATEGORY_ANIME` mirrors `CATEGORYMANGA`. The GraphQL has upstream's shapes with "Anime" in the names (`createAnimeCategory`, `updateAnimeCategories` for one anime's categories, as `updateMangaCategories`), so the app calls both kinds the same way.
- **The stub is replaced, not kept.** M0070's `ANIME_CATEGORY` was never written. M0072 drops it when it has the stub's shape and creates the list under that name, so a rerun leaves the new table alone.
- **Default is the category of the entries in no other** (upstream's and Mihon's model), with one row per kind. An entry's categories never list it. Its name is reserved. It can't be renamed, moved or deleted, and new entries can't be flagged into it; for anime each of these is `BAD_REQUEST`, where upstream ignores it.
- **Names are trimmed, 1 to 64 characters, and unique ignoring case**, for a new category and a rename. The backend checks anime names. Upstream checks only a new manga name, and case-sensitively, so the app checks every name of both kinds before it sends it.
- **New library entries go into the categories flagged "New entries"** (upstream's `default` flag), unless they are in a category already (a removed entry keeps its categories, as upstream's manga do). Otherwise they are in Default. Mihon's "ask every time" prompt isn't added; the Categories button on the details page and the Library's bulk assign set them afterwards.
- **The backend applies that flag for manga too, through one upstream edit.** Upstream's GraphQL `updateManga(s)`, which the app's "Add to library" calls, skipped it (only the REST `Library.addMangaToLibrary` applied it). The alternative was the app assigning the flagged categories after an add; the backend keeps one rule for both kinds and every client, at the cost of a small `// Madoyomi:` edit in `MangaMutation.updateMangas` that calls `MangaNewEntryCategories`.
- **The Library's tabs, as Mihon's:** there are no tabs while Default is the only category, and the whole library shows. Otherwise there is a tab per category in order, with its count; Default's tab shows only while it holds entries; an empty category keeps its tab. The open tab is remembered per kind on this device (`madoyomi.libraryCategory`), and Left/Right move between tabs.
- **A sort per category, in the backend** (the category's meta, `madoyomi.library.sort` = `title:asc` …), so Stage 6's backups carry it, as the reader settings per manga. A category without one uses the kind's last chosen sort on this device (Phase 3's `madoyomi.librarySort`, which every change also updates), and that is all there is without tabs. The keys stay title and date added; Stage 2 adds the unread count and last read/watched.
- **The management dialog** (the Library's "Categories"): create, rename inline, move up and down (buttons, no drag), the "New entries" flag, and delete with a confirm in the row that says where the entries go. Every change is saved at once; Default isn't listed.
- **Assigning:** a details page's "Categories" button (named after the entry's categories while it is in the library) and the Library's selection both open the same picker. It has a box per category: checked, unchecked, or mixed when some of the selected entries are in it. A mixed box changes nothing until it is clicked. Save sends one change for all the entries. A new category can be made in the picker and is checked at once.
- **Selection in the Library:** Select turns the cards into toggles (a click, Shift+click for a range in the shown order, Select all for the tab); the bar has Set categories and Remove from library, the second confirmed by a second click ("Remove 5 entries?"); Esc or Done ends it.
- **Bulk removal** goes through `updateAnimes`/upstream's `updateMangas` (two new documents); the browse cards and the entries' details lose their library mark, as one removal does.
- **The update and download flags** (`includeInUpdate`, `includeInDownload`) are stored for both kinds but not shown yet: Stage 3 (the update checker) and Stage 4 (downloads) use them and add them to the dialog.

### Stage 2 (2026-11-15)

Decided by the user:
- **The History view follows the anime/manga switch,** as the Library, Browse, Extensions and Settings do (and as Aniyomi keeps anime and manga history apart). The alternative was one list mixing both kinds by time: a "continue" list across both, but the only screen ignoring the switch, and two histories to page through together.

Decided in the stage (defaults, not asked):
- **Manga history is upstream's `CHAPTER_USER.last_read_at`, not a table.** Upstream stamps it whenever a page is saved, its data loaders and its backup (`BackupHistory`) already use it, so Stage 6 restores it with no extra work. Mihon's `history` table would have been a second copy to keep in step. The cost is one upstream edit: upstream also stamped it when "Mark unread" reset the page (`isRead: false, lastPageRead: 0`), which would have listed every chapter marked unread as just read; a patch that marks unread no longer stamps (Mihon's rule).
- **Anime history stays one row per episode** (`ANIME_HISTORY`, Aniyomi's `animehistory`): no migration, and Stage 6 can write Aniyomi's `BackupAnimeHistory` from it. It is written by playback (the player's saves: on load, every 5 s while playing, on close) and by an explicit position (`updateEpisode(s)` with `lastSecondSeen`); watched marks alone never write it, as upstream's `isRead` doesn't stamp. A refresh that moves an episode to a new URL moves its history with its watch state.
- **What history means:** an episode played, even for a moment, or a chapter opened (the reader saves the page it opens on). Both kinds store epoch seconds, as upstream's column.
- **One row per entry,** its episode or chapter played or read last (Mihon's and Aniyomi's history screens), grouped by local day: Today, Yesterday, then the date (with the year when it isn't this one), with the time of day and how far it got ("12:34 left", "Page 13 of 92", Watched, Read).
- **Resume:** a chapter opens in the reader, which resumes at its saved page; an episode opens its details page with the picker for that episode (`?play=<id>`, dropped from the URL once used), so the resume prompt, the next-episode toast and the live progress work as they do there; mpv's window comes up over the app as from the list. A finished item resumes the first unfinished one after it in the source's order ("Next: Ep. 2 …"), else the item again ("Watch again" / "Read again"); the backend works this out (`resume`).
- **Delete removes the entry from history** (every episode's or chapter's date), not only its latest item, so the row goes away rather than showing an older chapter (Mihon asks with a "remove all" box; one behaviour is simpler). Clear all asks with a second click ("Clear all anime history?"). Neither touches the watched/read marks or the positions.
- **The badges:** the unwatched/unread count on each library card's top left, above 0, in the accent color, from one grouped query for the whole list: upstream's `MangaType.unreadCount` for manga (its existing data loader) and `AnimeType.unwatchedCount` for anime (a Madoyomi data loader of the same shape). A Seasons entry counts its seasons' episodes. Counted from the stored episodes and chapters: an entry never opened (nothing fetched yet) has no badge. The library query is fetched again after a watched or read mark, every reader save (debounced), and a playback session's start, watched mark and end; the History too.
- **The new sorts:** "Unwatched"/"Unread" (most first by default) and "Last watched"/"Last read" (newest first; entries never watched or read go last either way), also as a category's own sort (`unread:desc`, `lastRead:desc` in its meta). The manga library uses upstream's deprecated top-level `unreadCount` and `lastReadChapter`, as it already uses `inLibrary`/`inLibraryAt`; a move to `user { … }` would change them together.
- **History pages are 50 entries** with Show more; there is no search (not in the plan).

## 10. Findings log

### Stage 1 (2026-11-14 to 2026-11-15)

- **Baseline before the stage** (main at `0e54309`, Phase 5's exit run merged as #38; rerun before any edit):
  - server: `.\gradlew.bat build` green with 794 tests, 0 failures (core 694 with 8 skipped, AndroidCompat 7, ext-host 8, api 85), on the first build;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 54 files (53 + 1 skipped), 578 passed + 11 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 11/11 with 53 documents;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **The stage ran over two sessions.** The first built it, ran the checks below and started the UI check, but stopped before recording the results. The second (the user's choice: continue rather than redo) reviewed the diff, fixed the manga new-entry gap below, reran every check and redid the live checks.
- **Upstream's GraphQL ignored the "New entries" flag for manga.** `updateManga(s)` only upserts `MANGA_USER`; the flagged categories were applied only by the REST `Library.addMangaToLibrary`, which the app doesn't use. So a manga added from its details page stayed in Default whatever the dialog said, while anime (Madoyomi's code) honoured it. Found in review, fixed in the backend (§9) and covered by `MangaNewEntryCategoriesApiTest` and the live spec. Before the fix the new live-spec step could not have passed for manga.
- **Counts after the stage:**
  - server: `.\gradlew.bat build` green with **807** tests, 0 failures (core 695 with 8 skipped, AndroidCompat 7, ext-host 8, api 97): 13 new (`AnimeCategoryApiTest` 9, `MangaNewEntryCategoriesApiTest` 3, the M0072 case in `MigrationsFromEmptyDbTest`). The first build after the fix failed only `CefTest` (`IOException: proxy.example`, the known `SocksProxyTest` leak); the rerun was green;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24 (one earlier run in the first session lost AnimeGG's site, 19 passed and 6 failed; the rerun passed);
  - app: 58 files (57 + 1 skipped), **605 passed + 12 skipped** (27 new: `categories` utils and operations, `CategoriesDialog`, `EntryCategoriesButton`, `LibraryView`'s tabs, sort and selection, `entries`' bulk removal; the live spec's new test is the 12th skipped one); `pnpm format:check`, `pnpm typecheck`, `pnpm lint` and `pnpm build` clean;
  - the live spec: **12/12 with 69 documents** (16 new). For each kind it made, renamed, moved and deleted categories, set a sort in a category's meta, put two stored entries into two categories and took them out of one, and added an entry that was out of the library and in no category into a category flagged for new entries (anime 3 and manga 3, both put back), with each kind's categories as they were before at the end;
  - `cargo test`: 17, unchanged (the shell didn't change; run in the first session on this branch).
- **Live in `pnpm tauri dev`** (data dir `p6-ui`, 5 anime and 6 manga in the library):
  - anime: the tabs Watching 3, Dropped 0, Later 2, Favorites 1 and Default 2 from the first session; Watching kept its own sort (Title, Z to A) while Default showed A to Z;
  - the Categories dialog: "Live S1" added (its tab appeared at once with 0), "watching" refused ("There is a category named…", ignoring case), moved up above Favorites (the tabs followed), renamed in place, flagged "New entries";
  - bulk assign: on Default, a click and a Shift+click selected both entries, Set categories checked Later and the renamed category, Save: Default's tab went away (empty), Later 4 and the new category 2, and the selection ended. On Later, Select all then Set categories showed Watching, the new category and Favorites mixed, Later checked and Dropped empty (Cancel changed nothing). A click that landed on "Remove from library" only armed it ("Remove 4 entries?"); nothing was removed;
  - manga: its own list (no tabs, none of the anime categories). "Reading" made in the dialog brought the tabs (Default 6, Reading 0); flagged "New entries"; Theme World removed from the library on its page and added again: its button read "Categories: Reading" (the backend fix). Its picker made "Favorites" in place, checked at once; saved: "Categories: Reading, Favorites", and the Library showed Default 5, Reading 1, Favorites 1;
  - a sort per tab: Title on Reading, Date added on Default, each kept when switching; Favorites (no sort of its own) followed the last choice, as designed. Deleting Favorites asked in its row ("Its 1 entry goes to Default unless in another category"), then its open tab fell back to Default and Theme World stayed in Reading;
  - after an app restart, the manga Library opened on Reading (Title, A to Z) and the anime one on Later, with every category and count kept. Left/Right moved between tabs; an empty category's tab says "Nothing in Dropped yet…";
  - the token: no `token=` in `sidecar.log`, `madoyomi.log` or the dev output, nor in the live spec's data dir. The only ERROR lines were source failures (MangaDex has no chapters for Theme World, and upstream's updater on the seeded entries).
- **Not checked:**
  - a large library (hundreds of entries across many categories): the tabs and counts are computed in the app from the one library list, which Phase 3 already virtualizes; Stage 2's badges will add per-entry counts, a good point to measure;
  - drag-to-reorder: not built (up/down buttons, §9);
  - `includeInUpdate` and `includeInDownload`: stored, not shown or used until Stages 3 and 4;
  - categories in a backup: Stage 6 (the sort is in the category's meta so it can be carried).

### Stage 2 (2026-11-15)

- **Baseline before the stage** (main at `2b759a0`, Stage 1 merged as #39; rerun before any edit, job 404): server `.\gradlew.bat build` green with 807 tests, 0 failures (core 695 with 8 skipped, AndroidCompat 7, ext-host 8, api 97); `tools/smoke-api.ps1 -NoBuild` 24/24; app 58 files (57 + 1 skipped), 605 passed + 12 skipped, `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean; the live spec 12/12 (69 documents); `cargo test` 17, `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **Upstream stamped `last_read_at` on "Mark unread".** The chapter list's Mark unread sends `isRead: false, lastPageRead: 0` (back to page 1), and upstream's `updateChapters` stamps `last_read_at` with any `lastPageRead`. With history read from that column, every chapter marked unread would have jumped to the top of History as just read. Fixed by the one upstream edit (§9), covered by `HistoryApiTest` and checked live (below).
- **`ANIME_HISTORY` lost a re-listed episode's history.** `AnimeDetails.reconcileEpisodes` carried the watch state to a re-listed episode with the same number, but the history row went with the deleted episode (cascade); `AnimeDetailsTest` even asserted it. Now it moves along, as upstream carries `last_read_at`.
- **`updateEpisode`'s `lastSecondSeen` is a `LongString`:** the first `HistoryApiTest` run failed on an `Int` literal (a test mistake, not a server one).
- **Counts after the stage:**
  - server: `.\gradlew.bat build` green with **813** tests, 0 failures (core 695 with 8 skipped, AndroidCompat 7, ext-host 8, api 103): `HistoryApiTest` 6 new, `AnimeDetailsTest`'s carry-over case extended; `tools/smoke-api.ps1 -NoBuild` 24/24 (job 408, and again on the final tree, job 417: its first run, job 414, failed only `CefTest` with `IOException: proxy.example`, the known `SocksProxyTest` leak);
  - app: 61 files (60 + 1 skipped), **625 passed + 13 skipped** (20 new: `history` operations and utils, `HistoryView`, the library's badges and new sorts, `?play=` on the details page, the realtime and `fetchLibrary` changes, the new sort keys in a category's meta; the live spec's new test is the 13th skipped one); `pnpm format:check`, `pnpm typecheck`, `pnpm lint` and `pnpm build` clean (job 409, and on the final tree with the READMEs, job 415);
  - the live spec: **13/13 with 75 documents** (6 new) on `live-data` (job 410). For each kind it saved a stored episode's and chapter's own position again, found its entry first in the history with that item and a time within 2 minutes, read the library lists with their counts, deleted the entry's history (1 row each) and found the marks and positions as they were;
  - `cargo test` 17, unchanged (the shell didn't change).
- **Live in `pnpm tauri dev`** (data dir `p6-ui`):
  - the sidebar has History under Library. The anime Library's Watching tab showed the badges 13 ("Oshi no Ko" 2nd Season) and 11 ("Oshi no Ko"), none on an entry with nothing unwatched;
  - played "Oshi no Ko" Ep. 1 (KickAssAnime, HLS 1080p) in mpv, seeked to 5:00 and closed mpv at 5:10 (through its IPC pipe). The row showed its progress; History (anime) showed Today, "Oshi no Ko", Ep. 1 - Mother and Children, 10:28 AM · 1:16:50 left, and Resume. Resume opened the details page (History still marked in the sidebar) with Ep. 1's picker and "Resume from 5:10";
  - marked Ep. 1 watched from the list: the card's badge went from 11 to 10, and History showed "10:28 AM · Watched" (the bulk mark didn't restamp it) with "Next: Ep. 2 - The Third Option";
  - manga History listed the reading of the earlier stages (Yesterday: Berserk, Funtime Phobia, First Love Trap, Osora, two test entries), with "Read again" on finished last chapters and "Next: Chapter 2" on a finished first one. Resume on Kingdom (Chapter 893, page 7 of 20) opened the reader at 7/20; after paging, History showed Kingdom under Today at 10:30 AM, Page 10 of 20;
  - Remove on "Double Page Test" took its row away. Mark unread on Osora's chapter 126 (it was Read) left Osora under Yesterday at 1:07 AM, now "Page 1 of 103" and Resume: the upstream edit at work;
  - Kingdom added to the library (into Reading, the flagged category) showed the badge 828; reading Chapter 893 to its last page made it 827, and History "10:32 AM · Read" with "Read again";
  - the sort: "Unwatched" (Most first) put 13, 10, then the entry without a badge; "Last read" on the manga Default tab; the Watching tab put back to Title, Z to A afterwards;
  - Clear all (anime) armed "Clear all anime history?", the second click emptied it ("Nothing watched yet."), and Ep. 1 stayed watched (its badge 10);
  - the token: no `token=` in `sidecar.log`, `madoyomi.log` or the dev output. The only ERROR line was MangaDex's "No chapters found" for a library entry opened to look for a manga badge (the MangaDex entries in `p6-ui` have no chapters, as Stage 1 found for Theme World).
- **Not checked:**
  - a large history or library (thousands of chapters read across hundreds of entries): `CHAPTER_USER` has no index on `last_read_at` (an upstream table), and the history query groups the user's rows by manga; fine at the sizes here, to measure before adding an index (server/UPSTREAM.md);
  - history in a backup: Stage 6. Manga's is upstream's `BackupHistory` already; anime's needs Aniyomi's `BackupAnimeHistory` from `ANIME_HISTORY`;
  - the "Watch again" path for a finished last episode in the UI (the backend's `resume` falls back to the item, covered by `HistoryApiTest`; no such entry in `p6-ui`);
  - a Seasons entry's badge in the UI (no Seasons entry in `p6-ui`'s library; covered by `HistoryApiTest`).
