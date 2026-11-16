package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.library.LibraryUpdater
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.database.dbTransaction
import java.util.concurrent.ConcurrentHashMap

/** One row of the queue, as the queue and the downloaders see it ([DownloadTable]). */
data class DownloadItem(
    val id: Int,
    val kind: ExtensionKind,
    /** The manga or anime. */
    val entryId: Int,
    /** The chapter or episode. */
    val itemId: Int,
    val userId: Int,
    val sourceId: Long,
    val state: DownloadItemState,
    val position: Int,
    val progressDone: Int,
    val progressTotal: Int,
    val bytes: Long,
    val tries: Int,
    val error: String?,
    val addedAt: Long,
    val finishedAt: Long,
)

/** What a kind's downloads are made by. */
interface ItemDownloader {
    /**
     * Downloads [item] (its staged parts kept between attempts, so a retry or a restart goes on from them), reporting
     * how far it got; returns the bytes of what it wrote. Throws when it fails; a [CancellationException] means it was
     * paused, cancelled or stopped.
     */
    suspend fun download(
        item: DownloadItem,
        progress: suspend (done: Int, total: Int, bytes: Long) -> Unit,
    ): Long

    /** Removes what [item] staged (it was cancelled). */
    fun discard(item: DownloadItem) {}
}

/**
 * The download queue (Phase 6 Stage 4), Madoyomi's for both kinds, kept in [DownloadTable] so it survives a restart:
 * its order, the paused and failed items, and how far each got (the downloaders keep their staged parts).
 *
 * - **Order:** the queued items in [DownloadTable.position] order, both kinds in one queue.
 * - **Limits:** at most [DownloadSettings.maxParallel] downloads at once, and [DownloadSettings.perSource] of one
 *   source, two of a source starting at least [staggerMs] apart (the update check's stagger); each source's own rate
 *   limits apply on top.
 * - **States:** QUEUED → DOWNLOADING → DONE. A failure is tried again after [retryDelaysMs] (QUEUED meanwhile), up to
 *   [maxTries] attempts, then FAILED with the error until [retry]. [pause] keeps an item (PAUSED) until [resume];
 *   [cancel] removes it and what it staged. A DOWNLOADING row found at [start] was cut off by a stop: it is QUEUED
 *   again.
 * - **Changes:** [changes] counts every change, for the `downloadsChanged` subscription.
 */
object DownloadQueue {
    private val logger = KotlinLogging.logger {}

    /** Attempts before an item is FAILED: the first one and two automatic retries. */
    @Volatile
    var maxTries: Int = 3

    /** The waits before the automatic retries. */
    @Volatile
    var retryDelaysMs: List<Long> = listOf(5_000, 30_000)

    /** Milliseconds between the starts of two downloads of one source. */
    @Volatile
    var staggerMs: Long = 1_000

    /** The downloaders by kind (Stage 5 adds anime's); tests put fakes here. */
    val downloaders: MutableMap<ExtensionKind, ItemDownloader> = ConcurrentHashMap(mapOf(ExtensionKind.MANGA to MangaChapterDownloader))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class Running(
        val job: Job,
        val sourceKey: String,
    )

    private val running = ConcurrentHashMap<Int, Running>()
    private val retryAt = ConcurrentHashMap<Int, Long>()
    private val lastStart = ConcurrentHashMap<String, Long>()
    private var loop: Job? = null

    private val _changes = MutableStateFlow(0L)

    /** Goes up with every change of the queue (added, started, progress, done, failed, moved, removed). */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Starts the workers (once): an item cut off while DOWNLOADING is QUEUED again, and the queue goes on. */
    fun start() {
        synchronized(lock) {
            if (loop?.isActive == true) return
            val resumed =
                dbTransaction {
                    DownloadTable.update({ DownloadTable.state eq DownloadItemState.DOWNLOADING }) {
                        it[state] = DownloadItemState.QUEUED
                    }
                }
            if (resumed > 0) logger.info { "download queue: $resumed downloads cut off by the last stop are queued again" }
            DownloadSettingsStore.onChange = { wakeUp() }
            LibraryUpdater.onNewItems = { entry, ids -> MangaDownloads.autoDownload(entry, ids) }
            loop = scope.launch { run() }
        }
        wakeUp()
    }

    /**
     * Stops the workers without touching the queue: what was being downloaded stays DOWNLOADING, as after a crash, and
     * [start] queues it again. Tests use it to stand for a restart.
     */
    suspend fun stop() {
        val jobs =
            synchronized(lock) {
                val l = loop
                loop = null
                listOfNotNull(l) + running.values.map { it.job }
            }
        jobs.forEach { it.cancelAndJoin() }
        running.clear()
        retryAt.clear()
        lastStart.clear()
    }

