package madoyomi.core.error

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Stable error codes clients can branch on: GraphQL `errors[].extensions.code` and the `code` of a REST error body
 * (see [ErrorClassifier]). [httpStatus] is the status a REST endpoint answers with.
 */
enum class ErrorCode(
    val httpStatus: Int,
) {
    /** A repo index could not be downloaded. */
    REPO_FETCH_FAILED(424),

    /** A repo index is not in the expected format (or not an index at all). */
    REPO_FORMAT_MISMATCH(422),

    /** A repo's packages are not of the repo type it was added as. */
    REPO_TYPE_MISMATCH(422),

    NOT_FOUND(404),

    /** An extension APK has no valid signature (apksig), or is not an APK at all. */
    SIGNATURE_INVALID(422),

    /** An update is signed by a different, untrusted certificate; the installed version is kept. */
    SIGNATURE_CHANGED(409),

    /** The extension's signing certificate is not trusted, so its code may not run. */
    UNTRUSTED(403),

    /** An APK is not what the repo listed: another package, version code or extension kind. */
    PACKAGE_MISMATCH(422),

    /** An install, update or uninstall could not be done (already installed, no update, no APK URL, …). */
    INSTALL_FAILED(409),

    /** A source (extension) request failed: the site answered with an HTTP error or could not be reached. */
    SOURCE_FAILED(424),

    /**
     * A trusted extension could not be instantiated: its class is missing or not a source, its constructor threw, or
     * it references an API the host lacks (`details.cause`, and the missing class or member in `details.missing`).
     */
    LOAD_FAILED(500),

    /** The request itself is invalid: a GraphQL syntax or validation error, a bad argument. */
    BAD_REQUEST(400),

    /** No valid API token (sidecar), or no logged-in user. */
    UNAUTHORIZED(401),

    /** The user lacks the permission for this operation. */
    FORBIDDEN(403),

    /** Anything unexpected. The message is the exception's message; the stack trace is only in the log. */
    INTERNAL(500),
}

/** An error with a [code], a human-readable [message] and optional structured [details]. */
class MadoyomiException(
    val code: ErrorCode,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
