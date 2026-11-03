# Phase 1 — Backend foundation

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 0](phase-0-spikes.md) · Next: [Phase 2](phase-2-anime-sources.md)

## 1. Goal

Turn the Phase 0 findings into a real backend in `server/`: a Suwayomi-based Kotlin/JVM service, rebranded as Madoyomi, that knows about both **anime** and **manga**, manages user-added repos in both index formats, and installs, updates, trusts and uninstalls extensions of both types through the API. No anime source calls yet (that is Phase 2). This phase builds the plumbing they run on.

## 2. Depends on / inputs

- Phase 0 complete; the fork-vs-library and REST-vs-GraphQL decisions are recorded.
- `docs/research/suwayomi-notes.md` and `docs/research/index-pb.proto` from Phase 0.
- The sidecar handshake from Phase 0 S5 (`MADOYOMI_PORT=<port>` on stdout).

## 3. Deliverables

- `server/` Gradle multi-module project:
  - `server/core`: DB, settings, shared models, migrations
  - `server/ext-host`: APK download, dex2jar, classloader, Android stubs (from Suwayomi)
  - `server/ext-manga`: Mihon source adapter (from Suwayomi)
  - `server/ext-anime`: Aniyomi adapter (skeleton only in this phase)
  - `server/api`: HTTP + WebSocket layer
- Anime domain: tables and entities for anime, episodes, anime sources and anime extensions.
- Repo manager: JSON (anime) and protobuf (manga) parsers, plus add/remove/refresh.
- Extension lifecycle: install, update, uninstall, trust by signing-cert hash.
- API: the repo, extension, trust and source operations in Suwayomi's GraphQL API at `/api/graphql` (subscriptions over `graphql-transport-ws`), mapped from the PLAN.md §5 REST sketch in step 17; `/health` stays plain HTTP.
- `NOTICE` / `THIRD_PARTY.md` with Suwayomi (MPL-2.0) and Aniyomi/Mihon (Apache-2.0) attribution.

## 4. Detailed steps

### Backend — fork & structure
1. Fork Suwayomi-Server into `server/` (keep git history as a subtree or note the upstream commit in `server/UPSTREAM.md`).
2. Rename packages/app name/data dir to Madoyomi (`%APPDATA%/Madoyomi`). Keep MPL-2.0 headers on Suwayomi-derived files.
3. Split into the modules above. Move Suwayomi's extension loading into `ext-host` and the manga source code into `ext-manga`.
4. Turn off the Suwayomi parts we don't need (bundled WebUI, its own tray/launcher) behind flags or delete them.
5. Startup: bind `127.0.0.1:0`, print `MADOYOMI_PORT=<port>`, and accept a `--data-dir` argument plus a shared auth token passed in by Tauri (reject requests without it).

### Backend — domain & DB
6. Add a `type` discriminator (`MANGA` | `ANIME`) to repos and extensions. **Design note:** repos and extensions share upstream's `EXTENSIONSTORE` and `EXTENSION` tables, with a `type` column, because their lifecycle code (refresh, install, update, trust, uninstall) is shared. Sources are typed by table instead: manga in `SOURCE`, anime in `ANIME_SOURCE`. That keeps upstream's manga-only `SOURCE` queries untouched. Upstream queries on the shared tables that assume manga filter on `type = MANGA` (listed in `server/UPSTREAM.md`, "Domain & DB").
7. Add anime tables: `anime`, `episode`, `anime_source`, plus `anime_history` and `anime_category` stubs for Phase 6. Keep them separate from the manga tables.
8. Write migrations for the new tables, and test that upgrading from an empty DB works.

### Backend — repo manager
9. `RepoIndexParser` interface with `JsonIndexParser` (yuzono `index.min.json`) and `ProtobufIndexParser` (Keiyoushi `index.pb`), both producing a common `ExtensionListing` model. The parsers take the repo kind, because Aniyomi's `index.pb` (yuzono) uses a different schema from Mihon's (§10).
10. Detect the format from the URL suffix or content sniffing; let the user override it.
11. Repo CRUD + refresh (manual, plus on app start). Cache the last good index if a refresh fails.

