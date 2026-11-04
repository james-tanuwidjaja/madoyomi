package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.exthost.ExtensionKind

/** The steps of an install or update, in order; it ends in [INSTALLED] or [FAILED]. */
enum class InstallState {
    /** Resolving the repo listing and downloading the APK. */
    DOWNLOADING,

    /** apksig verification and the package, version and kind checks. */
    VERIFYING,

    /** dex2jar, storing the jar and icon, recording the rows (and, if trusted, loading a manga extension). */
    CONVERTING,
    INSTALLED,
    FAILED,
}

/** One step of an install/update, for the Stage 7 `extensionInstallProgress` subscription. */
data class ExtensionInstallProgress(
    /** The package; for a sideloaded file, its file name until the APK has been read. */
    val pkgName: String,
    val kind: ExtensionKind?,
    val state: InstallState,
    val errorCode: ErrorCode? = null,
    val message: String? = null,
)

/** What an install or update did. */
data class InstallResult(
    val pkgName: String,
    val kind: ExtensionKind,
    val versionName: String,
    val versionCode: Long,
    /** Lowercase hex SHA-256 of the signing certificate (`EXTENSION.signature_hash`). */
    val signatureHash: String,
    /** The hash is in `TRUSTED_SIGNATURE`. */
    val trusted: Boolean,
    /** The extension's code was loaded (trusted extensions only). */
    val loaded: Boolean,
    /** Non-blocking findings, e.g. an extensions-lib version outside the expected range. */
    val warnings: List<String>,
)
