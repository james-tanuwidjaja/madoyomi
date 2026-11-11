package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import okhttp3.Headers

/**
 * The videos of an episode, never persisted: video URLs expire. The steps are those of the Aniyomi app
 * (`EpisodeLoader`, `HosterLoader`):
 * - **Hosters.** An `AnimeHttpSource` whose class declares `getHosterList`, `hosterListRequest` or `hosterListParse`
 *   (extensions-lib 16) is asked for its hosters, sorted by its `sortHosters()`. Any other source (lib 14, or one that
 *   doesn't use hosters) is asked for `getVideoList(episode)`, sorted by `sortVideos()` and wrapped into the single
 *   `no_hoster_list` hoster (`toHosterList()`).
 * - **Videos of a hoster.** The hoster's own `videoList` if it has one, otherwise `getVideoList(hoster)`, then
 *   `sortVideos()`. A video whose `videoUrl` is the string `"null"` (the lib-14 constructor with a null URL) gets its
 *   URL from `getVideoUrl`. A `lazy` hoster is only loaded on request ([fetchHosterVideos]). The hosters load in
 *   parallel, and a failing one reports its error instead of failing the others.
 * - **Resolving.** A video that isn't `initialized` may need the source's `resolveVideo` before it plays (AnimePahe:
 *   the page URL becomes the stream URL). [resolveVideo] does that on the video of the last fetch, kept in memory for
 *   a while per episode.
 */
object AnimeVideos {
    data class EpisodeHoster(
        val index: Int,
        val name: String,
        val url: String,
        val lazy: Boolean,
        val state: HosterState,
        val videos: List<EpisodeVideo>,
        /** Why the hoster failed (`state = ERROR`): the error's code and message, as GraphQL/REST would report it. */
        val errorCode: ErrorCode?,
        val errorMessage: String?,
    )

    enum class HosterState { READY, IDLE, ERROR }

    data class EpisodeVideo(
        val index: Int,
        val videoTitle: String,
        val videoUrl: String,
        /** The lib-14 page URL (`Video.url`). As in Aniyomi, a `Video.copy` (e.g. after `getVideoUrl`) drops it. */
        val videoPageUrl: String,
        val resolution: Int?,
        val bitrate: Int?,
        /** The video's headers, or the source's own headers if it has none (what the player must send). */
        val headers: List<VideoHeader>,
        val preferred: Boolean,
        /** Whether the URL is final; if not, [resolveVideo] before playing. */
        val initialized: Boolean,
        val subtitleTracks: List<VideoTrack>,
        val audioTracks: List<VideoTrack>,
        val timestamps: List<VideoTimestamp>,
        val mpvArgs: List<VideoHeader>,
    )

    data class VideoHeader(
        val name: String,
        val value: String,
    )

    data class VideoTrack(
        val url: String,
        val lang: String,
    )

    data class VideoTimestamp(
        val start: Double,
        val end: Double,
        val name: String,
        val type: String,
    )

    private class Fetch(
        val source: AnimeSource,
        val hosters: List<Hoster>,
        val videos: Array<List<Video>?>,
        val at: Long = System.currentTimeMillis(),
    )

    private const val CACHE_SIZE = 32
    private const val CACHE_MILLIS = 10 * 60 * 1000L

