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
- [x] Anime-specific stubs added (`SharedPreferences`, `Context`, `Uri`, `Base64`, `Log`, …). The static scan over the top 20 finds no missing class or member. The remaining `"Stub!"`s are in the deep-link `UrlActivity`, one WebView SSL callback (hanime) and `EditTextPreference` binder code: `OnBindEditTextListener` bodies (checked with jadx in AnimePahe and StreamingCommunity) and the `TextWatcher`s they install (the others). Madoyomi never runs any of these (Stage 2, §10). The `androidx.preference` value accessors are implemented; runtime confirmation comes from the Stage 5 harness
- [x] Missing class/method logger (`ExtensionCompatLog`: class-loader hook, `AnimeSourceCalls` → `EXTENSION_INCOMPATIBLE`, link-check install warnings; Stage 2, §9. GraphQL `compatIssues` is Stage 4)
- [x] Shared OkHttp client, cookie jar, rate limiting (`AnimeExtensionRuntimeTest`: AnimeGG's client is `NetworkHelper`'s, with its cookie jar shared with `android.webkit.CookieManager` both ways; `rateLimit` keeps the jar)
- [ ] Cloudflare/WebView shim reachable from anime sources
- [x] Injekt/`NetworkHelper` lookups resolved (the extension's own `injectLazy<NetworkHelper>()` gets the app's instance; same test)

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
   - *Done* (2026-11-04), except the GraphQL `compatIssues` field, which moves to Stage 4 with the other operations (the core API is there: `ExtensionCompatLog`).
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
  - Neither pattern was seen before (Phase 1 only ran MangaPill). Both are open (Stage 3 prompt).
- **Runtime:** AnimePahe's preference screen sets up with no `"Stub!"` (3 `ListPreference`, 2 `SwitchPreferenceCompat`, 1 `EditTextPreference`), and the values round-trip through its `SharedPreferences` (`AnimeExtensionRuntimeTest`). AnimeGG's client is `NetworkHelper`'s, and its cookie jar and `android.webkit.CookieManager` see each other's cookies.
