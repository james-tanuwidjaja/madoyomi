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
- UI `app/src/views/ReaderView.vue` + `app/src/components/reader/`: `PagedReader`, `WebtoonReader`, `DoublePageReader`, `ReaderToolbar`, `ReaderSettings`. **Settled in Stage 4:** there is no `DoublePageReader`. `PagedReader` shows a view of one page or two (the pairing is in `utils/doublePage.ts`), so the zoom, the click zones, the failed page and the panels stay in one place (§9).
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
- [x] Error + retry handling (Stage 1, the backend: one retry, an expired image URL got anew first, `SOURCE_FAILED` with `httpCode` and `retried`; tests for each case. Stage 2, the reader: a failed page shows the error with the source's HTTP status and "Click to retry"; pages that failed while the backend was away come back by themselves once it is ready; live with the backend killed mid-chapter, §10)

### Reader modes
- [x] Paged LTR (Stage 2: `PagedReader`, the toolbar's direction switch for the session; live on MangaPill, §10)
- [x] Paged RTL (Stage 2: the default; the left side and ← go forward, the slider fills from the right; live, §10)
- [x] Vertical webtoon (virtualized) (Stage 3: `WebtoonReader` over the pure `utils/webtoonNav.ts`: heights computed from each page's ratio, only the items near the viewport in the DOM, the anchor kept when sizes, the width or the gap change, a load window in pixels; live on a 103-page and a 95-page Webtoons chapter, 800×30000 pages and an empty chapter, §10)
- [x] Double page with spread detection + shift (Stage 4: the double mode of `PagedReader`, over the pure `utils/doublePage.ts`. The first page is alone. A page wider than tall is alone, and the pairs start again after it. "Shift" pairs the first two. Right to left, the first page is on the right. Live on a generated chapter with spreads at an odd and an even index: both directions, Shift, the slider, and leaving and resuming, §10)
- [x] Seamless next-chapter transition (Stage 4: the panels are the transition, "Finished: … · Next chapter: …". The neighbour's page list is fetched ahead, so one more step goes on into it at once, in place. In the strip, a new wheel gesture past its end does the same. Live in every mode, forward and back, on Webtoons and the Local source, §10)
- [x] Zoom/pan (Stage 4: the single and double modes, over the pure `utils/zoom.ts`. Ctrl+wheel zooms about the cursor; Ctrl+=, Ctrl+− and Ctrl+0 work too, and so does a double-click in the middle third. The range is 1× to 4×, clamped to the page. A drag, the wheel or the arrows pan. A page turn goes back to fit, and so does Esc. Live, §10. None in the webtoon strip, §9)

### Controls & settings
- [x] Keyboard, mouse wheel, click zones (Stage 2: `utils/pagedNav.ts`, unit-tested for both directions; live: clicks, ←/→, Space, Page Down, Home/End, F, Esc, M and the wheel, §10)
- [x] Toolbar (slider, mode switch, prev/next, fullscreen) (Stage 2: all but the mode switch; Stage 3: "Paged"/"Webtoon" for the session, keeping the page, and "Page gap" in the webtoon mode; Stage 4: the three modes as a segmented control (Single, Double, Webtoon), "Shift" in the double mode, a spread's label ("4–5 / 92"), and buttons that leave the focus on the reader after a mouse click; live, §10)
- [ ] Reader settings (global + per manga)

### Progress
- [x] Page progress saved (debounced) (Stage 2: `useReadingProgress`, 1 s, through `updateChapter`; written on leaving, on a chapter change and when the window goes; a failed save tried again every 5 s; live, the page closed at was in the database, §10)
- [x] Chapter marked read at the end (Stage 2: on showing the last page; live, and again with the backend down while it was shown, §10)
- [x] Resume at last page (Stage 2: `?page=`, else `lastPageRead` unless the chapter is read; live, after an app restart too, §10)

### Performance & tests
- [x] Concurrency limit for image requests (Stage 2: `usePageLoader`, at most 4, the page shown first and taking the place of a less urgent one; unit and component tests, §10)
- [x] Performance measured on a 100+ page webtoon chapter (Stage 3, the first numbers, on the dev desktop: the 103-page Webtoons chapter top to bottom at a normal speed with no frame over 50 ms (the longest 8 ms) and no blank frame, at most 5 `<img>` and 5 items in the DOM, 552–642 ms to the first page, the WebView2 processes' memory flat after the chapter. Stage 6 measures again, with the memory after 5 chapters, §10)
- [x] Navigation logic unit tests (Stage 2: the paged part, `pagedNav.spec.ts` 34; Stage 3: the strip's layout, page shown, anchor, load window and keys, `webtoonNav.spec.ts` 19; Stage 4: the double-page pairing, spread detection, steps, progress page and load window, `doublePage.spec.ts` 19; zoom and pan, `zoom.spec.ts` 13; the strip's wheel past its ends, `webtoonNav.spec.ts` 24 now)
- [x] Proxy/cache backend tests (Stage 1: `PageCacheTest` 15, `ReaderSettingsTest` 4, `PageImagesApiTest` 7)

## 6. Exit criteria

- [ ] A 100+ page webtoon chapter scrolls smoothly (no visible stutter, no blank pages while scrolling at normal speed) on a mid-range laptop.
- [ ] All four modes work and remember per-manga settings.
- [ ] Reopening a chapter resumes at the last page.

## 7. Risks & notes

- Sources that need cookies from Cloudflare: the proxy must use the same cookie jar as the source (PLAN.md §6, Cloudflare).
- Very tall images (some webtoons put a whole chapter in a few huge images) may need tiling or downscaling. **Stage 3: shown whole.** Live, 800×30000 pages scrolled with no frame over 50 ms and no blank frame. Each decodes to about 96 MB, so with two around the viewport the renderer held 373 MB private, back to the baseline once the chapter was left (§10). Revisit if a source's pages are wider or taller than that.
- Watch memory: release off-screen images in webtoon mode. **Stage 3:** pages outside the load window (a screen behind, two ahead) are aborted and their object URLs revoked; live, the WebView2 processes' memory after the 103-page chapter was where it started (§10).

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
   - *Done* (2026-11-12):
     - the route `/manga/:id/read/:chapterId?page=N` (`ReaderView`), outside the app shell, with `PagedReader` and `ReaderToolbar` (`components/reader/`): click zones, keys, the wheel, the two transition panels, the page slider, previous/next chapter, the direction for the session (`stores/reader.ts`) and fullscreen (Tauri's `setFullscreen`, one capability permission);
     - `usePageLoader` (pages as blobs with the token in the header, at most 4 requests, −1/+4 around the reader, a retry past the cache), the backend prefetch of the next block and the next chapter, `useReadingProgress` (debounced saves, the read mark, flushes, retries), `useFullscreen`, and the pure `utils/pagedNav.ts`;
     - the app's `updateChapter` / `updateChapters` (`UPDATE_CHAPTER(S)_MUTATION`, 50 documents), `api.fetchAsset`, `useUpdateChapters`, and `lastReadAt` in `ChapterFields`;
     - the chapter list: rows open the reader (a click, or Read / Continue), "Page 13 of 92" with a bar, the read filter, Mark read / Mark unread / Mark previous as read; the details page's "Start reading" / "Continue: …";
     - tests: 79 new (78 net), 425 passed + 10 skipped; the live spec 10/10 with 50 documents and an `updateChapter(s)` round trip; no server change;
     - live in `pnpm tauri dev` on MangaPill (§10): both directions with clicks, keys and the wheel, resume after an app restart, the read marks, the next and previous chapter, the end of the last chapter, fullscreen, the read filter and bulk marks, and failed pages with the backend killed mid-chapter. Found and fixed on the way: MangaPill's chapter numbers (the next chapter), a failed read mark, the pages that failed during a restart, and "Continue reading".
3. **Webtoon mode** (steps 7, 14, first numbers of 15): `WebtoonReader`, virtualized, pages sized by their intrinsic ratio, the page gap, off-screen images released, very tall images, progress from the scroll position. Verified by unit tests and a first measurement on a 100+ page chapter.
   - *Done* (2026-11-13):
     - `components/reader/WebtoonReader.vue` over the pure `utils/webtoonNav.ts` (the layout, the page shown, the anchor, the load window in pixels, the keys): an 800 px strip at most, scrolled natively, only the items near the viewport in the DOM, each page as tall as its ratio makes it (the median of the known ones before it loads), the anchor kept when sizes, the width or the gap change, the end panel a viewport tall, and past the strip's ends the next or previous chapter;
     - `usePageLoader`: a window given by the strip (`wanted`), each page `decode()`d before it counts as loaded, and the natural sizes kept for the chapter (`size(i)`); `useReadingProgress` marks the chapter read on reaching the end panel too; `stores/reader.ts` has `mode` and `pageGap` for the session; the toolbar's mode switch and page gap; `ReaderView` routes the keys and the wheel by mode;
     - `PageFailure.vue` and `ChapterPanel.vue` out of `PagedReader`, and the dev-only stats overlay `ReaderStats.vue` (the ` key);
     - tests: 44 new, 469 passed + 10 skipped; no server, shell or operation change;
     - live in `pnpm tauri dev` (§10): the 103-page Webtoons chapter top to bottom with the wheel and the keys, the slider, the gap, fullscreen as a resize, a mode switch mid-chapter, leaving and reopening (after an app restart too), the read mark, the chapter boundaries, 800×30000 pages from the Local source and an empty chapter. The first step-15 numbers: no frame over 50 ms and no blank frame while scrolling, 552–642 ms to the first page, the memory flat.
4. **Double page, zoom and pan, the seamless next chapter** (steps 8–10): `DoublePageReader` with spread detection and "shift by one", zoom and pan in paged mode, and the "Next chapter: …" transition in every mode. Verified by pairing and spread unit tests and live.
   - *Done* (2026-11-14):
     - the double-page mode in `PagedReader` (a view of one or two pages, side by side in the reading direction) over the pure `utils/doublePage.ts`. It covers the pairing (the first page alone, a page wider than tall alone, "shift by one"), the step by spread, the page that progress counts (the spread's last), the load window and the label. `ReaderView` keeps the position on a page index and shows the spread that holds it, so the page shown stays on screen when a size changes the pairs;
     - zoom and pan in the single and double modes, over the pure `utils/zoom.ts`: Ctrl+wheel about the cursor, Ctrl+=/−/0, a double-click in the middle third (that third's single click, the toolbar, waits 250 ms), 1× to 4× clamped to the page, a drag, the wheel and the arrows panning, Esc and a page turn back to fit;
     - the seamless next chapter. The panels read "Next chapter: …" / "Previous chapter: …". The next chapter's page list is fetched ahead near the end, and the previous one's on its panel, so one more step opens it at once, in place. In the strip, a new wheel gesture at the bottom (top) does the same (`createEdgeWheel`), and that notch doesn't also scroll the new strip. The strip and the pager are keyed by chapter. `useReadingProgress` follows the chapter as well as the position;
     - the toolbar: Single / Double / Webtoon as a segmented control (`stores/reader.ts` `mode` has three values and `setMode`), "Shift" in the double mode (`shift`), the direction in both paged modes, "4–5 / 92", and buttons and a slider that leave the keys to the reader after a mouse;
     - tests: 54 new, 523 passed + 10 skipped; no server, shell or operation change;
     - live in `pnpm tauri dev` (§10): a generated Local chapter with spreads at an odd and an even index, both directions, Shift, the slider and resume; zoom by Ctrl+wheel, the keys and a double-click, with pans by drag, wheel and arrows; the seamless transition forward and back in all three modes, on Webtoons (network) and the Local source; the read marks and "Page 5 of 8"; the stats overlay on the 103-page chapter again.
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

### Stage 2 (2026-11-12)

Decided in the stage (defaults, not asked; the plan was sent to the user before the build):
- **The route is `/manga/:id/read/:chapterId?page=N`, shown without the app shell** (`meta.bare`; App.vue renders such a route outside `AppShell`), so the reader has the whole window. `?page=` counts from 1 (and `last`); the reader keeps it up to date with `router.replace`, so a reload opens the same page. Moving to another chapter replaces the route too, so Back (the toolbar's "← <manga>", Esc) returns to the details page, not through every chapter read.
- **Where a chapter opens:** `?page=`, else `lastPageRead` (upstream's, a page index from 0, clamped by upstream to the page count, so one past the last index is the last page), unless the chapter is read: a read chapter starts at page 1, as in Mihon. The next chapter opens the same way; the previous one at its last page.
- **The page list is asked of the source on every open** (`fetchChapterPages` as a query with `staleTime` and `gcTime` 0): its image URLs may have expired, and the chapter's progress must be the stored one.
- **Pages are fetched as blobs, not `<img src>`** (`api.fetchAsset`, `usePageLoader`):
  - the token goes in the `Authorization` header (upstream's token filter takes it on every route), so no image URL in the reader carries `?token=`;
  - the error body is readable (`SOURCE_FAILED` with `details.httpCode`), which an `<img>` can't see;
  - a page that leaves the window is aborted, and its object URL revoked.
- **At most 4 page requests at a time** (step 14), in the order current page, the 4 ahead, the 1 behind (`pagesToLoad`). When all 4 are taken, a more urgent page (the one shown after a jump, a retry) takes the place of the least urgent one, which is asked for again later (the backend keeps fetching it into its cache). `decode()` and `loading="lazy"` aren't used: a page is shown from a blob already in memory, and only one `<img>` is in the DOM. Stage 3's webtoon mode is where decoding off-screen pages matters.
- **The backend prefetch** (Stage 1's `prefetchChapterPages`): the block of 10 pages after the reader's own window, once per block of 10 (pages 5–14 from page 0, 15–24 from page 10, …), and within 5 pages of the end the next chapter's first 5 from where it would open. The reader's own window and the backend's blocks join up.
- **A failed page** shows "Couldn't load page N", the hint for its code, the code, the source's HTTP status and "Click to retry". A click fetches it again past the webview's cache (`cache: "reload"`), and with it every page that failed because the backend was away. Those (`NETWORK`, `BACKEND_UNAVAILABLE`) also come back by themselves when the backend is ready again; a source's failure waits for its click.
- **The paged navigation** (`utils/pagedNav.ts`, pure):
  - the click zones are thirds: the outer ones turn the page, the middle one toggles the toolbar. Reading right to left, the left side goes forward (Mihon's);
  - keys: → forward left to right and back right to left (← the other way); ↓, Page Down and Space forward; ↑, Page Up and Shift+Space back; Home and End; [ and ] the previous and next chapter; M the toolbar; F fullscreen; Esc leaves fullscreen, else the reader. Nothing with Ctrl, Alt or the Windows key (the webview's shortcuts, and zoom in Stage 4). Keys typed into the slider, a select or a text field are left to them, and Space or Enter on a button;
  - the wheel turns one page per gesture: deltas add up to 50 px, then 350 ms of nothing counts, and a 200 ms pause starts a new sum, so a trackpad or a free-spinning wheel doesn't race through the chapter;
  - the chapter boundaries: past the last page is a panel, "Finished: <chapter>" and "Next: <chapter>" (or "There's no next chapter.", with Back to the manga); one more step opens the next chapter. Before the first page is "Previous: <chapter>", only when there is one; one more step opens it at its last page. The panels keep an accidental extra step from leaving the chapter. Stage 4's seamless transition will replace them.
- **The toolbar** shows at the start and hides at the first page turn (Mihon keeps it hidden; showing it first says it exists); the middle zone or M brings it back. Reading right to left, the slider fills from the right and the chapter buttons swap sides. The mode switch and the settings come with the other modes (Stages 3–5).
- **The direction** is right to left by default (the global default of §8) and the toolbar flips it for the app session (`stores/reader.ts`); Stage 5 keeps it on this device and per manga, and applies §8's default by the genres.
- **Fullscreen is the window's** (Tauri's `setFullscreen`, `core:window:allow-set-fullscreen` added to the capability): WebView2's element fullscreen would only fill the webview. In a plain browser (`pnpm dev`) it is the document's. Leaving the reader leaves fullscreen.
- **Progress** (steps 12–13, `useReadingProgress`): the page shown is saved through upstream's `updateChapter` (`lastPageRead`, which stamps `lastReadAt`) 1 s after paging stops, and at once when the reader moves to another chapter, closes, or the window goes (`pagehide`, `beforeunload`). Showing the last page marks the chapter read at once (Mihon's rule), once per reader. The panels aren't saved. A save that fails (the backend restarting) is shown ("Couldn't save your place in this chapter") and tried again every 5 s; a failed read mark is kept with the later pages of that chapter, so going back a page before the retry doesn't lose it. Each saved chapter replaces its row in the cached chapter list, so the details page has it without a refetch.
- **The next chapter** (`adjacentChapter`): the nearest one in the source's order (`sourceOrder + 1` and on). Another group's copy of the current chapter (the same number, another scanlator) is skipped, and among the copies of the chapter that follows (a run of the same number), the current one's scanlator is preferred. A chapter of the same group is never skipped. That last rule came from the live run: MangaPill names Berserk's chapters "Group 2 Chapter 385", which upstream's chapter recognition reads as number 2 (390 of 405 chapters), so skipping every same-numbered chapter left no next chapter (§10). Mihon's default is the next one in the source's order; its "skip duplicates" option prefers the same scanlator. The list's scanlator filter doesn't apply to the reader.
- **The chapter list** (ChapterList over `EntryItemList`'s new `openable` and `pages`):
  - a click on a row (not on its checkbox or buttons) opens the reader, and so does its button: "Continue" for a chapter begun and not read, else "Read";
  - a row begun and not read shows "Page 13 of 92" with a bar;
  - the read filter works (Phase 3's placeholder note is gone);
  - Mark read, Mark unread and, with one row, Mark previous as read (every earlier unread chapter in the source's order), through `updateChapters`. **Mark unread also sends the chapter back to page 1** (`lastPageRead: 0`), as Mihon does and as Phase 4's unwatched clears the position;
  - the selection rules are Phase 4's.
- **"Start reading" / "Continue: <chapter>"** on the details page (`resumeChapter`): the chapter read last (upstream's `lastReadAt`, now in `ChapterFields`) if it isn't finished, else the first unread one after it in the source's order, else the first unread from the oldest (Mihon's resume button). A chapter marked unread (page 0) isn't one to continue, though upstream stamps it. Mihon's rule alone, the first unread from the oldest, offered "Chapter 1" after reading Chapter 385 in the live run.
- **`updateChapter` for the reader, `updateChapters` for the list** (both upstream's, no server change). Upstream returns `updateChapters`' chapters in the database's order, not the given one.
- **The reader's background is #111 in both themes**, as readers are.
- **Vite pre-bundles `@tauri-apps/api/window`** (`optimizeDeps.include`): the reader is a lazy route that imports it, and in `pnpm tauri dev` its first open made Vite optimize the dependency and reload the page mid-navigation (§10). The build was never affected.

### Stage 3 (2026-11-13)

Decided in the stage (defaults, not asked; the plan was sent to the user before the build and they didn't object):
- **Our own pure layout, not TanStack Virtual** (`utils/webtoonNav.ts`): a page's height is computed (the strip's width × its image's ratio), not measured, so prefix sums and a binary search give scroll ↔ page exactly, and it is unit-tested without a browser. Only the items near the viewport are in the DOM, placed absolutely in a sizer as tall as the whole strip. Phase 3's `VirtualList` measures its rows; the strip knows its heights.
- **Page heights:** the loader `decode()`s each blob before the page counts as loaded (no blank frame when its `<img>` is put in, and the natural size is known), and keeps the sizes for the chapter after a page is released, so a page scrolled back to keeps its height. A page not loaded yet gets the median ratio of the known ones (1.5 before any).
- **The anchor:** when sizes, the width or the gap change, the strip keeps a point still: after a seek or an open, the page's top at the viewport's top; after a user scroll, the point at the viewport's centre. The strip's own scrollTop moves at once (a `flush: "sync"` watcher), the element's after the DOM update (`flush: "post"`), so the page shown is never read from the old offset in the new layout. A seek to a position the strip didn't report waits for the DOM update too. The element is only moved when it isn't there already, and the strip records the value the browser took (it rounds to device pixels and clamps), so its own scroll event isn't taken for the user's and a smooth key scroll isn't cut short (§10).
- **Width:** fit to the window, at most 800 px (Webtoons' own images are 800 px wide). The fit modes are Stage 5's.
- **Gap:** none by default; the toolbar's "Page gap: on/off" (16 px), for the session.
- **The load window is in pixels:** one screen behind, two ahead, at least 2 pages ahead, at most 16; the visible pages first, then the ones ahead, then the ones behind. Still at most 4 requests (`usePageLoader`'s new `wanted`); pages that leave it are aborted and revoked. The backend's block prefetch is unchanged.
- **Very tall images are shown whole** (no downscaling, no tiling): Chromium rasterizes only the tiles in view, and the pixel window keeps one or two such pages loaded. Checked live with generated 800×30000 pages (§10).
- **The page "shown"** (saved, in the slider and the URL) is the one at the viewport's top edge, so a chapter reopened where it was saved shows the same page again, without drift. The end panel is a viewport tall so that every page can reach the top; at the strip's bottom the end panel is shown. **Reaching the end panel also marks the chapter read** (`useReadingProgress`, both modes), because a fling or End can pass the last page between two scroll events.
- **Input in the strip:** the native wheel (ReaderView's wheel gate is skipped in the webtoon mode); ↓/↑ 15 % of a screen (at least 60 px), Space/Page Down/Page Up 90 %, smooth; Home/End; [ and ] the chapters; ← and → nothing. At the very bottom a further step down opens the next chapter, at the very top a step up the previous one at its last page. A click toggles the toolbar; a user scroll hides it, a seek (the slider, Home/End) doesn't.
- **The toolbar:** the mode switch "Paged"/"Webtoon" (`stores/reader.ts` `mode`, for the session, paged by default; Stage 5 applies §8's default by genre), the gap toggle in the webtoon mode only, the direction in the paged mode only. In the webtoon mode the slider fills from the left and seeks to the page's top. Switching mid-chapter keeps the page.
- **A dev-only stats overlay** (`ReaderStats.vue`, the ` key, only with `import.meta.env.DEV`): fps, frames over 50 ms and the longest, "blank" frames (a page in the viewport not loaded yet: the strip's `data-blank`), the `<img>` and strip items in the DOM (now and the most), the JS heap and the time to the first page, with Reset and Close (which don't take the focus, §10). The process memory for step 15 comes from the WebView2 process tree under madoyomi.exe (`Win32_Process` by `ParentProcessId`), not from the overlay.
- **Shared parts out of `PagedReader`:** `reader/PageFailure.vue` (a failed page and "Click to retry") and `reader/ChapterPanel.vue` (the previous and end panels, "<chapter> has no pages." for an empty one), with the same test ids.
- **No server change, no shell change, no new operation** (so no new live-spec test).

### Stage 4 (2026-11-14)

Decided by the user (asked with the plan; each was the recommended option):
- **The webtoon strip stays one strip per chapter.** The end panel is the transition, and a further scroll at the very bottom (a new wheel gesture, or the keys as in Stage 3) opens the next chapter at its top, already loaded. The alternative was one joined strip with the next chapter's pages appended below the panel, as in Mihon, with the URL, the toolbar and progress switching as you scroll. That would mean a rework of the strip's layout, anchor and load window, the loader and progress, which are all per chapter.
- **A double-click zooms only in the middle third.** The side thirds still turn the page at once. The middle third's single click (the toolbar) waits 250 ms for a second click. Zoomed in, a double-click anywhere goes back to fit. The alternative was a double-click anywhere, with every single click waiting about 250 ms before it turns the page (Mihon with double-tap zoom on).
- **The double page starts with page 1 alone** (the print convention: 1 | 2–3 | 4–5; a chapter often opens on a single title or colour page). "Shift" pairs from page 1 (1–2 | 3–4) for the session. The alternative was pairing from page 1 by default.

Decided in the stage (defaults, in the plan sent to the user):
- **No `DoublePageReader`:** `PagedReader` shows a view (`pages`) of one page or two, in reading order, side by side, and reversed right to left (`flex-direction: row-reverse`, so the first page is on the right). The zoom, the click zones, the failed page and the panels are shared. The pairing and the steps are pure, in `utils/doublePage.ts`.
- **The pairing goes from the chapter's start:** the first page is alone (unless shifted). Then pairs, except that a page wider than tall (width > height, from the loader's decoded size) is alone and the pairing starts again after it. The page before a wide one has no partner and is alone too, and so can be the last page. A page whose size isn't known yet counts as portrait.
- **The page shown stays stable:** the reader's position stays a page index (the anchor, in the URL and the slider), and the view is the spread that holds it. When a size arrives and changes the pairs, that page stays on screen: it can change sides or end up alone. Ahead of the reader the sizes are known before it gets there (the load window decodes them). Behind a resume point, a spread found later can change the pairs, which is what Shift is for.
- **Progress counts the spread's last page** (`progressPage`): the page saved is the furthest one seen, a reopened chapter shows the same spread, and the last spread marks the chapter read. The slider seeks to a page and shows its spread; the label is "4–5 / 92".
- **The double-page load window:** the spread, then the next 4 pages, then the 2 before (`spreadPagesToLoad`), still 4 requests at a time. The backend's block prefetch is unchanged.
- **Steps go by spread:** forward to the next spread's first page, or past the last one to the end panel; back to the previous spread's first page, or from the first to the "Previous chapter" panel. The panels are the paged mode's. The keys, the wheel gate and the click zones are the paged mode's too.
- **Zoom and pan** (`utils/zoom.ts`, `PagedReader`):
  - the scale goes from 1 (fit) to 4, as `translate() scale()` on a layer the size of the reader;
  - it is always clamped by the pages' own rect: their edges never come inside the window, and along an axis where they are still smaller than the window, they stay centred;
  - Ctrl+wheel zooms about the cursor (about 1.2× a notch), Ctrl+= and Ctrl+− by 1.25× about the centre, and Ctrl+0 goes back to fit;
  - zoomed in, a drag pans (a move over 4 px is a drag, not a click), the wheel pans (Shift+wheel sideways), the arrows pan by 15 % of the window, a click does nothing and a double-click goes back to fit. Space and Page Up/Down still turn the page, and Esc goes back to fit before it leaves fullscreen or the reader;
  - a page turn, a mode switch, a new chapter or a resize goes back to fit.
- **No zoom in the webtoon strip:** its width is already fitted (at most 800 px), zooming it would change every page's height (the layout and the anchor), and Stage 5's fit/width setting is the place for a wider strip. Ctrl+wheel there does nothing (its default is prevented). The webview's own zoom keys are off in Tauri.
- **The seamless transition:**
  - the panels stay as the transition ("Finished: … · Next chapter: …", "Start of … · Previous chapter: …"), so an extra step doesn't leave a chapter by accident (Mihon's transition page);
  - near the end (the same `nearEnd` as the backend's prefetch of the next chapter's first pages), the reader also fetches the next chapter's page list, and on the "Previous chapter" panel the previous one's. These are kept for this reader only (`adjacent`, the last two), not in the query cache, and dropped once their chapter has been read here, so a later open never uses stale progress;
  - one more step then opens the chapter from that data, with no "Loading…" screen, while its own query asks the source again in the background (same page routes, so nothing reloads);
  - the URL, the toolbar's chapter, the window title, progress (the last chapter's flushed) and the read marks follow the route as before;
  - the strip and the pager are keyed by chapter, so a new chapter gets a new strip. A strip kept across chapters would put its old anchor on the new layout and report a wrong position.
- **The strip's wheel past its ends** (`createEdgeWheel`): only a gesture that starts at the end counts (after a 200 ms pause), so a fling that reaches the bottom doesn't carry on into the next chapter. One notch (50 px) then opens it. That wheel event's default is prevented (live, it also scrolled the new strip, §10). The top works the same way for the previous chapter, opened at its last page. The keys are Stage 3's.
- **The mode switch** is a segmented control, Single / Double / Webtoon (`aria-pressed`, `data-action="mode-<mode>"`), for the session. The direction applies to both paged modes, and "Shift: on/off" shows in the double mode.
- **The toolbar's focus** (Stage 3's open point): a mouse click on a toolbar button doesn't take the focus (`mousedown` prevented on buttons), so Space then turns the page instead of pressing the button again. Tab still reaches every button, and Space or Enter presses the focused one. The slider is blurred on pointer-up, so the keys are the reader's again after a drag. Tab still focuses it, and its arrows then move it.
- **No server, shell or operation change** (so no new live-spec test).

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

### Stage 2 (2026-11-12)

- **Baseline before the stage** (main at `cc07fb2`, Stage 1 merged as #33; rerun before any edit):
  - server: `.\gradlew.bat build` green with 794 tests, 0 failures (core 694 with 8 skipped, AndroidCompat 7, ext-host 8, api 85), on the first build;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 41 files (40 + 1 skipped), 347 passed + 10 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 10/10 with 48 documents;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - app: 46 files (45 + 1 skipped), **425 passed + 10 skipped**. 79 new tests (78 net: Phase 3's "doesn't make chapters selectable" is gone): `pagedNav.spec.ts` 34 (steps and panels, the click zones and keys in both directions, the wheel, loading and prefetch ranges, where a chapter opens, the next and previous chapter, `resumeChapter`), `ReaderView.spec.ts` 16, `usePageLoader.spec.ts` 7, `useReadingProgress.spec.ts` 8, `ChapterList.spec.ts` 6, `client.spec.ts` 4 (`fetchAsset`), `entries.spec.ts` 2, `MangaDetailsView.spec.ts` 1, `routes.spec.ts` 1. Lint, typecheck, format:check and build clean;
  - the live spec: 10/10 with **50 documents** (`UPDATE_CHAPTER_MUTATION`, `UPDATE_CHAPTERS_MUTATION`); its reader test also reads page 1 through `fetchAsset` (the header, no `?token=`) and saves page 3, a page past the end (clamped to 92 of 92) and the read mark through `updateChapter(s)`, then puts the chapter back;
  - `cargo test` 17; build, clippy and fmt clean (the capability file only);
  - server: not changed, so not rebuilt (the baseline above stands; smoke and the live spec ran on Stage 1's jar).
- **Live in `pnpm tauri dev`** (data dir `live-data`, MangaPill 'Berserk', computer use):

  | Check | Result |
  |---|---|
  | open from a row (a click, the Read button) | Chapter 385 opened at 1 / 23; a click on the row opened Chapter 382, 383 and 384 later. The first row click of the session bounced back to the details page: Vite optimized `@tauri-apps/api/window` on the reader's first lazy import and reloaded the page mid-navigation (tauri-dev.log, 19:23:01). Pre-bundled since (`optimizeDeps`); the next runs didn't reload |
  | right to left (the default) | the left third and ← forward, the right third and → back; Space and Page Down forward; one wheel notch down forward, up back; the slider fills from the right |
  | left to right (the toolbar) | → and the right third forward, 3 → 5; the slider fills from the left, "Next chapter" moves to the right |
  | toolbar | shown at the start, hidden at the first turn, back with the middle third or M; "3 / 23" |
  | leave mid-chapter, restart the app | closed at page 5 of Chapter 385: the database had `lastPageRead` 4 with `lastReadAt`; after an app restart the row showed "Page 5 of 23" with a bar and Continue, the header "Continue: Group 2 Chapter 385", and the reader opened at 5 / 23 (direction back to right to left: the session's) |
  | read to the end | End, then forward: "Finished: Group 2 Chapter 385 · Next: Group 2 Chapter 386"; forward again opened 386 at 1 / 24; its end said "There's no next chapter." with "Back to Berserk"; both rows Read in the list |
  | the previous chapter | from 386 page 1, back: "Start of … · Previous: Group 2 Chapter 385", back again: 385 at 23 / 23 |
  | fullscreen | F filled the screen (no title bar or taskbar), the toolbar said "Exit fullscreen"; Esc left fullscreen, a second Esc went back to the details page |
  | read filter and bulk marks | Show Read: "405 chapters · 2 shown"; 386 selected, Mark unread: it left the filter, and the header moved to "Continue: Group 2 Chapter 386" |
  | a failed page | the backend killed (its pid from sidecar.log) while Chapter 384 was open, then End: "Couldn't load page 25", "The backend isn't running right now…", `BACKEND_UNAVAILABLE`, "Click to retry"; after the restart (new port) the click loaded it |
  | the read mark during a restart | in that run, the last page's read mark failed ("Couldn't save your place in this chapter") and was never tried again; fixed (a retry every 5 s, the mark kept with later pages). Again on Chapter 383 and 382: the note went away by itself once the backend was back, and the chapter showed Read |
  | pages that failed during a restart | in 383, page 23 (behind the last) stayed failed after the retry of page 24; fixed (transient failures are fetched again when the backend is ready, and with any retry). On 382 the last page and the one behind it came back by themselves, without a click (`021.jpg`, `022.jpg` in the cache) |
  | the backend prefetch | Chapter 384's cache folder: pages 1–5 (the reader's window), **6–15 (the prefetch's first block)**, 24–25 (the jump to the end); the same in 383, 385 and 386 |
  | the token | no `token=` in sidecar.log, madoyomi.log or the dev output; the reader's page requests carry it in the header. The backend stopped with exit code 0 at each close |

- **MangaPill's chapter numbers are wrong for Berserk:** 390 of its 405 chapters are number 2.0 ("Group 2 Chapter 386" read as 2 by the chapter recognition), the others 5, 6, 7, 8…, and none has a scanlator. The first rule (skip every chapter with the current one's number) found no next chapter; the rule now skips only another group's copy (§9). Numbers can't be trusted for order; `sourceOrder` is.
- **A chapter whose pages are already in when the reader mounts** (a hot reload in the run; a reopen within the cache's lifetime) set its start position before the progress watcher existed, so opening it at its last page didn't mark it. The watcher runs at once now (a test for it).
- **"Continue reading" by Mihon's rule alone** (the first unread chapter from the oldest) offered Chapter 1 after Chapter 385 was begun; it follows `lastReadAt` now (§9).
- **Not checked, or not coverable yet:**
  - a source's own failure live (`SOURCE_FAILED` with an HTTP status): no page failed at MangaPill; the component test shows the status, and the live spec reads the 404 body past the end. A backend restart stood in for a failure live;
  - `[` and `]` (the chapter keys) live: unit-tested; the toolbar's and the panels' chapter steps were used live, and Home, End, F and Esc;
  - the concurrency limit live: the webview's requests weren't counted; the loader and component tests check at most 4;
  - a 100+ page chapter in the paged mode: the reader holds 6 pages whatever the length; the 100+ page measurement is Stage 3's (webtoon);
  - Webtoons and MangaDex in the reader: the paged run was on MangaPill; Stage 3 reads the 103-page Webtoons chapter.

### Stage 3 (2026-11-13)

- **Baseline before the stage** (main at `3d8a34b`, Stage 2 merged as #34; rerun before any edit):
  - server: `.\gradlew.bat build` green with 794 tests, 0 failures (core 694 with 8 skipped, AndroidCompat 7, ext-host 8, api 85), BUILD SUCCESSFUL in 6m 37s;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 46 files (45 + 1 skipped), 425 passed + 10 skipped; `pnpm lint`, `pnpm typecheck` and `pnpm build` clean. `pnpm format:check` flagged only `src/utils/webtoonNav.ts`, a file of this stage already on disk when the baseline's app checks ran (the rest was clean);
  - the live spec: 10/10 with 50 documents;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - app: 48 files (47 + 1 skipped), **469 passed + 10 skipped**. 44 new: `webtoonNav.spec.ts` 19 (heights, the layout, the page shown, the anchor, the load window, the keys), `WebtoonReader.spec.ts` 13 (the items in the DOM, sizes, where it opens, the page reported, the anchor after a seek, a scroll and a resize, a position it didn't report, the gap, the keys' steps, clicks, the blank count, an empty chapter), `ReaderView.spec.ts` 7 (the mode switch, progress and the read mark from the strip, the keys, the slider and Home/End, the toolbar kept during a seek, the previous chapter, the load window, the wheel), `usePageLoader.spec.ts` 4 (`wanted`, `decode` and the sizes), `useReadingProgress.spec.ts` 1 (the end panel's read mark). Lint, typecheck, format:check and build clean;
  - server and shell: not changed, so not rebuilt (the baseline above stands); no operation changed, so the live spec wasn't rerun.
- **Found and fixed while writing and reviewing the strip:**
  - the page shown was read from the old scroll offset in the new layout when a page above got its size: it emitted a wrong position, which then seeked. The strip's own scrollTop now moves with the layout at once, the element's after the DOM update;
  - a seek's own scroll could be taken for the user's: the strip compared the browser's scroll event with the value it had asked for, not the one the browser took (rounded to device pixels, or clamped while the sizer still had its old height), and a user scroll hides the toolbar. It now records what the browser took, seeks after the DOM update, and leaves the element alone when it is already there (which also keeps a layout change from cutting a smooth key scroll short). Tests: the toolbar stays up while the slider seeks, a resize keeps the point at the centre, an empty chapter;
  - `Array.prototype.at` isn't in the app's TS lib (the previous session's typecheck); a plain last-element helper.
- **Live in `pnpm tauri dev`** (data dir `live-data`, computer use, the dev desktop with an NVIDIA GPU and a 145 Hz screen):

  | Check | Result |
  |---|---|
  | open and switch | Webtoons.com [en] "Osora" (S2) Chapter 126, 103 pages: opened in the paged mode at 1 / 103; "Paged" → "Webtoon": an 800 px strip, the slider filling from the left, "Page gap: off", no direction button |
  | the wheel | 5 to 10 notches every 0.4–0.6 s: 1 → 13 → 44 / 103, and 89 → the end panel. Smooth, no blank page (numbers below) |
  | the keys | Page Down, Space, ↓, Shift+Space, ↑, Page Up: 13 → 15 → 20 / 103 (smooth steps); ← and → nothing; End on Chapter 125 put 95 / 95 at the top |
  | the slider | a click jumped 44 → 66, a drag 66 → 89; **the toolbar stayed up through both**; the pages came in from the source within half a second |
  | the page gap | on at 8 / 95 of Chapter 125: 16 px between pages, the point at the viewport's centre stayed put |
  | a resize | F (fullscreen) and F again at 8 / 95: the point at the centre kept both ways, back at 8 / 95 |
  | a mode switch mid-chapter | at 8 / 95: the paged mode showed page 8; back to webtoon, page 8's top at the viewport's top |
  | leave and reopen | Esc at 17 / 95: the row "Page 17 of 95" with Continue; reopened at 17 / 95 with page 17's top at the viewport's top |
  | after an app restart | the backend stopped with exit code 0; "Continue: (S2) Chapter 125", the row "Page 17 of 95"; it opened at 17 / 95 (paged, the session's default again), and in webtoon the same view as before |
  | read to the end | Chapter 126 to its end panel ("Finished: … · There's no next chapter."): Read in the list. Chapter 125: End, Page Down twice → "Next: (S2) Chapter 126"; Page Down again opened 126; Page Up at its top → "Previous: (S2) Chapter 125"; again → 125 at 95 / 95 |
  | very tall pages | the Local source, "Tall Strip Test" Chapter 1 (generated: 800×1200, three 800×30000 with a marker every 500 px, 800×1200): every page whole at its own size (the markers line up); the slider put page 4's top at the top; scrolling up showed page 3's bottom at once (loaded behind); End and Page Down to the end panel marked it Read |
  | an empty chapter | an empty folder "Chapter 2" in the Local manga: "Chapter 2 has no pages.", 0 / 0 with the slider off, the "Previous" panel above it |
  | the token | no `token=` in sidecar.log (both runs), madoyomi.log or the dev output; no warning in the dev output |

- **The first step-15 numbers** (the dev stats overlay; memory from the WebView2 process tree under madoyomi.exe, the JVM left out):
  - **time to the first page:** 552 ms (Chapter 126, opened in the paged mode: the page list from the source, then page 1; some of its pages may have been in the cache since Stage 1), **642 ms** (Chapter 125 in the webtoon mode, nothing cached), 36 ms (the Local source);
  - **scrolling the 103-page chapter at a normal speed:** pages 1–13 in 5,102 frames, 15–44 in 12,505, 89 to the end in 3,670: **no frame over 50 ms (the longest 8 ms), 145 fps (the screen's rate) throughout, 0 blank frames**. The two slider jumps to uncached pages gave 59 blank frames (410 ms in all) until those pages arrived;
  - **the DOM:** at most 5 `<img>` and 5 strip items (3 to 4 most of the time); the JS heap 26–27 MB throughout;
  - **memory:** before the chapter 484 MB working set and 333 MB private; at the end of the 103 pages (the reader still open) 503 MB and 273 MB; the tall chapter with pages 3 and 4 around the viewport 922 MB and 520 MB (the renderer 373 MB private: an 800×30000 page decodes to about 96 MB); after leaving it 465 MB and 276 MB;
  - **the tall pages:** the longest frame 42 ms, none over 50 ms, 0 blank frames.
- **Space on a focused button presses it** (Stage 2's rule, so the toolbar works by keyboard): after a click on the stats overlay's Reset, four Space presses reset the stats instead of scrolling. The overlay's buttons don't take the focus now. **The toolbar's buttons still do:** after a click on "Webtoon" or "Page gap", Space presses that button again until the strip is clicked. Left for Stages 4–5 (the toolbar and its settings), noted in the next stage's prompt.
- **The §6 criterion names a mid-range laptop;** these numbers are from the dev desktop, so PLAN.md's milestone stays open for the exit run (Stage 6), which measures again on the same chapter.
- **Not checked, and why:**
  - a mid-range laptop (§6): only the dev desktop was at hand;
  - memory after 5 chapters (step 15's third number): Stage 6's;
  - a failed page in the strip live: no page failed; `WebtoonReader.spec.ts` covers the failed page and its retry, and Stage 2 checked the same `PageFailure` live;
  - dragging the window's edge: fullscreen on and off stood in (both resize the webview); the `ResizeObserver` path is in `WebtoonReader.spec.ts`;
  - Home, `[` and `]` live in the strip: in `ReaderView.spec.ts`, and Stage 2 used the chapter steps live; End and the panels' chapter steps were used live;
  - a trackpad or a free-spinning wheel: only a notched wheel (the tool's notches);
  - the generated tall chapter and the empty "Chapter 2" folder are in `live-data/local` (gitignored scratch data), not in the repo.

### Stage 4 (2026-11-14)

- **Baseline before the stage** (main at `1acdacd`, Stage 3 merged as #35; rerun before any edit):
  - server: `.\gradlew.bat build` green with 794 tests, 0 failures (core 694 with 8 skipped, AndroidCompat 7, ext-host 8, api 85), BUILD SUCCESSFUL in 8m 12s;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 48 files (47 + 1 skipped), 469 passed + 10 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 10/10 (50 documents, no operation changed since Stage 2);
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - app: 50 files (49 + 1 skipped), **523 passed + 10 skipped**. The 54 new tests:
    - `doublePage.spec.ts` 19: spread detection, the pairing (first alone, shifted, wide pages at odd and even indexes, every page once), the spread of a page and a stable page when the pairs change, the steps both ways and through wide pages, the progress page, the load window, the label;
    - `zoom.spec.ts` 13: zoom about a point, the limits, the clamp and the centring, the pan, the double-click toggle, the wheel factor, the keys;
    - `webtoonNav.spec.ts` 5: the wheel past the strip's ends;
    - `ReaderView.spec.ts` 17: the double mode's spreads right to left, Shift, wide pages, progress and the read mark, the resume page, a mode switch and the load window; zoom by Ctrl+wheel and the keys, the pans, Esc, a page turn, the double-click, a drag and none in the strip; the seamless step forward and back with the page list fetched ahead; the strip's wheel at both ends; the toolbar's focus.
    
    Lint, typecheck, format:check and build clean. Stage 2's middle-click test now waits the 250 ms, and the mode test uses the segmented control;
  - server and shell: not changed, so not rebuilt (the baseline above stands); no operation changed, so the live spec wasn't rerun.
- **Live in `pnpm tauri dev`** (data dir `live-data`, computer use, the dev desktop, a 1920×1080 screen). The test chapters were generated with Pillow into `live-data/local/Double Page Test` (gitignored, not in the repo): Chapter 1 has 14 pages of 1000×1500, with 2000×1500 spreads at indexes 3 and 8 (pages 4 and 9); Chapter 2 has 8 pages with a spread at index 5. Every page is numbered, with a grid, a red border and corner labels:

  | Check | Result |
  |---|---|
  | the mode switch, the focus | "Single · Double · Webtoon" in the toolbar; a click on Double, then **Space turned the page** (the button kept no focus) |
  | double, right to left | 1 alone, then 2–3 with 2 on the right, **4 (the spread at an odd index) alone**, 5–6, 7–8, **9 (the spread at an even index) alone**, 10–11 |
  | Shift, left to right | Home, then Shift: 1–2 ("1–2 / 14", "Shift: on"); left to right put 1 on the left; → gave 3 alone (its partner 4 is wide), then 4, then 5–6 ("5–6 / 14") |
  | the slider | in right to left it fills from the right: a click showed 2–3 / 8; Space after it turned the page (the slider let the focus go) |
  | leave and resume | Esc on Chapter 2's 4–5: the row "Page 5 of 8" (the spread's last page), "Continue: Chapter 2"; Continue reopened on 4–5 / 8 |
  | zoom by the keys | Ctrl+= ×3: about 1.95× about the centre; ↑ and ← ×8 stopped exactly at the page's top-left corner (its red border at the window's edge, no black past it) |
  | pan by drag and wheel | a drag moved the view; the wheel panned down to the page's bottom edge and **didn't turn the page** |
  | double-click | at fit, in the middle third: 2× there, and the toolbar didn't toggle; zoomed, a double-click went back to fit; Space after a zoom turned the page and went back to fit |
  | Ctrl+wheel | 3 notches about the cursor: about 1.8×, the point under it kept (the "3" moved from y≈175 to ≈230 in the half-scale shot, as a zoom about y≈102 puts it), the page still narrower than the window centred. **The computer-use tool's scroll doesn't hold Ctrl** (twice it turned back a page, as a plain wheel does), so this check used a real Ctrl+wheel sent through Windows input (`SendInput`, a scratch script in the gitignored agent folder) |
  | Ctrl+0, Esc | both back to fit; Esc while zoomed didn't leave the reader |
  | the seamless transition, double | End (14 alone), Space: "Finished: Chapter 1 · Next chapter: Chapter 2"; Space: Chapter 2's page 1 in the next screenshot, the toolbar "Chapter 2", "2–3 / 8"; back: "Start of Chapter 2 · Previous chapter: Chapter 1", then Chapter 1's 14. Chapter 1 Read in the list |
  | the seamless transition, single, a network source | Webtoons "Osora" (S2) Chapter 124 (99 pages), End, Space ×2: **Chapter 125's first page in the screenshot taken right after the key**, no "Loading…" (Stage 3 measured 642 ms to its first page cold); the toolbar "(S2) Chapter 125", "1 / 95"; back through "Previous chapter: (S2) Chapter 124" into 124's last page at once; 124 Read in the list |
  | the seamless transition, webtoon | Local Chapter 2 at its top ("Previous chapter" panel): the gesture that got there stayed; a new notch up opened Chapter 1 with page 14's top at the window's top. At the bottom of Chapter 1, the gesture that got there stayed, a new notch opened Chapter 2 with page 1's top at the top |
  | the stats overlay, 103 pages | Chapter 126 in the strip, pages 1 → 13 at a normal speed: 4,282 frames at 145 fps, **no frame over 50 ms (the longest 8 ms), 0 blank frames**, at most 5 `<img>` and 5 items, the heap 28 MB, 507 ms to the first page (Stage 3: the longest 8 ms, 552–642 ms) |
  | the token | no `token=` in sidecar.log, madoyomi.log or the dev output; no warning in the dev output; the backend stopped with exit code 0 |

- **Found and fixed live:** the wheel notch that opened the next (previous) chapter in the strip also scrolled the new strip about 80 px, so its first page's top sat below the window's top (and page 13's bottom showed above Chapter 1's page 14). The opening wheel event's default is prevented now (a test). After the fix both ends put the page's top exactly at the top.
- **Found while writing it:**
  - a strip kept across a chapter change would have put its old anchor (the end panel) on the new chapter's layout and reported the new chapter's end panel as the position, marking it read. The strip and the pager are keyed by chapter;
  - the progress watcher missed a step into a chapter that lands on the same position (an empty chapter's 0 → the next one's 0); it now follows the chapter too;
  - `zoom.ts` gave `-0` for a centred axis; normalized.
- **Not checked, and why:**
  - a mid-range laptop (§6) and memory after 5 chapters: Stage 6's;
  - a trackpad (pinch, momentum past the strip's end): only a notched wheel;
  - Ctrl+wheel through the computer-use tool: it doesn't send Ctrl with its wheel (above); checked with a real input instead;
  - Shift+wheel sideways, a resize while zoomed, and a double-click in a side third (two page turns): in the component tests or by the code path, not live;
  - Tab reaching the toolbar's buttons and Space/Enter pressing them: unchanged keyboard behaviour; not tested live;
  - double page on a real source's chapter with spreads: the generated chapter stood in (real scans' joined spreads are wider than tall in the same way); MangaPill and MangaDex weren't read in the double mode;
  - the `Double Page Test` chapters are in `live-data/local` (gitignored scratch data), not in the repo.
