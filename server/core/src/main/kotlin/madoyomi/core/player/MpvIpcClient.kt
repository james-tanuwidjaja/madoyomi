package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * mpv's JSON IPC (Phase 4 step 4; [player/IPC.md](../../../../../../../../player/IPC.md)): one JSON object per line in
 * both directions. A command is `{"command": [...], "request_id": n}` and its reply `{"request_id": n, "error":
 * "success", "data": …}`; everything else mpv sends is an event (`{"event": "property-change", "id": …, "name": …,
 * "data": …}`, `end-file`, `seek`, …), handed to [onEvent] on the reader thread, in order.
 *
 * On Windows the endpoint is a named pipe (`\\.\pipe\<name>`), opened as an [AsynchronousFileChannel], so with
 * overlapped I/O: a synchronous handle (`RandomAccessFile`) serializes I/O, and the reader thread's pending read would
 * block every write. Elsewhere it is a Unix socket.
 *
 * The client closes itself when mpv closes the pipe (mpv exited): [onClosed] runs once, and pending commands fail.
 */
class MpvIpcClient private constructor(
    private val transport: Transport,
    private val name: String,
    private val onEvent: (JsonObject) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) : Closeable {
    /** A duplex byte stream: [read] and [write] may run at once from different threads. */
    interface Transport : Closeable {
        /** Reads into [buffer]; -1 once the other side closed. Blocks. */
        fun read(buffer: ByteBuffer): Int

        /** Writes all of [buffer]. */
        fun write(buffer: ByteBuffer)
    }

    class MpvIpcException(
        message: String,
    ) : IOException(message)

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement?>>()
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val reader = Thread(::readLoop, "mpv-ipc-$name").apply { isDaemon = true }

    val isClosed: Boolean get() = closed.get()

    private fun start(): MpvIpcClient = apply { reader.start() }

    /**
     * Sends a command (`["get_property", "time-pos"]`, `["quit"]`, …); the future completes with the reply's `data`,
     * or fails with [MpvIpcException] when mpv answers an error, or with an [IOException] once the pipe is closed.
     * [async] runs it as mpv's asynchronous command (`sub-add` and `audio-add` load in the background then).
     */
    fun command(
        vararg args: Any?,
        async: Boolean = false,
    ): CompletableFuture<JsonElement?> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement?>()
        if (closed.get()) return future.apply { completeExceptionally(IOException("mpv's IPC is closed")) }
        pending[id] = future
        val message =
            buildJsonObject {
                put("command", JsonArray(args.map(::toJson)))
                put("request_id", id)
                if (async) put("async", true)
            }
        try {
            send(message)
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        return future
    }

    /** `observe_property` with our [id]: mpv sends the current value at once, then every change, as events. */
    fun observe(
        id: Int,
        property: String,
    ): CompletableFuture<JsonElement?> = command("observe_property", id, property)

    private fun send(message: JsonObject) {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            if (closed.get()) throw IOException("mpv's IPC is closed")
            transport.write(ByteBuffer.wrap(bytes))
        }
    }

    private fun readLoop() {
        val buffer = ByteBuffer.allocate(64 * 1024)
        val line = ByteArrayOutputStream()
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                buffer.clear()
                val n = transport.read(buffer)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buffer.get(i)
                    if (b == '\n'.code.toByte()) {
                        dispatch(line.toString(Charsets.UTF_8))
                        line.reset()
                    } else {
                        line.write(b.toInt())
                    }
                }
            }
        } catch (e: Throwable) {
            // a read that fails because we closed the channel is the normal end
            if (!closed.get()) failure = e
        }
        shutdown(failure)
    }

    private fun dispatch(text: String) {
        if (text.isBlank()) return
        val message =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                logger.warn { "mpv IPC $name: not JSON: ${text.take(200)}" }
                null
            } ?: return
        val event = (message["event"] as? JsonPrimitive)?.contentOrNull
        val requestId = (message["request_id"] as? JsonPrimitive)?.longOrNull
        if (event == null && requestId != null) {
            val future = pending.remove(requestId) ?: return
            val error = (message["error"] as? JsonPrimitive)?.contentOrNull ?: "success"
            if (error == "success") future.complete(message["data"]) else future.completeExceptionally(MpvIpcException(error))
            return
        }
        try {
            onEvent(message)
        } catch (e: Exception) {
            logger.warn(e) { "mpv IPC $name: event handler failed on ${event ?: "a message"}" }
        }
    }

    private fun shutdown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { transport.close() }
        val error = IOException("mpv's IPC is closed", cause)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        try {
            onClosed(cause)
        } catch (e: Exception) {
            logger.warn(e) { "mpv IPC $name: close handler failed" }
        }
    }

    /** Waits until the other side closed the pipe and every message before it was handed over; false on timeout. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        if (Thread.currentThread() !== reader) reader.join(timeoutMillis)
        return closed.get()
    }

    /** Closes the pipe (mpv keeps running); pending commands fail. */
    override fun close() {
        if (closed.get()) return
        // the reader sees closed (or a failed read) and runs shutdown; do it here too so close() is synchronous
        shutdown(null)
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Connects to the pipe [pipeName] (`madoyomi-mpv-<session>`), retrying every [retryMillis] until [timeoutMillis]
         * passes or [keepTrying] says no (mpv exited). mpv creates the pipe a moment after it starts (longer while an
         * HLS stream opens). Returns null if it never could.
         */
        fun connect(
            pipeName: String,
            timeoutMillis: Long,
            onEvent: (JsonObject) -> Unit,
            onClosed: (Throwable?) -> Unit = {},
            keepTrying: () -> Boolean = { true },
            retryMillis: Long = 100,
            windows: Boolean = MpvLocator.isWindows,
        ): MpvIpcClient? {
            val path = MpvCommand.pipePath(pipeName, windows)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var last: Throwable? = null
            while (true) {
                try {
                    val transport = if (windows) PipeTransport.open(path) else SocketTransport.open(path)
                    return MpvIpcClient(transport, pipeName, onEvent, onClosed).start()
                } catch (e: IOException) {
                    last = e
                }
                if (System.nanoTime() >= deadline || !keepTrying()) break
                try {
                    Thread.sleep(retryMillis)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            logger.debug { "mpv IPC $pipeName: no connection (${last?.message})" }
            return null
        }

        /** Over any [transport] (tests). */
        fun over(
            transport: Transport,
            name: String,
            onEvent: (JsonObject) -> Unit,
            onClosed: (Throwable?) -> Unit = {},
        ): MpvIpcClient = MpvIpcClient(transport, name, onEvent, onClosed).start()

        internal fun toJson(value: Any?): JsonElement =
            when (value) {
                null -> JsonNull
                is JsonElement -> value
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
    }

    /** A Windows named pipe with overlapped I/O: a read and a write can be pending at once. */
    private class PipeTransport(
        private val channel: AsynchronousFileChannel,
    ) : Transport {
        override fun read(buffer: ByteBuffer): Int = await { channel.read(buffer, 0).get() }

        override fun write(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) await { channel.write(buffer, 0).get() }
        }

        override fun close() = channel.close()

        private fun <T> await(block: () -> T): T =
            try {
                block()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted", e)
            }

        companion object {
            fun open(path: String): PipeTransport =
                PipeTransport(AsynchronousFileChannel.open(Path.of(path), StandardOpenOption.READ, StandardOpenOption.WRITE))
        }
    }

    /** A Unix socket (mpv's `--input-ipc-server` elsewhere); a blocking channel reads and writes concurrently. */
    private class SocketTransport(
        private val channel: SocketChannel,
    ) : Transport {
        override fun read(buffer: ByteBuffer): Int = channel.read(buffer)

        override fun write(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun close() = channel.close()

        companion object {
            fun open(path: String): SocketTransport {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                try {
                    channel.connect(UnixDomainSocketAddress.of(path))
                } catch (e: IOException) {
                    channel.close()
                    throw e
                }
                return SocketTransport(channel)
            }
        }
    }
}

/** A JSON value as mpv sends it: a number, a flag or a string. */
internal fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()

internal fun JsonElement?.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

internal fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
