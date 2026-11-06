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

    As decided in Phase 0, these map to GraphQL at `/api/graphql` (REST only for binaries), like the Phase 1 endpoints ([phase-1 step 17](phase-1-backend-foundation.md#api)). Stage 4:

    | PLAN.md §5 REST sketch | GraphQL |
    |---|---|
    | `GET /api/sources?type=anime` | `animeSources(condition: {id, name, lang, extensionPkgName})` (Phase 1). `AnimeSourceType` gains `iconUrl`, plus `supportsLatest` and `isConfigurable` from the live source (null while it can't be used), and `anime` (its stored entries) |
    | `GET /api/sources/{id}/popular?page=`, `…/latest`, `…/search?q=&page=&filters=` | `fetchSourceAnime(input: {source, type: POPULAR\|LATEST\|SEARCH, page, query, filters: [FilterChange]})` → `{animes, hasNextPage}`; entries are stored with stable ids (upstream's `fetchSourceManga`) |
    | `GET /api/sources/{id}/filters` | `AnimeSourceType.filters` → upstream's `Filter` union (`SelectFilter`, `GroupFilter`, …) |
    | `GET /api/sources/{id}/preferences` | `AnimeSourceType.preferences` → upstream's `Preference` union |
    | `PUT /api/sources/{id}/preferences` | `updateAnimeSourcePreference(input: {source, change: SourcePreferenceChange})` → `{preferences, source}` (`MANAGE_SOURCE_PREFERENCES`, as upstream's `updateSourcePreference`) |
    | `GET /api/anime/{id}` (+ refresh) | `anime(id)`, `animes(condition, filter, order, paging)` (upstream's `manga`/`mangas`); refresh: `fetchAnimeAndEpisodes(input: {id, fetchAnime, fetchEpisodes})` → `{anime, episodes, seasons}` plus the source's error, if any, in `errors[]` (upstream's `fetchMangaAndChapters`) |
    | `GET /api/anime/{id}/episodes` | `AnimeType.episodes`, `episodes(condition: {animeId, …}, filter, order, paging)` (upstream's `chapters`); a `Seasons` entry has `AnimeType.seasons` and each season `parent` |
    | `GET /api/episodes/{id}/videos` | `fetchEpisodeVideos(input: {episodeId})` → `{episode, hosters: [{index, name, state, videos: [{videoUrl, headers, subtitleTracks, audioTracks, timestamps, mpvArgs, initialized, …}]}]}`; `fetchHosterVideos(input: {episodeId, hosterIndex})` for a lazy or failed hoster; `resolveEpisodeVideo(input: {episodeId, hosterIndex, videoIndex})` before playing a video that isn't `initialized` |
    | (compat log) | `ExtensionType.compatIssues` → `{kind, target, origin, context, count, firstSeen, lastSeen}` |
    | `GET /api/image?src=&sourceId=` (thumbnails) | REST `GET /api/v1/anime/{animeId}/thumbnail` (binary; the source's client and headers; cached, `cache-control: max-age=86400`). `AnimeType.thumbnailUrl` points there |

### Compatibility harness
17. `tools/compat-test/`: for each of the top ~20 yuzono extensions (by repo order or a list in `top20.txt`): install → popular page 1 → first anime details → episodes → videos for the first episode → record pass/fail per step + errors + missing classes.
18. Write the results table to `docs/research/anime-compat.md`. Re-run after each stub fix.

## 5. Checklist

### API surface
- [x] Package layout matches `extensions-lib` exactly (the Aniyomi app's `source-api`, ported in Phase 1. `ExtensionLinkCheck` resolves every reference into the API of AnimeGG (lib 14) and AnimePahe (lib 16); Stage 1, §10)
- [x] `SAnime`, `SEpisode`, `Video`, `Track`, `AnimesPage` (every field is stored or mapped by the Stage 3 services: `ANIME`/`EPISODE` since M0071, `Video` with headers, subtitle and audio tracks, timestamps and mpv args; AnimeGG end to end through them, §10)
- [x] `AnimeSource`, `AnimeCatalogueSource`, `AnimeHttpSource`, `ParsedAnimeHttpSource` (Stage 6):
  - all 20 of the top 20 are `AnimeHttpSource`s (`compat-scan.md`, "Extension API classes used"), and 18 of them pass the whole chain in the harness;
  - none extends `ParsedAnimeHttpSource`, so `ParsedAnimeHttpSourceTest` runs a selector-based source through browse (popular, latest, search), details with episodes, and videos against a loopback site;
  - `AnimeSource`/`AnimeCatalogueSource` are the services' fakes (`FakeAnimeSource`, `AnimeExtensionLoaderTest`)
- [x] `ConfigurableAnimeSource`, `AnimeSourceFactory` (Stage 6):
  - all 20 are configurable, and 19 round-trip a preference live (`anime-compat.md`; hentaihaven has none to change);
  - streamingcommunity, the one factory, gives 4 sources, which the harness and `AnimeRoundTripApiTest` change one by one
- [x] Source loading + stable source IDs (trusted extensions load at install, at trust and on first use, behind `ExtensionLoadGate`; `ANIME_SOURCE` is reconciled with the live sources. IDs come from the extension: `AnimeHttpSource.generateId`, MD5 of `name/lang/versionId`, e.g. AnimeGG `2247987965040486818`. Stage 1, §9)
- [x] Suspend and legacy `fetch*` APIs (as needed for the pinned version). One surface, the newest (lib 17), which keeps every lib-14/16 member; it is link-checked against both generations (§9)

### Android stubs & networking
- [x] Anime-specific stubs added (`SharedPreferences`, `Context`, `Uri`, `Base64`, `Log`, …). The static scan over the top 20 finds no missing class or member. The remaining `"Stub!"`s are in the deep-link `UrlActivity`, one WebView SSL callback (hanime) and `EditTextPreference` binder code: `OnBindEditTextListener` bodies (checked with jadx in AnimePahe and StreamingCommunity) and the `TextWatcher`s they install (the others). Madoyomi never runs any of these (Stage 2, §10). The `androidx.preference` value accessors are implemented; runtime confirmation comes from the Stage 5 harness
- [x] Missing class/method logger (`ExtensionCompatLog`: class-loader hook, `AnimeSourceCalls` → `EXTENSION_INCOMPATIBLE`, link-check install warnings; Stage 2, §9. On GraphQL as `extension.compatIssues` since Stage 4)
- [x] Shared OkHttp client, cookie jar, rate limiting (`AnimeExtensionRuntimeTest`: AnimeGG's client is `NetworkHelper`'s, with its cookie jar shared with `android.webkit.CookieManager` both ways; `rateLimit` keeps the jar)
- [ ] Cloudflare/WebView shim reachable from anime sources. **Deferred** (the "measure first" decision, §9 Stage 5): 1 of the top 20 is Cloudflare-blocked (animepahe), and 18/20 pass without a solver. Its WebView bypass runs and gives up (no KCEF). In the manga sample, comix needs a real WebView too (`manga-compat.md`, WEBVIEW). Left open; a later phase decides KCEF or FlareSolverr
- [x] Injekt/`NetworkHelper` lookups resolved (the extension's own `injectLazy<NetworkHelper>()` gets the app's instance; same test)

### Filters & preferences
- [x] Filter → JSON serializer (all filter types): `AnimeFilters.filtersOf`, upstream's GraphQL `Filter` union (Stage 3, §9). Round-trips over every filter of kickassanime, aniwave and hanime (§10); on GraphQL as `AnimeSourceType.filters` since Stage 4
- [x] JSON → filter state deserializer: `AnimeFilters.apply`, upstream's `FilterChange` on a fresh list, `BAD_REQUEST` with the position path for a change that doesn't fit
- [x] Preference screen → JSON schema: `AnimeSourcePreferences`, upstream's `Preference` union and `preferenceOf` (AnimePahe's 7 preferences; Stage 3)
- [x] Preference updates persisted per source: in its `SharedPreferences` (`source_<id>`), the change listener first as on Android, then the source is re-instantiated on next use (Stage 3, §9). On GraphQL as `updateAnimeSourcePreference` since Stage 4

### API
- [x] Sources list / popular / latest / search: `animeSources` (with the live `supportsLatest`, `isConfigurable`), `fetchSourceAnime(POPULAR|LATEST|SEARCH)` (Stage 4, step 16; `AnimeBrowseApiTest`, smoke step 6 on AnimeGG)
- [x] Filters endpoint: `AnimeSourceType.filters`, changes through `fetchSourceAnime(filters: [FilterChange])` (`AnimeBrowseApiTest` on a fake, `AnimeExtensionApiTest` on kickassanime)
- [x] Preferences GET/PUT: `AnimeSourceType.preferences`, `updateAnimeSourcePreference` (`AnimeExtensionApiTest`: AnimePahe installed from a loopback repo, 7 preferences, switch and list changes persist)
- [x] Anime details, episodes, videos: `anime`/`animes`/`episodes`, `fetchAnimeAndEpisodes`, `fetchEpisodeVideos`/`fetchHosterVideos`/`resolveEpisodeVideo`, and the REST thumbnail (Stage 4; `AnimeDetailsApiTest`, `AnimeVideosApiTest`, `AnimeThumbnailTest`; the AnimeGG chain of `tools/smoke-api.ps1`, §10)
- [x] Browsed entries persisted with stable IDs (`AnimeEntries.upsert` by `(source, url)`, unique since M0071; episodes by `(url, anime)`; Stage 3)

### Compatibility
- [x] `tools/compat-test/` harness runs unattended (`run-compat.ps1`: the top 20 on the real sidecar jar in 6.6 min, process health included; Stage 5)
- [x] `docs/research/anime-compat.md` generated (from the run's JSON; `-ReportOnly` redraws it after a triage edit)
- [x] Failures triaged (stub gap vs. source broken upstream): `tools/compat-test/triage.txt`. Run 1 had 3 failures: anizone was a host gap (fixed), animepahe is Cloudflare, and hentaihaven's video host is down; §10

## 6. Exit criteria

- [x] At least 18 of the top 20 yuzono extensions pass popular → details → episodes → videos (extensions that are broken upstream are excluded and listed). **18 of 20** on 2026-11-06 (`docs/research/anime-compat.md`), with resolve too, and no exclusion needed. The two failures are animepahe (Cloudflare) and hentaihaven (its video host is unreachable). The same 18 of 20 in Stage 6's final run (2026-11-06 20:18), with the leak fixes in.
- [x] Filters and preferences round-trip through the API for at least 3 extensions that use them (Stage 6):
  - `AnimeRoundTripApiTest`, offline: every filter of kickassanime (81 changes), aniwave (112) and hanime (220, with a sort and tri-states) goes through `fetchSourceAnime(SEARCH)` and reaches the source with exactly those states. A preference change of AnimePahe, streamingcommunity (4 sources), hanime and kickassanime goes through `updateAnimeSourcePreference` and is read back from the re-instantiated source;
  - live, in the harness: a preference round-trip in 19 of 20 extensions (22 of 23 sources), and a filtered search answered by 18 of 18 extensions with filters.
- [x] No regression in manga sources (Stage 6):
  - `tools/smoke-api.ps1`'s MangaPill chain passes (24 of 24 steps);
  - the manga sample passes 9 of 10 through popular, details + chapters, pages and the page image (`docs/research/manga-compat.md`), with 0 failures that are Madoyomi's. comix needs a real WebView (KCEF is off since Phase 1; deferred with the shim above);
  - every class of the sample verifies after the repair (`compat-scan.md`: 0 verify errors, 0 unrepaired).

## 7. Risks & notes

- Missing Android APIs are the main risk (PLAN.md §6). The missing-class log + compat harness is the mitigation; fix by frequency.
- Different extensions may target different lib versions (PLAN.md §6). Record the version per extension in the compat report.
- Some extensions return magnet/torrent links. Mark them "unsupported (torrent)" for now (Phase 8).

## 8. Decisions closed here

- Pinned `extensions-lib` version(s) supported at launch.
- Whether multiple lib versions must be supported side by side.

### Stage plan (2026-11-04)

Each stage ends in something verifiable and a commit:
1. **Anime loader behind the trust gate**, plus `ExtensionLinkCheck`. *Done.*
2. **Top-20 list**: jsDelivr ranking → `tools/compat-test/top20.txt`, plus a manga sample.
   - Static link-check scan over them.
   - Missing-class/method logging at runtime (`EXTENSION_INCOMPATIBLE`), exposed as `compatIssues`.
   - Android/`androidx.preference` stubs fixed by frequency.
   - Networking checks.
   - *Done* (2026-11-04), except the GraphQL `compatIssues` field, which moves to Stage 4 with the other operations (the core API is there: `ExtensionCompatLog`).
3. **Core anime services**: migration `M0071_AnimeBrowse` (unique `(source, url)` / `(url, anime)`, the missing `SAnime`/`SEpisode` columns).
   - Browse with filters, upserted into `ANIME`.
   - Details plus episodes (reconcile, seasons).
   - Hosters → videos (not persisted).
   - Filter and preference mapping.
   - *Done* (2026-11-05). The `Dex2jarRepair` patterns of Stage 2 were left to Stage 6 (user decision).
4. **GraphQL operations**: `fetchSourceAnime`, `fetchAnimeAndEpisodes`, `fetchEpisodeVideos`, `updateAnimeSourcePreference`, `anime`/`animes`/`episodes`, and `AnimeSourceType.filters`/`preferences`.
   - The `/api/v1/anime/{id}/thumbnail` route.
   - The PLAN.md §5 mapping table.
   - `smoke-api.ps1` anime steps, with the helpers moved to `tools/lib/MadoyomiServer.ps1`.
   - *Done* (2026-11-05), with `extension.compatIssues` from Stage 2.
5. **Compat harness** `tools/compat-test/run-compat.ps1` and its first run → `docs/research/anime-compat.md`, including process health (JVM aborts, threads, memory).
   - Then the Cloudflare decision: possibly a KCEF solver stage, 5b.
   - *Done* (2026-11-06). Run 1 gave 17/20. Three host fixes followed: large-stack source threads (anizone), Cloudflare as `SOURCE_FAILED`, and the video-fetch cache dropped on unload. Run 2 gave **18/20**.
   - Only animepahe is Cloudflare-blocked, so there is **no Stage 5b**: Cloudflare is deferred (§9).
6. **Fix round**:
   - the class loaders still alive after uninstall (15 of 20; §10);
   - the two `Dex2jarRepair` patterns of the manga sample (user decision, Stage 3);
   - filters/preferences round-trip through the API for ≥ 3 extensions;
   - no regression in manga;
   - the one-JVM verdict;
   - the remaining API-surface checklist items;
   - docs closed.
   - *Done* (2026-11-06):
     - the extension class loaders alive after uninstalling the top 20 went from 15 to 1, explained in §10;
     - both repair patterns are fixed;
     - every exit criterion passes: 18/20, filters and preferences through the API, no manga regression;
     - one JVM stays. **Phase 2 is done.** The Cloudflare/WebView shim stays deferred (§5).

## 9. Decision record

Phase-wide decisions first (from the Phase 2 plan, 2026-11-04), then the decisions of each stage.

Phase-wide:

| Decision | Why |
|---|---|
| **One API surface, the newest.** `ext-anime` is the Aniyomi app's `source-api` (lib 17), which keeps every lib-14/16 member: the Rx `fetch*` methods, both `Video` constructors, the hidden lib-16 `copy`, `Hoster`/`toHosterList`. No lib versions side by side. The manifest lib is recorded, never used to pick an implementation. | Phase 0 found the manifest lib doesn't describe the binary API (AnimeGG declares 14 and calls lib-16 members). The link check (Stage 1) proves the surface per extension. |
| **The "top 20" is ranked by jsDelivr monthly downloads** of yuzono's APKs, summed per package, torrent extensions excluded, **NSFW included** (user decision 2026-11-04: everything by downloads, real usage). The list is a committed snapshot (`tools/compat-test/top20.txt`); the report has a SAFE-only sub-tally. | Neither index has a popularity signal; jsDelivr serves the APKs and publishes per-file hit counts. |
| **Cloudflare: measure first** (user decision 2026-11-04). A KCEF-backed solver is only added (as an extra stage) if Cloudflare-blocked sources push the harness below 18/20; otherwise they are listed as "needs Cloudflare solver" and deferred. | Upstream's `CloudflareInterceptor` only uses FlareSolverr, an external service; KCEF is never used for Cloudflare, and a solver is sizeable work. |
| Filters and preferences are exposed through upstream's GraphQL `Filter`/`Preference` unions and its `FilterChange`/`SourcePreferenceChange` inputs (Stages 3–4). | Typed JSON; one Phase 3 renderer serves both kinds. |
| The compat harness is a PowerShell driver over the real sidecar jar (`tools/compat-test/`), outside Gradle. | Only an outside process can see a JVM abort or runaway threads/memory, which decide whether "one JVM" stays (phase-1 §8). |

Stage 1 (loader):

| Decision | Why |
|---|---|
| Anime sources get their own cache, `AnimeGetSource` (core, `madoyomi.core.anime`), registered with `SourceRegistry`; upstream's manga `GetSource` stays untouched. | Upstream's cache is typed to manga `Source`. |
| A trusted anime extension is instantiated at install/update, when its key becomes trusted, and on first use (a Phase 1 install needs no reinstall). Each load reconciles `ANIME_SOURCE` with the live sources and sets `is_loaded = true`. | The same meaning of `is_loaded` as for manga; the live code is the authority for its sources. |
| An update instantiates the new code before the old version is unloaded. | A new version that can't be loaded leaves the installed one as it was. |
| A load failure other than the gate's `UNTRUSTED` is `LOAD_FAILED` (new code, 500), with `details.cause` and, for a `LinkageError`, `details.missing`. At install it fails the install, as a manga `setupJar` failure does; later, `AnimeSourceAccess` reports it. No new column. | A stable code the trust/compat UI can branch on; nothing half-installed is kept. |
| `PackageTools` closes a class loader it created when the load fails (a `// Madoyomi:` edit). | Upstream leaked it, and the leaked loader kept the jar open: on Windows that locks the jar against uninstall and update (found by Stage 1's tests). |
| `ExtensionLinkCheck` (ext-host) checks a jar statically: every external class, method and field is resolved over the host's class files read as bytes (nothing is loaded or initialised), and members whose body only throws `"Stub!"` (android-jar) are reported. | A gap would otherwise surface as a `NoSuchMethodError` deep inside some source call. Static, so it covers code paths the harness never reaches. |

Stage 2 (top 20, compat scan, logging, stubs):

| Decision | Why |
|---|---|
| **Top 20:** `tools/compat-test/select-top.ps1` sums jsDelivr's monthly hits per package over every `/apk/` file of yuzono (all versions), excludes torrent, keeps NSFW, and reads each row's content warning from yuzono's `index.pb` with a small PowerShell protobuf reader (`tools/compat-test/lib/Protobuf.ps1`). Ties go by package name. The snapshot (2026-11-04) is `top20.txt`. | The phase-wide decision. The JSON index can't tell MIXED from NSFW (phase-1 §10). |
| **Manga sample:** 10 Keiyoushi extensions ranked by the GitHub `download_count` of each package's current APK asset (the one `index.pb` points at), per day since its release was published; NSFW included. Snapshot `manga-sample.txt`. | Keiyoushi's APKs are GitHub release assets, not served by jsDelivr: its jsDelivr stats list only the index files and two icons. Its `index.min.json` is a two-entry "Outdated App" placeholder, so the list comes from `index.pb`. Keiyoushi rebuilds everything into a few releases (5 on 2026-11-04) and Mihon auto-updates to each build, so the count approximates the installed base. |
| **The scan is a JUnit test** (`CompatScanTest`, only with `MADOYOMI_COMPAT_SCAN=1`) driven by `tools/compat-test/scan.ps1`. It writes the generated, committed `docs/research/compat-scan.md`. APKs go to the gitignored `tools/compat-test/apks/`. | The link check needs the host classpath, which Gradle's test runtime has. The report is text and diffable between runs. |
| **Activity-only issues don't count:** `ExtensionLinkCheck.Report` knows the jar's `android.app.Activity` subclasses. An issue referenced only from them is left out of the warnings, the compat log and the scan's counts (it is listed separately). | Every extension's `UrlActivity` (a deep-link entry point) uses `Activity` stubs, and Madoyomi never starts an activity. |
| **The link check runs at every install (both kinds) and only warns** (`InstallResult.warnings`, one line per kind with the first 5 targets). Its issues go into the compat log once the install has succeeded, and the log starts over with each new version. | A stub on a path that never runs must not block an install, but the trust/compat UI should see it. |
| **Runtime logging:** `ChildFirstURLClassLoader` (a `// Madoyomi:` edit) reports classes found nowhere to `ExtensionClassLoadHook`. `ExtensionCompatLog` (core, in memory, at most 200 distinct issues per package) records them as `CLASS_NOT_FOUND`, a kind of its own because it may be a handled probe; known probes are dropped. Each distinct issue is logged once. | Catches missing classes where the extension asks for them. Members can't be seen at load time; the call wrapper catches those. |
| **`EXTENSION_INCOMPATIBLE` (501)** for an API gap at call time. `AnimeSourceCalls.call`/`callBlocking` wrap every anime source call (from Stage 3): a `NoClassDefFoundError`, `NoSuchMethodError`/`NoSuchFieldError`, `AbstractMethodError` or android-jar `RuntimeException("Stub!")` anywhere in the cause chain is recorded and rethrown with `details.pkgName`, `sourceId`, `operation`, `kind`, `missing`, `cause`. Other failures pass through unchanged. `ErrorClassifier` maps a raw gap that arrives another way (upstream's manga code) to the same code, without a package. Load failures stay `LOAD_FAILED` but are recorded too. | 501 "not implemented": the host lacks what the extension needs, which is neither the site's fault (`SOURCE_FAILED`) nor an unknown server fault (`INTERNAL`). One classifier (`ExtensionIncompatibility`) serves the wrapper, the loader and the error model. |
| **`androidx.preference` value accessors** (`ListPreference.getValue/setValue/setValueIndex/getEntry`, `MultiSelectListPreference.getValues/setValues/findIndexOfValue`, `TwoStatePreference.isChecked/setChecked`) read and persist through the screen's `SharedPreferences` (`getSharedPreferences()` + `getKey()`), as Android does. Before the preference is on a screen, the value is kept in the object, then `defaultValue` applies. `setOnPreferenceClickListener` stores the listener. | Extensions call these in `setupPreferenceScreen`, which upstream's `"Stub!"` turned into a failure. Persisting on `set*` is Android's behaviour, and upstream's serializer (`getCurrentValue`) reads the same keys. |
| **An android-jar class is replaced by leaving it out of the android-jar AndroidCompat uses** (`strippedAndroidStubs`, `madoyomiAndroidOverrides`) and implementing it in AndroidCompat. Only `android.os.LocaleList` (on `java.util.Locale`) for now. | Every Keiyoushi extension of the sample calls `LocaleList.getDefault/get/size`. Two copies of a class on the classpath would be resolved by classpath order; upstream's android-jar is built the same way (stripped of what AndroidCompat implements). |
| **No `android.widget`/`android.view` replacements.** The `TextView`/`View`/`EditText` stubs of 6 top-20 extensions are all in `EditTextPreference.OnBindEditTextListener` bodies and their `TextWatcher`s (checked with jadx in AnimePahe and StreamingCommunity). | Only a UI that binds the edit dialog runs them, and Madoyomi never calls the listener (its preference UI is its own, Phase 3). |
| **Lib-16 suspend request helpers** `OkHttpClient.get(String\|HttpUrl, …)`/`post(…)` were added to `ext-host`'s `Requests.kt` (a `// Madoyomi:` edit, from Aniyomi's `core/common`). | A gap in the extension API itself (anikoto, kotokai, aniwave call `get$default`), which `ExtensionLinkCheckTest`'s three APKs didn't use. |

Stage 3 (core anime services):

| Decision | Why |
|---|---|
| **The services are core objects in `madoyomi.core.anime`:**<br>• `AnimeBrowse` (popular, latest, search)<br>• `AnimeDetails` (details, then episodes or seasons)<br>• `AnimeVideos` (hosters, videos, `resolveVideo`)<br>• `AnimeFilters`<br>• `AnimeSourcePreferences`<br>• `AnimeEntries` (row ↔ model mapping, the upsert)<br><br>Every source call goes through `AnimeSourceCalls`. An unknown anime or episode is `NOT_FOUND`, and an input that doesn't fit is `BAD_REQUEST`. | Stage 4's GraphQL operations become thin wrappers, and the services are tested without GraphQL. |
| **M0071** adds:<br>• the missing `SAnime`/`SEpisode` columns, `memo` included;<br>• seasons as `ANIME` rows with `parent_id` (`ON DELETE SET NULL`) and `season_source_order`;<br>• `CREATE UNIQUE INDEX IF NOT EXISTS` on `ANIME (source, url)` and `EPISODE (url, anime)`, after deleting duplicates (lowest id kept).<br><br>The foreign key is declared inline in `ADD COLUMN IF NOT EXISTS`. | The memo is the extension's own data and must come back to it with the entry, as upstream stores `MangaTable.memo`. A season is browsable and can go in the library like any entry. Both statements are portable to H2 and PostgreSQL and idempotent, and `ADD CONSTRAINT IF NOT EXISTS` is H2-only. |
| **The browse upsert is upstream's `MangaList.insertOrUpdate`** (library rows untouched; null fields keep the stored value), plus:<br>• the page is deduplicated by url;<br>• entries without a url are dropped;<br>• a unique-key race is retried once.<br><br>Ids come back in page order, each once. | Upstream inserted a url twice when a page listed it twice. With the unique index, a concurrent browse of the same page would otherwise fail. |
| **Episode reconcile is upstream's chapter reconcile, trimmed:**<br>• matched by url;<br>• `source_order` counts from the oldest (1);<br>• the watch state is never overwritten, and a deleted episode's state moves to a new one with the same number;<br>• a `date_upload` of 0 keeps the stored date.<br><br>**An empty list changes nothing** (upstream throws "No chapters found"). | One numbering for chapters and episodes. A site that changes its links must not lose what was watched. An anime can legitimately have no episodes yet. |
| **Which children to fetch follows `fetch_type`:**<br>• `Episodes` → `getAnimeEpisodeUpdate`. An initialized entry gets details and episodes in one call, which the source runs in parallel; otherwise details come first.<br>• `Seasons` → `getAnimeSeasonUpdate`.<br><br>A dropped season loses its `parent_id` but is not deleted. The stored episodes or seasons are handed to the source. | The lib-17 entry points; their defaults run the lib-14/16 methods, so one path serves every extension. A season may be in the library. |
| **Videos follow the Aniyomi app's `EpisodeLoader`/`HosterLoader`:**<br>• hosters only from an `AnimeHttpSource` whose own classes declare a hoster method (`checkHasHosters`), else `getVideoList(episode).toHosterList()`;<br>• `sortHosters`/`sortVideos`;<br>• the `"null"` URL → `getVideoUrl`;<br>• lazy hosters on request;<br>• `resolveVideo` only for videos that aren't `initialized`.<br><br>Hosters load in parallel, and a failing one carries its error code instead of failing the call. A video without headers gets the source's headers. | The same videos as in Aniyomi, for every lib generation. A source call on the plain `AnimeCatalogueSource` hoster defaults would recurse (they do in Aniyomi too, §10), and this path never makes one. |
| **`resolveVideo` works on the last fetch of the episode**, kept in memory: 32 episodes, 10 minutes, and only while its source is still the live instance. | A `Video` is extension state that can't be rebuilt from an id. Resolving needs the original object, and video URLs expire anyway. |
| **Filters and preferences use upstream's GraphQL types as they are:**<br>• `Filter`, `FilterChange`, `Preference`, `preferenceOf`, `SourcePreferenceChange`;<br>• filter changes are `BAD_REQUEST` with the position path when they don't fit (upstream threw a plain exception);<br>• a change to a header or separator is ignored. | The phase-wide decision: one shape and one Phase 3 renderer for both kinds. AndroidCompat's `androidx.preference` classes are shared, so upstream's mapping applies unchanged. |
| **A preference change:**<br>• builds the screen anew;<br>• runs the extension's change listener first and saves only if it agrees (Android's order);<br>• drops the source from the cache so its next use is a new instance. | Upstream cached the screen (a change before any read was a null pointer), saved before asking the listener, and ignored its veto. Extensions often read a preference once (a `by lazy` base URL). |
| The **ext-anime port keeps Aniyomi's self-recursive `AnimeCatalogueSource.getHosterList`/`getVideoList(hoster)`** unchanged. | Aniyomi has the same code. No Madoyomi path calls it, and the port stays identical to upstream. |

Stage 4 (GraphQL operations):

| Decision | Why |
|---|---|
| **The operations are thin wrappers in `api`** (`madoyomi.api.graphql`: `AnimeQuery`, `EpisodeQuery`, `AnimeMutation`, `AnimeTypes`, `AnimeVideoTypes`). They have upstream's shapes: `fetchSourceAnime` ↔ `fetchSourceManga`, `fetchAnimeAndEpisodes` ↔ `fetchMangaAndChapters`, `anime`/`animes`/`episodes` ↔ `manga`/`mangas`/`chapters` (condition, filter, order, cursor paging, with upstream's filter and pagination helpers), and `updateAnimeSourcePreference` ↔ `updateSourcePreference`. There are no per-user tables; the anime tables have none. | The core services are tested without GraphQL (Stage 3). One shape for both kinds keeps the Phase 3 client's code paths alike. |
| **Data loaders through the schema hook**: `GraphQLSchemaContribution.dataLoaders`, appended to upstream's `TachideskDataLoaderRegistryFactory` (a `// Madoyomi:` edit). Anime, episodes-of-anime, seasons-of-anime, source and entries-of-source loaders. | Upstream's types resolve their relations through data loaders; a list of 100 anime with `source` would otherwise be 100 queries. The loaders are found with ServiceLoader, like the operations. |
| **`AnimeSourceType.supportsLatest`/`isConfigurable` are null** while the source can't be used (untrusted, not loadable). **`filters`/`preferences` fail** with the reason (`UNTRUSTED`, `NOT_FOUND`, `LOAD_FAILED`). | `animeSources` lists sources from the repo listing too (Phase 1), so a list query must not fail for one untrusted source; the extension's `isTrusted`/`isLoaded` say why. Filters and preferences are asked for one source at a time, where the reason is what the UI needs. |
| **`fetchAnimeAndEpisodes` returns the stored data plus the error** (`DataFetcherResult`, as upstream). The error is classified like any other (`MadoyomiGraphQLErrors.errorOf`: code and details), and an unknown id is a plain `NOT_FOUND`. `fetchEpisodes` refreshes the seasons of a `Seasons` entry, and the payload has `seasons`. | Upstream's partial result was `toGraphQLError()` without a code. The stored entry is still worth showing when the site is down. |
| **Three video mutations:** `fetchEpisodeVideos` (every non-lazy hoster), `fetchHosterVideos` (one hoster on request) and `resolveEpisodeVideo`. Hosters and videos are addressed by index in the last fetch (`AnimeVideos`' in-memory cache). They are mutations because they call the source. | They match Aniyomi's three steps (`EpisodeLoader`, lazy `HosterLoader`, `getResolvedVideo`). A `Video` is extension state that can't be rebuilt from an id (Stage 3). |
| **`compatIssues` is a field of upstream's `ExtensionType`** (a `// Madoyomi:` resolver, like `isTrusted`), with `ExtensionCompatIssueType` in core. The enums are named `ExtensionCompatIssueKind`/`ExtensionCompatIssueOrigin` in the schema. | The issues belong to the extension the UI already shows. A separate query would duplicate the extension lookup. |
| **Thumbnails: REST `GET /api/v1/anime/{animeId}/thumbnail`** (`AnimeHttp` in `api`, `AnimeThumbnails` in core):<br>• upstream's manga thumbnail flow: the source's client and headers, `FORCE_NETWORK`, one details refresh on 404/410/301/522/523 or an unreachable host;<br>• cached in upstream's thumbnail cache as `anime-<id>.<ext>`, cleared when a details fetch changes the URL;<br>• the plain client when the source can't be used;<br>• `AnimeType.thumbnailUrl` is the proxied path, `backgroundUrl` the source's URL as it is. | Binary endpoints stay REST (Phase 0). Upstream's `clearCachedImages(cachedThumbnails)` then clears both kinds, and the `anime-` prefix can't collide with a manga id. Thumbnails of library entries (upstream's permanent `ThumbnailDownloadHelper`) are Phase 6, with the anime library. |
| **`tools/lib/MadoyomiServer.ps1`** holds the server and API helpers; `smoke-api.ps1` dot-sources it. | Stage 5's compat harness drives the same jar the same way. |

Stage 5 (compat harness):

| Decision | Why |
|---|---|
| **The harness:** `tools/compat-test/run-compat.ps1` drives the real sidecar jar over GraphQL on a fresh data dir (`MadoyomiServer.ps1`). Per extension:<br>• install, with the progress subscription; the source must be usable;<br>• popular page 1, retried once;<br>• details + episodes (a `Seasons` entry: its first season);<br>• videos of the newest episode, then the oldest, loading lazy hosters until one has videos;<br>• resolve a video.<br><br>Up to 3 popular entries are tried, and every call has a 120 s timeout. **An extension passes when one entry gets through the whole chain.** | Step 17's chain, plus resolve, which Phase 4 needs before playing. Several entries, because one entry's dead link isn't the extension's fault. |
| **Failure classes:**<br>• `HOST_GAP`: `EXTENSION_INCOMPATIBLE`, or a runtime compat issue;<br>• `CLOUDFLARE`: the server says so, or a 403/503 from a site whose base URL shows PowerShell a challenge;<br>• `SITE_DOWN`: the host is unreachable;<br>• `SOURCE_ERROR`, `INTERNAL`, `JVM_ABORT`.<br><br>A hand-written verdict in `tools/compat-test/triage.txt` (e.g. `upstream-broken`, `site-down`) overrides the automatic class. `-ReportOnly` redraws the report from the saved JSON. | The report stays generated, and the triage is versioned with it. A probe challenge alone doesn't count: OkHttp passes where PowerShell gets one (hentaihaven). |
| **Process health is measured from outside:**<br>• after the repo add, after each extension, and after uninstalling everything;<br>• `jcmd GC.run` + `GC.heap_info` (live heap), `jstat -class`, `jcmd VM.classloader_stats` (extension class loaders), `Thread.print`, and the process counters;<br>• `-XX:ErrorFile` puts any hs_err into the data dir;<br>• a dead JVM is recorded and restarted. | What decides "one JVM" (phase-1 §8) is only visible from outside the process. Measuring after a full GC makes the heap numbers comparable. |
| **Source calls run on large-stack threads:** `AnimeSourceCalls.call` switches to a dispatcher of 64 daemon threads with 128 MB stacks. | `java.util.regex` recurses once per repetition of a group, but Android's ICU regex doesn't. anizone's `(?:[^'\\]|\\.)*` over its 41K-character page needs more than 16 MB (§10). Windows only reserves a thread's stack and commits it as it is used. The JVM-wide alternative, an `-Xss` in the Tauri launch command, is left to Phase 3/7 if a non-suspend path ever needs it. |
| **A Cloudflare challenge is `SOURCE_FAILED` with `details.reason = "CLOUDFLARE"`** (`ErrorClassifier`; upstream's `CloudflareInterceptor` message, matched as a constant). | It was `INTERNAL`, a server fault. The site blocked the request, and the UI can say so. |
| **Cloudflare outcome** (the phase-wide "measure first"): 1 of the top 20 is blocked (animepahe), and the harness passes 18/20 without a solver. Cloudflare-blocked sources are listed as "needs Cloudflare solver" and **deferred**, so there is no Stage 5b. | The decision rule of 2026-11-04: a solver only if Cloudflare pushes the result below 18/20. Cloudflare serves 19 of the 20 sites (rule34video uses DDoS-Guard), and PowerShell's probe gets a challenge on three (animepahe, missav, hentaihaven). Only animepahe challenges the extension's OkHttp, though. |
| **Dropping a source drops what is cached for it:** `AnimeGetSource`'s unregister paths call `AnimeVideos.dropStale()`. | The harness found 18 of 20 extension class loaders alive after uninstalling all of them, and the kept video fetches held them (§10). |

Stage 6 (fix round):

| Decision | Why |
|---|---|
| **Unload stops the threads an extension left running** (`ExtensionThreads`; user decision 2026-11-06: interrupt and log, nothing forced).<br>• A thread is the extension's when its class, `Runnable`, context class loader or a stack frame comes from its loader, or it is a worker of a JDK pool whose thread factory does.<br>• The extension's own pool is shut down; every other thread is interrupted.<br>• A thread still alive after 500 ms gets the sockets its `Runnable` holds closed.<br>• A thread still running after that is left alone and logged. | A live thread keeps its extension's class loader until the process ends. The ones found were all blocked where an interrupt doesn't reach: NanoHTTPD's `accept()` (missav, onetwothreeanime, reanime, streamingcommunity bundle their own) and an idle pool's `take()` (streamingcommunity). Closing what the thread's own objects hold is what the extension's `stop()` would do. |
| **Extension class loaders are named** (`madoyomi-extension-<n>`), and stack frames are matched by the loader's name. | R8's short class names (`w2`, `v7`) repeat across extensions. Matching frames by class name stopped other extensions' threads in the first try. |
| **Inherited access-control contexts are dropped**:<br>• `AnimeSourceCalls`' pool threads are created privileged;<br>• at every unload, every live thread's inherited context is cleared, when no SecurityManager is installed;<br>• the shared OkHttp pool's idle connections are evicted. | A thread, and a TLS connection, keeps the access-control context of the stack that created it: the protection domain of every extension frame on it, and so that loader. A pool thread that once ran kickassanime started reanime's server, which then kept kickassanime. JDK 21 uses the context only under a SecurityManager, which Madoyomi never installs. |
| **`Json` is a factory in Injekt**, not a singleton (a `// Madoyomi:` edit in `AppModule`). | A `Json` instance caches a schema per serial descriptor it decodes, strongly. The shared one held every extension's descriptors, which was 4 of 5 loaders in one heap dump. Each user now has its own instance. |
| **Weak preference listeners** (`JavaSharedPreferences`, as Android's `SharedPreferencesImpl`); a lazy load that an unload overtook registers nothing (`AnimeGetSource.unloadCount`); a fetch of a source unloaded meanwhile isn't kept; a manga source's cached preference screen and filter list go at unload. | Each held the extension's objects for the process's lifetime, or could put an unloaded extension back. |
| **The server jar opens `java.lang` and `java.util.concurrent`** to itself (`Add-Opens` in its manifest, `--add-opens` in the tests). | Reading a thread's `Runnable`, a pool worker's pool and a thread's inherited context needs them. A launch without `-jar` must pass them (`server/UPSTREAM.md`, "Startup contract"); without them the stop is interrupt-only. |
| **`tools/compat-test/lib/HprofLoaderRoots.java`**, a dependency-free HPROF reader. The harness takes a heap dump and reports each kept loader's nearest holder when loaders are still alive after the uninstall and an idle wait (`-SettleSec`). | No heap analyser is installed. A JDK 21 single-file program needs no build, and the next leak is diagnosed by the same run that finds it. |
| **Two more `Dex2jarRepair` patterns** (mangadex; mangafire, comix, mangakakalot):<br>• a constructor's super call that skips its direct superclass is rewritten to it, and the superclass gets the constructor back;<br>• a `new T` that dex2jar left unfolded before `S.<init>` gets its call rewritten to `T.<init>`. | They are the same R8 constructor removal seen from the subclass, and the same `new` site in dex2jar's other output form. The manga sample now verifies with 0 errors. |
| **Filters and preferences are verified through GraphQL offline** (`AnimeRoundTripApiTest`), and live in the harness (one preference round-trip per configurable source, one filtered search per extension), neither counted in its pass. | The exit criterion asks for the API. The offline test checks every state reaching the source, and the live steps show the site accepts a filtered request. |
| **Manga regression check: the harness's manga mode** (`run-compat.ps1 -Kind Manga`, the 10-extension Keiyoushi sample through upstream's manga API into `docs/research/manga-compat.md`; user decision 2026-11-06), plus the smoke test's MangaPill chain. A regression is a failure that is Madoyomi's (`HOST_GAP`, `INTERNAL`, `JVM_ABORT`). | Phase 1 only ran MangaPill. The sample is what users install, and the Stage 2 scan already showed repair gaps there. |

## 10. Findings log

**2026-11-04: loader and link check (Stage 1).** `ExtensionLinkCheck` over the three test APKs (after dex2jar and `Dex2jarRepair`), against the host classpath:

| APK | External classes / members | Missing classes / members | Stubs |
|---|---|---|---|
| AnimeGG v14.2 (lib 14) | 105 / 180 | 0 / 0 | 0 |
| AnimePahe v16.56 (lib 16) | 217 / 561 | 0 / 0 | 7, all in its preference dialog: `android.view.View.findViewById`/`getRootView`/`setEnabled`, `android.widget.TextView.addTextChangedListener`/`getError`/`setError`/`setInputType` |
| MangaPill v1.6.1 (manga) | 106 / 258 | 0 / 0 | 3: `android.os.LocaleList.get`/`getDefault`/`size` |

- Every reference into the extension API (`eu.kanade.tachiyomi.*`), Injekt, OkHttp, Jsoup and RxJava resolves for both lib generations. That matches Phase 0's runtime result (AnimeGG end to end).
- The AnimePahe stubs are the preference-dialog gap Phase 0 predicted statically (`docs/research/anime-ext-classes.md`). They only run when a UI binds the `EditTextPreference`, and Madoyomi never does.
- MangaPill's `LocaleList` stubs were not known before; nothing has hit them at runtime yet (MangaPill browses and reads). Stage 2 fixes stubs by frequency over the top 20.
- **Upstream leaked a class loader on every failed load.** `PackageTools.loadExtensionSourcesWithLockHeld` created a `ChildFirstURLClassLoader` and only stored it once `Class.forName` succeeded. A failed load left it open, holding the jar, so on Windows the jar couldn't be deleted (uninstall, update) until restart. Fixed, and covered by `ExtensionLifecycleTest`, which renames the jar after a failed load.
- AnimeGG installs trusted and loaded with its live source `2247987965040486818` (`https://www.animegg.org`) replacing whatever the listing said.

**2026-11-04: top 20, compat scan, logging, stubs (Stage 2).**
- **The top 20** (`tools/compat-test/top20.txt`; jsDelivr, last month, 436 APK files with hits): hanime, oppaistream, animepahe, missav, anidb, rule34video, onetwothreeanime, movy, hentaimama, anikoto, kickassanime, kisskh, hentaihaven, reanime, streamingcommunity, kotokai, anizone, javguru, aniwave, animeonsen.
  - 9 SAFE, 1 MIXED, 10 NSFW. Lib 14 ×6, lib 16 ×14.
  - The hit counts are small (#1 hanime 1,927, #20 animeonsen 453 a month), so most Aniyomi clients probably fetch from raw GitHub, not jsDelivr. The ranking is relative only.
- **The manga sample** (`manga-sample.txt`): mangafire, mangadex, kagane, asurascans, comix, webtoons, toonily, mangakakalot, weebcentral, aquamanga (3 SAFE, 7 MIXED), 11k–33k downloads a day.
- **Static scan** (`docs/research/compat-scan.md`, generated): all 30 APKs convert. Reachable issues per extension, before and after this stage's fixes (Activity-only issues not counted):

  | | Before | After |
  |---|---|---|
  | Anime with no reachable issue | 11 of 20 (SAFE 3 of 9) | 14 of 20 (SAFE 6 of 9) |
  | Manga with no reachable issue and no verify error | 0 of 10 | 6 of 10 |
  | Missing classes, all 30 | 0 | 0 |
  | Missing members, all 30 | 6 in 3 anime extensions | 0 |

  What the 20 anime extensions still hit, all in code that only runs with a UI or a WebView error:

  | Class | Extensions | Where |
  |---|---|---|
  | `android.view.View` (`findViewById`, `getRootView`, `setEnabled`, `post`) | 4 | `EditTextPreference.OnBindEditTextListener` bodies and their `TextWatcher`s |
  | `android.widget.TextView` (`addTextChangedListener`, `get/setError`, `setInputType`, `setHint`, `setHorizontallyScrolling`), `android.widget.EditText.selectAll` | 5 | the same |
  | `android.net.http.SslError.toString`, `android.webkit.SslErrorHandler.cancel` | 1 (hanime) | `WebViewClient.onReceivedSslError` |

- **An extension-API gap, not an Android one:** anikoto, kotokai and aniwave (one multisrc theme, lib 16) call `eu.kanade.tachiyomi.network.RequestsKt.get$default(OkHttpClient, String|HttpUrl, Headers, CacheControl, Continuation, …)`, the suspend `OkHttpClient.get` that Aniyomi's lib 16 added. Suwayomi's `Requests.kt` doesn't have it, so these three would have thrown `NoSuchMethodError` on their first request. Added (with `post`); all three are clean now.
- **`android.app.Activity`** (`<init>`, `onCreate`, `getIntent`, `finish`): 14 of 30 extensions (5 anime, 9 manga), always from `UrlActivity`, the deep-link activity. Not reachable in Madoyomi; reported separately.
- **`android.os.LocaleList`** (`getDefault`, `get`, `size`): all 10 manga extensions (a shared Keiyoushi utility; Stage 1 saw it in MangaPill) and no anime one. Implemented on `java.util.Locale`; gone from the scan, and MangaPill now installs with no warnings.
- **Manga WebView rendering** (not Stage 2's business, recorded for the manga side): mangafire and comix lay out a `WebView` (`View.measure/layout`, `Resources.getDisplayMetrics`, `WebChromeClient` `ConsoleMessage`), and comix renders it into a `VirtualDisplay`/`ImageReader` and sends `MotionEvent`s. That needs a real WebView, not stubs.
- **New R8 patterns that `Dex2jarRepair` doesn't handle** (manga only; every anime class verifies):
  - **mangadex** (#2 of the sample): 8 classes fail with `Bad <init> method call … Type 'java/lang/Object' is not assignable to 'f4'`. They are kotlinx-serialization DTOs whose sealed parent lost its no-op constructor to R8, so the subclass constructor calls `Object.<init>` directly (jadx: `f4 extends u`, and `f4.<init>` has no super call). This is the constructor elision of phase-1 §10, but as a *super call inside a constructor* rather than at a `new` site. The repair would rewrite it to the direct superclass and add the missing constructors, as it already does for `new` sites.
  - **mangafire, comix, mangakakalot**: 1 class each, `Call to wrong <init> method`, with the repair's note `dex has 1 new java/lang/Exception, jar has 0`. A `new` site the repair couldn't map, because dex2jar emitted a different number of them.
  - Neither pattern was seen before (Phase 1 only ran MangaPill). Both are open: left to the Stage 6 fix round (user decision 2026-11-05).
- **Runtime:** AnimePahe's preference screen sets up with no `"Stub!"` (3 `ListPreference`, 3 `SwitchPreferenceCompat`, 1 `EditTextPreference`; Stage 2 counted 2 switches, Stage 3's mapping found the third), and the values round-trip through its `SharedPreferences` (`AnimeExtensionRuntimeTest`). AnimeGG's client is `NetworkHelper`'s, and its cookie jar and `android.webkit.CookieManager` see each other's cookies.

**2026-11-05: core anime services (Stage 3).**
- **The live chain** (`AnimeServicesLiveTest`, `MADOYOMI_LIVE_SOURCE_TESTS=1`; real sites, extensions loaded without the trust gate):

  | Extension | Popular | Details → episodes | Videos | Resolved |
  |---|---|---|---|---|
  | AnimeGG | 125 entries, same ids on a second call | "One Piece", 1167 episodes reconciled | 1 hoster (`no_hoster_list`, lib 14), 3 videos | `[SUBBED] AnimeGG:1080p`, an mp4 with `User-Agent` and `referer` headers |
  | AnimePahe | not reached | | | |

  - The whole AnimeGG chain takes about 7 s.
  - AnimePahe is behind Cloudflare. Its own WebView interceptor runs first, then upstream's `CloudflareInterceptor` fails with "Cloudflare bypass currently disabled" (FlareSolverr is off). This is the "measure first" decision of Stage 5; the live test skips a Cloudflare-blocked site and says so.
  - Before that, the test environment showed that **extensions need Android's main looper.** AnimePahe's interceptor does `Handler(Looper.getMainLooper())`, which throws a null pointer when no main loop runs. The sidecar starts one (`LooperThread` in upstream's `applicationSetup`); `ApplicationTest.testingSetup()` doesn't, so the live test starts it.
- **Filters of real extensions** (`AnimeFiltersTest`): every filter is moved off its default, the changes are applied to a fresh list, and the mapped result is compared.

  | Extension | Filters | Changes | Types |
  |---|---|---|---|
  | kickassanime (SAFE) | 7 | 81 | header, separator, 4 selects, a group of 77 checkboxes |
  | aniwave (SAFE) | 8 | 112 | a select and 7 groups of 111 checkboxes |
  | hanime (NSFW, #1) | 4 | 220 | 64 tri-states and 154 checkboxes in 2 groups, a sort, a select |

  - The text filter is covered by the fake list. hanime is the only top-20 extension with a sort or a tri-state (from the dex strings of all 20).
  - In all three, a fresh `getFilterList()` has the defaults again after a search, so the lists aren't shared objects.
- **AnimePahe has 7 preferences, not 6:** `preferred_mirror`, `preferred_quality` and `preferred_lang` (lists), `pref_show_site_number`, `preferred_link_type` and `preferred_av1` (switches), and `cf_bypass_ua` (edit text).
  - All of them map, and list, switch and edit-text changes persist and read back.
  - Stage 2's count said 2 switches; it is corrected above.
- **Aniyomi's `AnimeCatalogueSource.getHosterList`/`getVideoList(hoster)` call themselves** (the Aniyomi app's `source-api` has the same two lines). `AnimeHttpSource` overrides both, and the app (and now Madoyomi) never asks a non-HTTP source for hosters, so they are unreachable.
- Hoster use in the top 20 (whether `getHosterList` is declared, lazy hosters) was not measured here; the Stage 5 harness reports it per extension.
- **Test isolation (fixed here).** The madoyomi scratch-database tests restored the database after a test with `previousPrimary?.let { TransactionManager.defaultDatabase = it }`. That broke other tests:
  - `primaryDatabase` is the explicit default, or else the newest connection. Restoring it made an *implicit* primary explicit, for example `ApplicationTest`'s shared `mem:test` or another test's scratch database.
  - An explicit default beats every later `Database.connect`, so a later scratch test wrote into the wrong database.
  - It only worked because every `madoyomi.*` class ran before any `ApplicationTest` subclass. Gradle runs previously failed classes first, which showed it: `OpdsExploreNsfwTest` then `ExtensionLifecycleTest` gave 23 failures, and the reverse order made Opds count a 4th source.
  - Now each scratch database is the explicit default while its test runs, and the previous explicit default (`TransactionManager.defaultDatabase`, possibly none) is restored. This applies to `ExtensionLifecycleTest`, `ExtensionLifecycleLiveTest`, `SignatureTrustTest`, `RepoServiceTest`, `RepoServiceLiveTest`, `MigrationsFromEmptyDbTest` and the new `ScratchDatabase`; `api`'s `ApiGraphQLTest` already did this.
  - The Stage 3 DB tests use `ScratchDatabase` (`AnimeTestSources.kt`) rather than `ApplicationTest`.
- Task 6 (the two `Dex2jarRepair` patterns of the manga sample) was not done: left to the Stage 6 fix round (user decision 2026-11-05).

**2026-11-05: GraphQL operations (Stage 4).**
- **`tools/smoke-api.ps1` against the real repos passes all 24 steps** (the jar as the Tauri shell starts it, a fresh data dir, GraphQL and the binary REST routes only; `MADOYOMI_PORT` after 7.6 s). The new AnimeGG chain:

  | Step | Result |
  |---|---|
  | install | `isLoaded = true` at install (Phase 1's smoke expected `false`: anime wasn't loaded then) |
  | `animeSources` | `2247987965040486818`, `supportsLatest = true`, `isConfigurable = true`, 2 filters |
  | `fetchSourceAnime POPULAR` | 125 entries, the same ids on a second call, 7.6 s for both |
  | `fetchAnimeAndEpisodes` | "One Piece", 1167 episodes, no error |
  | `fetchEpisodeVideos` (newest, "Episode 1180") | 1 hoster (`no_hoster_list`, lib 14), 3 videos, headers `User-Agent` and `referer` |
  | `resolveEpisodeVideo` | `[SUBBED] AnimeGG:1080p`, initialized, its URL on `www.animegg.org` (23 ms) |
  | thumbnail | `image/jpeg`, 67,554 bytes from the source (its headers), the same from the cache with `?token=` |
  | `compatIssues` | none |

  The manga chain (MangaPill) is unchanged: 10 popular, "Berserk" with 405 chapters, 92 pages, the page image as `image/jpeg` from the source and from the cache. The repos list 249 anime and 1458 manga extensions now (247 and 1454 on 2026-11-03).
- **AnimePahe through GraphQL** (`AnimeExtensionApiTest`, installed from a loopback repo, no network): it loads at install, its 7 preferences come back with the union's types (3 switches, 3 lists, 1 edit text), and a switch and a list change persist. `extension.compatIssues` lists its 7 static `STUB`s (the `EditTextPreference` dialog, Stage 1). kickassanime's 7 filters come back as upstream's union types.
- **Schema notes.** graphql-kotlin takes a nested class's simple name as the GraphQL name, so `ExtensionCompatLog`'s `IssueKind`/`Origin` got `@GraphQLName`s (`ExtensionCompatIssueKind`/`ExtensionCompatIssueOrigin`) rather than a bare `Origin` in the schema. Enums outside the schema's `supportedPackages` work (upstream's `ContentWarning` and `UpdateStrategy` already do), so `FetchType` and `AnimeUpdateStrategy` are the extension API's own. `KotlinDataLoader` needs a non-null value type; an unknown key still resolves to null through the Java `DataLoader`, as upstream's `MangaDataLoader` does.
- **Smoke refactor gotcha.** In PowerShell, a function's pipeline output is its return value, so Gradle's output inside `Resolve-MadoyomiJar` became the jar path (the first run failed with "The filename or extension is too long"). Build output goes to `Out-Host` now; the Stage 5 harness has the same trap.

**2026-11-06: compat harness (Stage 5).** `tools/compat-test/run-compat.ps1` on the real sidecar jar, a fresh data dir, and yuzono (249 anime extensions). The report is `docs/research/anime-compat.md`, from run 2.

| | Run 1 (before the fixes) | Run 2 (after) |
|---|---|---|
| Pass, whole chain incl. resolve | 17 of 20 | **18 of 20** |
| SAFE only | 7 of 9 | 8 of 9 |
| Failures | animepahe (Cloudflare), hentaihaven (video host down; the probe misclassified it as Cloudflare), anizone (`StackOverflowError`) | animepahe (CLOUDFLARE), hentaihaven (SITE_DOWN) |
| JVM aborts, hs_err, OutOfMemoryError | none | none |
| Exit after stdin closed | 0 | 0 |
| Duration | 6.9 min | 6.6 min |

- **anizone: a host difference, not an extension bug.** Popular threw `StackOverflowError`, 1024 frames of `java.util.regex` (`Branch`/`GroupHead`/`Loop`). The extension's `JSON\.parse\('((?:[^'\\]|\\.)*)'\)` runs over a 41K-character literal on the popular page (22K on the home page).
  - Java's regex recurses once per repetition of the group. Android's ICU regex is iterative, so the extension works on a phone.
  - A single-file Java check on JDK 21 over the same pattern:

    | Input | 1 MB stack | 4 MB | 16 MB | 64 MB |
    |---|---|---|---|---|
    | 2K characters | overflows | ok | ok | ok |
    | 10K | overflows | overflows | ok | ok |
    | 41K | overflows | overflows | overflows | ok |
    | 250K | overflows | overflows | overflows | ok |

  - Fixed: source calls run on 128 MB-stack threads (§9 Stage 5; `AnimeSourceCallsTest` overflows a 1 MB thread with the pattern and passes on the dispatcher). anizone passes in 5.7 s.
- **Cloudflare:**
  - only animepahe is blocked. Its own WebView interceptor waits about 60 s, then upstream's `CloudflareInterceptor` throws "Cloudflare bypass currently disabled";
  - that surfaced as `INTERNAL` and is `SOURCE_FAILED` with `details.reason = "CLOUDFLARE"` now (`ErrorModelTest`, through the real interceptor against a loopback "Cloudflare" site);
  - 19 of the 20 sites are served by Cloudflare. PowerShell's probe gets a challenge on 3 (animepahe, missav, hentaihaven), and OkHttp only on animepahe. So a plain probe can't decide a Cloudflare failure; the classifier now needs the server's word or a 403/503.
  - Outcome: 1 blocked, 18/20 without a solver. **Deferred, no Stage 5b** (§9).
- **hentaihaven: its video host is unreachable.** Browse, details and episodes work. The only hoster, Octopus, needs `octopusmanifest.org`, which resolves (195.16.73.90) but refuses TCP 443 from this network (`Test-NetConnection`). Triaged `site-down`.
- **Hoster use** (the open question of Stage 3), over the 19 extensions that reached videos:
  - 12 declare their own hoster list (1 to 10 hosters), and 7 get Aniyomi's `no_hoster_list` (hanime, oppaistream, missav, onetwothreeanime, kickassanime, streamingcommunity, animeonsen);
  - **none has a lazy hoster**, so `fetchHosterVideos` wasn't needed in the run;
  - every resolve was immediate: the videos came back `initialized`;
  - the probe of the resolved URL with the video's headers answered 200/206 for all 18, mostly HLS (`mpegurl`), plus one DASH manifest (animeonsen) and plain mp4 (rule34video).
- **Extensions run local HTTP servers inside the JVM.** missav (`localhost`), onetwothreeanime and reanime (`127.0.0.1`) return video URLs served by a proxy they start themselves. reanime's run added about 60 threads, which went away when they were idle. Phase 4 has to keep the source instance alive while mpv plays, and mpv must be allowed to reach loopback.
- **Lib versions** (installed, the repo's current): 14 for 6 extensions, 16 for 14. They're the same as the Stage 2 snapshot.
- **Process health (one JVM for the whole run; the table is in the report):**
  - the live heap after a full GC grew from 102 MB (repo added) to 122 MB after 20 extensions, and was 113 MB after uninstalling them;
  - metaspace grew from 77 to 102 MB;
  - OS threads peaked at 239. That counts the new source pool (at most 64, idle ones end after 60 s) and the extensions' proxies, and it was 195 at the end;
  - private bytes went from 694 to 799 MB. The pool's 128 MB stacks are only reserved, which private bytes don't show;
  - no abort, no hs_err, exit code 0.
  - Nothing grows per extension beyond its own classes, so **one JVM stays** as far as crashes, threads and memory go (phase-1 §8); the Stage 6 verdict closes it.
- **Class loaders survive uninstall.**
  - Run 1: after uninstalling all 20, 18 extension class loaders were still alive after a full GC, and 46 classes were unloaded. The two that were collected (animepahe, anizone) are the two that never reached videos: `AnimeVideos` kept the last fetch, and so the source, for up to 10 minutes. Fixed (`AnimeGetSource` drops the fetches with the source; `AnimeVideosTest` checks that a dropped source is collected).
  - Run 2: 15 alive, 253 classes unloaded. **Other holders remain**, about 1.2 MB of metaspace per extension that isn't unloaded.
  - Not a crash risk; it matters for uninstall and update over a long session. The holders were found and fixed in Stage 6 (below): 1 loader left.
- **Runtime compat issues: none.** The compat log only has the static `STUB`s of the scan (the `EditTextPreference` dialog), and no extension hit a missing class or member at runtime.
- **Harness gotcha.** PowerShell variables are case-insensitive, so the script's `$results` was its `[string]$Results` parameter, and `+=` concatenated the result objects into one string (the first trial). Renamed `$extResults`.

**2026-11-06: fix round (Stage 6).** The final run is `docs/research/anime-compat.md` (20:18, 12.6 min with a 330 s idle wait at the end). The manga sample is `docs/research/manga-compat.md`, and the scan `docs/research/compat-scan.md`.

| | Stage 5, run 2 | Stage 6 final run |
|---|---|---|
| Pass, whole chain incl. resolve | 18 of 20 | **18 of 20** (the same two failures) |
| Preference round-trip (live) | - | 19 of 20 extensions, 22 of 23 sources (hentaihaven has nothing to change) |
| Filtered search (live) | - | 18 of 18 extensions with filters |
| Extension class loaders alive after uninstalling all 20 | 15 (1402 classes) | **1** (reanime, 240 classes), the same after 330 s |
| Classes unloaded | 253 | 1637 |
| Live heap: baseline → 20 installed → all uninstalled | 102 → 122 → 113 MB | 101 → 124 → 110 MB |
| OS threads: peak / after the idle wait | 239 / 195 | 238 / 81 |
| JVM aborts, hs_err, OutOfMemoryError, exit code | none, none, 0, 0 | none, none, 0, 0 |

- **What kept the class loaders, from heap dumps** (`tools/compat-test/lib/HprofLoaderRoots.java`, the shortest strong path from a GC root). The repro was 3, then 6, then 2 extensions on the jar; each fix was confirmed by the next dump.

  | Holder | Kept | Fix |
  |---|---|---|
  | NanoHTTPD listener threads, blocked in `accept()`. missav, onetwothreeanime, reanime and streamingcommunity bundle their own R8-renamed copy, so tracking Madoyomi's `HttpServer` wouldn't see them | its own loader | `ExtensionThreads`: interrupt, then close the server socket the thread's `Runnable` holds |
  | streamingcommunity's own `ThreadPoolExecutor` (`streamingunity-subs`), idle in `take()` | its own | the pool is shut down when its thread factory is the extension's |
  | `Thread.inheritedAccessControlContext` of a thread created with an extension's frames on the stack: a source-pool thread, which then started reanime's server (holding kickassanime), and a `DefaultDispatcher` worker (holding reanime) | another extension's loader too | pool threads created privileged; every live thread's inherited context cleared at unload |
  | the Injekt `Json` singleton's `DescriptorSchemaCache`, keyed by each extension's serial descriptors | 4 of 5 in one dump | `Json` is a factory: each injection gets its own instance |
  | idle pooled HTTP/2 connections: the TLS context captured the handshake's access-control context | for up to OkHttp's 5-minute keep-alive | the shared pool's idle connections are evicted at unload |
  | (found reading the code, not in a dump) an extension's preference listeners in `JavaSharedPreferences`; a lazy load overtaken by an uninstall; a video fetch stored after `dropStale`; a manga source's cached preference screen and filter list | | weak listeners as on Android; `unloadCount`; a re-check; dropped at unload |

  - The first try matched stack frames by class name, and R8's names repeat across extensions (`w2`, `v7`), so one extension's uninstall stopped another's threads. Loaders are named now, and frames are matched by the loader's name.
- **The one left: reanime.** Its last holder is an OkHttp HTTP/2 reader thread (`OkHttp streamwish.com`, one of its video hosters). The connection's TLS context has reanime's protection domain.
  - It was still alive 330 s after the uninstall, past OkHttp's 5-minute keep-alive, and the eviction at unload didn't close it. So it isn't idle: most likely a stream reanime's proxy opened and never closed when its server stopped. It may also belong to a client of reanime's own (its own connection pool).
  - This is the extension's own connection. Closing a busy connection could break another extension's request to the same host, so it is left. The cost is 240 classes, about 1.2 MB of metaspace, until restart. The tests: `ExtensionLifecycleTest` checks that the class loaders of an uninstalled AnimeGG and MangaPill are collected, and `ExtensionThreadsTest` covers each kind of thread.
- **Threads stopped at the final run's uninstall** (server log):
  - 4 NanoHTTPD listeners (missav, onetwothreeanime, reanime, streamingcommunity), closed through their sockets;
  - streamingcommunity's pool (4 workers), shut down;
  - no thread left running, and no other extension's thread touched.
- **Dex2jarRepair.** Both patterns of Stage 2 are fixed, and the scan shows 0 verify errors and 0 unrepaired sites over all 30 APKs (`Dex2jarRepairCompatTest` on the four).
  - mangadex: 8 constructors whose super call skipped `u` (R8 removed `u.<init>()V`), now rewritten to `u.<init>`, and `u` gets it back.
  - mangafire, comix, mangakakalot: dex2jar's `NewTransformer` had kept `new T` and named only `S` in the `<init>` call (javap: `new n0` … `invokespecial Object.<init>`); the call is rewritten to `T.<init>`.
  - The manga sample with no reachable issue and no verify error went from 6 to 7 of 10. mangadex, mangafire and comix still have their UI or WebView stubs.
- **Extension API classes used** (scan): every one of the top 20 is a plain `AnimeHttpSource` and every one is a `ConfigurableAnimeSource`; streamingcommunity is the only `AnimeSourceFactory`, and none uses `ParsedAnimeHttpSource` (AnimeGG doesn't either). The manga sample: 10 `HttpSource`, 8 `ConfigurableSource`, 4 `SourceFactory`, no `ParsedHttpSource`.
- **Manga sample, live** (`run-compat.ps1 -Kind Manga`, 1.3 min):
  - 9 of 10 pass through popular, details + chapters, pages and the first page image through the server;
  - every extension loader was collected after the uninstall (0 alive, 705 classes unloaded);
  - comix fails at popular with "CEF is disabled". It renders comix.to in an `android.webkit.WebView`, and the sidecar runs with KCEF off (phase-1 sidecar defaults). Triaged `webview` and deferred with the shim, not counted as a regression.
- **Filters and preferences.** The API test sends every filter state of the three extensions through GraphQL. hanime's sort and 64 tri-states included, all arrive exactly. The live preference step changed list values (mirror, quality, title language) and switches (rule34video's `uploader_filter_enabled`), and each was read back from a new instance.
- **One JVM: the verdict (phase-1 §8).** Over two full runs (Stage 5 and Stage 6) the top 20 ran in one JVM with no abort, no hs_err and no OutOfMemoryError. The live heap stayed within about 25 MB of the baseline, and threads went back to baseline after the idle wait. The uninstall leak is fixed down to one connection the extension keeps open. **One JVM stays; no process isolation.**
- **Gotchas.**
  - A non-capturing Kotlin lambda is a cached singleton, so a "can this listener be collected?" test needs a capturing one.
  - GraphQL union fields (`name` on `Filter`) only work inside `... on` fragments.
  - In PowerShell, the harness's `$tries` local was its own `-Tries` parameter again (renamed `$attempt`).
