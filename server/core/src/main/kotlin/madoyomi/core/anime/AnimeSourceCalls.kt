package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionCompatLog
import madoyomi.core.extension.ExtensionIncompatibility
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one way Madoyomi calls into an anime source. A failure that means the host lacks an API the extension uses
 * (a missing class or member, an unimplemented abstract method, an Android `"Stub!"`) is recorded in
 * [ExtensionCompatLog] and becomes [ErrorCode.EXTENSION_INCOMPATIBLE]; every other exception (the site's HTTP errors,
 * the extension's own failures) passes through unchanged, for `ErrorClassifier`.
 */
object AnimeSourceCalls {
    /**
     * The stack of the threads source calls run on. Android's regex engine (ICU) is iterative, `java.util.regex`
     * recurses once per repetition of a group, so an extension's `(?:a|b)*` over a long page overflows a JVM thread's
     * default 1 MB stack where it works on Android (anizone, Phase 2 §10: a 41K-character `JSON.parse('…')` needs more
     * than 16 MB; 64 MB was enough up to 250K). Windows only reserves a thread's stack and commits it as it is used.
     */
    const val STACK_SIZE: Long = 128L * 1024 * 1024

    private const val MAX_THREADS = 64

    private val threadCount = AtomicInteger()

    /** Source code runs here: up to [MAX_THREADS] daemon threads with [STACK_SIZE] stacks, idle ones end after 60 s. */
    val dispatcher: CoroutineDispatcher =
        ThreadPoolExecutor(MAX_THREADS, MAX_THREADS, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), ::newThread)
            .apply { allowCoreThreadTimeOut(true) }
            .asCoroutineDispatcher()

    /**
     * A pool thread. The pool grows while a source call runs (an `async` inside the extension), and a new thread
     * inherits the access-control context of the stack that creates it: the protection domain of every extension
     * frame on it, and so that extension's class loader, for as long as the thread lives. The threads it starts in
     * turn inherit that too (a pool thread that once ran kickassanime started reanime's local server, which then kept
     * kickassanime's loader after uninstall; Phase 2 §10, Stage 6). Created privileged, a thread inherits only the
     * host's own frames.
     */
    @Suppress("DEPRECATION")
    private fun newThread(runnable: Runnable): Thread =
        java.security.AccessController.doPrivileged(
            java.security.PrivilegedAction {
                Thread(null, runnable, "anime-source-${threadCount.incrementAndGet()}", STACK_SIZE).apply {
                    isDaemon = true
                    // not whatever the creating thread had (an extension may set its own)
                    contextClassLoader = AnimeSourceCalls::class.java.classLoader
                }
            },
        )

    /**
     * Runs [block], a call of [operation] (`popular`, `search`, `videos`, …) on [source], on [dispatcher]. The
     * extension's code resumes there after each suspension (a network call), so its parsing runs on the large stack.
     */
    suspend fun <T> call(
        source: AnimeSource,
        operation: String,
        block: suspend () -> T,
    ): T =
        // translated on the source's thread, so the original error is the cause (not a copy made by the coroutine
        // stack-trace recovery that runs when assertions are on)
        withContext(dispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw translate(source, operation, e)
            }
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
