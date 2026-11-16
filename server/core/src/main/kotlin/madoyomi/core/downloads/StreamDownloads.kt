package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.AnimeVideos.EpisodeVideo
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * HLS and DASH episodes (Phase 6 Stage 5, §8 "the anime download tool"): ffmpeg first, yt-dlp when ffmpeg is missing
 * or fails, and an error naming both failures when both do. The video's headers go on the command line ([ProcessArgs]
 * keeps them whole on Windows): for ffmpeg `-user_agent` and one `-headers` block (CRLF between them), which its HLS
 * demuxer also sends with every segment and key; for yt-dlp one `--add-header` each. Neither resumes: a retry starts
 * the stream again (what was staged is deleted first).
 */
object StreamDownloads {
    private val logger = KotlinLogging.logger {}

    /** Tests: what [ffmpegCommand]'s `-progress` reports is read every this often (ffmpeg's own default is 500 ms). */
    @Volatile
    var ffmpegProgressPeriodSeconds: Double = 0.5

    /**
     * Downloads the stream [url] (the video's, or one of its audio tracks' with [name] `audio-<n>`) into [staging] as
     * `<name>.mkv` (ffmpeg) or `<name>.ts` (yt-dlp), with [video]'s headers.
     */
    suspend fun download(
        episodeId: Int,
        url: String,
        video: EpisodeVideo,
        staging: File,
        hls: Boolean,
        progress: suspend (DownloadProgress) -> Unit,
        name: String = "video",
    ): Pair<File, EpisodeDownloadMethod> {
        var ffmpegProblem = "ffmpeg was not found"
        val located =
            try {
                DownloadTools.find(DownloadTools.Tool.FFMPEG)
            } catch (e: DownloadTools.NotFound) {
                ffmpegProblem = e.message ?: ffmpegProblem
                logger.warn { "episode $episodeId: $ffmpegProblem; trying yt-dlp" }
                null
            }
        if (located != null) {
            try {
                return ffmpegDownload(episodeId, located.file, url, video, staging, hls, progress, name) to EpisodeDownloadMethod.FFMPEG
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ffmpegProblem = e.message ?: e.javaClass.simpleName
                logger.warn { "episode $episodeId: ffmpeg failed: $ffmpegProblem; trying yt-dlp" }
            }
        }
        val ytDlp =
            try {
                DownloadTools.find(DownloadTools.Tool.YT_DLP)
            } catch (e: DownloadTools.NotFound) {
                throw IOException("ffmpeg failed: $ffmpegProblem; yt-dlp failed: ${e.message}")
            }
        return try {
            ytDlpDownload(episodeId, ytDlp.file, located?.file, url, video, staging, hls, progress, name) to EpisodeDownloadMethod.YT_DLP
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("ffmpeg failed: $ffmpegProblem; yt-dlp failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ffmpeg

    /** The ffmpeg arguments after the executable ([hlsOptions]: the HLS demuxer's options this ffmpeg knows). */
    internal fun ffmpegCommand(
        url: String,
        video: EpisodeVideo,
        output: File,
        hlsOptions: List<String>,
    ): List<String> =
        buildList {
            addAll(listOf("-hide_banner", "-nostdin", "-y", "-loglevel", "info", "-nostats"))
            addAll(listOf("-progress", "pipe:1", "-stats_period", ffmpegProgressPeriodSeconds.toString()))
            val headers = video.headers.filter { it.name.isNotBlank() && it.value.none { c -> c == '\r' || c == '\n' } }
            headers
                .lastOrNull {
                    it.name.trim().equals(
                        "User-Agent",
                        ignoreCase = true,
                    )
                }?.let { addAll(listOf("-user_agent", it.value.trim())) }
            val rest = headers.filterNot { it.name.trim().equals("User-Agent", ignoreCase = true) }
            if (rest.isNotEmpty()) {
                add("-headers")
                add(rest.joinToString("") { "${it.name.trim()}: ${it.value.trim()}\r\n" })
            }
            addAll(hlsOptions)
            addAll(listOf("-i", url, "-c", "copy", "-f", "matroska", output.absolutePath))
        }

    private val hlsOptionsByFfmpeg = ConcurrentHashMap<String, List<String>>()

    /**
     * The HLS demuxer's options that let segments with any extension through (anime sites disguise them as `.jpg`,
     * `.png`, …): `-allowed_extensions ALL`, and on an ffmpeg that has them `-allowed_segment_extensions ALL` and
     * `-extension_picky 0`, which newer builds check. Asked once per ffmpeg (`-h demuxer=hls`): an option this ffmpeg
     * doesn't know would fail the run.
     */
    internal fun hlsOptions(ffmpeg: File): List<String> =
        hlsOptionsByFfmpeg.getOrPut(ffmpeg.absolutePath) {
            val help =
                runCatching {
                    runBlocking {
                        val lines = StringBuilder()
                        ToolProcess.run(
                            listOf(ffmpeg.absolutePath, "-hide_banner", "-h", "demuxer=hls"),
                            ffmpeg.absoluteFile.parentFile,
                            onStdout = { lines.appendLine(it) },
                            onStderr = { lines.appendLine(it) },
                            idleTimeoutMs = 15_000,
                        )
                        lines.toString()
                    }
                }.getOrDefault("")
            buildList {
                if ("allowed_extensions" in help) addAll(listOf("-allowed_extensions", "ALL"))
                if ("allowed_segment_extensions" in help) addAll(listOf("-allowed_segment_extensions", "ALL"))
                if ("extension_picky" in help) addAll(listOf("-extension_picky", "0"))
            }
        }

    private val DURATION = Regex("""Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")

    private suspend fun ffmpegDownload(
        episodeId: Int,
        ffmpeg: File,
        url: String,
        video: EpisodeVideo,
        staging: File,
        hls: Boolean,
        progress: suspend (DownloadProgress) -> Unit,
        name: String,
    ): File {
        val output = File(staging, "$name.mkv")
        output.delete()
        val options = if (hls) hlsOptions(ffmpeg) else emptyList()
        var duration = 0.0
        var seconds = 0.0
        var size = 0L
        val reports = Channel<DownloadProgress>(Channel.CONFLATED)
        logger.info { "episode $episodeId: ffmpeg ${if (hls) "HLS" else "DASH"} download from ${AnimeEpisodeDownloader.hostOf(url)}" }
        val result =
            coroutineScope {
                val reporter = launchReporter(reports, progress)
                try {
                    ToolProcess.run(
                        listOf(ffmpeg.absolutePath) + ffmpegCommand(url, video, output, options),
                        staging,
                        onStdout = { line ->
                            val key = line.substringBefore('=')
                            val value = line.substringAfter('=', "")
                            when (key) {
                                "out_time_us", "out_time_ms" -> {
                                    value.toLongOrNull()?.let { seconds = it / 1_000_000.0 }
                                }

                                "total_size" -> {
                                    value.toLongOrNull()?.let { size = it }
                                }

                                "progress" -> {
                                    reports.trySend(
                                        DownloadProgress(
                                            seconds.toInt().coerceAtLeast(0),
                                            duration.toInt(),
                                            size,
                                            DownloadProgressUnit.SECONDS,
                                        ),
                                    )
                                }
                            }
                        },
                        onStderr = { line ->
                            if (duration == 0.0) {
                                DURATION.find(line)?.let { m ->
                                    duration = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
                                }
                            }
                        },
                    )
                } finally {
                    reports.close()
                    reporter.join()
                }
            }
        if (result.exitCode != 0) throw IOException(explain("ffmpeg", result))
        if (!output.isFile || output.length() == 0L) throw IOException("ffmpeg wrote nothing (${explain("ffmpeg", result)})")
        progress(DownloadProgress(duration.toInt(), duration.toInt(), output.length(), DownloadProgressUnit.SECONDS))
        return output
    }

    private fun CoroutineScope.launchReporter(
        reports: Channel<DownloadProgress>,
        progress: suspend (DownloadProgress) -> Unit,
    ) = launch {
        for (p in reports) progress(p)
    }

    /** Why a tool failed, from the last lines of its output: the last one that names an error, else the last one. */
    internal fun explain(
        tool: String,
        result: ToolProcess.Result,
    ): String {
        val lines = result.tail.map { it.trim() }.filter { it.isNotEmpty() }
        val line =
            lines.lastOrNull { l -> ERROR_WORDS.any { l.contains(it, ignoreCase = true) } }
                ?: lines.lastOrNull()
                ?: "no output"
        return "$tool exited with code ${result.exitCode}: ${line.take(300)}"
    }

    private val ERROR_WORDS = listOf("error", "failed", "invalid", "forbidden", "not found", "denied", "refused", "unable")

    // ---------------------------------------------------------------------------------------------------------------
    // yt-dlp

    private const val PROGRESS_PREFIX = "MADOYOMI"

    /** The yt-dlp arguments after the executable. */
    internal fun ytDlpCommand(
        url: String,
        video: EpisodeVideo,
        staging: File,
        hls: Boolean,
        ffmpeg: File?,
        name: String = "video",
    ): List<String> =
        buildList {
            addAll(
                listOf(
                    "--ignore-config",
                    "--no-playlist",
                    "--newline",
                    "--no-colors",
                    "--no-cache-dir",
                    "--no-mtime",
                    "--force-generic-extractor",
                    "--retries",
                    "3",
                    "--fragment-retries",
                    "5",
                    "--progress-template",
                    "download:$PROGRESS_PREFIX %(progress.downloaded_bytes)s %(progress.total_bytes)s " +
                        "%(progress.total_bytes_estimate)s %(progress.fragment_index)s %(progress.fragment_count)s",
                ),
            )
            video.headers
                .filter { it.name.isNotBlank() && it.value.none { c -> c == '\r' || c == '\n' } }
                .forEach { addAll(listOf("--add-header", "${it.name.trim()}:${it.value.trim()}")) }
            ffmpeg?.let { addAll(listOf("--ffmpeg-location", it.absolutePath)) }
            if (hls) {
                addAll(listOf("--hls-use-mpegts", "-o", File(staging, "$name.ts").absolutePath))
            } else {
                addAll(listOf("--merge-output-format", "mkv", "-o", File(staging, "$name.%(ext)s").absolutePath))
            }
            add("--")
            add(url)
        }

    private suspend fun ytDlpDownload(
        episodeId: Int,
        ytDlp: File,
        ffmpeg: File?,
        url: String,
        video: EpisodeVideo,
        staging: File,
        hls: Boolean,
        progress: suspend (DownloadProgress) -> Unit,
        name: String,
    ): File {
        staging.listFiles()?.filter { it.name.startsWith("$name.") }?.forEach { it.delete() }
        val reports = Channel<DownloadProgress>(Channel.CONFLATED)
        val errors = mutableListOf<String>()
        logger.info { "episode $episodeId: yt-dlp ${if (hls) "HLS" else "DASH"} download from ${AnimeEpisodeDownloader.hostOf(url)}" }
        val result =
            coroutineScope {
                val reporter = launchReporter(reports, progress)
                try {
                    ToolProcess.run(
                        listOf(ytDlp.absolutePath) + ytDlpCommand(url, video, staging, hls, ffmpeg, name),
                        staging,
                        onStdout = { line ->
                            if (line.startsWith(PROGRESS_PREFIX)) {
                                parseYtDlpProgress(line)?.let { reports.trySend(it) }
                            } else if (line.startsWith("ERROR")) {
                                synchronized(errors) { errors += line }
                            }
                        },
                    )
                } finally {
                    reports.close()
                    reporter.join()
                }
            }
        val output =
            staging
                .listFiles()
                ?.filter {
                    it.isFile && it.name.startsWith("$name.") && it.extension !in setOf("part", "ytdl", "temp") &&
                        !it.name.contains(".part")
                }?.maxByOrNull { it.length() }
        if (result.exitCode != 0 || output == null || output.length() == 0L) {
            val withErrors = result.copy(tail = result.tail + synchronized(errors) { errors.toList() })
            throw IOException(
                if (result.exitCode !=
                    0
                ) {
                    explain("yt-dlp", withErrors)
                } else {
                    "yt-dlp wrote nothing (${explain("yt-dlp", withErrors)})"
                },
            )
        }
        return output
    }

    /** `MADOYOMI <bytes> <total> <estimate> <fragment> <fragments>` (yt-dlp prints `NA` for what it doesn't know). */
    internal fun parseYtDlpProgress(line: String): DownloadProgress? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6 || parts[0] != PROGRESS_PREFIX) return null

        fun num(i: Int) = parts[i].toDoubleOrNull()?.toLong()
        val bytes = num(1) ?: 0
        val total = num(2) ?: num(3) ?: 0
        val fragment = num(4)?.toInt()
        val fragments = num(5)?.toInt()
        return if (fragments != null && fragments > 0) {
            DownloadProgress((fragment ?: 0).coerceIn(0, fragments), fragments, bytes, DownloadProgressUnit.SEGMENTS, total)
        } else {
            AnimeEpisodeDownloader.bytesProgress(bytes, total)
        }
    }
}
