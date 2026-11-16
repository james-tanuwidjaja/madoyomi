package madoyomi.core.library

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import madoyomi.core.anime.AnimeCategories
import madoyomi.core.anime.AnimeDetails
import madoyomi.core.anime.AnimeEntries
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.settings.userConfig
import suwayomi.tachidesk.server.settings.value

/**
 * The library update check (Phase 6 Stage 3) for both kinds: each library entry of the chosen categories gets its
 * episode or chapter list fetched again, the list is diffed against the database (upstream's
 * `Chapter.updateChapterListDatabase` for manga, [AnimeDetails.reconcileEpisodes] for anime), and an entry that got
 * new items is marked ([LibraryUpdateMarks]). Madoyomi's own in place of upstream's manga `Updater`, whose schedule is
 * off (server/UPSTREAM.md "Library updates (Phase 6)"), so one queue, one status and one schedule cover both kinds.
 *
 * - **Which entries:** upstream's category rule. Without a category, the categories flagged INCLUDE are checked, else
 *   the UNSET ones, and an entry in an EXCLUDE category is left out; with a category, that category's entries
 *   whatever its flag. Entries whose source said "fetch once" are left out, and so are the ones the settings skip
 *   (completed, with unwatched or unread items, not started: [LibraryUpdateSettings]).
 * - **Rate limits:** the entries of one source are checked one at a time, [staggerMs] apart; at most
 *   `serverConfig.maxSourcesInParallel` sources (upstream's setting, 6) are checked at once. Each source's own rate
 *   limits (its OkHttp interceptors) apply on top.
 * - **New items:** the items inserted by the check (`fetched_at` from its start; a re-listed item keeps its old date).
 *   An entry that had none stored before gets none counted: its first fetch is not news.
 * - **One run at a time.** A check started while one runs adds the entries not queued yet to it.
 */
object LibraryUpdater {
    private val logger = KotlinLogging.logger {}

    enum class Trigger { MANUAL, SCHEDULED }

    /** What to check: the library of a kind ([categoryId] null), or one category of it. Manga is per user. */
    data class Target(
        val kind: ExtensionKind,
        val categoryId: Int? = null,
        val userId: Int = 1,
    )

    data class Entry(
        val kind: ExtensionKind,
        val id: Int,
        val title: String,
        val sourceId: Long,
        val userId: Int = 1,
    )

    data class Failure(
        val entry: Entry,
        val error: String,
    )

    data class Found(
        val entry: Entry,
        val newCount: Int,
    )

