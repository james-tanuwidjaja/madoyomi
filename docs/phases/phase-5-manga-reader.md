# Phase 5 — Manga reader

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 4](phase-4-playback.md) · Next: [Phase 6](phase-6-library-features.md)

## 1. Goal

A fast, comfortable manga reader in the Vue app with paged (LTR/RTL), vertical webtoon and double-page modes. Every image goes through the backend proxy so source headers/cookies apply, with prefetching and a disk cache so long webtoon chapters scroll smoothly.

## 2. Depends on / inputs

- Phase 1: manga sources work (`GET /api/manga/{id}/chapters`, `GET /api/chapters/{id}/pages`).
- Phase 3: Details screen with the chapter list; the minimal image proxy (if added there).

## 3. Deliverables

- Backend `server/core/image/`: `ImageProxy` (`GET /api/image?src=&sourceId=`, or by page ID), a disk cache with a size limit and LRU eviction, and page prefetch. **Settled in Stage 1 (§9):** upstream's page route (`GET /api/v1/manga/{mangaId}/chapter/{sourceOrder}/page/{index}`) already is the proxy, so there is no `/api/image`. Madoyomi's part is `madoyomi.core.reader` (`PageImages`, `PageCache`, `ReaderSettings`), called from upstream's `Page.getPageImage`.
- DB: `chapter_progress` (last page, read flag, updated at). **Settled in Stage 1:** upstream's `CHAPTER_USER` already has `last_page_read`, `is_read` and `last_read_at`, so there is no new table.
- API: `PUT /api/chapters/{id}/progress`, `POST /api/chapters/{id}/prefetch`. **Settled in Stage 1:** progress goes through upstream's `updateChapter(s)` (Stage 2), and the prefetch is the GraphQL mutation `prefetchChapterPages`, with `readerSettings`, `setReaderSettings`, `pageCache` and `clearPageCache`.
- UI `app/src/views/ReaderView.vue` + `app/src/components/reader/`: `PagedReader`, `WebtoonReader`, `DoublePageReader`, `ReaderToolbar`, `ReaderSettings`.
- Reader settings: default mode (global and per manga), reading direction, fit (width/height/original), background color, page gap (webtoon), preload count.

## 4. Detailed steps

### Backend — image pipeline
1. `ImageProxy`: resolve the page image URL (`imageUrlParse` when needed), fetch it with the source's OkHttp client (headers, cookies, rate limits), stream it to the client with the correct `Content-Type`, and add `Cache-Control`.
2. Disk cache keyed by a source + URL hash under `data/cache/images/`, with a size cap (default 1 GB, configurable) and LRU eviction. **Corrected in Stage 1:** the cache is upstream's chapter-page cache, `<data dir>/cache/manga/<source>/<manga title>/<scanlator_><chapter name>/NNN.<ext>` (in the data dir since the Phase 3 exit run). The cap and the eviction are added to it (§9).
3. Prefetch endpoint: warm the cache for the next N pages and the first pages of the next chapter.
4. Retry once on failure; return a clear error for the UI to show a "tap to retry" placeholder.

### Frontend — reader core
5. Route `/manga/:id/read/:chapterId?page=`. Load the page list, then render it in the chosen mode.
6. **Paged:** one page per view, LTR or RTL, click zones (left/right/menu), keyboard arrows/space, mouse wheel to change page, preload ±N pages.
7. **Webtoon:** continuous vertical scroll, virtualized (only nearby pages in the DOM), images sized by intrinsic ratio to avoid layout jumps, optional gap.
8. **Double page:** two pages side by side, respecting direction; detect wide (spread) images and show them alone; "shift by one" toggle.
9. Seamless chapter transition: show "Next chapter: …" and keep going without leaving the reader.
10. Zoom/pan in paged mode (ctrl+wheel, double-click).
11. Toolbar: chapter title, page slider, mode switch, settings, prev/next chapter, fullscreen.

### Progress
12. Save the current page (debounced) through `PUT /api/chapters/{id}/progress`; mark the chapter read on the last page.
13. Opening a chapter resumes at its last page.