    /**
     * Tests: waits until nothing is downloading and nothing is queued (failed and paused items may stay). A row counts
     * while it is DOWNLOADING too: the scheduler claims it in the table just before its job runs.
     */
    suspend fun awaitIdle(timeoutMs: Long = 30_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (running.isNotEmpty() || count(DownloadItemState.QUEUED) > 0 || count(DownloadItemState.DOWNLOADING) > 0) {
            check(System.currentTimeMillis() < until) { "downloads still running: ${items(null)}" }
            delay(20)
        }
    }

    /** A new item for [enqueue]. */
    data class NewItem(
        val kind: ExtensionKind,
        val entryId: Int,
        val itemId: Int,
        val sourceId: Long,
        val userId: Int = 1,
    )

    /**
     * Queues [items] at the end, in the order given. An item already queued or downloading stays as it is; a paused or
     * failed one is queued again where it was (its tries reset); a done one (its file gone since) goes to the end.
     * Returns how many were queued.
     */
    fun enqueue(items: List<NewItem>): Int {
        if (items.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val queued =
            dbTransaction {
                var next = nextPosition()
                var count = 0
                for (item in items.distinctBy { it.kind to it.itemId }) {
                    val column = itemColumn(item.kind)
                    val existing = DownloadTable.selectAll().where { column eq item.itemId }.firstOrNull()
                    if (existing == null) {
                        DownloadTable.insert {
                            it[kind] = item.kind
                            it[entryColumn(item.kind)] = item.entryId
                            it[column] = item.itemId
                            it[userId] = item.userId
                            it[sourceId] = item.sourceId
                            it[state] = DownloadItemState.QUEUED
                            it[position] = next++
                            it[addedAt] = now
                        }
                        count++
                        continue
                    }
                    val id = existing[DownloadTable.id].value
                    when (existing[DownloadTable.state]) {
                        DownloadItemState.QUEUED, DownloadItemState.DOWNLOADING -> {
                            Unit
                        }

                        DownloadItemState.PAUSED, DownloadItemState.FAILED -> {
                            DownloadTable.update({ DownloadTable.id eq id }) {
                                it[state] = DownloadItemState.QUEUED
                                it[tries] = 0
                                it[error] = null
                            }
                            retryAt.remove(id)
                            count++
                        }

                        DownloadItemState.DONE -> {
                            DownloadTable.update({ DownloadTable.id eq id }) {
                                it[state] = DownloadItemState.QUEUED
                                it[position] = next++
                                it[progressDone] = 0
                                it[progressTotal] = 0
                                it[bytes] = 0
                                it[tries] = 0
                                it[error] = null
                                it[addedAt] = now
                                it[finishedAt] = 0
                            }
                            count++
                        }
                    }
                }
                count
            }
        if (queued > 0) {
            logger.info { "download queue: $queued queued" }
            changed()
            wakeUp()
        }
        return queued
    }

    /** Pauses [ids] (null: every queued or downloading item of [kind]); a running one stops at once. Returns how many. */
    fun pause(
        kind: ExtensionKind?,
        ids: List<Int>?,
    ): Int {
        val paused =
            synchronized(lock) {
                val targets =
                    select(kind, ids) { DownloadTable.state inList listOf(DownloadItemState.QUEUED, DownloadItemState.DOWNLOADING) }
                if (targets.isEmpty()) return 0
                dbTransaction {
                    DownloadTable.update({ DownloadTable.id inList targets }) { it[state] = DownloadItemState.PAUSED }
                }
                targets.forEach { id ->
                    retryAt.remove(id)
                    running[id]?.job?.cancel()
                }
                targets.size
            }
        changed()
        wakeUp()
        return paused
    }

    /** Queues the paused [ids] again (null: every paused item of [kind]), where they were. Returns how many. */
    fun resume(
        kind: ExtensionKind?,
        ids: List<Int>?,
    ): Int = requeue(kind, ids, DownloadItemState.PAUSED)

    /** Queues the failed [ids] again (null: every failed item of [kind]), their tries reset. Returns how many. */
    fun retry(
        kind: ExtensionKind?,
        ids: List<Int>?,
    ): Int = requeue(kind, ids, DownloadItemState.FAILED)

    private fun requeue(
        kind: ExtensionKind?,
        ids: List<Int>?,
        from: DownloadItemState,
    ): Int {
        val targets = select(kind, ids) { DownloadTable.state eq from }
        if (targets.isEmpty()) return 0
        dbTransaction {
            DownloadTable.update({ (DownloadTable.id inList targets) and (DownloadTable.state eq from) }) {
                it[state] = DownloadItemState.QUEUED
                it[tries] = 0
                it[error] = null
            }
        }
        targets.forEach { retryAt.remove(it) }
        changed()
        wakeUp()
        return targets.size
    }

    /**
     * Removes [ids] from the queue (null: every unfinished item of [kind]), stopping a running one and discarding what
     * they staged. Done items stay (they are the completed list; [clearCompleted] or deleting the file removes them).
     * Returns how many went.
     */
    suspend fun cancel(
        kind: ExtensionKind?,
        ids: List<Int>?,
    ): Int {
        val (items, jobs) =
            synchronized(lock) {
                val targets = select(kind, ids) { DownloadTable.state neq DownloadItemState.DONE }
                if (targets.isEmpty()) return 0
                val items = dbTransaction { DownloadTable.selectAll().where { DownloadTable.id inList targets }.map(::toItem) }
                dbTransaction { DownloadTable.deleteWhere { DownloadTable.id inList targets } }
                targets.forEach { retryAt.remove(it) }
                items to targets.mapNotNull { running[it]?.job }
            }
        jobs.forEach { it.cancelAndJoin() }
        items.forEach { item -> runCatching { downloaders[item.kind]?.discard(item) } }
        logger.info { "download queue: ${items.size} cancelled" }
        changed()
        wakeUp()
        return items.size
    }

    /** Cancels the unfinished downloads of these chapters or episodes (their files are being deleted). */
    suspend fun cancelItems(
        kind: ExtensionKind,
        itemIds: List<Int>,
    ): Int {
        if (itemIds.isEmpty()) return 0
        val ids =
            dbTransaction {
                DownloadTable
                    .select(DownloadTable.id)
                    .where { (itemColumn(kind) inList itemIds) and (DownloadTable.state neq DownloadItemState.DONE) }
                    .map { it[DownloadTable.id].value }
            }
        return if (ids.isEmpty()) 0 else cancel(kind, ids)
    }

    /** Forgets the done rows of these chapters or episodes (their files were deleted). */
    fun forgetDone(
        kind: ExtensionKind,
        itemIds: List<Int>,
    ): Int {
        if (itemIds.isEmpty()) return 0
        val removed =
            dbTransaction {
                DownloadTable.deleteWhere { (itemColumn(kind) inList itemIds) and (state eq DownloadItemState.DONE) }
            }
        if (removed > 0) changed()
        return removed
    }

    /** Removes the done rows of [kind] (null: both) from the completed list; the files stay. Returns how many. */
    fun clearCompleted(kind: ExtensionKind?): Int {
        val removed =
            dbTransaction {
                DownloadTable.deleteWhere {
                    (state eq DownloadItemState.DONE) and (kind?.let { DownloadTable.kind eq it } ?: Op.TRUE)
                }
            }
        if (removed > 0) changed()
        return removed
    }

    /**
     * Moves [id] to place [to] (from 0; past the end: last) among the unfinished items of its kind, the others keeping
     * their order. `NOT_FOUND` for an unknown or done item.
     */
    fun reorder(
        id: Int,
        to: Int,
    ) {
        dbTransaction {
            val row =
                DownloadTable
                    .selectAll()
                    .where { (DownloadTable.id eq id) and (DownloadTable.state neq DownloadItemState.DONE) }
                    .firstOrNull()
                    ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No download $id in the queue", mapOf("id" to id))
            val list = unfinished(row[DownloadTable.kind])
            // the kind's items take the same positions in their new order, so the other kind's stay put between them
            val slots = list.map { it.position }.sorted()
            val moved = list.filter { it.id != id }.toMutableList()
            moved.add(to.coerceIn(0, moved.size), list.first { it.id == id })
            moved.forEachIndexed { i, item ->
                if (item.position != slots[i]) {
                    DownloadTable.update({ DownloadTable.id eq item.id }) { it[position] = slots[i] }
                }
            }
        }
        changed()
        wakeUp()
    }

    /** The unfinished items of [kind] (null: both) in queue order. */
    fun items(kind: ExtensionKind?): List<DownloadItem> =
        dbTransaction {
            DownloadTable
                .selectAll()
                .where { (DownloadTable.state neq DownloadItemState.DONE) and (kind?.let { DownloadTable.kind eq it } ?: Op.TRUE) }
                .orderBy(DownloadTable.position to SortOrder.ASC, DownloadTable.id to SortOrder.ASC)
                .map(::toItem)
        }

    /** The done items of [kind] (null: both), the last finished first, and whether there are more. */
    fun completed(
        kind: ExtensionKind?,
        limit: Int,
        offset: Int,
    ): Pair<List<DownloadItem>, Boolean> =
        dbTransaction {
            val rows =
                DownloadTable
                    .selectAll()
                    .where { (DownloadTable.state eq DownloadItemState.DONE) and (kind?.let { DownloadTable.kind eq it } ?: Op.TRUE) }
                    .orderBy(DownloadTable.finishedAt to SortOrder.DESC, DownloadTable.id to SortOrder.DESC)
                    .limit(limit.coerceIn(1, 500) + 1)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map(::toItem)
            rows.take(limit.coerceIn(1, 500)) to (rows.size > limit.coerceIn(1, 500))
        }

    /** How many items of [state] there are, of [kind] (null: both). */
    fun count(
        state: DownloadItemState,
        kind: ExtensionKind? = null,
    ): Long =
        dbTransaction {
            DownloadTable
                .selectAll()
                .where { (DownloadTable.state eq state) and (kind?.let { DownloadTable.kind eq it } ?: Op.TRUE) }
                .count()
        }

    /** The last time an item of [kind] (null: both) finished, epoch milliseconds; 0 if none did. */
    fun lastFinishedAt(kind: ExtensionKind?): Long =
        dbTransaction {
            DownloadTable
                .select(DownloadTable.finishedAt)
                .where { (DownloadTable.state eq DownloadItemState.DONE) and (kind?.let { DownloadTable.kind eq it } ?: Op.TRUE) }
                .orderBy(DownloadTable.finishedAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(DownloadTable.finishedAt) ?: 0L
        }

    // ---------------------------------------------------------------------------------------------------------------
    // The workers

    private fun wakeUp() {
        wake.trySend(Unit)
    }

    private fun changed() {
        _changes.value += 1
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val waitMs =
                try {
                    schedule()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "download queue: could not start the next downloads" }
                    IDLE_MS
                }
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    private const val IDLE_MS = 60_000L

    /** Starts what the limits allow; returns how long to wait before looking again (a retry or a stagger due). */
    private fun schedule(): Long {
        val settings = DownloadSettingsStore.current()
        var next = IDLE_MS
        var started = false
        synchronized(lock) {
            if (loop == null || running.size >= settings.maxParallel) return next
            val now = System.currentTimeMillis()
            val perSource =
                running.values
                    .groupingBy { it.sourceKey }
                    .eachCount()
                    .toMutableMap()
            val queued =
                dbTransaction {
                    DownloadTable
                        .selectAll()
                        .where { DownloadTable.state eq DownloadItemState.QUEUED }
                        .orderBy(DownloadTable.position to SortOrder.ASC, DownloadTable.id to SortOrder.ASC)
                        .map(::toItem)
                }
            for (item in queued) {
                if (running.size >= settings.maxParallel) break
                if (running.containsKey(item.id)) continue
                val downloader = downloaders[item.kind] ?: continue
                val due = retryAt[item.id]
                if (due != null) {
                    if (due > now) {
                        next = minOf(next, due - now)
                        continue
                    }
                    retryAt.remove(item.id)
                }
                val key = "${item.kind}:${item.sourceId}"
                if ((perSource[key] ?: 0) >= settings.perSource) continue
                val last = lastStart[key]
                if (last != null && now - last < staggerMs) {
                    next = minOf(next, last + staggerMs - now)
                    continue
                }
                val claimed =
                    dbTransaction {
                        DownloadTable.update({ (DownloadTable.id eq item.id) and (DownloadTable.state eq DownloadItemState.QUEUED) }) {
                            it[state] = DownloadItemState.DOWNLOADING
                            it[error] = null
                        }
                    }
                if (claimed != 1) continue
                lastStart[key] = now
                perSource[key] = (perSource[key] ?: 0) + 1
                launch(item.copy(state = DownloadItemState.DOWNLOADING), key, downloader)
                started = true
            }
        }
        if (started) changed()
        return next.coerceAtLeast(1)
    }

    private fun launch(
        item: DownloadItem,
        key: String,
        downloader: ItemDownloader,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val bytes = downloader.download(item) { done, total, b -> progress(item.id, done, total, b) }
                    finished(item, bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed(item, e)
                }
            }
        val entry = Running(job, key)
        running[item.id] = entry
        job.invokeOnCompletion {
            running.remove(item.id, entry)
            changed()
            wakeUp()
        }
        logger.debug { "download ${item.id} (${item.kind} ${item.itemId}) started" }
        job.start()
    }

    private fun progress(
        id: Int,
        done: Int,
        total: Int,
        bytes: Long,
    ) {
        val n =
            dbTransaction {
                DownloadTable.update({ (DownloadTable.id eq id) and (DownloadTable.state eq DownloadItemState.DOWNLOADING) }) {
                    it[progressDone] = done
                    it[progressTotal] = total
                    it[DownloadTable.bytes] = bytes
                }
            }
        if (n > 0) changed()
    }

    private fun finished(
        item: DownloadItem,
        bytes: Long,
    ) {
        dbTransaction {
            DownloadTable.update({ (DownloadTable.id eq item.id) and (DownloadTable.state eq DownloadItemState.DOWNLOADING) }) {
                it[state] = DownloadItemState.DONE
                it[DownloadTable.bytes] = bytes
                it[finishedAt] = System.currentTimeMillis()
                it[error] = null
            }
        }
        logger.info { "download ${item.id} (${item.kind} ${item.itemId}) done, $bytes bytes" }
    }

    private fun failed(
        item: DownloadItem,
        e: Throwable,
    ) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val tries = item.tries + 1
        val final = tries >= maxTries
        val n =
            dbTransaction {
                DownloadTable.update({ (DownloadTable.id eq item.id) and (DownloadTable.state eq DownloadItemState.DOWNLOADING) }) {
                    it[state] = if (final) DownloadItemState.FAILED else DownloadItemState.QUEUED
                    it[DownloadTable.tries] = tries
                    it[error] = message
                }
            }
        if (n == 0) return
        if (final) {
            logger.warn { "download ${item.id} (${item.kind} ${item.itemId}) failed after $tries tries: $message" }
        } else {
            val wait = retryDelaysMs.getOrElse(tries - 1) { retryDelaysMs.lastOrNull() ?: 0 }
            retryAt[item.id] = System.currentTimeMillis() + wait
            logger.info { "download ${item.id} (${item.kind} ${item.itemId}) failed ($message); trying again in $wait ms" }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun itemColumn(kind: ExtensionKind): Column<EntityID<Int>?> =
        when (kind) {
            ExtensionKind.MANGA -> DownloadTable.chapter
            ExtensionKind.ANIME -> DownloadTable.episode
        }

    private fun entryColumn(kind: ExtensionKind): Column<EntityID<Int>?> =
        when (kind) {
            ExtensionKind.MANGA -> DownloadTable.manga
            ExtensionKind.ANIME -> DownloadTable.anime
        }

    private fun nextPosition(): Int =
        (
            DownloadTable
                .select(DownloadTable.position)
                .orderBy(DownloadTable.position to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(DownloadTable.position) ?: 0
        ) + 1

    private fun unfinished(kind: ExtensionKind): List<DownloadItem> =
        DownloadTable
            .selectAll()
            .where { (DownloadTable.state neq DownloadItemState.DONE) and (DownloadTable.kind eq kind) }
            .orderBy(DownloadTable.position to SortOrder.ASC, DownloadTable.id to SortOrder.ASC)
            .map(::toItem)

    // the ids among [ids] (null: all) of [kind] (null: both) that [where] holds for
    private fun select(
        kind: ExtensionKind?,
        ids: List<Int>?,
        where: () -> Op<Boolean>,
    ): List<Int> =
        dbTransaction {
            DownloadTable
                .select(DownloadTable.id)
                .where {
                    where() and
                        (kind?.let { DownloadTable.kind eq it } ?: Op.TRUE) and
                        (ids?.let { DownloadTable.id inList it } ?: Op.TRUE)
                }.map { it[DownloadTable.id].value }
        }

    private fun toItem(row: ResultRow): DownloadItem {
        val kind = row[DownloadTable.kind]
        return DownloadItem(
            id = row[DownloadTable.id].value,
            kind = kind,
            entryId = row[entryColumn(kind)]?.value ?: 0,
            itemId = row[itemColumn(kind)]?.value ?: 0,
            userId = row[DownloadTable.userId],
            sourceId = row[DownloadTable.sourceId],
            state = row[DownloadTable.state],
            position = row[DownloadTable.position],
            progressDone = row[DownloadTable.progressDone],
            progressTotal = row[DownloadTable.progressTotal],
            bytes = row[DownloadTable.bytes],
            tries = row[DownloadTable.tries],
            error = row[DownloadTable.error],
            addedAt = row[DownloadTable.addedAt],
            finishedAt = row[DownloadTable.finishedAt],
        )
    }
}
