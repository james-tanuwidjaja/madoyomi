package madoyomi.core.error

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import madoyomi.core.extension.ExtensionIncompatibility
import madoyomi.core.extension.SourceAccess
import suwayomi.tachidesk.manga.impl.util.network.isHostUnreachable
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UnauthorizedException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** An exception as clients see it: GraphQL `errors[]` (`message`, `extensions.code/details`) or a REST error body. */
data class ClassifiedError(
    val code: ErrorCode,
    val message: String,
    val details: Map<String, Any?>,
    /** A rejection or a failure outside the server (no stack trace in the log), as opposed to a server fault. */
    val expected: Boolean,
) {
    val httpStatus: Int get() = code.httpStatus

    /** The REST error body. */
    fun body(): Map<String, Any?> = mapOf("code" to code.name, "message" to message, "details" to details.ifEmpty { null })
}

/** Maps any exception to a stable [ErrorCode]. Used by the GraphQL error handler and the REST exception handlers. */
object ErrorClassifier {
    private const val MAX_CAUSE_DEPTH = 16

    fun classify(error: Throwable): ClassifiedError {
        val e = unwrap(error)
        madoyomiCause(e)?.let { return ClassifiedError(it.code, it.message ?: it.code.name, it.details, it.code != ErrorCode.INTERNAL) }
        return when {
            e is UnauthorizedException -> {
                expected(ErrorCode.UNAUTHORIZED, e.message ?: "Unauthorized")
            }

            e is ForbiddenException -> {
                expected(ErrorCode.FORBIDDEN, e.message ?: "Forbidden")
            }

            e is StubSource.SourceNotInstalledException -> {
                val diagnosed = SourceAccess.diagnose(e.id)
                ClassifiedError(diagnosed.code, diagnosed.message!!, diagnosed.details, diagnosed.code != ErrorCode.INTERNAL)
            }

            e is HttpException -> {
                expected(ErrorCode.SOURCE_FAILED, e.message ?: "Source request failed", mapOf("httpCode" to e.code))
            }

            e is IOException && e.isHostUnreachable() -> {
                expected(ErrorCode.SOURCE_FAILED, e.message ?: "Source host unreachable")
            }

            e is IllegalArgumentException -> {
                expected(ErrorCode.BAD_REQUEST, e.message ?: "Bad request")
            }

            // an extension reached a gap in the host's API through a path that doesn't go through AnimeSourceCalls
            // (upstream's manga code); the package isn't known here
            ExtensionIncompatibility.classify(e) != null -> {
                val found = ExtensionIncompatibility.classify(e)!!
                ClassifiedError(
                    ErrorCode.EXTENSION_INCOMPATIBLE,
                    "An extension needs an API Madoyomi doesn't provide (${found.kind.name.lowercase()}: ${found.target})",
                    mapOf("kind" to found.kind.name, "missing" to found.target, "cause" to found.error.javaClass.name),
                    expected = false,
                )
            }

            else -> {
                ClassifiedError(ErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName, emptyMap(), expected = false)
            }
        }
    }

    /** Drops the wrappers futures and reflection put around the real exception. */
    fun unwrap(error: Throwable): Throwable {
        var e = error
        repeat(MAX_CAUSE_DEPTH) {
            val cause = e.cause
            if (cause != null && (e is CompletionException || e is ExecutionException || e is InvocationTargetException)) {
                e = cause
            } else {
                return e
            }
        }
        return e
    }

    private fun madoyomiCause(error: Throwable): MadoyomiException? =
        generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<MadoyomiException>().firstOrNull()

    private fun expected(
        code: ErrorCode,
        message: String,
        details: Map<String, Any?> = emptyMap(),
    ) = ClassifiedError(code, message, details, expected = true)
}
