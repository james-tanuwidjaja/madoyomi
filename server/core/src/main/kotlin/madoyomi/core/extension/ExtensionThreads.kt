package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.NetworkChannel
import java.util.IdentityHashMap
import java.util.concurrent.ThreadPoolExecutor

/**
 * Stops the threads an extension started and left running when it is unloaded (Phase 2 §9, Stage 6; user decision
 * 2026-11-06: interrupt and log, nothing forced). A live thread keeps its extension's class loader, and so every
 * class of the extension, until the process ends. The Stage 5 harness found them in missav, onetwothreeanime and
 * reanime, which each bundle their own (R8-renamed) NanoHTTPD and start a local video proxy that nothing ever stops,
 * and in streamingcommunity, whose own thread pool idles forever.
 *
 * A thread belongs to the extension when any of these comes from the extension's loader:
 * - its class;
 * - its `Runnable`;
 * - its context class loader;
 * - one of its stack frames (matched by the loader's name: R8's short class names repeat across extensions);
 * - for a worker of a JDK `ThreadPoolExecutor`, the pool's thread factory.
 *
 * Such a pool is shut down (`shutdownNow`, which interrupts its workers). Every other thread is interrupted. One still
 * alive after [GRACE_MILLIS] is usually blocked in a socket call (`ServerSocket.accept`, a keep-alive read), which an
 * interrupt doesn't end. The server and client sockets its `Runnable` holds are closed, as the extension's own
 * `stop()` would do. A thread that still runs after that is left alone and logged.
 *
 * Every live thread also loses its inherited access-control context ([clearInheritedContexts]): a thread created
 * while the extension's code was on the stack (an OkHttp or coroutine worker started inside a source call) keeps the
 * extension's protection domain there, and so its class loader. Without a SecurityManager, which JDK 21 never
 * installs unless asked, the context isn't used for anything.
 *
 * Reading a thread's `Runnable` and fields needs `java.lang` and `java.util.concurrent` opened to Madoyomi (`Add-Opens`
 * in the server jar's manifest, `--add-opens` in the tests). Without them the stop is interrupt-only.
 */
object ExtensionThreads {
    private val logger = KotlinLogging.logger {}

    const val GRACE_MILLIS = 500L

    /** How far the sockets are looked for from a thread's `Runnable` (extension objects only). */
    private const val SOCKET_DEPTH = 4

    enum class Outcome { INTERRUPTED, POOL_SHUT_DOWN, SOCKETS_CLOSED, STILL_RUNNING }

    data class Stopped(
        val thread: String,
        val outcome: Outcome,
        val socketsClosed: Int = 0,
    )

    /** Stops [loader]'s threads (see the class doc) and reports each one; [pkg] is for the log. */
    fun stop(
        loader: ClassLoader,
        pkg: String,
        graceMillis: Long = GRACE_MILLIS,
    ): List<Stopped> {
        clearInheritedContexts()
        val threads = threadsOf(loader)
        if (threads.isEmpty()) return emptyList()
        val pools = threads.mapNotNull { pool(it, loader) }.distinct()
        pools.forEach { it.shutdownNow() }
        threads.forEach { it.interrupt() }
        threads.forEach { it.join(graceMillis.coerceAtLeast(1) / threads.size + 1) }
        val stopped =
            threads.map { thread ->
                if (!thread.isAlive) {
                    return@map Stopped(thread.name, if (pool(thread, loader) != null) Outcome.POOL_SHUT_DOWN else Outcome.INTERRUPTED)
                }
                val closed = closeSockets(thread, loader)
                if (closed > 0) thread.join(graceMillis)
                Stopped(thread.name, if (thread.isAlive) Outcome.STILL_RUNNING else Outcome.SOCKETS_CLOSED, closed)
            }
        stopped.forEach { s ->
            when (s.outcome) {
                Outcome.STILL_RUNNING -> {
                    logger.warn { "$pkg: thread '${s.thread}' is still running after unload; its class loader stays" }
                }

                else -> {
                    logger.info {
                        "$pkg: stopped its thread '${s.thread}' (${s.outcome.name.lowercase()}, ${s.socketsClosed} sockets closed)"
                    }
                }
            }
        }
        return stopped
    }

    /** The live threads (other than the caller) that run [loader]'s code. */
    fun threadsOf(loader: ClassLoader): List<Thread> {
        val name = loader.name
        val current = Thread.currentThread()
        return Thread
            .getAllStackTraces()
            .filter { (thread, frames) ->
                thread !== current &&
                    thread.isAlive &&
                    (
                        thread.javaClass.classLoader === loader ||
                            thread.contextClassLoader === loader ||
                            task(thread)?.javaClass?.classLoader === loader ||
                            pool(thread, loader) != null ||
                            (name != null && frames.any { it.classLoaderName == name })
                    )
            }.keys
            .toList()
    }

