package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.database.dbTransaction
import kotlin.math.max

/**
 * Refreshing an anime from its source: details, then its episodes or, for an entry whose `fetch_type` is
 * `Seasons`, its seasons. The counterpart of upstream's `fetchMangaAndChapters` / `updateMangaAndChapters`.
 */
object AnimeDetails {
    /** The anime after the refresh, and its episode ids or season ids (in the source's order; one list is empty). */
    data class Result(
        val animeId: Int,
        val fetchType: FetchType,
        val episodeIds: List<Int>,
        val seasonIds: List<Int>,
    )

    // one refresh of an anime at a time (upstream's mangaInfoMutex), striped so it never grows
    private val locks = Array(64) { Mutex() }

    /**
     * Refreshes the anime [animeId]: its details if [fetchDetails], its episodes or seasons ("children") if
     * [fetchChildren]. Which children is decided by the entry's `fetch_type`, after the details fetch when there is
     * one. An entry that is initialized and has episodes gets both in one `getAnimeEpisodeUpdate` call (the source
     * fetches them in parallel); otherwise details come first.
     */
    suspend fun fetch(
        animeId: Int,
        fetchDetails: Boolean = true,
        fetchChildren: Boolean = true,
    ): Result =
        locks[Math.floorMod(animeId, locks.size)].withLock {
            val row = AnimeEntries.animeRow(animeId)
            val source = AnimeSourceAccess.require(row[AnimeTable.sourceReference])
            var anime = AnimeEntries.toSAnime(row)
            var episodesFetched: List<SEpisode>? = null

            if (fetchDetails) {
                val together = fetchChildren && row[AnimeTable.initialized] && row[AnimeTable.fetchType] == FetchType.Episodes
                val update =
                    AnimeSourceCalls.call(source, if (together) "details+episodes" else "details") {
                        source.getAnimeEpisodeUpdate(anime, storedEpisodes(animeId), fetchDetails = true, fetchEpisodes = together)
                    }
                AnimeEntries.writeDetails(animeId, update.anime)
                anime = AnimeEntries.toSAnime(AnimeEntries.animeRow(animeId))
                if (together) episodesFetched = update.episodes
            }

            if (!fetchChildren) return@withLock Result(animeId, anime.fetch_type, currentEpisodeIds(animeId), currentSeasonIds(animeId))

            when (anime.fetch_type) {
                FetchType.Episodes -> {
                    val episodes =
                        episodesFetched
                            ?: AnimeSourceCalls
                                .call(source, "episodes") {
                                    source.getAnimeEpisodeUpdate(anime, storedEpisodes(animeId), fetchDetails = false, fetchEpisodes = true)
                                }.episodes
                    Result(animeId, FetchType.Episodes, reconcileEpisodes(animeId, episodes), emptyList())
                }

                FetchType.Seasons -> {
                    val seasons =
                        AnimeSourceCalls
                            .call(source, "seasons") {
                                source.getAnimeSeasonUpdate(anime, storedSeasons(animeId), fetchDetails = false, fetchSeasons = true)
                            }.seasons
                    Result(animeId, FetchType.Seasons, emptyList(), reconcileSeasons(row, seasons))
                }
            }
        }

    // what the DB has, handed to the source with the entry (a lib-17 source may update it rather than refetch)
    private fun storedEpisodes(animeId: Int): List<SEpisode> =
        dbTransaction {
            EpisodeTable
                .selectAll()
                .where { EpisodeTable.anime eq animeId }
                .orderBy(EpisodeTable.sourceOrder to SortOrder.DESC)
                .map(AnimeEntries::toSEpisode)
        }

    private fun storedSeasons(animeId: Int): List<SAnime> =
        dbTransaction {
            AnimeTable
                .selectAll()
                .where { AnimeTable.parentId eq animeId }
                .orderBy(AnimeTable.seasonSourceOrder to SortOrder.ASC)
                .map(AnimeEntries::toSAnime)
        }

    /** The anime's episode ids in the source's order (newest first, as `source_order` descending). */
    fun currentEpisodeIds(animeId: Int): List<Int> =
        dbTransaction {
            EpisodeTable
                .selectAll()
                .where { EpisodeTable.anime eq animeId }
                .orderBy(EpisodeTable.sourceOrder to SortOrder.DESC)
                .map { it[EpisodeTable.id].value }
        }

    /** The anime's season ids in the source's order. */
    fun currentSeasonIds(animeId: Int): List<Int> =
        dbTransaction {
            AnimeTable
                .selectAll()
                .where { AnimeTable.parentId eq animeId }
                .orderBy(AnimeTable.seasonSourceOrder to SortOrder.ASC)
                .map { it[AnimeTable.id].value }
        }

    private class WatchState(
        val watched: Boolean,
        val bookmark: Boolean,
        val lastSecondSeen: Long,
        val totalSeconds: Long,
    )