### Backend — extension lifecycle
12. Install: download APK → verify package name and version code → extract the signing cert SHA-256 → dex2jar → store under `data/extensions/{type}/{pkg}/`.
13. Trust: untrusted extensions are installed but **not loaded** until the user trusts their cert hash. Store trusted hashes in a table.
14. Update: compare the version code with the repo listing and mark "update available"; updating re-runs install and keeps trust if the cert is unchanged.
15. Uninstall: unload the classloader, delete the files and remove the source rows.
16. Anime extensions: install/trust/list only. Instantiating sources comes in Phase 2.

### API
17. Implement the repo and extension endpoints from PLAN.md §5 in the API style chosen in Phase 0: Suwayomi's GraphQL API, extended. Everything is at `/api/graphql`; subscriptions use the `graphql-transport-ws` protocol on the same path.

    | PLAN.md §5 REST sketch | GraphQL |
    |---|---|
    | `GET /api/repos` | `extensionStores(condition: {type})` (existing query; the type gains `type`, `format`, `signingKeyTrusted`, `lastRefreshedAt`, `lastError`) |
    | `POST /api/repos` | `addExtensionStore(input: {indexUrl, type, format})` → store + signing key (extends the existing mutation) |
    | `DELETE /api/repos` | `removeExtensionStore(input: {indexUrl})` (existing) |
    | refresh | `fetchExtensions(input: {type})` (existing, made type-aware) |
    | `GET /api/extensions?type=` | `extensions(condition: {type})`; `ExtensionType` gains `type`, `signatureHash`, `isTrusted`, `isLoaded`, `isTorrent` |
    | `POST …/install\|update\|uninstall` | `updateExtension(input: {id, patch: {install\|update\|uninstall}})` (existing, type-aware) |
    | `POST …/trust` | new: `trustExtension(input: {pkgName})` and `trustSignature`/`untrustSignature(input: {hash})`; query `trustedSignatures` |
    | `GET /api/sources?type=` | `sources` (manga, existing) + new `animeSources(condition)` → `AnimeSourceType` (id, name, lang, extension, baseUrl) |
    | `/ws` events | subscriptions `extensionInstallProgress(pkgName?)` and `extensionStoreRefreshed(type?)` |
    | `/health` | plain HTTP, no token |
