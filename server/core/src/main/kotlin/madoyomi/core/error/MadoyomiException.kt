package madoyomi.core.error

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/** Stable error codes clients can branch on. Stage 7 maps them to GraphQL `errors[].extensions.code`. */
enum class ErrorCode {
    /** A repo index could not be downloaded. */
    REPO_FETCH_FAILED,

    /** A repo index is not in the expected format (or not an index at all). */
    REPO_FORMAT_MISMATCH,

    /** A repo's packages are not of the repo type it was added as. */
    REPO_TYPE_MISMATCH,

    NOT_FOUND,

    /** An extension APK has no valid signature (apksig), or is not an APK at all. */
    SIGNATURE_INVALID,

    /** An update is signed by a different, untrusted certificate; the installed version is kept. */
    SIGNATURE_CHANGED,

    /** The extension's signing certificate is not trusted, so its code may not run. */
    UNTRUSTED,

    /** An APK is not what the repo listed: another package, version code or extension kind. */
    PACKAGE_MISMATCH,

    /** An install, update or uninstall could not be done (already installed, no update, no APK URL, …). */
    INSTALL_FAILED,
}

/** An error with a [code], a human-readable [message] and optional structured [details]. */
class MadoyomiException(
    val code: ErrorCode,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
