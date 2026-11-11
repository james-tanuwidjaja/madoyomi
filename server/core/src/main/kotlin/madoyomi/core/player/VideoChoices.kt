package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.AnimeVideos.EpisodeHoster
import madoyomi.core.anime.AnimeVideos.HosterState

/**
 * A video by what stays the same between fetches of an episode's videos (Phase 4 Stage 4): its hoster's name and
 * its title and resolution. Indexes don't: they are only valid for one fetch.
 */
data class VideoRef(
    val hosterName: String,
    val videoTitle: String,
    val resolution: Int?,
)

/** Finding a video again after the videos were fetched again, and the next one to offer when it can't play. */
object VideoChoices {
    data class Position(
        val hosterIndex: Int,
        val videoIndex: Int,
        val ref: VideoRef,
    )

    private fun positionsOf(hosters: List<EpisodeHoster>): List<Position> =
        hosters
            .filter { it.state == HosterState.READY }
            .flatMap { h -> h.videos.map { v -> Position(h.index, v.index, VideoRef(h.name, v.videoTitle, v.resolution)) } }

    /**
     * The same video in a new fetch: the READY hoster with the same name, then the video with the same title, else
     * the one with the same resolution (as the app's picker matches a remembered choice). Unlike the picker, no other
     * video stands in: null if neither matches.
     */
    fun find(
        hosters: List<EpisodeHoster>,
        ref: VideoRef,
    ): Position? {
        val hoster = hosters.firstOrNull { it.name == ref.hosterName && it.state == HosterState.READY } ?: return null
        val video =
            hoster.videos.firstOrNull { it.videoTitle == ref.videoTitle }
                ?: ref.resolution?.let { r -> hoster.videos.firstOrNull { it.resolution == r } }
                ?: return null
        return Position(hoster.index, video.index, VideoRef(hoster.name, video.videoTitle, video.resolution))
    }

    /**
     * The video to offer after [failed] could not play: the next one in the source's order (the READY hosters in
     * their order, each one's videos in theirs), wrapping around to the first; never [failed] itself (matched as
     * [find] does). Without [failed] in the list, the first video. Null when there is no other video.
     */
    fun next(
        hosters: List<EpisodeHoster>,
        failed: VideoRef,
    ): Position? {
        val all = positionsOf(hosters)
        val at =
            find(hosters, failed)?.let { f -> all.indexOfFirst { it.hosterIndex == f.hosterIndex && it.videoIndex == f.videoIndex } } ?: -1
        if (at < 0) return all.firstOrNull()
        return (1 until all.size).map { all[(at + it) % all.size] }.firstOrNull()
    }
}

/** What mpv's log (`<data dir>/logs/mpv.log`, the last session's output at `--msg-level=all=warn`) says. */
object MpvLog {
    private val HTTP_ERROR = Regex("""HTTP error (\d{3})""")

    /** The status of the last `HTTP error NNN` FFmpeg logged (a refused or missing URL), if any. */
    fun httpStatus(text: String): Int? =
        HTTP_ERROR
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /** The last [maxBytes] of the log, or "" if it can't be read. */
    fun tail(
        file: java.io.File,
        maxBytes: Int = 64 * 1024,
    ): String =
        runCatching {
            java.io.RandomAccessFile(file, "r").use { f ->
                val start = (f.length() - maxBytes).coerceAtLeast(0)
                f.seek(start)
                val bytes = ByteArray((f.length() - start).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")

    /**
     * Whether a file that mpv ended as `eof` stopped well before its end (more than [EARLY_EOF_SECONDS] short of a
     * known duration). With an HTTP error in the log, that is a URL that stopped answering while playing: mpv treats
     * a refused range request as the end of the file.
     */
    fun endedEarly(
        positionSeconds: Double?,
        durationSeconds: Double?,
    ): Boolean = positionSeconds != null && durationSeconds != null && durationSeconds - positionSeconds > EARLY_EOF_SECONDS

    const val EARLY_EOF_SECONDS = 10.0

    /** Statuses that mean the URL was refused, gone or expired: worth fetching the videos again. */
    val EXPIRED_STATUSES = setOf(401, 403, 404, 410)
}
