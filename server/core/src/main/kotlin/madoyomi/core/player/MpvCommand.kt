package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.AnimeVideos.EpisodeVideo
import java.io.File
import java.util.Locale

/**
 * The mpv command line for one video (Phase 4 step 1, with Phase 0 S4's findings).
 *
 * Everything that comes from the source (headers, the title, subtitle and audio URLs, the video's own `mpvArgs`) goes
 * into a per-session config file that mpv reads with `--include`, written with mpv's fixed-length quoting
 * (`option=%<bytes>%value`), so any character arrives verbatim. Only our own values are on the command line: the
 * config dir, the include file, the IPC pipe, the start time and the URL after `--`. The reasons:
 * - Java's `ProcessBuilder` on Windows doesn't escape a `"` inside an argument, so a header value such as
 *   `sec-ch-ua: "Chromium";v="130"` would be cut, and a crafted one could add options;
 * - other processes can read a process's command line, and headers may carry cookies.
 *
 * Headers (S4): `User-Agent` → `user-agent`, `Referer` → `referrer`, every other header one
 * `http-header-fields-append=Name: value` (never the comma-joined `http-header-fields`, which splits values on `,`).
 * Subtitles and audio tracks are one `sub-files-append`/`audio-files-append` each. `--ytdl=no` always: otherwise mpv's
 * ytdl hook retries a failed URL through yt-dlp with a browser User-Agent.
 *
 * A header with an invalid name or a line break, and an `mpvArgs` option that would take control away from Madoyomi
 * ([DENIED_OPTIONS]: the IPC pipe, config and script loading, files it would write) are left out and listed in
 * [Built.dropped].
 */
object MpvCommand {
    data class Options(
        val title: String,
        /** Where to start, in seconds (`--start`); null or ≤ 0 starts at the beginning. */
        val startSeconds: Double?,
        /** The IPC pipe's name; the path is [pipePath]. */
        val pipeName: String,
        /** mpv's config dir (`player/` in dev, the bundled one in release, else one in the data dir). */
        val configDir: File,
        /** Where the session's config file ([Built.include]) is written. */
        val includeFile: File,
        val windows: Boolean = MpvLocator.isWindows,
    )

    data class Built(
        /** The arguments after the executable. */
        val args: List<String>,
        /** The content of [Options.includeFile]. */
        val include: String,
        /** What was left out, and why (for the log). */
        val dropped: List<String>,
    )

    fun pipePath(
        name: String,
        windows: Boolean = MpvLocator.isWindows,
    ): String = if (windows) """\\.\pipe\$name""" else "/tmp/$name.sock"

    fun build(
        video: EpisodeVideo,
        options: Options,
    ): Built {
        val url = playableUrl(video.videoUrl)
        val dropped = mutableListOf<String>()
        val include = StringBuilder("# Madoyomi playback session: generated, deleted when mpv exits\n")

        fun option(
            name: String,
            value: String,
        ) {
            include
                .append(name)
                .append("=%")
                .append(value.toByteArray(Charsets.UTF_8).size)
                .append('%')
                .append(value)
                .append('\n')
        }

        if (options.title.isNotBlank() && !hasLineBreak(options.title)) option("force-media-title", options.title)
        for (header in video.headers) {
            val name = header.name.trim()
            val value = header.value.trim()
            when {
                !HEADER_NAME.matches(name) -> dropped += "header with an invalid name"
                hasLineBreak(value) -> dropped += "header $name: line break in the value"
                name.equals("User-Agent", ignoreCase = true) -> option("user-agent", value)
                name.equals("Referer", ignoreCase = true) -> option("referrer", value)
                else -> option("http-header-fields-append", "$name: $value")
            }
        }
        for (track in video.subtitleTracks) {
            when {
                track.url.isBlank() || hasLineBreak(track.url) -> dropped += "subtitle track ${track.lang}: no usable URL"
                else -> option("sub-files-append", track.url)
            }
        }
        for (track in video.audioTracks) {
            when {
                track.url.isBlank() || hasLineBreak(track.url) -> dropped += "audio track ${track.lang}: no usable URL"
                else -> option("audio-files-append", track.url)
            }
        }
        for (arg in video.mpvArgs) {
            val name = arg.name.trim().removePrefix("--")
            when {
                !OPTION_NAME.matches(name) -> dropped += "mpv option with an invalid name"
                denied(name) -> dropped += "mpv option $name (reserved)"
                hasLineBreak(arg.value) -> dropped += "mpv option $name: line break in the value"
                else -> option(name, arg.value)
            }
        }

        val args =
            buildList {
                add("--config-dir=${options.configDir.absolutePath}")
                add("--include=${options.includeFile.absolutePath}")
                // ours, after the include: a later option wins
                add("--input-ipc-server=${pipePath(options.pipeName, options.windows)}")
                add("--ytdl=no")
                add("--idle=no")
                add("--force-window=immediate")
                add("--msg-level=all=warn")
                options.startSeconds?.takeIf { it > 0 }?.let { add("--start=${seconds(it)}") }
                add("--")
                add(url)
            }
        return Built(args, include.toString(), dropped)
    }

    /**
     * The URL as one safe argument: no line breaks or NUL, and `"` percent-encoded (Java doesn't escape it on Windows;
     * `%22` is the same URL).
     */
    fun playableUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "The video has no URL" }
        require(!hasLineBreak(trimmed) && '\u0000' !in trimmed) { "The video URL has a line break" }
        return trimmed.replace("\"", "%22")
    }

    /** Options a source's `mpvArgs` may not set: Madoyomi's control of mpv, code and config loading, written files. */
    val DENIED_OPTIONS =
        setOf(
            "input-ipc-server",
            "input-ipc-client",
            "config",
            "config-dir",
            "include",
            "use-filedir-conf",
            "profile",
            "ytdl",
            "ytdl-format",
            "ytdl-raw-options",
            "script",
            "scripts",
            "load-scripts",
            "script-opts",
            "input-conf",
            "input-file",
            "input-commands",
            "log-file",
            "dump-stats",
            "o",
            "of",
            "ofopts",
            "oac",
            "oacopts",
            "ovc",
            "ovcopts",
            "stream-record",
            "record-file",
            "screenshot-directory",
            "screenshot-dir",
            "screenshot-template",
            "watch-later-directory",
            "watch-later-dir",
            "save-position-on-quit",
            "idle",
            "player-operation-mode",
            "playlist",
            "start",
            "force-media-title",
        )

    private fun denied(name: String): Boolean {
        // list options take suffixes (`-append`, `-add`, `-set`, …): `scripts-append` is `scripts`
        val base = name.replace(LIST_SUFFIX, "")
        return name in DENIED_OPTIONS || base in DENIED_OPTIONS || name.startsWith("script-")
    }

    private fun hasLineBreak(value: String) = value.any { it == '\n' || it == '\r' }

    private fun seconds(value: Double): String =
        String
            .format(Locale.ROOT, "%.3f", value)
            .trimEnd('0')
            .trimEnd('.')

    // RFC 9110 token
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val OPTION_NAME = Regex("^[a-z0-9][a-z0-9-]*$")
    private val LIST_SUFFIX = Regex("-(append|add|set|pre|clr|remove|del|toggle)$")
}