    data class Status(
        val running: Boolean = false,
        val trigger: Trigger? = null,
        /** Epoch milliseconds; 0 before the first check. */
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        val total: Int = 0,
        /** Entries checked, failed ones included. */
        val done: Int = 0,
        /** Entries of the chosen categories left out by the settings or their update strategy. */
        val skipped: Int = 0,
        val checking: List<Entry> = emptyList(),
        val failures: List<Failure> = emptyList(),
        val found: List<Found> = emptyList(),
        val cancelled: Boolean = false,
    ) {
        fun newCount(kind: ExtensionKind): Int = found.filter { it.entry.kind == kind }.sumOf { it.newCount }
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Milliseconds between two entries (and two season fetches) of the same source. */
    @Volatile
    var staggerMs: Long = 1_000

    /** Tests: the number of sources checked at once, in place of `serverConfig.maxSourcesInParallel`. */
    @Volatile
    var parallelSourcesOverride: Int? = null

    /**
     * Called with the ids of the new chapters or episodes a check found in an entry (the download queue's automatic
     * downloads: Stage 4's [madoyomi.core.downloads.MangaDownloads.autoDownload], Stage 5's
     * [madoyomi.core.downloads.AnimeDownloads.autoDownload]).
     */
    @Volatile
    var onNewItems: ((Entry, List<Int>) -> Unit)? = null

    /** Tests: what checking an entry does (returns its new item count); null for the real fetch. */
    @Volatile
    var checkOverride: (suspend (Entry) -> Int)? = null

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Run(
        permits: Int,
    ) {
        val job = SupervisorJob()
        val semaphore = Semaphore(permits)
        val channels = HashMap<String, Channel<Entry>>()
        val queued = HashSet<String>()
    }

    private var run: Run? = null

    /**
     * Starts a check of [targets] (or adds them to the one running) and returns the status. A target naming an
     * unknown category is `NOT_FOUND`, before anything starts.
     */
    fun start(
        targets: List<Target>,
        trigger: Trigger,
    ): Status {
        val settings = LibraryUpdateSettingsStore.current()
        val selections = targets.map { select(it, settings) }
        val entries = selections.flatMap { it.first }.distinctBy { key(it) }
        val skipped = selections.sumOf { it.second }
        synchronized(lock) {
            val current = run
            val now = System.currentTimeMillis()
            val active = current != null && _status.value.running
            val r = if (active) current!! else Run(parallelSources()).also { run = it }
            val fresh = entries.filter { r.queued.add(key(it)) }
            val base =
                if (active) {
                    _status.value
                } else {
                    Status(running = true, trigger = trigger, startedAt = now)
                }
            val next = base.copy(total = base.total + fresh.size, skipped = base.skipped + skipped)
            if (next.total == next.done) {
                _status.value = next.copy(running = false, finishedAt = now)
                if (!active) run = null
                logger.info { "library update ($trigger): nothing to check, $skipped skipped" }
                return _status.value
            }
            _status.value = next
            logger.info {
                "library update ($trigger): ${fresh.size} entries to check" +
                    (if (active) " added to the running check" else "") + ", $skipped skipped"
            }
            fresh.forEach { entry ->
                r.channels
                    .getOrPut("${entry.kind}:${entry.sourceId}") { worker(r) }
                    .trySend(entry)
            }
            return _status.value
        }
    }

    /** Stops the running check; what was inserted so far stays. */
    fun stop(): Status {
        synchronized(lock) {
            val r = run ?: return _status.value
            run = null
            r.job.cancel()
            r.channels.values.forEach { it.close() }
            if (_status.value.running) {
                _status.value =
                    _status.value.copy(
                        running = false,
                        checking = emptyList(),
                        cancelled = true,
                        finishedAt = System.currentTimeMillis(),
                    )
                logger.info { "library update stopped: ${_status.value.done} of ${_status.value.total} checked" }
            }
            return _status.value
        }
    }

    /** Tests: waits until no check runs. */
    suspend fun awaitIdle(timeoutMs: Long = 30_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (_status.value.running) {
            check(System.currentTimeMillis() < until) { "library update still running: ${_status.value}" }
            delay(20)
        }
    }

    /** Tests: forgets the last status. */
    fun reset() {
        stop()
        _status.value = Status()
    }

    private fun parallelSources(): Int = (parallelSourcesOverride ?: serverConfig.maxSourcesInParallel.value).coerceIn(1, 20)

    private fun key(entry: Entry) = "${entry.kind}:${entry.id}:${entry.userId}"

    // one source's entries, one at a time and staggerMs apart, each holding one of the run's source permits
    private fun worker(r: Run): Channel<Entry> {
        val channel = Channel<Entry>(Channel.UNLIMITED)
        scope.launch(r.job) {
            var first = true
            for (entry in channel) {
                if (!first && staggerMs > 0) delay(staggerMs)
                first = false
                r.semaphore.withPermit { checkEntry(r, entry) }
            }
        }
        return channel
    }

    private suspend fun checkEntry(
        r: Run,
        entry: Entry,
    ) {
        update(r) { it.copy(checking = it.checking + entry) }
        val result =
            try {
                Result.success(checkOverride?.invoke(entry) ?: fetch(entry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "library update: ${entry.kind} ${entry.id} '${entry.title}' failed: ${e.message ?: e.javaClass.name}" }
                Result.failure(e)
            }
        val count = result.getOrNull() ?: 0
        if (count > 0) {
            val at = System.currentTimeMillis() / 1000
            when (entry.kind) {
                ExtensionKind.ANIME -> LibraryUpdateMarks.addAnime(entry.id, count, at)
                ExtensionKind.MANGA -> LibraryUpdateMarks.addManga(entry.userId, entry.id, count, at)
            }
        }
        val finished =
            update(r) { s ->
                s.copy(
                    done = s.done + 1,
                    checking = s.checking - entry,
                    found = if (count > 0) s.found + Found(entry, count) else s.found,
                    failures =
                        result.exceptionOrNull()?.let { s.failures + Failure(entry, it.message ?: it.javaClass.simpleName) }
                            ?: s.failures,
                )
            }
        if (finished) {
            synchronized(lock) {
                if (run === r && _status.value.done >= _status.value.total) {
                    run = null
                    r.channels.values.forEach { it.close() }
                    _status.value = _status.value.copy(running = false, checking = emptyList(), finishedAt = System.currentTimeMillis())
                    val s = _status.value
                    logger.info {
                        "library update (${s.trigger}) done: ${s.done} checked, ${s.failures.size} failed, " +
                            "${s.newCount(ExtensionKind.ANIME)} new episodes, ${s.newCount(ExtensionKind.MANGA)} new chapters"
                    }
                }
            }
        }
    }

    /** Applies [change] while [r] is the running check; returns whether every entry is done. */
    private fun update(
        r: Run,
        change: (Status) -> Status,
    ): Boolean =
        synchronized(lock) {
            if (run !== r) return false
            _status.value = change(_status.value)
            _status.value.done >= _status.value.total
        }

    // the real check of one entry: the fetch and the diff, then the number of items it inserted
    private suspend fun fetch(entry: Entry): Int =
        when (entry.kind) {
            ExtensionKind.ANIME -> fetchAnime(entry)
            ExtensionKind.MANGA -> fetchManga(entry)
        }

    private suspend fun fetchAnime(entry: Entry): Int {
        val ids = newEpisodes(entry.id)
        if (ids.isNotEmpty()) {
            // Stage 5: the anime downloads' turn (a Seasons entry's new episodes are its seasons')
            runCatching { onNewItems?.invoke(entry, ids) }
                .onFailure { logger.warn(it) { "library update: what follows the new episodes of anime ${entry.id} failed" } }
        }
        return ids.size
    }

    // the ids of the episodes the check of [animeId] inserted
    private suspend fun newEpisodes(animeId: Int): List<Int> {
        val row = AnimeEntries.animeRow(animeId)
        val initialized = row[AnimeTable.initialized]
        if (row[AnimeTable.fetchType] == FetchType.Seasons) {
            // the season list, then the episodes of each season already fetched once (a season never opened is left
            // for its first visit, which would otherwise count its whole list as new)
            val result = AnimeDetails.fetch(animeId, fetchDetails = !initialized, fetchChildren = true)
            val ids = mutableListOf<Int>()
            for (seasonId in result.seasonIds) {
                if (episodeCount(seasonId) == 0L) continue
                if (staggerMs > 0) delay(staggerMs)
                ids += fetchEpisodes(seasonId, fetchDetails = false)
            }
            return ids
        }
        return fetchEpisodes(animeId, fetchDetails = !initialized)
    }

    private suspend fun fetchEpisodes(
        animeId: Int,
        fetchDetails: Boolean,
    ): List<Int> {
        val before = episodes(animeId).keys
        val since = System.currentTimeMillis() / 1000
        AnimeDetails.fetch(animeId, fetchDetails = fetchDetails, fetchChildren = true)
        return newItemIds(before, since, episodes(animeId))
    }

    // id -> fetched_at of an anime's episodes
    private fun episodes(animeId: Int): Map<Int, Long> =
        dbTransaction {
            EpisodeTable
                .select(EpisodeTable.id, EpisodeTable.fetchedAt)
                .where { EpisodeTable.anime eq animeId }
                .associate { it[EpisodeTable.id].value to it[EpisodeTable.fetchedAt] }
        }

    private fun episodeCount(animeId: Int): Long =
        dbTransaction { EpisodeTable.selectAll().where { EpisodeTable.anime eq animeId }.count() }

    private suspend fun fetchManga(entry: Entry): Int {
        val before = chapters(entry.id).keys
        val initialized =
            dbTransaction {
                MangaTable
                    .select(MangaTable.initialized)
                    .where { MangaTable.id eq entry.id }
                    .single()[MangaTable.initialized]
            }
        val since = System.currentTimeMillis() / 1000
        // upstream's Updater: the details too when the user's setting says so or the entry was never initialized
        Manga.updateMangaAndChapters(entry.id, updateManga = !initialized || userConfig.updateMangas.value(entry.userId))
        val ids = newItemIds(before, since, chapters(entry.id))
        if (ids.isNotEmpty()) {
            runCatching { onNewItems?.invoke(entry, ids) }
                .onFailure { logger.warn(it) { "library update: what follows the new chapters of manga ${entry.id} failed" } }
        }
        return ids.size
    }

    // id -> fetched_at of a manga's chapters
    private fun chapters(mangaId: Int): Map<Int, Long> =
        dbTransaction {
            ChapterTable
                .select(ChapterTable.id, ChapterTable.fetchedAt)
                .where { ChapterTable.manga eq mangaId }
                .associate { it[ChapterTable.id].value to it[ChapterTable.fetchedAt] }
        }

    /**
     * The items the check inserted, re-listed ones left out (they keep their first fetch date), or none when the entry
     * had nothing stored before (its first fetch).
     */
    internal fun newItems(
        before: Set<Int>,
        since: Long,
        after: Map<Int, Long>,
    ): Int = newItemIds(before, since, after).size

    /** The ids of the items [newItems] counts. */
    internal fun newItemIds(
        before: Set<Int>,
        since: Long,
        after: Map<Int, Long>,
    ): List<Int> =
        if (before.isEmpty()) {
            emptyList()
        } else {
            after
                .filter { (id, fetchedAt) ->
                    id !in before && fetchedAt >= since
                }.keys
                .toList()
        }

    /** The entries of [target] to check, and how many of its library entries the settings or strategies leave out. */
    internal fun select(
        target: Target,
        settings: LibraryUpdateSettings,
    ): Pair<List<Entry>, Int> =
        when (target.kind) {
            ExtensionKind.ANIME -> selectAnime(target.categoryId, settings)
            ExtensionKind.MANGA -> selectManga(target.userId, target.categoryId, settings)
        }

    /** Upstream's rule (`Updater.addCategoriesToUpdateQueue`): the categories checked and the ones excluding entries. */
    private fun <C> chooseCategories(
        all: List<C>,
        flag: (C) -> IncludeOrExclude,
    ): Pair<List<C>, List<C>> {
        val byFlag = all.groupBy(flag)
        val chosen = byFlag[IncludeOrExclude.INCLUDE].orEmpty().ifEmpty { byFlag[IncludeOrExclude.UNSET].orEmpty() }
        return chosen to byFlag[IncludeOrExclude.EXCLUDE].orEmpty()
    }

    private fun selectAnime(
        categoryId: Int?,
        settings: LibraryUpdateSettings,
    ): Pair<List<Entry>, Int> {
        val flag = { row: ResultRow -> IncludeOrExclude.fromValue(row[AnimeCategoryTable.includeInUpdate]) }
        val (chosen, excluded) =
            if (categoryId != null) {
                listOf(AnimeCategories.row(categoryId)) to emptyList()
            } else {
                chooseCategories(AnimeCategories.list(), flag)
            }
        val rows =
            chosen
                .flatMap { AnimeCategories.libraryAnimeIn(it[AnimeCategoryTable.id].value) }
                .distinctBy { it[AnimeTable.id].value }
        val ids = rows.map { it[AnimeTable.id].value }
        val excludedIds = excluded.map { it[AnimeCategoryTable.id].value }.toSet()
        val categoriesOf = if (excludedIds.isEmpty()) emptyMap() else AnimeCategories.categoriesOf(ids)
        val stats = if (settings.skipUnseen || settings.skipNotStarted) AnimeHistory.stats(ids) else emptyMap()
        val started = if (settings.skipNotStarted) startedAnime(ids) else emptySet()
        val kept =
            rows.filter { row ->
                val id = row[AnimeTable.id].value
                row[AnimeTable.updateStrategy] == AnimeUpdateStrategy.ALWAYS_UPDATE &&
                    !(settings.skipCompleted && row[AnimeTable.status] == SAnime.COMPLETED) &&
                    !(settings.skipUnseen && (stats[id]?.unwatchedCount ?: 0) > 0) &&
                    !(settings.skipNotStarted && row[AnimeTable.initialized] && id !in started) &&
                    categoriesOf[id].orEmpty().none { it[AnimeCategoryTable.id].value in excludedIds }
            }
        val entries =
            kept
                .map { Entry(ExtensionKind.ANIME, it[AnimeTable.id].value, it[AnimeTable.title], it[AnimeTable.sourceReference]) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Entry::title))
        return entries to rows.size - kept.size
    }

    // anime with something watched or played (their seasons' episodes count for a Seasons entry)
    private fun startedAnime(ids: List<Int>): Set<Int> {
        if (ids.isEmpty()) return emptySet()
        return dbTransaction {
            val owner = HashMap<Int, Int>()
            ids.forEach { owner[it] = it }
            AnimeTable
                .select(AnimeTable.id, AnimeTable.parentId)
                .where { AnimeTable.parentId inList ids }
                .forEach { owner[it[AnimeTable.id].value] = it[AnimeTable.parentId]!!.value }
            val history = AnimeHistory.stats(ids).filterValues { it.lastSeenAt > 0 }.keys
            val watched =
                EpisodeTable
                    .select(EpisodeTable.anime)
                    .where {
                        (EpisodeTable.anime inList owner.keys.toList()) and
                            ((EpisodeTable.watched eq true) or (EpisodeTable.lastSecondSeen greaterEq 1L))
                    }.mapNotNull { owner[it[EpisodeTable.anime].value] }
                    .toSet()
            history + watched
        }
    }

    private fun selectManga(
        userId: Int,
        categoryId: Int?,
        settings: LibraryUpdateSettings,
    ): Pair<List<Entry>, Int> {
        val all = Category.getCategoryList(userId)
        val (chosen, excluded) =
            if (categoryId != null) {
                val one =
                    Category.getCategoryList(userId, includeDefault = true).firstOrNull { it.id == categoryId }
                        ?: throw MadoyomiException(
                            ErrorCode.NOT_FOUND,
                            "No manga category with id $categoryId",
                            mapOf("categoryId" to categoryId),
                        )
                listOf(one) to emptyList()
            } else {
                chooseCategories(all) { it.includeInUpdate }
            }
        val mangas = chosen.flatMap { CategoryManga.getCategoryMangaList(userId, it.id) }.distinctBy { it.id }
        val excludedIds = excluded.map { it.id }.toSet()
        val categoriesOf = if (excludedIds.isEmpty()) emptyMap() else CategoryManga.getMangasCategories(userId, mangas.map { it.id })
        // upstream's filters, with Madoyomi's settings in place of the per-user excludeUnreadChapters, excludeNotStarted
        // and excludeCompleted
        val kept =
            mangas.filter { m ->
                m.updateStrategy == UpdateStrategy.ALWAYS_UPDATE &&
                    !(settings.skipUnseen && (m.unreadCount ?: 0L) > 0L) &&
                    !(settings.skipNotStarted && m.initialized && (m.lastReadAt ?: 0L) <= 0L) &&
                    !(settings.skipCompleted && m.status == MangaStatus.COMPLETED.name) &&
                    categoriesOf[m.id].orEmpty().none { it.id in excludedIds }
            }
        val entries =
            kept
                .map { Entry(ExtensionKind.MANGA, it.id, it.title, it.sourceId.toLong(), userId) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Entry::title))
        return entries to mangas.size - kept.size
    }
}
