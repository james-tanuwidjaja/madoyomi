# Phase 5 — Manga reader

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 4](phase-4-playback.md) · Next: [Phase 6](phase-6-library-features.md)

## 1. Goal

A fast, comfortable manga reader in the Vue app with paged (LTR/RTL), vertical webtoon and double-page modes. Every image goes through the backend proxy so source headers/cookies apply, with prefetching and a disk cache so long webtoon chapters scroll smoothly.

## 2. Depends on / inputs

- Phase 1: manga sources work (`GET /api/manga/{id}/chapters`, `GET /api/chapters/{id}/pages`).
- Phase 3: Details screen with the chapter list; the minimal image proxy (if added there).

## 3. Deliverables

- Backend `server/core/image/`: `ImageProxy` (`GET /api/image?src=&sourceId=`, or by page ID), a disk cache with a size limit and LRU eviction, and page prefetch.
- DB: `chapter_progress` (last page, read flag, updated at).
- API: `PUT /api/chapters/{id}/progress`, `POST /api/chapters/{id}/prefetch`.
- UI `app/src/views/ReaderView.vue` + `app/src/components/reader/`: `PagedReader`, `WebtoonReader`, `DoublePageReader`, `ReaderToolbar`, `ReaderSettings`.
- Reader settings: default mode (global and per manga), reading direction, fit (width/height/original), background color, page gap (webtoon), preload count.

## 4. Detailed steps

### Backend — image pipeline
1. `ImageProxy`: resolve the page image URL (`imageUrlParse` when needed), fetch it with the source's OkHttp client (headers, cookies, rate limits), stream it to the client with the correct `Content-Type`, and add `Cache-Control`.
2. Disk cache keyed by a source + URL hash under `data/cache/images/`, with a size cap (default 1 GB, configurable) and LRU eviction.
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
- [ ] `ImageProxy` with source headers/cookies
- [ ] Disk cache with size cap + LRU eviction
- [ ] Prefetch endpoint (next pages + next chapter)
- [ ] Error + retry handling

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
- [ ] Proxy/cache backend tests

## 6. Exit criteria

- [ ] A 100+ page webtoon chapter scrolls smoothly (no visible stutter, no blank pages while scrolling at normal speed) on a mid-range laptop.
- [ ] All four modes work and remember per-manga settings.
- [ ] Reopening a chapter resumes at the last page.

## 7. Risks & notes

- Sources that need cookies from Cloudflare: the proxy must use the same cookie jar as the source (PLAN.md §6, Cloudflare).
- Very tall images (some webtoons put a whole chapter in a few huge images) may need tiling or downscaling.
- Watch memory: release off-screen images in webtoon mode.

## 8. Decisions closed here

- Default reader mode per content type (e.g. webtoon for manhwa sources, if the source provides a hint).
- Cache size default and location.
