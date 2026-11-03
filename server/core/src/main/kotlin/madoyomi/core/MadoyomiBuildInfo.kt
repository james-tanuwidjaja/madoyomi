package madoyomi.core

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.server.generated.BuildConfig

/**
 * Public view of the generated `BuildConfig`, for modules above `core` (such as `api`).
 * Upstream's buildconfig plugin generates `BuildConfig` as `internal`, which is only visible
 * inside `core`.
 */
object MadoyomiBuildInfo {
    /** App name, `Madoyomi-Server` (the Gradle root project name). */
    val name: String = BuildConfig.NAME

    /** App version, `vX.Y.Z`. */
    val version: String = BuildConfig.VERSION
}
