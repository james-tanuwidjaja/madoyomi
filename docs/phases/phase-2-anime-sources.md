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
- [x] Package layout matches `extensions-lib` exactly (the Aniyomi app's `source-api`, ported in Phase 1. `ExtensionLinkCheck` resolves every reference into the API of AnimeGG (lib 14) and AnimePahe (lib 16); Stage 1, §10)
- [ ] `SAnime`, `SEpisode`, `Video`, `Track`, `AnimesPage`
- [ ] `AnimeSource`, `AnimeCatalogueSource`, `AnimeHttpSource`, `ParsedAnimeHttpSource`
- [ ] `ConfigurableAnimeSource`, `AnimeSourceFactory`
- [x] Source loading + stable source IDs (trusted extensions load at install, at trust and on first use, behind `ExtensionLoadGate`; `ANIME_SOURCE` is reconciled with the live sources. IDs come from the extension: `AnimeHttpSource.generateId`, MD5 of `name/lang/versionId`, e.g. AnimeGG `2247987965040486818`. Stage 1, §9)
- [x] Suspend and legacy `fetch*` APIs (as needed for the pinned version). One surface, the newest (lib 17), which keeps every lib-14/16 member; it is link-checked against both generations (§9)

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

### Stage plan (2026-11-04)

Each stage ends in something verifiable and a commit:
1. **Anime loader behind the trust gate**, plus `ExtensionLinkCheck`. *Done.*
2. **Top-20 list**: jsDelivr ranking → `tools/compat-test/top20.txt`, plus a manga sample.
   - Static link-check scan over them.
   - Missing-class/method logging at runtime (`EXTENSION_INCOMPATIBLE`), exposed as `compatIssues`.
   - Android/`androidx.preference` stubs fixed by frequency.
   - Networking checks.
3. **Core anime services**: migration `M0071_AnimeBrowse` (unique `(source, url)` / `(url, anime)`, the missing `SAnime`/`SEpisode` columns).
   - Browse with filters, upserted into `ANIME`.
   - Details plus episodes (reconcile, seasons).
   - Hosters → videos (not persisted).
   - Filter and preference mapping.
4. **GraphQL operations**: `fetchSourceAnime`, `fetchAnimeAndEpisodes`, `fetchEpisodeVideos`, `updateAnimeSourcePreference`, `anime`/`animes`/`episodes`, and `AnimeSourceType.filters`/`preferences`.
   - The `/api/v1/anime/{id}/thumbnail` route.
   - The PLAN.md §5 mapping table.
   - `smoke-api.ps1` anime steps, with the helpers moved to `tools/lib/MadoyomiServer.ps1`.
5. **Compat harness** `tools/compat-test/run-compat.ps1` and its first run → `docs/research/anime-compat.md`, including process health (JVM aborts, threads, memory).
   - Then the Cloudflare decision: possibly a KCEF solver stage, 5b.
6. **Fix round until ≥ 18/20**, filters/preferences round-trip for ≥ 3 extensions, the one-JVM verdict, docs closed.

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
