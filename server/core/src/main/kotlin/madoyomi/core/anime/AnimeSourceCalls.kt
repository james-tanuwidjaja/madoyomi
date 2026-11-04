package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionCompatLog
import madoyomi.core.extension.ExtensionIncompatibility
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one way Madoyomi calls into an anime source. A failure that means the host lacks an API the extension uses
 * (a missing class or member, an unimplemented abstract method, an Android `"Stub!"`) is recorded in
 * [ExtensionCompatLog] and becomes [ErrorCode.EXTENSION_INCOMPATIBLE]; every other exception (the site's HTTP errors,
 * the extension's own failures) passes through unchanged, for `ErrorClassifier`.
 */
object AnimeSourceCalls {
    /** Runs [block], a call of [operation] (`popular`, `search`, `videos`, …) on [source]. */
    suspend fun <T> call(
        source: AnimeSource,
        operation: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw translate(source, operation, e)
        }

    /** The blocking variant of [call], for the non-suspend parts of the API (filters, preferences, headers). */
    fun <T> callBlocking(
        source: AnimeSource,
        operation: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw translate(source, operation, e)
        }

    private fun translate(
        source: AnimeSource,
        operation: String,
        error: Throwable,
    ): Throwable {
        if (error is VirtualMachineError || error is MadoyomiException) return error
        val found = ExtensionIncompatibility.classify(error) ?: return error
        val pkg = ExtensionCompatLog.pkgOf(source.javaClass) ?: source.javaClass.name
        ExtensionCompatLog.record(pkg, found.kind, found.target, ExtensionCompatLog.Origin.RUNTIME, operation)
        return incompatible(pkg, source.id, operation, found, error)
    }

    internal fun incompatible(
        pkg: String,
        sourceId: Long?,
        operation: String?,
        found: ExtensionIncompatibility.Found,
        error: Throwable,
    ) = MadoyomiException(
        ErrorCode.EXTENSION_INCOMPATIBLE,
        "$pkg needs an API Madoyomi doesn't provide (${found.kind.name.lowercase()}: ${found.target})" +
            (operation?.let { " in $it" } ?: ""),
        buildMap {
            put("pkgName", pkg)
            sourceId?.let { put("sourceId", it.toString()) }
            operation?.let { put("operation", it) }
            put("kind", found.kind.name)
            put("missing", found.target)
            put("cause", found.error.javaClass.name)
        },
        error,
    )
}