    private val holderField: Field? by lazy { accessible(Thread::class.java, "holder") }
    private val taskField: Field? by lazy { holderField?.type?.let { accessible(it, "task") } }

    // JDK 21: Thread.holder.task (JDK 17 and older: Thread.target)
    private val targetField: Field? by lazy { if (holderField == null) accessible(Thread::class.java, "target") else null }

    private val workerClass: Class<*>? by lazy {
        runCatching {
            Class.forName(
                "java.util.concurrent.ThreadPoolExecutor\$Worker",
            )
        }.getOrNull()
    }
    private val workerPoolField: Field? by lazy { workerClass?.let { accessible(it, "this\$0") } }

    @Suppress("DEPRECATION", "removal")
    private val contextField: Field? by lazy { accessible(Thread::class.java, "inheritedAccessControlContext") }

    @Suppress("DEPRECATION", "removal")
    private val emptyContext by lazy { java.security.AccessControlContext(arrayOf<java.security.ProtectionDomain>()) }

    /** The `Runnable` a thread was started with, if `java.lang` is open to us. */
    internal fun task(thread: Thread): Runnable? =
        try {
            val holder = holderField?.get(thread)
            when {
                holder != null -> taskField?.get(holder) as? Runnable
                else -> targetField?.get(thread) as? Runnable
            }
        } catch (_: ReflectiveOperationException) {
            null
        }

    /** The JDK pool [thread] is a worker of, if [loader] made its thread factory (the extension's own pool). */
    private fun pool(
        thread: Thread,
        loader: ClassLoader,
    ): ThreadPoolExecutor? {
        val worker = task(thread)?.takeIf { workerClass?.isInstance(it) == true } ?: return null
        val pool =
            try {
                workerPoolField?.get(worker) as? ThreadPoolExecutor
            } catch (_: ReflectiveOperationException) {
                null
            } ?: return null
        return pool.takeIf { it.threadFactory.javaClass.classLoader === loader }
    }

    /** Whether a thread's `Runnable` can be read (so sockets can be closed). */
    val canReadTasks: Boolean get() = taskField != null || targetField != null

    /** Whether a JDK pool's workers can be traced to their pool (so the extension's own pools are shut down). */
    val canReadPools: Boolean get() = canReadTasks && workerPoolField != null

    /**
     * Drops the access-control context every live thread inherited from the stack that created it (see the class doc),
     * when no SecurityManager is installed. Returns how many threads had one.
     */
    @Suppress("DEPRECATION", "removal")
    fun clearInheritedContexts(): Int {
        if (System.getSecurityManager() != null) return 0
        val field = contextField ?: return 0
        var cleared = 0
        Thread.getAllStackTraces().keys.forEach { thread ->
            try {
                val context = field.get(thread)
                if (context != null && context !== emptyContext) {
                    field.set(thread, emptyContext)
                    cleared++
                }
            } catch (_: Exception) {
            }
        }
        return cleared
    }

    private fun accessible(
        type: Class<*>,
        name: String,
    ): Field? =
        try {
            type.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: Exception) {
            // NoSuchFieldException, or InaccessibleObjectException without --add-opens
            null
        }

    /** Closes the sockets reachable from [thread]'s `Runnable` through objects of [loader]'s classes. */
    private fun closeSockets(
        thread: Thread,
        loader: ClassLoader,
    ): Int {
        val start = task(thread) ?: return 0
        var closed = 0
        val seen = IdentityHashMap<Any, Unit>()
        var level = listOf<Any>(start)
        repeat(SOCKET_DEPTH) {
            val next = mutableListOf<Any>()
            level.forEach { obj ->
                if (seen.put(obj, Unit) != null) return@forEach
                if (obj is ServerSocket || obj is Socket || obj is NetworkChannel) {
                    try {
                        (obj as Closeable).close()
                        closed++
                    } catch (_: Exception) {
                    }
                    return@forEach
                }
                if (obj.javaClass.classLoader !== loader) return@forEach
                fieldsOf(obj.javaClass).forEach { f ->
                    try {
                        f.get(obj)?.let(next::add)
                    } catch (_: Exception) {
                    }
                }
            }
            level = next
        }
        return closed
    }

    private fun fieldsOf(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .mapNotNull { f -> f.takeIf { runCatching { it.isAccessible = true }.isSuccess } }
            .toList()
}
