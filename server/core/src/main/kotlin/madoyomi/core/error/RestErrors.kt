package madoyomi.core.error

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.Context

/**
 * The REST/binary side of the error model: an error answer is the JSON body `{code, message, details}` (the same
 * `code` as GraphQL's `errors[].extensions.code`).
 */
object RestErrors {
    /** Answers with [error]'s status and body. */
    fun respond(
        ctx: Context,
        error: ClassifiedError,
    ) {
        ctx.status(error.httpStatus)
        ctx.json(error.body())
    }

    /** Writes the body for [code] and keeps the status already set (upstream's choice for that exception). */
    fun body(
        ctx: Context,
        code: ErrorCode,
        message: String?,
    ) {
        ctx.json(mapOf("code" to code.name, "message" to (message ?: code.name), "details" to null))
    }

    /** The code for an HTTP error status that has no exception to classify. */
    fun codeFor(status: Int): ErrorCode =
        when (status) {
            400 -> ErrorCode.BAD_REQUEST
            401 -> ErrorCode.UNAUTHORIZED
            403 -> ErrorCode.FORBIDDEN
            404 -> ErrorCode.NOT_FOUND
            in 400..499 -> ErrorCode.BAD_REQUEST
            else -> ErrorCode.INTERNAL
        }
}
