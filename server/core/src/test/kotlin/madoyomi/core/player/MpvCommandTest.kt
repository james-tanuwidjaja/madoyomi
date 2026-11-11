package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.AnimeVideos.EpisodeVideo
import madoyomi.core.anime.AnimeVideos.VideoHeader
import madoyomi.core.anime.AnimeVideos.VideoTrack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MpvCommand] (Phase 4 step 18): the S4 header forms, subtitles and audio tracks, the source's `mpvArgs` with the
 * reserved ones left out, and only our own values on the command line. The include file is read back with mpv's
 * fixed-length quoting (`name=%<bytes>%value`), so a value comes back exactly as the source gave it.
 */
class MpvCommandTest {
    private val options =
        MpvCommand.Options(
            title = "One Piece - Episode 1",
            startSeconds = null,
            pipeName = "madoyomi-mpv-test",
            configDir = File("C:/Madoyomi/player"),
            includeFile = File("C:/data/cache/player/test.conf"),
            windows = true,
        )

    private fun video(
        url: String = "https://cdn.example.org/ep1.m3u8",
        headers: List<Pair<String, String>> = emptyList(),
        subtitles: List<String> = emptyList(),
        audio: List<String> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
    ) = EpisodeVideo(
        index = 0,
        videoTitle = "1080p",
        videoUrl = url,
        videoPageUrl = "",
        resolution = 1080,
        bitrate = null,
        headers = headers.map { VideoHeader(it.first, it.second) },
        preferred = false,
        initialized = true,
        subtitleTracks = subtitles.map { VideoTrack(it, "English") },
        audioTracks = audio.map { VideoTrack(it, "Japanese") },
        timestamps = emptyList(),
        mpvArgs = mpvArgs.map { VideoHeader(it.first, it.second) },
    )

