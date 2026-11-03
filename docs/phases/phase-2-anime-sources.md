# Phase 2 — Anime source support

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 1](phase-1-backend-foundation.md) · Next: [Phase 3](phase-3-frontend-mvp.md)

## 1. Goal

Make installed Aniyomi extensions work: implement the `animesource` API surface they compile against, add the Android stubs they need, and expose browsing, search, filters, episodes, videos and per-source preferences through the API. Measure compatibility with a repeatable test harness over the most popular yuzono extensions.

## 2. Depends on / inputs

- Phase 1: anime extensions can be installed and trusted; `server/ext-anime` module exists.
- Phase 0 S3: `docs/research/anime-ext-classes.md` (referenced classes) and the missing-class log.
- Aniyomi `extensions-lib` version that yuzono builds against (pinned in Phase 0).

## 3. Deliverables

- `server/ext-anime`: implementations of `AnimeSource`, `AnimeCatalogueSource`, `AnimeHttpSource`, `ParsedAnimeHttpSource`, `ConfigurableAnimeSource`, `AnimeSourceFactory`.
- Models: `SAnime`, `SEpisode`, `Video`, `Track`, `AnimesPage`, `AnimeFilter*`, `AnimeFilterList`.
- Android stubs added to `server/ext-host` (shared with manga where possible).
- Serializers: filters → JSON (and back), preferences → JSON schema (and back).
- Anime API endpoints: sources, popular/latest/search, filters, preferences, anime details, episodes, videos.
- `tools/compat-test/`: a harness that runs the top ~20 yuzono extensions and writes `docs/research/anime-compat.md`.

## 4. Detailed steps

### API surface (`ext-anime`)
1. Mirror the package layout of `extensions-lib` exactly (`eu.kanade.tachiyomi.animesource.*`, `eu.kanade.tachiyomi.animesource.model.*`, `eu.kanade.tachiyomi.animesource.online.*`). Extensions link by fully qualified name.
2. Implement models with the same field names, defaults and mutability as upstream (`SAnime.create()`, `SEpisode.create()`, `Video(url, quality, videoUrl, headers, subtitleTracks, audioTracks)`).
3. Implement `AnimeHttpSource` helpers: `client`, `headers`, `headersBuilder()`, `popularAnimeRequest/Parse`, `searchAnimeRequest/Parse`, `episodeListRequest/Parse`, `videoListRequest/Parse`, `videoUrlParse`, `List<Video>.sort()`.
4. Implement `ParsedAnimeHttpSource` (Jsoup selectors).
5. Load sources: read the extension class from the APK manifest meta-data, instantiate it (a single source or an `AnimeSourceFactory`), and register sources with stable IDs (the upstream ID algorithm: MD5 of `name/lang/versionId`).
6. Support both suspend (`getPopularAnime`) and the older RxJava/`fetch*` APIs if the pinned lib version still uses them.

### Android stubs
7. Start from Suwayomi's `AndroidCompat`, then add what anime extensions need: `SharedPreferences` (file-backed per source), `Context`, `Application`, `Uri`, `Base64`, `Log`, `TextUtils`, `Handler`/`Looper` (no-op), `webkit.CookieManager` (bridged to the OkHttp cookie jar).
8. Add a classloader hook that logs missing classes/methods with the extension package, so gaps show up in the compat report.

### Networking
9. Shared OkHttp client with a cookie jar, a `RateLimitInterceptor` (`rateLimit`, `rateLimitHost`), a user-agent setting, and Cloudflare handling via Suwayomi's WebView shim.
10. Make `network.NetworkHelper` and `Injekt`/`injectLazy` lookups resolve to our instances.

### Filters & preferences
11. Filter serializer: `Header`, `Separator`, `Text`, `CheckBox`, `TriState`, `Select`, `Sort`, `Group` → JSON with a stable index path. The deserializer applies UI state back onto a fresh `AnimeFilterList`.
12. Preferences: run `setupPreferenceScreen(screen)` against a fake `PreferenceScreen` that records `ListPreference`, `EditTextPreference`, `SwitchPreferenceCompat`, `MultiSelectListPreference`, `CheckBoxPreference` → JSON schema (key, title, summary, type, entries, entryValues, default, current value).
13. `PUT` preferences writes to that source's `SharedPreferences` and re-instantiates the source if needed.

### API
14. `GET /api/sources?type=anime`, `GET /api/sources/{id}/popular|latest|search`, `GET /api/sources/{id}/filters`, `GET|PUT /api/sources/{id}/preferences`.
15. `GET /api/anime/{id}` (details, with `?refresh=true`), `GET /api/anime/{id}/episodes`, `GET /api/episodes/{id}/videos`.
16. Store browsed anime/episodes in the DB (upsert by source + URL) so the IDs stay stable.

### Compatibility harness
17. `tools/compat-test/`: for each of the top ~20 yuzono extensions (by repo order or a list in `top20.txt`): install → popular page 1 → first anime details → episodes → videos for the first episode → record pass/fail per step + errors + missing classes.
18. Write the results table to `docs/research/anime-compat.md`. Re-run after each stub fix.

## 5. Checklist

### API surface
- [ ] Package layout matches `extensions-lib` exactly
- [ ] `SAnime`, `SEpisode`, `Video`, `Track`, `AnimesPage`
- [ ] `AnimeSource`, `AnimeCatalogueSource`, `AnimeHttpSource`, `ParsedAnimeHttpSource`
- [ ] `ConfigurableAnimeSource`, `AnimeSourceFactory`
- [ ] Source loading + stable source IDs
- [ ] Suspend and legacy `fetch*` APIs (as needed for the pinned version)

### Android stubs & networking
- [ ] Anime-specific stubs added (`SharedPreferences`, `Context`, `Uri`, `Base64`, `Log`, …)
- [ ] Missing class/method logger
- [ ] Shared OkHttp client, cookie jar, rate limiting
- [ ] Cloudflare/WebView shim reachable from anime sources
- [ ] Injekt/`NetworkHelper` lookups resolved

### Filters & preferences
- [ ] Filter → JSON serializer (all filter types)
- [ ] JSON → filter state deserializer
- [ ] Preference screen → JSON schema
- [ ] Preference updates persisted per source

### API
- [ ] Sources list / popular / latest / search
- [ ] Filters endpoint
- [ ] Preferences GET/PUT
- [ ] Anime details, episodes, videos
- [ ] Browsed entries persisted with stable IDs

### Compatibility
- [ ] `tools/compat-test/` harness runs unattended
- [ ] `docs/research/anime-compat.md` generated
- [ ] Failures triaged (stub gap vs. source broken upstream)

## 6. Exit criteria

- [ ] At least 18 of the top 20 yuzono extensions pass popular → details → episodes → videos (extensions that are broken upstream are excluded and listed).
- [ ] Filters and preferences round-trip through the API for at least 3 extensions that use them.
- [ ] No regression in manga sources.

## 7. Risks & notes

- Missing Android APIs are the main risk (PLAN.md §6). The missing-class log + compat harness is the mitigation; fix by frequency.
- Different extensions may target different lib versions (PLAN.md §6). Record the version per extension in the compat report.
- Some extensions return magnet/torrent links. Mark them "unsupported (torrent)" for now (Phase 8).

## 8. Decisions closed here

- Pinned `extensions-lib` version(s) supported at launch.
- Whether multiple lib versions must be supported side by side.
