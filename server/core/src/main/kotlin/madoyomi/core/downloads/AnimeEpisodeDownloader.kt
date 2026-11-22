package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import madoyomi.core.anime.AnimeSourceAccess
import madoyomi.core.anime.AnimeVideos
import madoyomi.core.anime.AnimeVideos.EpisodeVideo
import madoyomi.core.anime.AnimeVideos.HosterState
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.player.VideoRef
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Downloads an episode (step 11), joining the queue as anime's [ItemDownloader]:
 * 1. **the video** is resolved right before the download, through the extension as Play does
 *    ([AnimeVideos.fetchEpisodeVideos], sorted by the player settings' preferred server and quality, else the source's
 *    order): the first video of the first hoster that has one (a lazy hoster loaded when its turn comes), resolved
 *    by the source when it isn't `initialized`;
 * 2. **its kind** from the first answer: an HLS playlist (`#EXTM3U`, or an `mpegurl` type) or a DASH manifest (`<MPD`,
 *    `dash+xml`) goes to ffmpeg, anything else is a file the backend streams itself with the video's headers;
 * 3. **a direct file** is staged in `<downloads>/.partial/anime/<episodeId>/video.part` and resumed from what is
 *    staged with a `Range` request when the server answers 206 for the same video and size (else it starts over);
 * 4. **HLS and DASH** go through `ffmpeg -headers … -i <url> -c copy` into Matroska; when ffmpeg is missing or fails,
 *    through yt-dlp (`--add-header`), and when both fail the error names both failures (a known risk). Neither resumes:
 *    a retry starts the stream again;
 * 5. **subtitles** (the video's subtitle tracks) are fetched with the same headers and saved beside the file
 *    (`<episode>.<lang>.<ext>`); one that fails is left out and logged. **External audio tracks** (KickAssAnime sends
 *    its audio apart from a video-only stream) are downloaded as the video is (a file streamed, HLS through ffmpeg or
 *    yt-dlp) and saved beside it (`<episode>.<lang>.audio.<ext>`); one that fails fails the download;
 * 6. everything is moved to `downloads/anime/<source>/<title>/<episode>.<ext>` and recorded ([AnimeDownloads]).
 *
 * Progress: [DownloadProgressUnit.BYTES] for a file (thousandths, with the size), [DownloadProgressUnit.SECONDS] for
 * ffmpeg (of the duration it reports), [DownloadProgressUnit.SEGMENTS] for yt-dlp (its fragments).
 */
object AnimeEpisodeDownloader : ItemDownloader {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val network: NetworkHelper by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    /** Tests: another staging folder than `<downloads>/.partial/anime`. */
    @Volatile
    var stagingRootOverride: File? = null

    /** Tests: the client for the files and subtitles in place of the source's. */
    @Volatile
    var clientOverride: OkHttpClient? = null

    /** How often the progress is written while bytes come in. */
    @Volatile
    var progressIntervalMs: Long = 500

    private const val STATE_FILE = "state.json"
    private const val PART_FILE = "video.part"

    fun stagingRoot(): File = stagingRootOverride ?: File(applicationDirs.downloadsRoot, ".partial/anime")

    fun stagingDir(episodeId: Int): File = File(stagingRoot(), episodeId.toString())

    /** What is staged between attempts (`state.json`): which video the part file is of. */
    @Serializable
    private data class Staged(
        val hoster: String,
        val title: String,
        val resolution: Int? = null,
        val ext: String,
        val totalBytes: Long = 0,
    )

    /** The video to download: where it is in the fetch and what it is. */
    data class Pick(
        val hosterIndex: Int,
        val videoIndex: Int,
        val video: EpisodeVideo,
        val ref: VideoRef,
    )

    override suspend fun download(
        item: DownloadItem,
        progress: suspend (DownloadProgress) -> Unit,
    ): Long {
        val episodeId = item.itemId
        AnimeDownloads.local(episodeId)?.takeIf { it.file.isFile }?.let { local ->
            progress(DownloadProgress(1000, 1000, local.bytes, DownloadProgressUnit.BYTES, local.bytes))
            return local.bytes
        }
        val pick = pick(episodeId)
        val staging = stagingDir(episodeId)
        staging.mkdirs()
        val client = client(item.sourceId)
        val headers = headersOf(pick.video)

        // the row ends on the video's progress (its unit and size), whatever the audio tracks reported after it
        var videoProgress: DownloadProgress? = null
        val (videoFile, method) =
            fetchVideo(episodeId, pick, staging, client, headers) {
                videoProgress = it
                progress(it)
            }
        val audio = fetchAudio(episodeId, pick.video, staging, client, headers, progress)
        val subtitles = fetchSubtitles(episodeId, pick.video, staging, client, headers)
        val local = AnimeDownloads.place(episodeId, videoFile, subtitles, pick.video.videoTitle, method, audio)
        videoProgress?.let { progress(it) }
        staging.deleteRecursively()
        logger.info {
            "episode $episodeId of anime ${item.entryId} saved as ${local.file.path} (${method.name.lowercase()}, ${local.bytes} bytes, " +
                "${local.subtitles.size} subtitles, ${local.audio.size} audio tracks)"
        }
        return local.bytes
    }

    override fun discard(item: DownloadItem) {
        stagingDir(item.itemId).deleteRecursively()
    }

    /** The video to download ([AnimeVideos]' order: the preferred server and quality first), resolved. */
    suspend fun pick(episodeId: Int): Pick {
        val hosters = AnimeVideos.fetchEpisodeVideos(episodeId)
        val problems = mutableListOf<String>()
        for (listed in hosters.sortedBy { it.index }) {
            val hoster =
                when (listed.state) {
                    HosterState.READY -> listed
                    HosterState.IDLE -> AnimeVideos.fetchHosterVideos(episodeId, listed.index)
                    HosterState.ERROR -> listed
                }
            if (hoster.state == HosterState.ERROR) {
                problems += "${hoster.name.ifBlank { "hoster ${hoster.index}" }}: ${hoster.errorMessage ?: "failed"}"
                continue
            }
            val first = hoster.videos.firstOrNull() ?: continue
            val video = AnimeVideos.resolveVideo(episodeId, hoster.index, first.index)
            return Pick(hoster.index, first.index, video, VideoRef(hoster.name, video.videoTitle, video.resolution))
        }
        throw MadoyomiException(
            ErrorCode.SOURCE_FAILED,
            if (problems.isEmpty()) "The source has no video for this episode" else "No video to download (${problems.joinToString("; ")})",
            mapOf("episodeId" to episodeId),
        )
    }

    // the source's client (its cookies, interceptors and rate limits), without a call timeout: a file takes as long as
    // it takes, a read that stalls for a minute fails
    private suspend fun client(sourceId: Long): OkHttpClient {
        clientOverride?.let { return it }
        val base =
            runCatching { AnimeSourceAccess.require(sourceId) as? AnimeHttpSource }.getOrNull()?.client ?: network.client
        return base
            .newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** The video's headers for OkHttp (the ones OkHttp refuses left out). */
    internal fun headersOf(video: EpisodeVideo): Headers {
        val builder = Headers.Builder()
        video.headers.forEach { h -> runCatching { builder.add(h.name.trim(), h.value.trim()) } }
        return builder.build()
    }

    private enum class Kind { FILE, HLS, DASH }

    private suspend fun fetchVideo(
        episodeId: Int,
        pick: Pick,
        staging: File,
        client: OkHttpClient,
        headers: Headers,
        progress: suspend (DownloadProgress) -> Unit,
    ): Pair<File, EpisodeDownloadMethod> {
        val part = File(staging, PART_FILE)
        val stateFile = File(staging, STATE_FILE)
        val staged =
            stateFile
                .takeIf { it.isFile }
                ?.let { runCatching { json.decodeFromString(Staged.serializer(), it.readText()) }.getOrNull() }
                ?.takeIf { it.hoster == pick.ref.hosterName && it.title == pick.ref.videoTitle && it.resolution == pick.ref.resolution }
        val from = if (staged != null && part.isFile) part.length() else 0L
        if (from == 0L) {
            part.delete()
            stateFile.delete()
        }
        val url = pick.video.videoUrl
        val response = get(client, url, headers, from)
        val kind: Kind
        var ext = staged?.ext ?: "mp4"
        response.use { r ->
            if (from > 0 && r.code == 416 && staged != null && staged.totalBytes > 0 && from >= staged.totalBytes) {
                // everything was there already
                return File(staging, "video.${staged.ext}").also { part.renameTo(it) } to EpisodeDownloadMethod.DIRECT
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for the video from ${hostOf(url)}")
            val head = r.peekBody(1024).bytes()
            kind = kindOf(url, r.header("Content-Type"), head)
            if (kind == Kind.FILE) {
                val resumed = from > 0 && r.code == 206 && sameTotal(r, staged)
                if (!resumed) {
                    part.delete()
                    ext = extOf(url, r.header("Content-Type"), head)
                }
                val start = if (resumed) from else 0L
                val total = totalOf(r, start)
                stateFile.writeText(
                    json.encodeToString(
                        Staged.serializer(),
                        Staged(pick.ref.hosterName, pick.ref.videoTitle, pick.ref.resolution, ext, total),
                    ),
                )
                if (resumed) logger.info { "episode $episodeId: resuming the download at $start of $total bytes" }
                stream(r, part, start, total, progress)
            }
        }
        if (kind == Kind.FILE) {
            val video = File(staging, "video.$ext")
            video.delete()
            if (!part.renameTo(video)) throw IOException("Could not move ${part.path}")
            return video to EpisodeDownloadMethod.DIRECT
        }
        part.delete()
        stateFile.delete()
        return StreamDownloads.download(episodeId, url, pick.video, staging, hls = kind == Kind.HLS, progress)
    }

    private suspend fun get(
        client: OkHttpClient,
        url: String,
        headers: Headers,
        from: Long,
    ): Response {
        val request =
            Request
                .Builder()
                .url(url)
                .headers(headers)
                .apply { if (from > 0) header("Range", "bytes=$from-") }
                .build()
        return client.newCall(request).await()
    }

    private fun sameTotal(
        r: Response,
        staged: Staged?,
    ): Boolean {
        val total = r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull() ?: return false
        return staged != null && (staged.totalBytes == 0L || staged.totalBytes == total)
    }

    private fun totalOf(
        r: Response,
        start: Long,
    ): Long =
        r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
            ?: r.body
                .contentLength()
                .takeIf { it > 0 }
                ?.let { it + start }
            ?: 0L

    private suspend fun stream(
        r: Response,
        part: File,
        start: Long,
        total: Long,
        progress: suspend (DownloadProgress) -> Unit,
    ) {
        val job = currentCoroutineContext()[Job]
        // a pause or cancel while a read blocks: closing the body ends the read
        val handle = job?.invokeOnCompletion { cause -> if (cause != null) runCatching { r.close() } }
        try {
            withContext(Dispatchers.IO) {
                var written = start
                var last = 0L
                val buffer = ByteArray(64 * 1024)
                r.body.byteStream().use { input ->
                    FileOutputStream(part, start > 0).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n =
                                try {
                                    input.read(buffer)
                                } catch (e: IOException) {
                                    currentCoroutineContext().ensureActive()
                                    throw e
                                }
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - last >= progressIntervalMs) {
                                last = now
                                progress(bytesProgress(written, total))
                            }
                        }
                    }
                }
                if (total > 0 && written < total) throw IOException("The video ended after $written of $total bytes")
                progress(bytesProgress(written, if (total > 0) total else written))
            }
        } finally {
            handle?.dispose()
        }
    }

    internal fun bytesProgress(
        written: Long,
        total: Long,
    ): DownloadProgress {
        val permille = if (total > 0) (written * 1000 / total).coerceIn(0, 1000).toInt() else 0
        return DownloadProgress(permille, if (total > 0) 1000 else 0, written, DownloadProgressUnit.BYTES, total)
    }

    private fun kindOf(
        url: String,
        contentType: String?,
        head: ByteArray,
    ): Kind {
        val text = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        val path = pathOf(url)
        return when {
            text.startsWith("#EXTM3U") || "mpegurl" in type -> Kind.HLS
            "dash+xml" in type || (text.startsWith("<") && "<MPD" in text) -> Kind.DASH
            path.endsWith(".m3u8") && !looksBinary(head) -> Kind.HLS
            path.endsWith(".mpd") && !looksBinary(head) -> Kind.DASH
            else -> Kind.FILE
        }
    }

    private fun looksBinary(head: ByteArray): Boolean = head.take(64).any { it.toInt() == 0 }

    /** The file's extension: its signature, else its URL's, else its type's, else mp4. */
    internal fun extOf(
        url: String,
        contentType: String?,
        head: ByteArray,
    ): String {
        fun at(
            offset: Int,
            vararg bytes: Int,
        ) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        val fromUrl = pathOf(url).substringAfterLast('/').substringAfterLast('.', "")
        return when {
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if ("webm" in type || fromUrl == "webm") "webm" else "mkv"
            at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) -> if (fromUrl in setOf("m4v", "mov")) fromUrl else "mp4"
            at(0, 0x47) && head.size > 188 && at(188, 0x47) -> "ts"
            fromUrl in VIDEO_EXTS -> fromUrl
            "matroska" in type -> "mkv"
            "webm" in type -> "webm"
            "mp2t" in type -> "ts"
            else -> "mp4"
        }
    }

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "flv")

    private fun pathOf(url: String): String =
        url.toHttpUrlOrNull()?.encodedPath?.lowercase(Locale.ROOT) ?: url.substringBefore('?').lowercase(Locale.ROOT)

    internal fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: "(no host)"

    /** A subtitle or audio file staged for [AnimeDownloads.place]. */
    data class StagedTrack(
        val file: File,
        val lang: String,
    )

    /**
     * The video's external audio tracks, each downloaded as the video is: a file streamed (no resume: tracks are small
     * next to the video), HLS or DASH through [StreamDownloads] (`audio-<n>.mkv` or `.ts`). Each reports its own
     * progress after the video's (the row's bar starts again for it). One that fails fails the download: the video may
     * have no audio of its own.
     */
    private suspend fun fetchAudio(
        episodeId: Int,
        video: EpisodeVideo,
        staging: File,
        client: OkHttpClient,
        headers: Headers,
        progress: suspend (DownloadProgress) -> Unit,
    ): List<StagedTrack> =
        video.audioTracks.withIndex().filter { it.value.url.isNotBlank() }.map { (i, track) ->
            val name = "audio-$i"
            staging.listFiles()?.filter { it.name.startsWith("$name.") }?.forEach { it.delete() }
            try {
                var file: File? = null
                var kind = Kind.FILE
                get(client, track.url, headers, 0).use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val head = r.peekBody(1024).bytes()
                    kind = kindOf(track.url, r.header("Content-Type"), head)
                    if (kind == Kind.FILE) {
                        val part = File(staging, "$name.part")
                        stream(r, part, 0, totalOf(r, 0), progress)
                        val target = File(staging, "$name.${audioExtOf(track.url, r.header("Content-Type"), head)}")
                        if (!part.renameTo(target)) throw IOException("Could not move ${part.path}")
                        file = target
                    }
                }
                val staged =
                    file ?: StreamDownloads.download(episodeId, track.url, video, staging, hls = kind == Kind.HLS, progress, name).first
                StagedTrack(staged, track.lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("The audio track '${track.lang}' from ${hostOf(track.url)} failed: ${e.message}", e)
            }
        }

    /** An audio file's extension: its signature, else its URL's, else m4a. */
    internal fun audioExtOf(
        url: String,
        contentType: String?,
        head: ByteArray,
    ): String {
        fun at(
            offset: Int,
            vararg bytes: Int,
        ) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        val fromUrl = pathOf(url).substringAfterLast('/').substringAfterLast('.', "")
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        return when {
            at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) -> "m4a"
            at(0, 'I'.code, 'D'.code, '3'.code) || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) -> "mp3"
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9) -> "aac"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "mka"
            at(0, 'O'.code, 'g'.code, 'g'.code, 'S'.code) -> "ogg"
            fromUrl in setOf("m4a", "aac", "mp3", "ogg", "opus", "mka", "flac", "wav") -> fromUrl
            "mpeg" in type -> "mp3"
            else -> "m4a"
        }
    }

    private suspend fun fetchSubtitles(
        episodeId: Int,
        video: EpisodeVideo,
        staging: File,
        client: OkHttpClient,
        headers: Headers,
    ): List<StagedTrack> {
        val saved = mutableListOf<StagedTrack>()
        video.subtitleTracks.forEachIndexed { i, track ->
            if (track.url.isBlank()) return@forEachIndexed
            try {
                val local = File(track.url).takeIf { !track.url.contains("://") && it.isFile }
                if (local != null) {
                    val copy = File(staging, "sub-$i.${subtitleExt(track.url, null, local.readBytes().take(64).toByteArray())}")
                    local.copyTo(copy, overwrite = true)
                    saved += StagedTrack(copy, track.lang)
                    return@forEachIndexed
                }
                get(client, track.url, headers, 0).use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val bytes = withContext(Dispatchers.IO) { r.body.bytes() }
                    val head = bytes.take(64).toByteArray()
                    if (String(head, Charsets.UTF_8).trimStart('\uFEFF').startsWith("#EXTM3U")) {
                        throw IOException("it is a playlist, not a subtitle file")
                    }
                    val file = File(staging, "sub-$i.${subtitleExt(track.url, r.header("Content-Type"), head)}")
                    file.writeBytes(bytes)
                    saved += StagedTrack(file, track.lang)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn {
                    "episode $episodeId: the subtitle track '${track.lang}' from ${hostOf(
                        track.url,
                    )} was left out: ${e.message}"
                }
            }
        }
        return saved
    }

    internal fun subtitleExt(
        url: String,
        contentType: String?,
        head: ByteArray,
    ): String {
        val fromUrl = pathOf(url).substringAfterLast('/').substringAfterLast('.', "")
        val text = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n')
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        return when {
            fromUrl in setOf("vtt", "srt", "ass", "ssa", "sub", "ttml", "dfxp") -> fromUrl
            text.startsWith("WEBVTT") || "vtt" in type -> "vtt"
            text.startsWith("[Script Info]") -> "ass"
            else -> "srt"
        }
    }
}
