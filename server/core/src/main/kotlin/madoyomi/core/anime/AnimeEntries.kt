package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * `ANIME`/`EPISODE` rows and the extension models: the mapping both ways, and the browse upsert that keeps an
 * entry's id stable by `(source, url)` (the unique key of M0071).
 */
object AnimeEntries {
    private val logger = KotlinLogging.logger {}

    /** The row [animeId], or `NOT_FOUND`. */
    fun animeRow(animeId: Int): ResultRow =
        dbTransaction { AnimeTable.selectAll().where { AnimeTable.id eq animeId }.singleOrNull() }
            ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No anime with id $animeId", mapOf("animeId" to animeId))

    /** The row [episodeId], or `NOT_FOUND`. */
    fun episodeRow(episodeId: Int): ResultRow =
        dbTransaction { EpisodeTable.selectAll().where { EpisodeTable.id eq episodeId }.singleOrNull() }
            ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No episode with id $episodeId", mapOf("episodeId" to episodeId))

    /** The entry as the extension sees it, with what the DB knows (its memo included). */
    fun toSAnime(row: ResultRow): SAnime =
        SAnime.create().apply {
            url = row[AnimeTable.url]
            title = row[AnimeTable.title]
            artist = row[AnimeTable.artist]
            author = row[AnimeTable.author]
            description = row[AnimeTable.description]
            genre = row[AnimeTable.genre]
            status = row[AnimeTable.status]
            thumbnail_url = row[AnimeTable.thumbnailUrl]
            background_url = row[AnimeTable.backgroundUrl]
            update_strategy = row[AnimeTable.updateStrategy]
            fetch_type = row[AnimeTable.fetchType]
            season_number = row[AnimeTable.seasonNumber]
            initialized = row[AnimeTable.initialized]
            memo = row[AnimeTable.memo]
        }

    fun toSEpisode(row: ResultRow): SEpisode =
        SEpisode.create().apply {
            url = row[EpisodeTable.url]
            name = row[EpisodeTable.name]
            episode_number = row[EpisodeTable.episodeNumber]
            fillermark = row[EpisodeTable.fillermark]
            scanlator = row[EpisodeTable.scanlator]
            date_upload = row[EpisodeTable.dateUpload]
            summary = row[EpisodeTable.summary]
            preview_url = row[EpisodeTable.previewUrl]
            memo = row[EpisodeTable.memo]
        }

    /** The url of [anime], or null if the extension never set it (`url` is `lateinit`). */
    internal fun urlOf(anime: SAnime): String? = runCatching { anime.url }.getOrNull()?.takeIf { it.isNotEmpty() }