### Performance
14. Use `decode()` / `loading="lazy"` sensibly and limit concurrent image requests (e.g. 4).
15. Measure: time to first page, scroll FPS on a 100+ page webtoon chapter, memory after reading 5 chapters.

### Tests
16. Unit tests: page navigation logic (LTR/RTL, double-page pairing, spread detection).
17. Backend tests: proxy forwards headers, cache hit/miss, eviction.

## 5. Checklist

### Image pipeline
- [x] `ImageProxy` with source headers/cookies (Stage 1: upstream's page route, which fetches through the source's own client; its fetch through `PageImages`. `PageImagesApiTest` checks the source's Referer and a cookie from the cookie jar on a loopback image host, and the live API run read MangaPill, Webtoons and MangaDex pages through it, §10)
- [x] Disk cache with size cap + LRU eviction (Stage 1: `PageCache`, 1 GB by default, Settings → Reader; tests for the order, the 90 % trim, the 5-minute window and the counted size; live, a 256 MB cap cut 303.5 MiB to 229.5 MiB, oldest first, and kept the chapter just read, §10)
- [x] Prefetch endpoint (next pages + next chapter) (Stage 1: `prefetchChapterPages`, 2 requests at a time; live, 20 pages of a 103-page Webtoons chapter cached in 1.2 s and then read in at most 20 ms each, and the next chapter's first 5 with its page list fetched first, §10)
- [x] Error + retry handling (Stage 1, the backend: one retry, an expired image URL got anew first, `SOURCE_FAILED` with `httpCode` and `retried`; tests for each case. The reader's "tap to retry" placeholder is Stage 2's)

### Reader modes
- [ ] Paged LTR
- [ ] Paged RTL
- [ ] Vertical webtoon (virtualized)
- [ ] Double page with spread detection + shift
- [ ] Seamless next-chapter transition
- [ ] Zoom/pan

### Controls & settings
- [ ] Keyboard, mouse wheel, click zones
- [ ] Toolbar (slider, mode switch, prev/next, fullscreen)
- [ ] Reader settings (global + per manga)

### Progress
- [ ] Page progress saved (debounced)
- [ ] Chapter marked read at the end
- [ ] Resume at last page

### Performance & tests
- [ ] Concurrency limit for image requests
- [ ] Performance measured on a 100+ page webtoon chapter
- [ ] Navigation logic unit tests
- [x] Proxy/cache backend tests (Stage 1: `PageCacheTest` 15, `ReaderSettingsTest` 4, `PageImagesApiTest` 7)

## 6. Exit criteria

- [ ] A 100+ page webtoon chapter scrolls smoothly (no visible stutter, no blank pages while scrolling at normal speed) on a mid-range laptop.
- [ ] All four modes work and remember per-manga settings.
- [ ] Reopening a chapter resumes at the last page.

## 7. Risks & notes

- Sources that need cookies from Cloudflare: the proxy must use the same cookie jar as the source (PLAN.md §6, Cloudflare).
- Very tall images (some webtoons put a whole chapter in a few huge images) may need tiling or downscaling.
- Watch memory: release off-screen images in webtoon mode.

## 8. Decisions closed here

- Default reader mode per content type (e.g. webtoon for manhwa sources, if the source provides a hint). **Decided in Stage 1 (the user's): a manga's own mode first; else webtoon when its genres or tags say Long Strip, Webtoon, Manhwa or Manhua; else the global default, paged right-to-left** (Mihon's default). Mihon sources give no reading-mode hint, so the genres are the only signal. Applied in Stage 5 (§9).
- Cache size default and location. **Decided in Stage 1 (the user's): 1 GB by default, 256 MB to 10 GB in Settings → Reader, in `<data dir>/cache/manga`** (where upstream's page cache already is since the Phase 3 exit run), with LRU eviction. Covers and downloads don't count (§9).

### Stage plan (2026-11-12)

Agreed with the user in Stage 1. Each stage ends in something verifiable and a commit:
1. **Image pipeline and Settings → Reader's cache** (steps 1–4, 17): upstream's page route through `PageImages`/`PageCache`: one fetch per page at a time, one retry (an expired image URL got anew), `SOURCE_FAILED` with the HTTP status, the cache's size cap with LRU eviction, and `prefetchChapterPages` (the next pages, and the first pages of the next chapter). The cache cap, what the cache holds and Clear through GraphQL and in Settings → Reader. The app's operations and types for the reader. Verified by server tests (the source's headers and cookies through a loopback image host, hit and miss, eviction, single-flight, retry, errors), the live spec, live API checks on MangaPill, Webtoons and MangaDex, and the Settings section in `pnpm tauri dev`.
   - *Done* (2026-11-12):
     - server: `madoyomi.core.reader` (`ReaderSettings`/`ReaderSettingsStore`, `PageCache`, `PageImages`), one upstream edit (`Page.getPageImage` calls `PageImages.get`), and `ReaderGraphQL` (`readerSettings`, `setReaderSettings`, `pageCache`, `clearPageCache`, `prefetchChapterPages`);
     - tests: 26 new (`PageCacheTest` 15, `ReaderSettingsTest` 4, `PageImagesApiTest` 7), 794 in all;
     - app: `api/operations/reader.ts` and the reader types (`fetchChapterPages`, `prefetchChapterPages`, the settings and the cache), `useReaderSettings`, Settings → **Reader** (the cap, what the cache holds, Clear); 9 new tests, and a live-spec test of the pipeline (48 documents);
     - live through the API (§10): a 103-page Webtoons chapter (page 1 from the source in 166–355 ms, then from the cache in 12–18 ms), six requests at once for one page sharing one fetch, a prefetch of 20 pages done in 1.2 s, the next chapter's first pages with its page list, a 404 past the end, the 256 MB cap trimming 303.5 MiB to 229.5 MiB, Clear; a MangaDex page 16 minutes after its chapter opened (no retry was needed);
     - live in `pnpm tauri dev` (§10): Settings → Reader showed 1 GB and "4.9 MB in 52 pages" with the folder, 512 MB was saved into `reader-settings.json` and kept after an app restart, and Clear left "0 bytes in 0 pages" and an empty folder.
2. **Reader core and paged LTR/RTL** (steps 5, 6, 11 in part, 12–14): the route `/manga/:id/read/:chapterId?page=` and `ReaderView`, `PagedReader` (click zones, keys, wheel), preloading ±N with at most 4 image requests at a time and the prefetch, a "tap to retry" placeholder, a first `ReaderToolbar` (title, page slider, previous/next chapter, fullscreen), progress saved (debounced) through `updateChapter(s)` and the chapter marked read on its last page, resume at the last page; chapter rows open the reader (Read/Continue), and the read filter and bulk read marks work. Verified by navigation unit tests (LTR/RTL), component tests and live in `pnpm tauri dev`.
3. **Webtoon mode** (steps 7, 14, first numbers of 15): `WebtoonReader`, virtualized, pages sized by their intrinsic ratio, the page gap, off-screen images released, very tall images, progress from the scroll position. Verified by unit tests and a first measurement on a 100+ page chapter.
4. **Double page, zoom and pan, the seamless next chapter** (steps 8–10): `DoublePageReader` with spread detection and "shift by one", zoom and pan in paged mode, and the "Next chapter: …" transition in every mode. Verified by pairing and spread unit tests and live.
5. **Reader settings, global and per manga** (step 11): `ReaderSettings` (mode, direction, fit, background, page gap, preload count), global on this device and per manga in its meta, and the default mode per content type (§8). Verified by unit tests and a live change that a reopened manga keeps.
6. **Exit run**: every §6 criterion from a fresh data dir, the step 15 measurements (time to first page, scroll FPS on a 100+ page webtoon chapter, memory after 5 chapters), and a fix round.

## 9. Decision record

### Stage 1 (2026-11-12)

Decided by the user:
- **The stage plan above** (six stages).
- **The default reader mode** (§8): a manga's own mode first; else webtoon when its genres or tags name Long Strip, Webtoon, Manhwa or Manhua; else the global default, paged right-to-left (Mihon's default). Mihon's source API has no reading-mode hint, so the genres are the only signal (MangaDex has a "Long Strip" format tag; many sites list Manhwa or Manhua as a genre). Stage 5 applies it. The alternatives were the same detection with a left-to-right default, or no detection.
- **The page cache** (§8): 1 GB by default, 256 MB to 10 GB in Settings → Reader, in `<data dir>/cache/manga` (where upstream's page cache already is since the Phase 3 exit run), least recently used pages first. Covers and downloads don't count. The alternatives were `%LOCALAPPDATA%\Madoyomi\cache\<data dir id>`, which doesn't roam with a roaming `%APPDATA%` (at the cost of moving an upstream path again), or a 500 MB default.

Decided in the stage (defaults, not asked):
- **Upstream's page route is the image proxy; there is no `/api/image`.** `GET /api/v1/manga/{mangaId}/chapter/{sourceOrder}/page/{index}` (from `fetchChapterPages`) already resolves the page's image URL (`getImageUrl` when the page has none), fetches it through `HttpSource.getImage` (the source's client: its headers, cookie jar and rate limits), caches it, and answers with its `Content-Type` and `Cache-Control: max-age=86400`. Covers already go through the thumbnail routes (Phase 3). So Madoyomi wraps the fetch instead of adding a route: one upstream edit in `Page.getPageImage` calls `madoyomi.core.reader.PageImages.get` (server/UPSTREAM.md "Manga reader (Phase 5)"). The token stays `?token=` on these routes, as for covers.
- **One fetch per page at a time.** Concurrent requests for a page (the reader's `<img>`, a prefetch, a second view) wait for the same download and share its result or error. Upstream let each one fetch and write the same `.tmp` file. The download runs in its own scope, so a caller that leaves doesn't cancel it.
- **The retry (step 4):**
  - a transient failure (an I/O error, HTTP 408, 425, 429, 5xx) is tried once more, after 0.5 s;
  - an HTTP 401, 403, 404 or 410 first gets the image URL anew and then tries once more, because image URLs may expire (signed or tokened image hosts) and upstream keeps the page list from the chapter's last open: a page with its own URL is resolved again (`getImageUrl`, and `PAGE.image_url` updated); otherwise the chapter's page list is fetched again, once for all of a chapter's pages that fail together;
  - a Cloudflare challenge isn't retried (another try won't pass it).

  This is Phase 4's "expired URL" rule for videos, applied to pages.
- **The error the reader gets:** `SOURCE_FAILED` (424) with `details.httpCode` (when there is one) and `details.retried`, and the message "The source answered HTTP 403 for this page". A page past the chapter's end stays upstream's `NOT_FOUND` (404). Stage 2's placeholder offers "tap to retry" on either.
- **The cap and the eviction (step 2):**
  - the cap is a backend setting, `<data dir>/reader-settings.json` (`ReaderSettingsStore`, as Phase 4's player settings), because the backend applies it;
  - a hit sets the file's modification time, which orders the eviction (NTFS doesn't keep access times reliably);
  - once the counted size passes the cap, a background trim removes the oldest files down to 90 % of the cap (so a full cache doesn't scan after every page), and empty folders;
  - files used in the last 5 minutes stay even over the cap: the chapter being read and its prefetch, and upstream's downloader, which stages a chapter's pages in this same folder (Phase 6 should keep that in mind);
  - `setReaderSettings` trims before it answers, so Settings shows what is left;
  - Clear removes every cached page (`clearPageCache`).
- **The prefetch (step 3) is a GraphQL mutation, `prefetchChapterPages(chapterId, from, count)`**, not `POST /api/chapters/{id}/prefetch`, to match the rest of the API:
  - count is 1 to 20;
  - the pages are fetched in the background through upstream's `getPageImage`, at most 2 requests at a time across chapters, so the pages the reader shows aren't held up behind the source's rate limiter;
  - the answer says which pages were queued and which were cached already;
  - a new prefetch of a chapter replaces the one still queued for it (the reader jumped);
  - the next chapter is the app's choice (Stage 2): it calls the mutation with that chapter's id. A chapter whose page list was never fetched gets it first;
  - downloaded chapters and the Local source are skipped.
- **Progress needs no new table and no new endpoint** (step 12, Stage 2): upstream's `CHAPTER_USER` has `last_page_read`, `is_read` and `last_read_at`, set by `updateChapter(s)` (`lastPageRead` clamped to the page count), and `fetchChapterPages` returns the chapter with them. This is the same choice as Phase 4's `EPISODE` fields.
- **Where the reader's own preferences will live** (Stage 5): global ones on this device (`localStorage`, like the theme and auto-play), per-manga ones in the manga's meta (upstream's `setMangaMeta`, as Suwayomi-WebUI does): they are tied to the database's ids and go into backups.
- **Settings → Reader** (between Player and Backend): the page cache's cap (256 MB, 512 MB, 1 GB, 2 GB, 5 GB, 10 GB; a hand-edited value is offered too), what the cache holds ("45.3 MB in 312 pages", with its folder), and Clear (off when the cache is empty).
- **The app's reader operations come now** (`api/operations/reader.ts`: `fetchChapterPages`, `prefetchChapterPages`, the settings and the cache), so the live spec validates them before Stage 2 uses them.

## 10. Findings log

### Stage 1 (2026-11-12)

- **Baseline before the stage** (main at `95d0989`, the Phase 4 exit merged as #32; rerun before any edit):
  - server: `.\gradlew.bat build` green with 768 tests, 0 failures (core 675 with 9 skipped, AndroidCompat 7, ext-host 8, api 78), on the first build;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 40 files (39 + 1 skipped), 338 passed + 9 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 9/9 with 42 documents;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - server: 794 tests, 0 failures (core 694 with 8 skipped, AndroidCompat 7, ext-host 8, api 85), green on the first build. The 26 new ones: `PageCacheTest` 15, `ReaderSettingsTest` 4, `PageImagesApiTest` 7;
  - smoke: 24/24 on the new jar (step 5 reads a MangaPill page through the new path);
  - the live spec: 10/10 on the new jar with 48 documents (6 new: `FETCH_CHAPTER_PAGES_MUTATION`, `PREFETCH_CHAPTER_PAGES_MUTATION`, `READER_SETTINGS_QUERY`, `SET_READER_SETTINGS_MUTATION`, `PAGE_CACHE_QUERY`, `CLEAR_PAGE_CACHE_MUTATION`). The new test read MangaPill's "Chapter 1" (92 pages): page 1 from the source in 252 ms, from the cache in 9 ms; pages 2–4 prefetched, page 2 then in 10 ms; a page past the end 404 `NOT_FOUND`;
  - app: 41 files (40 + 1 skipped), 347 passed + 10 skipped (the live spec). The 9 new: `reader.spec.ts` 5 (the calls and the size labels), `SettingsView.spec.ts` 4 (Reader); lint, typecheck, format:check and build clean;
  - `cargo`: no Rust change.
- **What upstream already had (read before planning):**
  - the page route fetches through the source (`HttpSource.getImage`), resolves the image URL when the page has none, caches into `<data dir>/cache/manga`, and answers with `Content-Type` and a day's `Cache-Control`. It is the proxy;
  - `fetchChapterPages` asks the source for the page list on every open (`refreshChapterPageList`) and returns the chapter with `pageCount`, `lastPageRead` and `isRead`. `updateChapter(s)` sets `isRead`, `isBookmarked` and `lastPageRead` (clamped to the page count) and stamps `lastReadAt`;
  - missing: a size cap and eviction (the cache grew without bound), a prefetch, any retry, one fetch per page (two requests at once both fetched and wrote the same `.tmp`), and a clear error for a non-200 that isn't thrown as an `HttpException` (upstream's `Exception("request error! …")`, which was `INTERNAL`). An `HttpException` from `awaitSuccess`, the usual case, was already `SOURCE_FAILED`, without a retry.
- **The page cache's folders are named by the source, the manga title and the chapter name, not by ids** (`getChapterCachePath(mangaId, chapterId)` → `DirName.getChapterDir`: `<source>/<title>/<scanlator_><chapter name>`). Phase 3's note that they were keyed by id is corrected in server/UPSTREAM.md; only the covers are. The tests clear the folder for each test instead of pointing it elsewhere, because upstream picks it.
- **Live through the API** (`reader-live1.ps1`, scratch, on the live spec's data dir with Webtoons (Keiyoushi `all.webtoons` 1.6.3) installed, run twice; the second run started from an empty cache):

  | Check | Result |
  |---|---|
  | a 100+ page webtoon chapter | Webtoons.com [en], the first popular series, "Osora" (S2) Chapter 126: **103 pages** (the page list in 643 and 702 ms) |
  | page 1, then again | from the source 200 `image/jpeg` 22519 bytes in 166 / 355 ms; from the cache in 12 / 18 ms |
  | 6 requests at once for page 50 (uncached) | all 68165 bytes, in 91 / 165 ms; one file `050.jpg`, no `.tmp` |
  | prefetch pages 2–21 | answered in 38 / 43 ms (20 queued); all cached after 1157 / 1150 ms (2 at a time); then read in at most 20 / 19 ms each (mean 9.4 / 8.6 ms) |
  | pages 22–41 without a prefetch, one at a time | at most 425 / 109 ms, mean 73.6 / 83.1 ms |
  | the next chapter (Chapter 125, 95 pages), first 5 | first run: its page list fetched first, answered in 526 ms, cached after 797 ms; second run (list already stored) answered in 9 ms, cached after 547 ms |
  | a page past the end | 404 `{"code":"NOT_FOUND"}` |
  | `prefetchChapterPages` with count 50 | `BAD_REQUEST` "count: must be 1 to 20" |
  | 300 MiB of day-old filler, then a 256 MiB cap | 303.5 MiB → **229.5 MiB** (90 % of the cap is 230.4) when `setReaderSettings` answered (89 ms): the 74 oldest filler files went, the chapter's 42 pages stayed; `reader-settings.json` had 256; madoyomi.log: "page cache over its cap of 256.0 MiB: removed 74 files (74.0 MiB), 229.5 MiB left". Put back to 1024 |
  | Clear | 0 pages, 0 bytes, no folder left; page 1 then fetched again (200, 74 ms) |
  | the token | no `token=` in the data dir's logs, though every image request above carried `?token=` |

- **MangaDex after 16 minutes** (`reader-live2.ps1`, scratch): MangaDex [en] "A Necromancer Betrayed by His Comrades…" Ch.2.3 (15 pages), page 1 read when the chapter opened, then page 7 (never fetched) 16 minutes later: 200 `image/jpeg` 524826 bytes in 502 ms, and page 8 after it, with no retry in the log. MangaDex's stored image URLs still worked, so the expired-URL path (a new page list, or the page's URL resolved again) is covered by `PageImagesApiTest` only. A source whose URLs do expire would show "its page list was got again" in madoyomi.log.
- **Settings → Reader live in `pnpm tauri dev`** (data dir `p5-ui`, its cache holding a copy of the 52 real pages the API run had cached, 4.9 MiB; computer use):
  - the section showed "Page cache 1 GB", "Cached pages 4.9 MB in 52 pages" and the folder `…\agent\p5-ui\cache\manga`;
  - 512 MB chosen with the keyboard (the native select): `reader-settings.json` had 512 and madoyomi.log "reader settings changed: pageCacheLimitMb=512";
  - Clear: "0 bytes in 0 pages", Clear disabled, and no file left in the folder;
  - the app closed (the backend "stopped (exit code 0)") and started again: 512 MB was still chosen;
  - no `token=` in the data dir's logs or in the `pnpm tauri dev` output.
- **Not checked, or not coverable yet:**
  - an image URL that expires, live: MangaDex's didn't within 16 minutes (above); the path is tested on a loopback host;
  - a source failing a page live (5xx, 403): no site failed during the runs; the tests cover each case;
  - cookies live: none of the sources read needs one for its images; `PageImagesApiTest` checks a cookie-jar cookie reaching the image host;
  - eviction during a download: upstream's downloader stages pages in this folder; the 5-minute window protects a running download's recent pages, but a download slower than that over a full cache could lose early pages before they are moved. Phase 6 should stage downloads elsewhere, or mark them in use;
  - the reader itself: Stage 2.
