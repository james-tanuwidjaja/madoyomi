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

    /** Upstream's single-instance check, which probes the configured port. Tauri owns the lifecycle instead. */
    val appMutexEnabled = false

    /** The sidecar only ever binds the loopback interface, whatever `server.ip` says. */
    const val LOOPBACK = "127.0.0.1"

    /** Origins allowed by CORS: the Tauri dev server and the production WebView2 origins. */
    val ALLOWED_ORIGINS = listOf("http://localhost:1420", "http://tauri.localhost", "https://tauri.localhost")
}
