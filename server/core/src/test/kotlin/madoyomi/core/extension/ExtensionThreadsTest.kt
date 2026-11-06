package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import madoyomi.core.anime.AnimeSourceCalls
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.ChildFirstURLClassLoader
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.ThreadPoolExecutor
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ExtensionThreads] and the uninstall leaks it closes, on "extension" classes compiled here and loaded by an
 * extension class loader (`ChildFirstURLClassLoader`), as an installed extension's are.
 */
class ExtensionThreadsTest {
    @TempDir
    lateinit var tmp: Path

    /** Compiles the extension classes into a directory; the host classpath is on javac's. */
    private fun compileExtension(): Path {
        val src = (tmp / "src").createDirectories()
        val out = (tmp / "classes").createDirectories()
        val sources =
            mapOf(
                // a local server like the NanoHTTPD copies of missav, onetwothreeanime and reanime: blocked in accept()
                "Listener" to
                    """
                    public class Listener implements Runnable {
                        public final java.net.ServerSocket server;
                        public Listener() throws java.io.IOException {
                            server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
                        }
                        public void run() {
                            try { while (true) server.accept().close(); } catch (java.io.IOException e) { }
                        }
                    }
                    """,
                "Sleeper" to
                    """
                    public class Sleeper implements Runnable {
                        public void run() { try { Thread.sleep(600000); } catch (InterruptedException e) { } }
                    }
                    """,
                "Stubborn" to
                    """
                    public class Stubborn implements Runnable {
                        public volatile boolean done;
                        public void run() { while (!done) { try { Thread.sleep(20); } catch (InterruptedException e) { } } }
                    }
                    """,
                // the extension's own thread pool, idle in take() (streamingcommunity's "streamingunity-subs")
                "Pool" to
                    """
                    public class Pool {
                        public static java.util.concurrent.ExecutorService pool;
                        public static void start() {
                            pool = java.util.concurrent.Executors.newFixedThreadPool(1, r -> new Thread(r, "test-Pool"));
                            pool.execute(() -> { });
                        }
                    }
                    """,
                // a JDK pool started from extension code: nothing of the extension in it but the creator's context
                "Leaky" to
                    """
                    public class Leaky {
                        public static java.util.concurrent.ExecutorService start() {
                            java.util.concurrent.ExecutorService e = java.util.concurrent.Executors.newSingleThreadExecutor();
                            e.execute(new Thread());
                            return e;
                        }
                    }
                    """,
                // grows the source pool from inside extension code, as an `async` in a source call does
                "Spawner" to
                    """
                    public class Spawner implements Runnable {
                        public void run() {
                            java.util.concurrent.Executor e =
                                kotlinx.coroutines.ExecutorsKt.asExecutor(madoyomi.core.anime.AnimeSourceCalls.INSTANCE.getDispatcher());
                            for (int i = 0; i < 3; i++) e.execute(new Noop());
                        }
                        static class Noop implements Runnable { public void run() { } }
                    }
                    """,
            )
        val files = sources.map { (name, code) -> (src / "$name.java").also { it.writeText(code.trimIndent()) }.toFile() }
        val javac = ToolProvider.getSystemJavaCompiler()
        assumeTrue(javac != null, "no system Java compiler (a JRE, not a JDK)")
        javac.getStandardFileManager(null, null, null).use { fm ->
            val ok =
                javac
                    .getTask(
                        null,
                        fm,
                        null,
                        listOf("-d", out.toString(), "-cp", System.getProperty("java.class.path")),
                        null,
                        fm.getJavaFileObjectsFromFiles(files),
                    ).call()
            assertTrue(ok, "the test extension compiles")
        }
        return out
    }

    private fun loader(classes: Path) = ChildFirstURLClassLoader(arrayOf(classes.toUri().toURL()))

    private fun start(
        loader: ClassLoader,
        className: String,
    ): Pair<Thread, Any> {
        val task = Class.forName(className, true, loader).getDeclaredConstructor().newInstance()
        val thread = Thread(task as Runnable, "test-$className").apply { isDaemon = true }
        thread.start()
        return thread to task
    }

    /** Starts the threads, stops them, and returns what [ExtensionThreads] reported plus a reference to the loader. */
    private fun startAndStop(classes: Path): Pair<Map<String, ExtensionThreads.Stopped>, WeakReference<ClassLoader>> {
        val loader = loader(classes)
        val (listener, _) = start(loader, "Listener")
        val (sleeper, _) = start(loader, "Sleeper")
        Thread.sleep(200) // both are blocked now: in accept() and in sleep()
        assertEquals(setOf(listener, sleeper), ExtensionThreads.threadsOf(loader).toSet())

        val stopped = ExtensionThreads.stop(loader, "test.extension").associateBy { it.thread }
        assertFalse(listener.isAlive)
        assertFalse(sleeper.isAlive)
        loader.close()
        return stopped to WeakReference(loader)
    }