    // the last fetch per episode, for resolveVideo and lazy hosters (access order: the eldest goes first)
    private val cache =
        object : LinkedHashMap<Int, Fetch>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Fetch>?) = size > CACHE_SIZE
        }

    /** The hosters of the episode [episodeId], with the videos of every hoster that isn't `lazy`. */
    suspend fun fetchEpisodeVideos(episodeId: Int): List<EpisodeHoster> {
        val (source, episode) = sourceAndEpisode(episodeId)
        val hosters = hostersOf(source, episode)
        val fetch = Fetch(source, hosters, arrayOfNulls(hosters.size))
        // only while the source is still the live one: an unload between getting it and here has already run
        // dropStale, and the fetch would keep the unloaded extension's class loader
        synchronized(cache) { if (AnimeGetSource.cached(source.id) === source) cache[episodeId] = fetch }
        return coroutineScope {
            hosters.indices.map { index -> async { loadHoster(fetch, index, force = false) } }.awaitAll()
        }
    }

    /** Loads the hoster [hosterIndex] of the episode's last fetch, `lazy` or not (re-fetching the hosters if needed). */
    suspend fun fetchHosterVideos(
        episodeId: Int,
        hosterIndex: Int,
    ): EpisodeHoster {
        val fetch = cachedOrFetch(episodeId)
        checkIndex(hosterIndex, fetch.hosters.size, "hoster")
        return loadHoster(fetch, hosterIndex, force = true)
    }

    /**
     * Video [videoIndex] of hoster [hosterIndex] of the episode's last fetch, resolved by the source if it isn't
     * `initialized` (Aniyomi's `getResolvedVideo`). `SOURCE_FAILED` if the source can't resolve it.
     */
    suspend fun resolveVideo(
        episodeId: Int,
        hosterIndex: Int,
        videoIndex: Int,
    ): EpisodeVideo {
        val fetch = cachedOrFetch(episodeId)
        checkIndex(hosterIndex, fetch.hosters.size, "hoster")
        if (fetch.videos[hosterIndex] == null) {
            val hoster = loadHoster(fetch, hosterIndex, force = true)
            if (hoster.state == HosterState.ERROR) {
                throw MadoyomiException(
                    hoster.errorCode ?: ErrorCode.SOURCE_FAILED,
                    hoster.errorMessage ?: "Hoster $hosterIndex failed",
                    details(episodeId),
                )
            }
        }
        val videos = fetch.videos[hosterIndex] ?: emptyList()
        checkIndex(videoIndex, videos.size, "video")
        val video = videos[videoIndex]
        val source = fetch.source
        val resolved =
            if (source is AnimeHttpSource && !video.initialized) {
                AnimeSourceCalls
                    .call(source, "resolveVideo") { source.resolveVideo(video) }
                    ?.takeIf { it.videoUrl.isNotEmpty() }
                    ?.copy(initialized = true)
                    ?: throw MadoyomiException(ErrorCode.SOURCE_FAILED, "The source could not resolve the video", details(episodeId))
            } else {
                video
            }
        fetch.videos[hosterIndex] = videos.toMutableList().also { it[videoIndex] = resolved }
        return toEpisodeVideo(source, videoIndex, resolved)
    }

    private fun details(episodeId: Int) = mapOf("episodeId" to episodeId)

    private fun checkIndex(
        index: Int,
        size: Int,
        what: String,
    ) {
        if (index !in 0 until size) {
            throw MadoyomiException(ErrorCode.BAD_REQUEST, "There is no $what $index ($size)", mapOf("index" to index))
        }
    }

    /**
     * Forgets the fetches whose source is no longer the live instance in [AnimeGetSource] (unloaded, updated,
     * re-instantiated). [AnimeGetSource] calls it when it drops sources: a kept fetch holds its source, and so the
     * class loader of an uninstalled extension (the compat harness found 18 of 20 loaders alive after uninstalling all;
     * Phase 2 §10).
     */
    internal fun dropStale() {
        synchronized(cache) { cache.values.removeIf { AnimeGetSource.cached(it.source.id) !== it.source } }
    }

    /**
     * The hosters of the episode's last fetch as they are now (loaded ones READY, the rest IDLE), without asking the
     * source; null if there is none kept. The player picks the next video to offer from it (Phase 4 Stage 4).
     */
    internal fun cachedHosters(episodeId: Int): List<EpisodeHoster>? {
        val fetch = synchronized(cache) { cache[episodeId] } ?: return null
        return fetch.hosters.indices.map { i ->
            val videos = fetch.videos[i]
            val state = if (videos != null) HosterState.READY else HosterState.IDLE
            toEpisodeHoster(fetch.source, i, fetch.hosters[i], state, videos.orEmpty())
        }
    }

    /** The name of hoster [hosterIndex] in the episode's last fetch, if it is kept. */
    internal fun hosterName(
        episodeId: Int,
        hosterIndex: Int,
    ): String? =
        synchronized(cache) { cache[episodeId] }
            ?.hosters
            ?.getOrNull(hosterIndex)
            ?.hosterName

    /** For tests: the episodes with a kept fetch. */
    internal fun cachedEpisodes(): Set<Int> = synchronized(cache) { cache.keys.toSet() }

    // a fetch is reused while it is fresh and its source is still the live instance (not unloaded, updated, or
    // re-instantiated after a preference change)
    private suspend fun cachedOrFetch(episodeId: Int): Fetch {
        synchronized(cache) {
            cache[episodeId]
                ?.takeIf { System.currentTimeMillis() - it.at < CACHE_MILLIS && AnimeGetSource.cached(it.source.id) === it.source }
                ?.let { return it }
        }
        fetchEpisodeVideos(episodeId)
        return synchronized(cache) { cache.getValue(episodeId) }
    }

    private suspend fun sourceAndEpisode(episodeId: Int): Pair<AnimeSource, SEpisode> {
        val episode = AnimeEntries.episodeRow(episodeId)
        val anime = AnimeEntries.animeRow(episode[EpisodeTable.anime].value)
        return AnimeSourceAccess.require(anime[AnimeTable.sourceReference]) to AnimeEntries.toSEpisode(episode)
    }

    @Suppress("DEPRECATION") // getVideoList(episode): the lib-14 path, as in Aniyomi
    private suspend fun hostersOf(
        source: AnimeSource,
        episode: SEpisode,
    ): List<Hoster> =
        when {
            source is AnimeHttpSource && declaresHosters(source) -> {
                val hosters = AnimeSourceCalls.call(source, "hosters") { source.getHosterList(episode) }
                AnimeSourceCalls.callBlocking(source, "hosters") { with(source) { hosters.sortHosters() } }
            }

            else -> {
                val videos = AnimeSourceCalls.call(source, "videos") { source.getVideoList(episode) }
                sorted(source, videos).toHosterList()
            }
        }

    /** Aniyomi's `checkHasHosters`: whether the extension's own classes declare one of the hoster methods. */
    @Suppress("DEPRECATION")
    private fun declaresHosters(source: AnimeHttpSource): Boolean {
        var current: Class<*>? = source.javaClass
        while (current != null &&
            current != ParsedAnimeHttpSource::class.java &&
            current != AnimeHttpSource::class.java &&
            current != Any::class.java
        ) {
            if (current.declaredMethods.any { it.name in HOSTER_METHODS }) return true
            current = current.superclass
        }
        return false
    }

    private val HOSTER_METHODS = setOf("getHosterList", "hosterListRequest", "hosterListParse")

    private fun sorted(
        source: AnimeSource,
        videos: List<Video>,
    ): List<Video> =
        if (source is AnimeHttpSource) {
            AnimeSourceCalls.callBlocking(source, "videos") { with(source) { videos.sortVideos() } }
        } else {
            videos
        }

    private suspend fun loadHoster(
        fetch: Fetch,
        index: Int,
        force: Boolean,
    ): EpisodeHoster {
        val hoster = fetch.hosters[index]
        fetch.videos[index]?.let { return toEpisodeHoster(fetch.source, index, hoster, HosterState.READY, it) }
        if (hoster.lazy && !force) return toEpisodeHoster(fetch.source, index, hoster, HosterState.IDLE, emptyList())
        return try {
            val videos = videosOf(fetch.source, hoster)
            fetch.videos[index] = videos
            toEpisodeHoster(fetch.source, index, hoster, HosterState.READY, videos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = ErrorClassifier.classify(e)
            toEpisodeHoster(fetch.source, index, hoster, HosterState.ERROR, emptyList(), classified.code, classified.message)
        }
    }

    private suspend fun videosOf(
        source: AnimeSource,
        hoster: Hoster,
    ): List<Video> {
        val videos =
            hoster.videoList
                ?: if (source is AnimeHttpSource) {
                    AnimeSourceCalls.call(source, "videos") { source.getVideoList(hoster) }
                } else {
                    emptyList()
                }
        val withUrls =
            if (source is AnimeHttpSource) {
                videos.map { video ->
                    if (video.videoUrl != "null") {
                        video
                    } else {
                        video.copy(videoUrl = AnimeSourceCalls.call(source, "videoUrl") { source.getVideoUrl(video) })
                    }
                }
            } else {
                videos
            }
        return sorted(source, withUrls)
    }

    private fun toEpisodeHoster(
        source: AnimeSource,
        index: Int,
        hoster: Hoster,
        state: HosterState,
        videos: List<Video>,
        errorCode: ErrorCode? = null,
        errorMessage: String? = null,
    ) = EpisodeHoster(
        index = index,
        name = hoster.hosterName,
        url = hoster.hosterUrl,
        lazy = hoster.lazy,
        state = state,
        videos = videos.mapIndexed { i, video -> toEpisodeVideo(source, i, video) },
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    private fun toEpisodeVideo(
        source: AnimeSource,
        index: Int,
        video: Video,
    ): EpisodeVideo {
        val headers: Headers? =
            video.headers ?: (source as? AnimeHttpSource)?.let { AnimeSourceCalls.callBlocking(it, "headers") { it.headers } }
        return EpisodeVideo(
            index = index,
            videoTitle = video.videoTitle,
            videoUrl = video.videoUrl,
            videoPageUrl = video.url,
            resolution = video.resolution,
            bitrate = video.bitrate,
            headers = headers?.map { (name, value) -> VideoHeader(name, value) }.orEmpty(),
            preferred = video.preferred,
            initialized = video.initialized,
            subtitleTracks = video.subtitleTracks.map { VideoTrack(it.url, it.lang) },
            audioTracks = video.audioTracks.map { VideoTrack(it.url, it.lang) },
            timestamps = video.timestamps.map { VideoTimestamp(it.start, it.end, it.name, it.type.name) },
            mpvArgs = video.mpvArgs.map { (name, value) -> VideoHeader(name, value) },
        )
    }
}
