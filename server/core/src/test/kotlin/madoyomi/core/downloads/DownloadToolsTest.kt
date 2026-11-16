package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.AnimeVideos
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pure parts of anime downloads (Phase 6 Stage 5): finding the tools, quoting their arguments, their commands. */
class DownloadToolsTest {
    @Test
    fun `the tools are looked for in the settings, then the property, the env and PATH, and a named path must exist`() {
        val dir = Files.createTempDirectory("tools").toFile()
        try {
            val onPath = File(dir, "ffmpeg.exe").apply { writeText("x") }
            val env = mapOf("PATH" to dir.path)
            val found = DownloadTools.find(DownloadTools.Tool.FFMPEG, override = "", property = { null }, env = env::get, windows = true)
            assertEquals(DownloadTools.Origin.PATH, found.origin)
            assertEquals(onPath.absoluteFile, found.file)

            val fromEnv = DownloadTools.find(DownloadTools.Tool.FFMPEG, "", { null }, mapOf("MADOYOMI_FFMPEG" to onPath.path)::get, true)
            assertEquals(DownloadTools.Origin.ENV, fromEnv.origin)

            val missing =
                assertFailsWith<DownloadTools.NotFound> {
                    DownloadTools.find(DownloadTools.Tool.YT_DLP, "C:/nowhere/yt-dlp.exe", { null }, env::get, true)
                }
            assertTrue("C:" in missing.message!! && "Settings → Downloads" in missing.message!!, missing.message)
            assertFailsWith<DownloadTools.NotFound> { DownloadTools.find(DownloadTools.Tool.YT_DLP, "", { null }, env::get, true) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Windows arguments keep quotes, spaces and line breaks whole`() {
        assertEquals("plain", ProcessArgs.quoteWindows("plain"))
        assertEquals("\"\"", ProcessArgs.quoteWindows(""))
        assertEquals("\"a b\"", ProcessArgs.quoteWindows("a b"))
        assertEquals("\"sec-ch-ua: \\\"Chromium\\\";v=\\\"130\\\"\"", ProcessArgs.quoteWindows("sec-ch-ua: \"Chromium\";v=\"130\""))
        assertEquals("\"a\\\\\\\"b\"", ProcessArgs.quoteWindows("a\\\"b"), "backslashes before a quote are doubled")
        assertEquals("\"x\r\ny\r\n\"", ProcessArgs.quoteWindows("x\r\ny\r\n"))
        assertEquals("\"C:\\a b\\\\\"", ProcessArgs.quoteWindows("C:\\a b\\"), "trailing backslashes doubled before the quote")
        assertEquals(
            listOf("C:/x y/ffmpeg.exe", "\"-headers v\""),
            ProcessArgs.forPlatform(listOf("C:/x y/ffmpeg.exe", "-headers v"), true),
        )
        assertEquals(listOf("a b"), ProcessArgs.forPlatform(listOf("a b"), false))
    }

    private fun video(headers: List<Pair<String, String>>) =
        AnimeVideos.EpisodeVideo(
            index = 0,
            videoTitle = "v",
            videoUrl = "https://cdn/x.m3u8",
            videoPageUrl = "",
            resolution = null,
            bitrate = null,
            headers = headers.map { AnimeVideos.VideoHeader(it.first, it.second) },
            preferred = false,
            initialized = true,
            subtitleTracks = emptyList(),
            audioTracks = emptyList(),
            timestamps = emptyList(),
            mpvArgs = emptyList(),
        )

    @Test
    fun `ffmpeg gets the User-Agent as its own option and the other headers in one CRLF block, yt-dlp one header each`() {
        val v = video(listOf("User-Agent" to "UA/1", "Referer" to "https://site/", "Cookie" to "a=1; b=2", "Bad" to "x\ny"))
        val out = File("out.mkv")
        val args = StreamDownloads.ffmpegCommand("https://cdn/x.m3u8", v, out, listOf("-allowed_extensions", "ALL"))
        assertEquals("UA/1", args[args.indexOf("-user_agent") + 1])
        assertEquals(
            "Referer: https://site/\r\nCookie: a=1; b=2\r\n",
            args[args.indexOf("-headers") + 1],
            "a header with a line break left out",
        )
        assertTrue(args.indexOf("-allowed_extensions") < args.indexOf("-i"), "the demuxer's options before the input")
        assertEquals(listOf("-i", "https://cdn/x.m3u8", "-c", "copy", "-f", "matroska", out.absolutePath), args.takeLast(7))

        val yt = StreamDownloads.ytDlpCommand("https://cdn/x.m3u8", v, File("stage"), hls = true, ffmpeg = null)
        assertEquals(
            listOf("User-Agent:UA/1", "Referer:https://site/", "Cookie:a=1; b=2"),
            yt.indices.filter { yt[it] == "--add-header" }.map {
                yt[
                    it +
                        1,
                ]
            },
        )
        assertEquals(listOf("--", "https://cdn/x.m3u8"), yt.takeLast(2), "the URL can't be taken for an option")
        assertTrue("--hls-use-mpegts" in yt && "--ignore-config" in yt)
    }

    @Test
    fun `progress is read from yt-dlp's template, by fragments when it counts them`() {
        assertEquals(
            DownloadProgress(3, 120, 4096, DownloadProgressUnit.SEGMENTS, 500_000),
            StreamDownloads.parseYtDlpProgress("MADOYOMI 4096 NA 500000 3 120"),
        )
        assertEquals(
            DownloadProgress(250, 1000, 250, DownloadProgressUnit.BYTES, 1000),
            StreamDownloads.parseYtDlpProgress("MADOYOMI 250 1000 NA NA NA"),
        )
        assertEquals(null, StreamDownloads.parseYtDlpProgress("[download] 10%"))
        assertEquals(DownloadProgress(0, 0, 10, DownloadProgressUnit.BYTES, 0), AnimeEpisodeDownloader.bytesProgress(10, 0))
    }

    @Test
    fun `a file's extension comes from its signature, then its URL and type, and a tool's failure from its last error line`() {
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0)
        assertEquals("mkv", AnimeEpisodeDownloader.extOf("https://x/v?id=1", null, mkv))
        assertEquals("mp4", AnimeEpisodeDownloader.extOf("https://x/v", null, "\u0000\u0000\u0000\u0020ftypisom".toByteArray()))
        assertEquals("webm", AnimeEpisodeDownloader.extOf("https://x/v.webm", null, ByteArray(8)))
        assertEquals("mp4", AnimeEpisodeDownloader.extOf("https://x/stream", "application/octet-stream", ByteArray(8)))
        assertEquals("m4a", AnimeEpisodeDownloader.audioExtOf("https://x/a", null, "\u0000\u0000\u0000\u0018ftypM4A ".toByteArray()))
        assertEquals("mp3", AnimeEpisodeDownloader.audioExtOf("https://x/a", null, "ID3\u0004".toByteArray()))
        assertEquals("opus", AnimeEpisodeDownloader.audioExtOf("https://x/a.opus", null, ByteArray(8)))
        assertEquals("vtt", AnimeEpisodeDownloader.subtitleExt("https://x/sub?lang=en", null, "WEBVTT\n".toByteArray()))
        assertEquals("ass", AnimeEpisodeDownloader.subtitleExt("https://x/s.ass", null, ByteArray(0)))
        assertEquals("srt", AnimeEpisodeDownloader.subtitleExt("https://x/s", null, "1\n00:00".toByteArray()))
        assertEquals(
            "ffmpeg exited with code 1: [hls @ 0x1] HTTP error 403 Forbidden",
            StreamDownloads.explain(
                "ffmpeg",
                ToolProcess.Result(1, listOf("Input #0", "[hls @ 0x1] HTTP error 403 Forbidden", "Exiting normally")),
            ),
        )
    }
}