    internal fun urlOf(episode: SEpisode): String? = runCatching { episode.url }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The title of [anime], or null if the extension left it unset or blank (`title` is `lateinit`). */
    internal fun titleOf(anime: SAnime): String? = runCatching { anime.title }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Upserts browsed [animes] of [sourceId] into `ANIME` by `(source, url)` and returns their ids, in page order,
     * each once. Upstream's `MangaList.insertOrUpdate`, plus:
     * - the page is deduplicated by url first (upstream inserted a url twice if a page listed it twice);
     * - entries without a url are left out;
     * - a unique-key violation (another browse inserted the same url meanwhile) is retried once, which then finds
     *   the row.
     *
     * New rows get every field. Existing rows outside the library get the browse fields: title and status, and the
     * nullable fields where the source gave one (upstream's rule). Library rows are left alone: their details come
     * from a details fetch only.
     */
    fun upsert(
        sourceId: Long,
        animes: List<SAnime>,
    ): List<Int> {
        val page = animes.mapNotNull { anime -> urlOf(anime)?.let { it to anime } }.distinctBy { it.first }
        if (page.size < animes.size) {
            logger.debug { "Anime source $sourceId: ${animes.size - page.size} entries without a url or listed twice" }
        }
        if (page.isEmpty()) return emptyList()
        return try {
            upsertOnce(sourceId, page)
        } catch (e: ExposedSQLException) {
            logger.debug { "Anime source $sourceId: browse upsert raced (${e.message}), retrying" }
            upsertOnce(sourceId, page)
        }
    }

    private fun upsertOnce(
        sourceId: Long,
        page: List<Pair<String, SAnime>>,
    ): List<Int> =
        dbTransaction {
            val urls = page.map { it.first }
            val existing =
                AnimeTable
                    .selectAll()
                    .where { (AnimeTable.sourceReference eq sourceId) and (AnimeTable.url inList urls) }
                    .associateBy { it[AnimeTable.url] }

            val inserted =
                AnimeTable
                    .batchInsert(page.filter { it.first !in existing }) { (url, anime) ->
                        this[AnimeTable.url] = url
                        this[AnimeTable.sourceReference] = sourceId
                        this[AnimeTable.title] = titleOf(anime) ?: url
                        this[AnimeTable.status] = anime.status
                        this[AnimeTable.artist] = anime.artist
                        this[AnimeTable.author] = anime.author
                        this[AnimeTable.description] = anime.description
                        this[AnimeTable.genre] = anime.genre
                        this[AnimeTable.thumbnailUrl] = anime.thumbnail_url
                        this[AnimeTable.backgroundUrl] = anime.background_url
                        this[AnimeTable.updateStrategy] = anime.update_strategy
                        this[AnimeTable.fetchType] = anime.fetch_type
                        this[AnimeTable.seasonNumber] = anime.season_number
                        this[AnimeTable.memo] = anime.memo
                    }.associate { it[AnimeTable.url] to it[AnimeTable.id].value }

            page.forEach { (url, anime) ->
                val row = existing[url] ?: return@forEach
                if (row[AnimeTable.inLibrary]) return@forEach
                AnimeTable.update({ AnimeTable.id eq row[AnimeTable.id] }) { it.browseFields(anime, row) }
            }

            val ids = existing.mapValues { it.value[AnimeTable.id].value } + inserted
            urls.map { ids.getValue(it) }
        }

    /** What a browse (or a season list) may change on an existing row. */
    private fun UpdateBuilder<*>.browseFields(
        anime: SAnime,
        row: ResultRow,
    ) {
        titleOf(anime)?.let { this[AnimeTable.title] = it }
        this[AnimeTable.status] = anime.status
        this[AnimeTable.artist] = anime.artist ?: row[AnimeTable.artist]
        this[AnimeTable.author] = anime.author ?: row[AnimeTable.author]
        this[AnimeTable.description] = anime.description ?: row[AnimeTable.description]
        this[AnimeTable.genre] = anime.genre ?: row[AnimeTable.genre]
        this[AnimeTable.thumbnailUrl] = anime.thumbnail_url ?: row[AnimeTable.thumbnailUrl]
        this[AnimeTable.backgroundUrl] = anime.background_url ?: row[AnimeTable.backgroundUrl]
        this[AnimeTable.updateStrategy] = anime.update_strategy
        this[AnimeTable.fetchType] = anime.fetch_type
        this[AnimeTable.seasonNumber] = anime.season_number
        this[AnimeTable.memo] = anime.memo
    }

    /**
     * Writes a details fetch ([details], the extension's answer for [animeId]) like upstream's `updateMangaDatabase`:
     * the url stays the DB's (a details answer often has none), unset or null fields keep the old value, and the row
     * becomes `initialized` with `last_fetched_at` now. A new thumbnail URL drops the cached thumbnail (upstream's
     * `clearThumbnail`).
     */
    internal fun writeDetails(
        animeId: Int,
        details: SAnime,
    ) {
        val thumbnailChanged =
            dbTransaction {
                val row = AnimeTable.selectAll().where { AnimeTable.id eq animeId }.single()
                AnimeTable.update({ AnimeTable.id eq animeId }) {
                    it.browseFields(details, row)
                    it[AnimeTable.initialized] = true
                    it[AnimeTable.lastFetchedAt] = System.currentTimeMillis() / 1000
                }
                details.thumbnail_url != null && details.thumbnail_url != row[AnimeTable.thumbnailUrl]
            }
        if (thumbnailChanged) AnimeThumbnails.clear(animeId)
    }
}