18. Events are GraphQL subscriptions, not a separate `/ws`: `extensionInstallProgress` (from the lifecycle's progress flow) and `extensionStoreRefreshed` (one event per refreshed repo).
19. Error model: every GraphQL error carries `extensions = {code, details}` (no stack traces in messages; they go to the log; unknown failures are `INTERNAL`), and REST/binary endpoints answer errors with the JSON body `{code, message, details}`.

### Tests & tooling
20. Unit tests for both index parsers, using fixture files in `server/*/src/test/resources`.
21. Integration test: add a repo → list → install → trust → list sources (manga), using a small fixture APK or a mocked download.
22. `./gradlew build` runs the tests. Add a `tools/run-server.ps1` dev script.

## 5. Checklist

### Fork & structure
- [x] Suwayomi forked into `server/`; upstream commit recorded in `server/UPSTREAM.md`
- [x] Rebranded (app name, data dir, config prefix, logs, User-Agents, version). Package names are kept upstream by decision; new code goes under `madoyomi.*` (see `server/UPSTREAM.md`)
- [x] MPL-2.0 headers kept; `NOTICE` / `THIRD_PARTY.md` added (moved upstream files are byte-identical renames except the few `// Madoyomi:` edits; the ported `ext-anime` files carry Apache-2.0 headers and `server/ext-anime/NOTICE.md`)
- [x] Modules created: `core`, `ext-host`, `ext-manga`, `ext-anime`, `api` (`api → core → {ext-manga, ext-anime} → ext-host → {AndroidCompat, server-config}`). Deviation from §3: GraphQL and Javalin stay in `core`, because about 30 non-GraphQL upstream files import GraphQL types. `api` holds the entry point, the sidecar HTTP layer and, from Stage 7, Madoyomi's API additions. `PackageTools` (dex2jar wrapper, source loading) also stays in `core`, because it needs `ApplicationDirs`. Old → new paths are in `server/UPSTREAM.md` "Moved paths".
- [x] Unused Suwayomi features removed or disabled (WebUI, tray, browser/Electron launch, KCEF by default, OPDS, SyncYomi/KOReader, update checks, app mutex)
- [x] Random port + `MADOYOMI_PORT` handshake + auth token (plus `--data-dir`, loopback-only bind, Tauri-only CORS, stdin watchdog, `GET /health`)

### Domain & DB
- [x] `type` discriminator on repos/extensions; sources typed by table (see step 6). Migration `M0069_MadoyomiExtensionKinds`; upstream manga queries filter on `type = MANGA` (`server/UPSTREAM.md`, "Domain & DB"; the GraphQL API takes a `type` since Stage 7)
- [x] Anime tables + migrations (`M0070_AnimeTables`: `ANIME_SOURCE`, `ANIME`, `EPISODE`, plus `ANIME_HISTORY` and `ANIME_CATEGORY` stubs; `TRUSTED_SIGNATURE` in `M0069`). Numbering and the upstream-collision rule are in `server/UPSTREAM.md`, "Database migrations"
- [x] Migration test from an empty DB (`MigrationsFromEmptyDbTest`: empty DB, second run is a no-op, upgrade from a pre-Madoyomi DB, Madoyomi migrations re-runnable, Exposed tables match the schema)

### Repo manager
- [x] `JsonIndexParser` (anime): legacy `index.min.json` with the `torrent` flag, `repo.json`, JSON v2 (`madoyomi.core.repo`)
- [x] `ProtobufIndexParser` (manga): Mihon `index.pb` via upstream's DTO, plus Aniyomi's `index.pb` schema for anime (§10)
- [x] Format detection + override (`AUTO` = upstream sniffing + `index_v2`; `JSON`/`PROTOBUF` force a parser; stored per repo)
- [x] Repo add/remove/refresh with last-good cache (`RepoService`: type check, store-key trust, shared-key-safe remove, startup + on-demand refresh, `last_error`; see §9)

### Extension lifecycle
- [x] Install (download, verify, cert hash, dex2jar, store): `madoyomi.core.extension.ExtensionLifecycle`, APK-only with apksig (`madoyomi.exthost.ApkInspector`), the `Dex2jarRepair` post-pass, `extensions/{manga|anime}/{pkg}/`; progress flow for Stage 7 (see §9, §10)
- [x] Trust prompt flow: untrusted extensions are not loaded (one gate in front of upstream's loader, `ExtensionLoadGate`; USER trust via `trustSignature`/`trustExtension`, which loads them; in GraphQL since Stage 7)
- [x] Update detection + update (upstream `hasUpdate`; an untrusted new certificate is refused with `SIGNATURE_CHANGED`)
- [x] Uninstall (unload + delete, `SOURCE`/`ANIME_SOURCE` rows, upstream's delete-on-next-start fallback)
- [x] Works for both anime and manga APKs (real AnimeGG/AnimePahe/MangaPill in the tests; live installs from yuzono and Keiyoushi)

### API
Mapped from the REST sketch to GraphQL (step 17).
- [x] Repos: `extensionStores(condition: {type})`, `addExtensionStore(input: {indexUrl, type, format})`, `removeExtensionStore`, `fetchExtensions(input: {type})`
- [x] Extensions: `extensions(condition: {type})` with `type`, `signatureHash`, `isTrusted`, `isLoaded`, `isTorrent`
- [x] `updateExtension(input: {id, patch: {install|update|uninstall}})` for both kinds (single id: says what it can't do; batch `updateExtensions` keeps upstream's skip semantics)
- [x] Trust: `trustExtension`, `trustSignature`/`untrustSignature`, `trustedSignatures`
- [x] Sources: `sources` (manga) + `animeSources(condition)` (anime sources from the listing)
- [x] Subscriptions over `graphql-transport-ws`: `extensionInstallProgress(pkgName?)`, `extensionStoreRefreshed(type?)`
- [x] Uniform error model (GraphQL `errors[].extensions = {code, details}`, REST `{code, message, details}`)

### Tests
- [x] Parser unit tests with fixtures (`core/src/test/kotlin/madoyomi/core/repo/`, text fixtures in `core/src/test/resources/repo/`; protobuf stored as base64 of the gzipped bytes)
- [x] Install → trust → list integration test (`api`: `ExtensionApiIntegrationTest`, both kinds, real APKs from a loopback repo; see §9)
- [ ] `./gradlew build` passes

## 6. Exit criteria

- [ ] From the API alone (curl/HTTPie), a user can add the yuzono and Keiyoushi repos, list available extensions of both types, install one of each, trust them, and see them as installed.
- [ ] Manga sources from installed extensions still browse and read (no regression from Suwayomi).
- [ ] All tests pass in `./gradlew build`.

## 7. Risks & notes

- Big fork, big merge cost later: record the upstream commit and keep Madoyomi changes in clearly separate files where possible (PLAN.md §6, "License mixing").
- Extensions are arbitrary code. Never load an extension before trust (PLAN.md §6, "Extensions are arbitrary JVM code").
- If Suwayomi already parses `index.pb`, reuse its parser instead of writing a new one.

## 8. Decisions closed here

- One JVM process for both extension types, or isolated processes? (final answer; PLAN.md §7)
- DB choice confirmed (Suwayomi's existing DB vs. SQLite).

## 9. Decision record

Repo manager (Stage 5), extension lifecycle (Stage 6) and API (Stage 7). The rest of the phase's decisions are added at the end of the phase.

| Decision | Why |
|---|---|
| Anime repos persist only in the DB (`EXTENSIONSTORE`, `type = ANIME`). Upstream's `extensionStores` setting stays a manga-only mirror. | No new setting and no generated-settings changes; the DB is the source of truth for both kinds, and the GraphQL `extensionStores` query reads it. |
| A pkg offered by two repos of the same kind keeps upstream's rule: one `EXTENSION` row, highest version code wins (ties: the older repo). | `pkg_name` is unique; trust is per signature, not per repo, so this doesn't affect trust. |
| A signing key that changes on refresh is not applied; the refresh fails (`last_error`) until the user re-adds the repo. | Same as Mihon. A compromised repo can't swap keys silently. |
| Adding checks every pkg against the repo type (`.animeextension.` vs `.extension.`) and rejects a mismatch (`REPO_TYPE_MISMATCH`). A refresh drops mismatched entries with a warning. | A wrong-type repo is a user mistake, best reported at once; one bad entry later shouldn't freeze a repo. |
| `ANIME_SOURCE` rows come from the listing at install (Stage 6), not at refresh. | Same as manga `SOURCE` rows today. |
| The existing `addExtensionStore`/`removeExtensionStore`/`fetchExtensions` mutations go through `RepoService` (manga). Stage 7 adds `type`/`format`. | They are the only entry points, and they must follow the trust model and the last-good cache. |
| A refresh never grants trust. Repos added before Stage 5 stay untrusted (`signing_key_trusted = false`) until they are re-added. | Trust is only ever granted by a user action. |
| Removing a repo revokes per signature: the row of a key shared with another trusted repo moves to that repo, and USER trust is never touched. | `TRUSTED_SIGNATURE.hash` is the primary key (one row per key). |

Extension lifecycle (Stage 6):

| Decision | Why |
|---|---|
| Install always downloads the APK and verifies it with apksig; Keiyoushi's `jarUrl` is ignored, and sideloaded jars are refused (`SIGNATURE_INVALID`). | A jar has no signature, so trust by signing certificate needs the APK. |
| Every converted jar goes through a dex2jar post-pass, `Dex2jarRepair` (user decision 2026-11-03, over loading Keiyoushi's `jarUrl`). | dex2jar turns R8-optimised dex into bytecode the JVM rejects (§10). The repair keeps the code the user trusts the one that runs. |
| `signature_hash` = SHA-256 of the first signer certificate, lowercase hex. | The same value and format as a repo's `signingKeyFingerprint`, so STORE trust needs no mapping. |
| The listing (expected pkg/version, sources) is re-fetched from the extension's repo at install time; no cache, nothing persisted. | Always matches the APK being installed and survives restarts; one index download per install; no migration. |
| One gate in front of upstream's only extension loader (`PackageTools.loadExtensionSourcesWithLockHeld`): a jar loads only with a permit (its verified hash) that is in `TRUSTED_SIGNATURE` at that moment. | Covers `GetSource` (and so GraphQL `SourceType`, browse, the updater), install and any path that bypasses Madoyomi's install. |
| Untrusted manga and all anime extensions are never instantiated; their source rows come from the listing. Trusted manga loads and reconciles `SOURCE` with the live sources (upstream `setupJar`). | Extensions are arbitrary code (§7). Anime loading is Phase 2. |
| An update signed by another, untrusted certificate is refused (`SIGNATURE_CHANGED`) before anything is written; the installed version stays as it was. Trusting the new certificate lets the retry through. | "The old version stays loaded, the new one is untrusted" without keeping two versions side by side. |
| Upstream-layout installs (no `signature_hash`) are untrusted and never loaded; uninstall + install, or an update, replaces them. Repos added before Stage 5 have no trust row, so their installs come out untrusted. | The converted jar has no signature left to check, and a refresh never grants trust. |
| User trust is a USER row; trusting a key a repo vouches for detaches its row from the repo. Untrusting only withdraws USER trust. Re-adding a repo also loads the installed extensions its key now covers. | `hash` is the primary key; the user's choice must outlive the repo, and the repo's must outlive an untrust. |
| Jars live in `<data>/extensions/{manga|anime}/{pkg}/{pkg}-v{versionName}.jar`; icons stay in upstream's `extensions/icon/` cache. | Upstream's icon endpoint serves that cache; pkg names of the two kinds never collide. |

API (Stage 7):

| Decision | Why |
|---|---|
| Madoyomi's GraphQL operations live in `api` and reach upstream's schema through one hook in `TachideskGraphQLSchema.kt`, which finds them with `ServiceLoader` (`GraphQLSchemaContribution`), not Koin. | Schema generation starts (`GraphQLSchemaProvider.init()`, async) before `applicationSetup()` starts Koin, as in upstream's `Main`; ServiceLoader has no ordering dependency, and shadowJar already merges service files. |
| A null `type` in a condition or input means "both kinds" (`extensions`, `extensionStores`, `fetchExtensions`); in `addExtensionStore` it means `MANGA`, and a null `format` means `AUTO`. | Usual GraphQL condition semantics; an add needs one kind, and `MANGA` keeps upstream's input valid. |
| Single-id `updateExtension` reports what it can't do (`NOT_FOUND`, `INSTALL_FAILED` for "already installed", "no update", "not installed"); batch `updateExtensions` keeps upstream's skip-what-doesn't-apply behaviour. | A client asking for one action should hear why it didn't happen; upstream's batch semantics are relied on (its `PermissionsTest` sends an unknown id and expects no error). |
| Every GraphQL error carries `extensions = {code, details}`, messages carry no stack traces, graphql-java's own errors are `BAD_REQUEST`, unknown failures `INTERNAL`; REST/binary errors are `{code, message, details}` with upstream's statuses. Added codes: `SOURCE_FAILED`, `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL`. One classifier (`ErrorClassifier`) serves both. | Clients branch on a stable code, never on message text; one mapping keeps GraphQL and REST in step. |
| A source that can't be loaded is diagnosed (`SourceAccess`: `NOT_FOUND`, or `UNTRUSTED` with the hash) instead of surfacing as upstream's null pointer or "Source not installed". | `GetSource.getSourceOrNull` swallows the reason; the trust prompt needs it. |
| Events are GraphQL subscriptions (`extensionInstallProgress`, `extensionStoreRefreshed`) on upstream's `graphql-transport-ws` endpoint, with no replay. | The protocol and endpoint already exist; a client subscribes before it starts the operation. |
| The integration tests run in `api` on the production singletons and network stack, against a loopback JDK `HttpServer` that serves `core`'s `TestRepo` fixtures and the real APKs; `api`'s tests use `core`'s test classes through the test source set's output. | No new dependency (okhttp `MockWebServer`) and the real HTTP path is covered; the resolvers use the `default` instances, which can't be swapped; one copy of the test helpers. |

## 10. Findings log

**2026-11-03: repo indexes (Stage 5).** No `protoc` was available, so a throwaway test walked the protobuf wire format and decoded with the DTOs.
- **yuzono `index.pb`** (`https://github.com/yuzono/anime-repo/raw/repo/index.pb`, reached from `repo.json`'s `index_v2`):
  - **It uses Aniyomi's schema, not Mihon's.** The store, contact and resources messages are the same, but in `Extension` field 7 is the content warning (1/2/3), **field 8 is a bool `is_torrent`**, and **field 9 is the repeated `Source`** (Mihon has the sources at field 8). Source fields: 1 id, 2 name, 3 language, 4 home URL, 5 mirror URLs (16 sources have some).
  - Upstream's Mihon DTO throws on it, so Suwayomi as it stands can't add yuzono with `AUTO`. Madoyomi has `AnimeNetworkExtensionStore` for it.
  - **The torrent flag is in the protobuf:** field 8 is set on exactly the 7 extensions with `"torrent": 1` in `index.min.json` (hentaitorrent, nyaatorrent, ptorrent, stremio, subsplease, torrentio, torrentioanime). So `is_torrent` comes from the index in both formats. Stage 6 can still cross-check it against the manifest (`tachiyomi.animeextension.torrent`).
  - 247 extensions, 301 sources, gzipped (15,970 bytes, 86,172 raw). The **same 247 pkgs and the same version codes as `index.min.json`** (Phase 0 also saw 247).
  - Content warnings: 175 SAFE, 5 MIXED, 67 NSFW. Libs: `14` ×197, `16` ×50.
  - Names have no `Aniyomi: ` prefix, which the JSON has. APK/icon URLs are on `cdn.jsdelivr.net` (the JSON's are relative to the raw GitHub repo).
  - Signing key `cbec121a…c45905`, the same as `repo.json`; badge `Yū`; Discord in the contact.
- **yuzono `index.min.json`:**
  - `torrent` is an int 0/1.
  - Source ids are JSON strings, which kotlinx decodes as `Long`.
  - `nsfw` is 0/1. As upstream does, 1 maps to MIXED, so the JSON can't tell MIXED from NSFW; the protobuf can.
- **Keiyoushi `index.pb`:**
  - 1452 extensions, 2365 sources (137 with mirror URLs), all 1452 with a `jar_url` (field 501).
  - Gzipped 106,214 bytes / 727,528 raw. Key `9add655a…4da2`.
  - Its `repo.json` has `index_v2` → `index.pb` and no `shortName`.
- **Live check** (`RepoServiceLiveTest`, `MADOYOMI_LIVE_REPO_TESTS=1`):
  - yuzono `AUTO` → `index.pb`, 247 listings / 7 torrent;
  - yuzono `JSON` → `repo.json` (legacy), 247 / 7;
  - Keiyoushi `index.min.json` `AUTO` → `index.pb`, 1452.

  Each repo is trusted as a STORE key and refreshes cleanly.
- kotlinx-protobuf can fail on truncated input with `IndexOutOfBoundsException`, not only `SerializationException`, so the parser treats any runtime exception as "not this format".

**2026-11-03: extension APKs (Stage 6).** `ApkInspector` (apksig 9.4.1) on the real APKs in `tools/apks/` (`tools/fetch-test-apks.ps1`):

| APK | Schemes that verify | minSdk | Signers | Cert SHA-256 | Repo key |
|---|---|---|---|---|---|
| yuzono AnimeGG v14.2 (code 2) | v2 only | 24 | 1 | `cbec121a…c45905` | yuzono `cbec121a…c45905`: **equal** |
| yuzono AnimePahe v16.56 (code 56) | v2 only | 24 | 1 | `cbec121a…c45905` | equal (one key signs all of yuzono) |
| Keiyoushi MangaPill v1.6.1 (code 106001) | v2 only | 26 | 1 | `9add655a…4da2` | Keiyoushi `9add655a…4da2`: **equal** |

- None has a v1 (JAR) signature, so apk-parser's `apkSingers`, which upstream's `getPackageInfo` reads, finds no certificate at all: upstream couldn't have checked these signatures even if it tried.
- The live run (`ExtensionLifecycleLiveTest`) installed AnimeGG from yuzono (`index.pb`) and MangaPill from Keiyoushi: both trusted by the repo key, AnimeGG with its listed `ANIME_SOURCE` (`2247987965040486818`, `https://www.animegg.org`), MangaPill loaded (`8448310129093543312`, `https://mangapill.com`).
- **Keiyoushi's manifest** (MangaPill): feature `tachiyomi.extension`, class `keiyoushi.source.Generated` (absolute, not `.`-relative), `tachiyomi.extension.nsfw=1`, `tachiyomix.contentWarning=1`, and `tachiyomix.extensionLib` as a binary-XML *float* that apk-parser prints as `{4:1070386381}` (1.6f). `ApkInspector` decodes it. Upstream's `getPackageInfo` would store the raw `{4:…}` text as the lib.
- **dex2jar mistranslates R8-optimised dex, in two ways.** Without a repair, 6 of MangaPill's 30 classes fail verification (`a`, `b`, `f`, `j`, `k`, `keiyoushi.source.Generated`; found by linking every class of the jar, which the tests now do).
  - **Constructor elision.** Keiyoushi builds with R8 optimisations that remove no-op constructors. The dex then does `new-instance LT;` + `invoke-direct LS;-><init>` with S an ancestor of T (e.g. `new-instance Lq;` + `Object.<init>()` for a synthetic `Function0` lambda). ART allows this, the JVM doesn't, and dex2jar 2.4.38 turns it into `new S`, so `keiyoushi.source.Generated` failed with `VerifyError` (and the object would have had the wrong class anyway, so turning off verification is no fix).
  - MangaPill has 12 such sites. `Dex2jarRepair` rewrites them and adds the missing constructors to 9 classes (`a, a0, b, b0, c, j, k, o, q`; `a`/`b` extend `c`, so `c` needs one too).
  - **Frames without the class hierarchy.** dex2jar computes stack map frames without knowing the APK's own classes, so in `f.intercept` a local holding an `a` or a `b` (both extend `c`) is typed `java/lang/Object`, and its use as `c` fails. This showed up only at runtime (browse): the JVM verifies a class when it is first used. `Dex2jarRepair` recomputes every class's frames with a hierarchy read from the jar and the host classpath. After both fixes, all 30 classes verify, and MangaPill installs, loads and browses.
  - The yuzono APKs have no elided constructors; their frames are recomputed as well, and all 37 classes of AnimeGG verify.
  - This is probably why upstream installs Keiyoushi's prebuilt `jarUrl`. That jar is a separate JVM build (different class layout), not the APK's dex, and it is unsigned.
  - Other R8 patterns may show up in other extensions; the Phase 2 compat harness should cover a manga sample too.
- **Sidecar smoke** (the existing `updateExtension(install)` mutation, real Keiyoushi):
  - On a fresh data dir, MangaPill installs to `extensions/manga/<pkg>/…-v1.6.1.jar` with `signature_hash` = the Keiyoushi key, trusted (STORE row), `is_loaded = true`, live `SOURCE` row `8448310129093543312`, and popular browses (10 mangas).
  - On a data dir made with the Stage 5 jar, which installed MangaPill upstream's way (Keiyoushi `jarUrl`, flat `extensions/` layout, no hash), the new jar refuses to load it (`UNTRUSTED`, so popular fails). Uninstall deletes the flat jar, and a reinstall gives the same result as on the fresh dir.

**2026-11-03: API (Stage 7).**
- **Sidecar smoke** (`smoke7`: the new jar against the real Keiyoushi and yuzono repos, over HTTP and `graphql-transport-ws`):
  - Fresh data dir: `addExtensionStore` with `type: MANGA` (Keiyoushi) and `type: ANIME` (yuzono) resolve to their `index.pb` (`format: AUTO`), both `signingKeyTrusted`; yuzono as `MANGA` is refused with `REPO_TYPE_MISMATCH`. `fetchExtensions` per type: 1454 manga, 247 anime (1701 without a type).
  - `updateExtension(install)` for MangaPill and AnimeGG: both trusted by their repo's key, MangaPill `isLoaded = true`, AnimeGG `isLoaded = false`; `extensionInstallProgress` delivered `DOWNLOADING → VERIFYING → CONVERTING → INSTALLED` for each over the WebSocket. `animeSources` lists AnimeGG (`2247987965040486818`), popular MangaPill browses (10 mangas), installing it again gives `errors[0].extensions.code = INSTALL_FAILED`, and `trustedSignatures` shows the two STORE keys with their repos and extensions. Closing stdin exits with code 0; the H2 rows (`EXTENSION`, `ANIME_SOURCE`, `SOURCE`, `TRUSTED_SIGNATURE`, `EXTENSIONSTORE`) and the jars under `extensions/{manga,anime}/<pkg>/` are as expected.
  - Data dir made with the Stage 6 jar (Keiyoushi + MangaPill installed): no migration is needed; MangaPill stays installed and loaded and browses, re-adding Keiyoushi and adding yuzono work, AnimeGG installs.
- graphql-java's own errors (syntax, validation) don't reach a `DataFetcherExceptionHandler`, but they do pass through an instrumentation's `instrumentExecutionResult`, which is where they get `code = BAD_REQUEST`. In graphql-java 26.1 that method's `parameters` argument is non-null in Kotlin.
- The server selects no WebSocket subprotocol (nothing in upstream or Madoyomi negotiates one). The smoke's .NET `ClientWebSocket` connects without requesting one (it worked); a client that requires `graphql-transport-ws` to be echoed back would reject the handshake (.NET does when a requested subprotocol isn't echoed; not tried here, worth checking with the Phase 3 client). PowerShell 5.1's `Invoke-WebRequest` also decodes the JSON as Latin-1 (the response has no charset), so the smoke prints yuzono's name `Yūzōnō` garbled; the server sends UTF-8.
- `ApplicationTest.databaseSetup` leaves its in-memory database as `TransactionManager.defaultDatabase`, so a test that connects a scratch database must set it as the default itself (core's `ExtensionLifecycleTest` doesn't extend `ApplicationTest` and never hit this).
- Upstream's `JavalinExceptionHandlersTest` asserted the plain-text 424 body; it now expects the JSON error body (a `// Madoyomi:` edit). Upstream's `CefTest` downloads a JBR from GitHub and failed once on a DNS error; it passes on a rerun.
