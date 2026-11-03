# Third-party code in this spike

- `src/main/kotlin/eu/kanade/tachiyomi/animesource/**` is copied from the Aniyomi app,
  `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/animesource/` at `aniyomiorg/aniyomi@9741444` (2026-09-14),
  licensed under the **Apache License 2.0**. Changes: `PreferenceScreen` is a plain typealias to AndroidCompat's
  `androidx.preference.PreferenceScreen` (was `expect`/`actual`), `@Stable` (Compose) removed, `logcat` replaced with
  `System.err`, and `awaitSingle` imported from Suwayomi (`suwayomi.tachidesk.manga.impl.util.lang`).
- `src/main/kotlin/mihon/core/common/extensions/JsonObject.kt` is copied from the same Aniyomi commit
  (`core/common`), Apache License 2.0, unchanged.

Spike code only: the real port goes into `server/ext-anime` in Phase 2 with proper headers and a NOTICE/THIRD_PARTY entry.