    /**
     * Reconciles `EPISODE` with [fetched], the source's list (newest first), like upstream's
     * `Chapter.updateChapterListDatabase`:
     * - by url (the list is deduplicated, episodes without a url are left out);
     * - `source_order` counts from the oldest (1) to the newest, as upstream numbers chapters;
     * - an episode the list no longer has is deleted (its history goes with it), and its watch state moves to a
     *   new episode with the same number (≥ 0), as upstream carries read state over;
     * - an update never touches the watch state (`watched`, `bookmark`, `last_second_seen`, `total_seconds`) or
     *   `fetched_at`; a `date_upload` of 0 keeps the stored date, and a new episode without one gets the newest date
     *   of the older episodes in the list (or now), as upstream does;
     * - an empty list changes nothing: an anime can have no episodes yet (upstream throws "No chapters found").
     *
     * Returns the episode ids in [fetched]'s order.
     */
    internal fun reconcileEpisodes(
        animeId: Int,
        fetched: List<SEpisode>,
    ): List<Int> {
        val episodes = fetched.mapNotNull { episode -> AnimeEntries.urlOf(episode)?.let { it to episode } }.distinctBy { it.first }
        if (episodes.isEmpty()) return currentEpisodeIds(animeId)
        val now = System.currentTimeMillis()

        return dbTransaction {
            val inDb = EpisodeTable.selectAll().where { EpisodeTable.anime eq animeId }.associateBy { it[EpisodeTable.url] }
            val urls = episodes.map { it.first }.toSet()

            val gone = inDb.values.filter { it[EpisodeTable.url] !in urls }
            val carried =
                gone
                    .filter { it[EpisodeTable.episodeNumber] >= 0f }
                    .associate {
                        it[EpisodeTable.episodeNumber] to
                            WatchState(
                                it[EpisodeTable.watched],
                                it[EpisodeTable.bookmark],
                                it[EpisodeTable.lastSecondSeen],
                                it[EpisodeTable.totalSeconds],
                            )
                    }
            if (gone.isNotEmpty()) {
                EpisodeTable.deleteWhere { EpisodeTable.id inList gone.map { it[EpisodeTable.id] } }
            }

            val ids = HashMap<String, Int>()
            var newestDate = 0L
            // oldest first, so source_order counts up and a new episode without a date inherits an older one's
            episodes.asReversed().forEachIndexed { index, (url, episode) ->
                val order = index + 1
                val existing = inDb[url]
                if (existing != null) {
                    val id = existing[EpisodeTable.id]
                    EpisodeTable.update({ EpisodeTable.id eq id }) {
                        it[EpisodeTable.name] = nameOf(episode, existing[EpisodeTable.name])
                        it[EpisodeTable.episodeNumber] = episode.episode_number
                        it[EpisodeTable.dateUpload] = episode.date_upload.takeIf { d -> d != 0L } ?: existing[EpisodeTable.dateUpload]
                        it[EpisodeTable.scanlator] = episode.scanlator
                        it[EpisodeTable.fillermark] = episode.fillermark
                        it[EpisodeTable.summary] = episode.summary
                        it[EpisodeTable.previewUrl] = episode.preview_url
                        it[EpisodeTable.memo] = episode.memo
                        it[EpisodeTable.sourceOrder] = order
                    }
                    newestDate = max(newestDate, episode.date_upload)
                    ids[url] = id.value
                } else {
                    val date =
                        if (episode.date_upload != 0L) {
                            newestDate = max(newestDate, episode.date_upload)
                            episode.date_upload
                        } else {
                            if (newestDate == 0L) now else newestDate
                        }
                    val state = carried[episode.episode_number].takeIf { episode.episode_number >= 0f }
                    ids[url] =
                        EpisodeTable
                            .insertAndGetId {
                                it[EpisodeTable.url] = url
                                it[EpisodeTable.name] = nameOf(episode, url)
                                it[EpisodeTable.episodeNumber] = episode.episode_number
                                it[EpisodeTable.dateUpload] = date
                                it[EpisodeTable.scanlator] = episode.scanlator
                                it[EpisodeTable.fillermark] = episode.fillermark
                                it[EpisodeTable.summary] = episode.summary
                                it[EpisodeTable.previewUrl] = episode.preview_url
                                it[EpisodeTable.memo] = episode.memo
                                it[EpisodeTable.sourceOrder] = order
                                it[EpisodeTable.fetchedAt] = now / 1000
                                it[EpisodeTable.anime] = animeId
                                if (state != null) {
                                    it[EpisodeTable.watched] = state.watched
                                    it[EpisodeTable.bookmark] = state.bookmark
                                    it[EpisodeTable.lastSecondSeen] = state.lastSecondSeen
                                    it[EpisodeTable.totalSeconds] = state.totalSeconds
                                }
                            }.value
                }
            }
            episodes.map { ids.getValue(it.first) }
        }
    }

    // `name` is lateinit in SEpisode
    private fun nameOf(
        episode: SEpisode,
        fallback: String,
    ): String = runCatching { episode.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * Reconciles the seasons of [parent] with [fetched], the source's season list. A season is an `ANIME` row of the
     * same source, upserted by `(source, url)` like a browsed entry, pointing at its parent (`parent_id`) with its
     * position in the list (`season_source_order`, from 1). A season the list no longer has only loses its parent: it
     * may be in the library. An empty list changes nothing.
     *
     * Returns the season ids in [fetched]'s order.
     */
    internal fun reconcileSeasons(
        parent: ResultRow,
        fetched: List<SAnime>,
    ): List<Int> {
        val parentId = parent[AnimeTable.id].value
        if (fetched.isEmpty()) return currentSeasonIds(parentId)
        val ids = AnimeEntries.upsert(parent[AnimeTable.sourceReference], fetched).filter { it != parentId }
        dbTransaction {
            AnimeTable.update({ (AnimeTable.parentId eq parentId) and (AnimeTable.id notInList ids) }) {
                it[AnimeTable.parentId] = null
            }
            ids.forEachIndexed { index, id ->
                AnimeTable.update({ AnimeTable.id eq id }) {
                    it[AnimeTable.parentId] = parentId
                    it[AnimeTable.seasonSourceOrder] = index + 1
                }
            }
        }
        return ids
    }
}
