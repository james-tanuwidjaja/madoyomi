package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MpvIpcClient] against a fake mpv on a real named pipe ([FakeMpvServer], Phase 4 step 19): connecting with retries,
 * replies by `request_id`, errors, `observe_property` events, messages split or merged across writes, writes while a
 * read is pending (overlapped I/O), and the close when mpv goes away.
 */
@EnabledOnOs(OS.WINDOWS)
class MpvIpcClientTest {
    private val pipe = "madoyomi-mpv-test-${UUID.randomUUID()}"
    private val servers = mutableListOf<FakeMpvServer>()
    private val clients = mutableListOf<MpvIpcClient>()

    // Iterated by the tests while the client's reader thread appends: a snapshot per iteration. A synchronized
    // list locks only single calls; iterating it threw ConcurrentModificationException (Phase 5 Stage 5's baseline).
    private val events: MutableList<JsonObject> = CopyOnWriteArrayList()

    private fun server(
        mode: String = "idle",
        configure: FakeMpvServer.Options.() -> Unit = {},
    ) = FakeMpvServer(pipe, FakeMpvServer.Options().apply { this.mode = mode }.apply(configure)).also { servers += it }

    private fun connect(
        timeout: Long = 5_000,
        onClosed: (Throwable?) -> Unit = {},
    ): MpvIpcClient =
        assertNotNull(MpvIpcClient.connect(pipe, timeout, onEvent = { events += it }, onClosed = onClosed)).also { clients += it }

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.close() }
    }

    private fun <T> CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `connects once the pipe appears, and gives up when told to or at the timeout`() {
        val pending = CompletableFuture.supplyAsync { MpvIpcClient.connect(pipe, 10_000, onEvent = {}) }
        Thread.sleep(500)
        server()
        val client = assertNotNull(pending.get(10, TimeUnit.SECONDS))
        clients += client
        assertEquals(JsonPrimitive(false), client.command("get_property", "pause").await())

        val start = System.nanoTime()
        assertNull(MpvIpcClient.connect("madoyomi-mpv-none-${UUID.randomUUID()}", 10_000, onEvent = {}, keepTrying = { false }))
        assertNull(MpvIpcClient.connect("madoyomi-mpv-none-${UUID.randomUUID()}", 300, onEvent = {}))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000)
    }

    @Test
    fun `commands get their own replies, and mpv's errors fail them`() {
        val fake = server()
        val client = connect()
        fake.play()
        val duration = client.command("get_property", "duration")
        val pause = client.command("get_property", "pause")
        assertEquals(100.0, duration.await()?.jsonPrimitive?.double)
        assertEquals(JsonPrimitive(false), pause.await())
        assertNull(client.command("set_property", "mute", true).await())
        assertTrue(fake.muted())

        val failed = assertFailsWith<ExecutionException> { client.command("get_property", "no-such-property").await() }
        assertIs<MpvIpcClient.MpvIpcException>(failed.cause)
        assertEquals("property unavailable", failed.cause?.message)
        assertTrue(fake.received().any { it.contains("\"request_id\":") && it.contains("\"get_property\"") })
    }

    @Test
    fun `observe_property sends the current value at once, then every change, in order`() {
        val fake = server()
        val client = connect()
        client.observe(1, "pause").await()
        client.observe(2, "time-pos").await()
        waitFor("the first values") { events.size >= 2 }
        assertEquals(listOf("pause", "time-pos"), events.take(2).map { it["name"]?.jsonPrimitive?.content })
        assertEquals(JsonPrimitive(false), events[0]["data"])

        fake.play()
        fake.seekTo(42.0)
        waitFor("the seek") {
            events.any {
                it["name"]?.jsonPrimitive?.content == "time-pos" &&
                    it["data"]?.jsonPrimitive?.content?.toDoubleOrNull() == 42.0
            }
        }
        val kinds = events.map { it["event"]?.jsonPrimitive?.content }
        val seek = kinds.indexOf("seek")
        assertTrue(seek >= 0 && kinds.indexOf("file-loaded") < seek, kinds.toString())
        assertTrue(kinds.lastIndexOf("playback-restart") > seek, kinds.toString())
    }

    @Test
    fun `messages split across writes or merged into one are read as lines`() {
        val fake = server()
        connect()
        waitFor("the connection") { fake.connections() == 1 }
        fake.sendRaw("""{"event":"spl""")
        Thread.sleep(50)
        fake.sendRaw("it\",\"data\":\"é\"}\n{\"event\":\"second\"}\n\n{\"event\":\"third\"}\n")
        waitFor("three events") { events.size >= 3 }
        assertEquals(listOf("split", "second", "third"), events.map { it["event"]?.jsonPrimitive?.content })
        assertEquals("é", events[0]["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `writes go through while a read is pending, from many threads at once`() {
        val fake = server()
        val client = connect()
        // nothing comes from the fake now: the reader thread sits in a pending read. A synchronous pipe handle would
        // block every write behind it; overlapped I/O doesn't
        Thread.sleep(200)
        assertEquals(JsonPrimitive(false), client.command("get_property", "pause").await())

        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val replies =
            (1..100).map { i ->
                CompletableFuture.supplyAsync({
                    start.await()
                    client.command("set_property", "mute", i % 2 == 0).get(10, TimeUnit.SECONDS)
                }, pool)
            }
        fake.play() // events flow while the commands go out
        start.countDown()
        replies.forEach { assertNull(it.get(10, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(101, fake.received().count { it.contains("\"request_id\":") })
    }

    @Test
    fun `when mpv closes the pipe the client closes, once, and pending and later commands fail`() {
        val fake = server()
        val closedCalls = CountDownLatch(1)
        var count = 0
        val client =
            connect(onClosed = {
                count++
                closedCalls.countDown()
            })
        waitFor("the connection") { fake.connections() == 1 }
        val pending = client.command("get_property", "nothing-answers-this-in-time")
        fake.dropClients()
        assertTrue(closedCalls.await(5, TimeUnit.SECONDS))
        assertTrue(client.awaitClosed(5_000))
        assertTrue(client.isClosed)
        // the fake may have answered (an error) before it dropped us, or the close failed it: either way it failed
        assertFailsWith<ExecutionException> { pending.await() }
        assertFailsWith<ExecutionException> { client.command("get_property", "pause").await() }
        client.close()
        assertEquals(1, count)
    }

    @Test
    fun `sub-add over IPC adds a titled track (the fake's track-list shows it)`() {
        val fake = server()
        val client = connect()
        fake.play()
        val track = MpvCommand.Track(MpvCommand.Track.Kind.SUBTITLE, "https://subs/en.vtt", "English")
        assertNull(client.command(*track.command(select = true).toTypedArray(), async = true).await())
        val list = client.command("get_property", "track-list").await() as JsonArray
        val sub = list.map { it as JsonObject }.single { it["type"]?.jsonPrimitive?.content == "sub" }
        assertEquals("English", sub["title"]?.jsonPrimitive?.content)
        assertEquals("https://subs/en.vtt", sub["external-filename"]?.jsonPrimitive?.content)
        assertTrue(fake.received().any { it.contains("\"async\":true") })
    }
}
