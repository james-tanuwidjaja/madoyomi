package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import okhttp3.CacheControl
import okhttp3.Response
import org.jetbrains.exposed.v1.core.ResultRow
import suwayomi.tachidesk.manga.impl.util.network.isHostUnreachable
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * An anime's thumbnail for `GET /api/v1/anime/{id}/thumbnail`: upstream's `Manga.fetchMangaThumbnail` for anime.
 * - It is cached in upstream's thumbnail cache (`tempThumbnailCacheRoot`) as `anime-<id>.<ext>`, so upstream's
 *   `clearCachedImages(cachedThumbnails)` clears both kinds. A details fetch that changes the URL drops it.
 * - It is fetched with the source's own client and headers (cookies, Referer, Cloudflare interceptors), uncached by
 *   OkHttp.
 * - Upstream's refresh rule applies: an entry with no thumbnail URL that was never initialized gets its details first.
 *   A URL that is gone (404, 410, 301, Cloudflare 522/523) or whose host is unreachable gets the details refreshed
 *   once, then the request is retried.
 * - When the source can't be used (uninstalled, untrusted, not loadable), the stored URL is fetched with the plain
 *   client, as upstream does for a `StubSource`. No extension code runs then.
 */
object AnimeThumbnails {
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val network: NetworkHelper by injectLazy()

    private val REFRESH_CODES = setOf(410, 301, 404, 523, 522)

    private fun cacheName(animeId: Int) = "anime-$animeId"

    /** The image and its MIME type; `NOT_FOUND` for an unknown anime or one without a thumbnail. */
    suspend fun get(animeId: Int): Pair<InputStream, String> {
        val row = AnimeEntries.animeRow(animeId)
        val source = usableSource(row[AnimeTable.sourceReference])
        return ImageResponse.getImageResponse(applicationDirs.tempThumbnailCacheRoot, cacheName(animeId)) {
            if (source is AnimeHttpSource) fromSource(source, row, refreshUrl = false) else plain(row)
        }
    }

    /** Drops the cached thumbnail of [animeId]. */
    fun clear(animeId: Int) {
        ImageResponse.clearCachedImage(applicationDirs.tempThumbnailCacheRoot, cacheName(animeId))
    }

    private suspend fun usableSource(sourceId: Long): AnimeSource? =
        try {
            AnimeSourceAccess.require(sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MadoyomiException) {
            null
        }

    private suspend fun fromSource(
        source: AnimeHttpSource,
        row: ResultRow,
        refreshUrl: Boolean,
    ): Response {
        val animeId = row[AnimeTable.id].value
        val needsDetails = row[AnimeTable.thumbnailUrl] == null && !row[AnimeTable.initialized]
        val url =
            (if (refreshUrl || needsDetails) refreshedUrl(animeId) else row[AnimeTable.thumbnailUrl])
                ?: throw noThumbnail(animeId)
        val (client, headers) = AnimeSourceCalls.callBlocking(source, "thumbnail") { source.client to source.headers }
        return try {
            AnimeSourceCalls.call(source, "thumbnail") {
                client.newCall(GET(url, headers, cache = CacheControl.FORCE_NETWORK)).awaitSuccess()
            }
        } catch (e: HttpException) {
            if (refreshUrl || e.code !in REFRESH_CODES) throw e
            fromSource(source, row, refreshUrl = true)
        } catch (e: IOException) {
            // the source may have moved its images to another host
            if (refreshUrl || !e.isHostUnreachable()) throw e
            fromSource(source, row, refreshUrl = true)
        }
    }

    private suspend fun refreshedUrl(animeId: Int): String? {
        AnimeDetails.fetch(animeId, fetchDetails = true, fetchChildren = false)
        return AnimeEntries.animeRow(animeId)[AnimeTable.thumbnailUrl]
    }

    private suspend fun plain(row: ResultRow): Response {
        val url = row[AnimeTable.thumbnailUrl] ?: throw noThumbnail(row[AnimeTable.id].value)
        return network.client.newCall(GET(url, cache = CacheControl.FORCE_NETWORK)).awaitSuccess()
    }

    private fun noThumbnail(animeId: Int) =
        MadoyomiException(ErrorCode.NOT_FOUND, "Anime $animeId has no thumbnail", mapOf("animeId" to animeId))
}
