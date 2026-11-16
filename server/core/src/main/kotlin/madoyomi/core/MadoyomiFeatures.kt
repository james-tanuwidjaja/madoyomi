package madoyomi.core

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Switches for upstream (Suwayomi) features that Madoyomi keeps in the tree but turns off, plus the
 * sidecar network constants. Upstream code checks these at the points marked `// Madoyomi:` (see
 * server/UPSTREAM.md). Disabling rather than deleting keeps upstream merges small.
 *
 * These are `val`s, not `const val`s, so guarded upstream code still compiles without
 * "condition is always false" warnings and can be switched back on in one place.
 */
object MadoyomiFeatures {
    /** OPDS catalog routes (`/api/opds/...`). */
    val opdsEnabled = false

    /** SyncYomi scheduling and KOReader progress pushes. */
    val syncEnabled = false

    /** Server self-update check against Suwayomi's GitHub releases. */
    val appUpdateCheckEnabled = false

    /**
     * Upstream's scheduled manga library update (`Updater.scheduleUpdateTask`, every `globalUpdateInterval` hours).
     * Madoyomi schedules its own for both kinds (`madoyomi.core.library.LibraryUpdateScheduler`, Phase 6 Stage 3).
     */
    val upstreamLibraryUpdateScheduleEnabled = false

    /**
     * Upstream's automatic download of new chapters (`Chapter.downloadNewChapters`), run on every chapter list fetch
     * into upstream's `DownloadManager`. Madoyomi's queue does it after a library update check instead
     * (`madoyomi.core.downloads.MangaDownloads.autoDownload`, Phase 6 Stage 4).
     */
    val upstreamAutoDownloadEnabled = false

    /**
     * Upstream's scheduled manga-only backup (`ProtoBackupExport.scheduleAutomatedBackupTask`, every `backupInterval`
     * days into `.tachibk` files in `backups`). Madoyomi schedules its own `.madoyomi-backup` of both kinds instead
     * (`madoyomi.core.backup.BackupScheduler`, Phase 6 Stage 6; off by default, the user's decision).
     */
    val upstreamAutomatedBackupEnabled = false

    /** Upstream's single-instance check, which probes the configured port. Tauri owns the lifecycle instead. */
    val appMutexEnabled = false

    /** The sidecar only ever binds the loopback interface, whatever `server.ip` says. */
    const val LOOPBACK = "127.0.0.1"

    /** Origins allowed by CORS: the Tauri dev server and the production WebView2 origins. */
    val ALLOWED_ORIGINS = listOf("http://localhost:1420", "http://tauri.localhost", "https://tauri.localhost")
}