    @Test
    fun `unload interrupts the extension's threads and closes the sockets of one blocked in accept`() {
        assertTrue(ExtensionThreads.canReadTasks, "java.lang is opened to the tests (--add-opens), as the server jar's manifest does")
        val (stopped, loader) = startAndStop(compileExtension())
        assertEquals(ExtensionThreads.Outcome.INTERRUPTED, stopped.getValue("test-Sleeper").outcome)
        // an interrupt doesn't end accept(): the server socket its Runnable holds is closed instead
        assertEquals(ExtensionThreads.Outcome.SOCKETS_CLOSED, stopped.getValue("test-Listener").outcome)
        assertEquals(1, stopped.getValue("test-Listener").socketsClosed)
        GcAssert.assertCollected(loader, "nothing keeps the extension's class loader once its threads are stopped")
    }

    @Test
    fun `a thread that ignores the interrupt is left running and reported`() {
        val loader = loader(compileExtension())
        val (thread, task) = start(loader, "Stubborn")
        try {
            val stopped = ExtensionThreads.stop(loader, "test.extension", graceMillis = 100).single()
            assertEquals("test-Stubborn", stopped.thread)
            assertEquals(ExtensionThreads.Outcome.STILL_RUNNING, stopped.outcome)
            assertTrue(thread.isAlive, "nothing is forced")
        } finally {
            task.javaClass.getField("done").setBoolean(task, true)
            thread.join(2000)
            loader.close()
        }
    }

    /** Starts the extension's own pool and stops it; the outcome and a reference to the loader. */
    private fun startPoolAndStop(classes: Path): Pair<ExtensionThreads.Stopped, WeakReference<ClassLoader>> {
        val loader = loader(classes)
        Class.forName("Pool", true, loader).getMethod("start").invoke(null)
        Thread.sleep(100) // its worker idles in take(), which no interrupt ends
        val stopped = ExtensionThreads.stop(loader, "test.extension").single()
        loader.close()
        return stopped to WeakReference(loader)
    }

    @Test
    fun `unload shuts down the extension's own thread pool`() {
        assertTrue(ExtensionThreads.canReadPools, "java.util.concurrent is opened to the tests, as the server jar's manifest does")
        val (stopped, loader) = startPoolAndStop(compileExtension())
        assertEquals("test-Pool", stopped.thread)
        assertEquals(ExtensionThreads.Outcome.POOL_SHUT_DOWN, stopped.outcome)
        GcAssert.assertCollected(loader, "nothing keeps the extension's class loader once its pool is shut down")
    }

    /** Starts a JDK pool from extension code (not the extension's, nothing to stop), unloads; the pool and the loader. */
    private fun startLeakyAndUnload(classes: Path): Pair<java.util.concurrent.ExecutorService, WeakReference<ClassLoader>> {
        val loader = loader(classes)
        val pool = Class.forName("Leaky", true, loader).getMethod("start").invoke(null) as java.util.concurrent.ExecutorService
        Thread.sleep(100)
        assertEquals(emptyList(), ExtensionThreads.stop(loader, "test.extension"), "a JDK pool with a JDK factory isn't the extension's")
        loader.close()
        return pool to WeakReference(loader)
    }

    @Test
    fun `a thread created while extension code ran doesn't keep it through its inherited context`() {
        val (pool, loader) = startLeakyAndUnload(compileExtension())
        try {
            // the pool's worker lives on (a core thread), created with the extension's frames on the stack
            GcAssert.assertCollected(loader, "the worker's inherited access-control context no longer has the extension's domain")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `source-pool threads created from extension code don't keep its class loader`() {
        val pool = (AnimeSourceCalls.dispatcher as ExecutorCoroutineDispatcher).executor as ThreadPoolExecutor
        assumeTrue(pool.poolSize <= pool.maximumPoolSize - 3, "the source pool has no room for new threads in this JVM")
        val before = pool.poolSize
        val ref = spawnPoolThreads(compileExtension())
        assertTrue(pool.poolSize >= before + 3, "the extension's code created pool threads (${pool.poolSize}, was $before)")
        // they idle for 60 s, so they are alive while the loader is checked: they must not inherit its protection
        // domain (the access-control context of the stack that created them) or its context class loader
        GcAssert.assertCollected(ref, "the new pool threads don't keep the extension's class loader")
    }

    private fun spawnPoolThreads(classes: Path): WeakReference<ClassLoader> {
        val loader = loader(classes)
        val (thread, _) = start(loader, "Spawner")
        thread.join(5000)
        Thread.sleep(100) // the no-op tasks have run
        loader.close()
        return WeakReference(loader)
    }
}