    /** The include file's options, decoded as mpv decodes `%n%` (n = the value's length in UTF-8 bytes). */
    private fun decode(include: String): List<Pair<String, String>> {
        val bytes = include.toByteArray(Charsets.UTF_8)
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < bytes.size) {
            val lineEnd = (i until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() } ?: bytes.size
            if (bytes[i] == '#'.code.toByte()) {
                i = lineEnd + 1
                continue
            }
            val eq = (i until lineEnd).first { bytes[it] == '='.code.toByte() }
            val name = String(bytes, i, eq - i, Charsets.UTF_8)
            assertEquals('%'.code.toByte(), bytes[eq + 1], "fixed-length quoting for $name")
            val close = (eq + 2 until bytes.size).first { bytes[it] == '%'.code.toByte() }
            val length = String(bytes, eq + 2, close - eq - 2, Charsets.US_ASCII).toInt()
            val value = String(bytes, close + 1, length, Charsets.UTF_8)
            assertEquals('\n'.code.toByte(), bytes[close + 1 + length], "a line ends after the value of $name")
            out += name to value
            i = close + 2 + length
        }
        return out
    }

    @Test
    fun `headers take S4's forms, every value arrives verbatim, quotes and commas included`() {
        val built =
            MpvCommand.build(
                video(
                    headers =
                        listOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                            "referer" to "https://animegg.org/",
                            "sec-ch-ua" to "\"Chromium\";v=\"130\", \"Not?A_Brand\";v=\"99\"",
                            "X-Test" to "a,b;c",
                            "Origin" to "https://例え.jp",
                        ),
                ),
                options,
            )

        assertEquals(
            listOf(
                "force-media-title" to "One Piece - Episode 1",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "referrer" to "https://animegg.org/",
                "http-header-fields-append" to "sec-ch-ua: \"Chromium\";v=\"130\", \"Not?A_Brand\";v=\"99\"",
                "http-header-fields-append" to "X-Test: a,b;c",
                "http-header-fields-append" to "Origin: https://例え.jp",
            ),
            decode(built.include),
        )
        // the length counts UTF-8 bytes: "Origin: https://例え.jp" is 19 ASCII characters and two 3-byte ones
        assertTrue("http-header-fields-append=%25%Origin: https://例え.jp\n" in built.include)
        assertEquals(emptyList(), built.dropped)
        // nothing from the source is on the command line
        assertFalse(built.args.any { "Chromium" in it || "animegg" in it || "Mozilla" in it })
    }

    @Test
    fun `a header with an invalid name or a line break is left out`() {
        val built =
            MpvCommand.build(
                video(headers = listOf("Bad Name" to "x", "X-Split" to "a\r\n--script=evil.lua", "Cookie" to "a=1")),
                options,
            )
        assertEquals(listOf("http-header-fields-append" to "Cookie: a=1"), decode(built.include).drop(1))
        assertEquals(listOf("header with an invalid name", "header X-Split: line break in the value"), built.dropped)
    }

    @Test
    fun `subtitle and audio tracks are kept for IPC with their labels, not put in the include file`() {
        val built =
            MpvCommand.build(
                video(
                    subtitles = listOf("https://subs/en,1.vtt", "C:\\Users\\me\\subs\\ep 1.ass", " "),
                    audio = listOf("https://audio/ja.m4a"),
                ),
                options,
            )
        assertEquals(listOf("force-media-title"), decode(built.include).map { it.first })
        assertEquals(
            listOf(
                MpvCommand.Track(MpvCommand.Track.Kind.SUBTITLE, "https://subs/en,1.vtt", "English"),
                MpvCommand.Track(MpvCommand.Track.Kind.SUBTITLE, "C:\\Users\\me\\subs\\ep 1.ass", "English"),
                MpvCommand.Track(MpvCommand.Track.Kind.AUDIO, "https://audio/ja.m4a", "Japanese"),
            ),
            built.tracks,
        )
        assertEquals(listOf("sub-add", "https://subs/en,1.vtt", "select", "English"), built.tracks[0].command(select = true))
        assertEquals(listOf("audio-add", "https://audio/ja.m4a", "auto", "Japanese"), built.tracks[2].command(select = false))
        assertEquals(
            listOf("sub-add", "u", "auto", "Subtitles"),
            MpvCommand.Track(MpvCommand.Track.Kind.SUBTITLE, "u", " ").command(select = false),
        )
        assertEquals(listOf("subtitle track English: no usable URL"), built.dropped)
    }

    @Test
    fun `the source's mpvArgs are applied, except the options Madoyomi keeps for itself`() {
        val built =
            MpvCommand.build(
                video(
                    mpvArgs =
                        listOf(
                            "demuxer-max-bytes" to "50M",
                            "--cache" to "yes",
                            "input-ipc-server" to "\\\\.\\pipe\\other",
                            "scripts-append" to "evil.lua",
                            "script-opts-append" to "a=b",
                            "ytdl" to "yes",
                            "--config-dir" to "C:/elsewhere",
                            "o" to "C:/out.mkv",
                            "Not An Option" to "x",
                        ),
                ),
                options,
            )
        assertEquals(listOf("demuxer-max-bytes" to "50M", "cache" to "yes"), decode(built.include).drop(1))
        assertEquals(
            listOf(
                "mpv option input-ipc-server (reserved)",
                "mpv option scripts-append (reserved)",
                "mpv option script-opts-append (reserved)",
                "mpv option ytdl (reserved)",
                "mpv option config-dir (reserved)",
                "mpv option o (reserved)",
                "mpv option with an invalid name",
            ),
            built.dropped,
        )
    }

    @Test
    fun `the command line has only Madoyomi's values, ytdl off, and the URL after --`() {
        val built = MpvCommand.build(video(url = " https://cdn.example.org/a\"b.m3u8?x=1 "), options.copy(startSeconds = 83.5))
        assertEquals(
            listOf(
                "--config-dir=${File("C:/Madoyomi/player").absolutePath}",
                "--include=${File("C:/data/cache/player/test.conf").absolutePath}",
                """--input-ipc-server=\\.\pipe\madoyomi-mpv-test""",
                "--ytdl=no",
                "--idle=no",
                "--force-window=immediate",
                "--msg-level=all=warn",
                "--start=83.5",
                "--",
                "https://cdn.example.org/a%22b.m3u8?x=1",
            ),
            built.args,
        )
    }

    @Test
    fun `no start time starts at the beginning, and whole seconds have no decimals`() {
        assertFalse(MpvCommand.build(video(), options).args.any { it.startsWith("--start") })
        assertFalse(MpvCommand.build(video(), options.copy(startSeconds = 0.0)).args.any { it.startsWith("--start") })
        assertTrue("--start=600" in MpvCommand.build(video(), options.copy(startSeconds = 600.0)).args)
        assertTrue("--start=1.25" in MpvCommand.build(video(), options.copy(startSeconds = 1.2500001)).args)
    }

    @Test
    fun `a URL that is empty or has a line break can't be played`() {
        assertFailsWith<IllegalArgumentException> { MpvCommand.build(video(url = "  "), options) }
        assertFailsWith<IllegalArgumentException> { MpvCommand.build(video(url = "https://a/b\n--script=x"), options) }
    }

    @Test
    fun `the pipe is a Windows named pipe`() {
        assertEquals("""\\.\pipe\madoyomi-mpv-1""", MpvCommand.pipePath("madoyomi-mpv-1", windows = true))
        assertEquals("/tmp/madoyomi-mpv-1.sock", MpvCommand.pipePath("madoyomi-mpv-1", windows = false))
    }
}
