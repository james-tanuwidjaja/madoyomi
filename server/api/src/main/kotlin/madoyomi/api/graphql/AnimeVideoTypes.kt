package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import madoyomi.core.anime.AnimeVideos
import madoyomi.core.error.ErrorCode

/** A hoster (server) of an episode and its videos. Never persisted: video URLs expire. */
data class EpisodeHosterType(
    @GraphQLDescription(
        "Position in the episode's hoster list (the source's order, then the preferred server's), for " +
            "fetchHosterVideos/resolveEpisodeVideo",
    )
    val index: Int,
    @GraphQLDescription("Position in the source's own order")
    val sourceIndex: Int,
    val name: String,
    val url: String,
    @GraphQLDescription("The source loads this hoster's videos only on request (fetchHosterVideos)")
    val lazy: Boolean,
    @GraphQLDescription("READY: videos loaded; IDLE: a lazy hoster not loaded yet; ERROR: loading failed (errorCode)")
    val state: AnimeVideos.HosterState,
    val videos: List<EpisodeVideoType>,
    val errorCode: ErrorCode?,
    val errorMessage: String?,
) {
    constructor(hoster: AnimeVideos.EpisodeHoster) : this(
        index = hoster.index,
        sourceIndex = hoster.sourceIndex,
        name = hoster.name,
        url = hoster.url,
        lazy = hoster.lazy,
        state = hoster.state,
        videos = hoster.videos.map(::EpisodeVideoType),
        errorCode = hoster.errorCode,
        errorMessage = hoster.errorMessage,
    )
}

/** A playable video: what the player needs (URL, headers, tracks, mpv arguments). */
data class EpisodeVideoType(
    @GraphQLDescription(
        "Position in its hoster's video list (sorted by the source, then by the preferred server and quality), for " +
            "resolveEpisodeVideo",
    )
    val index: Int,
    @GraphQLDescription("Position in the source's own order")
    val sourceIndex: Int,
    @GraphQLDescription(
        "Of the preferred server, or at the hoster's quality closest to the preferred one (false without a preference)",
    )
    val matchesPreference: Boolean,
    val videoTitle: String,
    val videoUrl: String,
    @GraphQLDescription("The lib-14 page URL the video came from, empty if none")
    val videoPageUrl: String,
    val resolution: Int?,
    val bitrate: Int?,
    @GraphQLDescription("The headers the player must send: the video's own, or the source's")
    val headers: List<VideoHeaderType>,
    val preferred: Boolean,
    @GraphQLDescription("False: resolve it (resolveEpisodeVideo) before playing, the URL may not be the stream yet")
    val initialized: Boolean,
    val subtitleTracks: List<VideoTrackType>,
    val audioTracks: List<VideoTrackType>,
    val timestamps: List<VideoTimestampType>,
    @GraphQLDescription("Extra mpv options the source asks for (name, value)")
    val mpvArgs: List<VideoHeaderType>,
) {
    constructor(video: AnimeVideos.EpisodeVideo) : this(
        index = video.index,
        sourceIndex = video.sourceIndex,
        matchesPreference = video.matchesPreference,
        videoTitle = video.videoTitle,
        videoUrl = video.videoUrl,
        videoPageUrl = video.videoPageUrl,
        resolution = video.resolution,
        bitrate = video.bitrate,
        headers = video.headers.map { VideoHeaderType(it.name, it.value) },
        preferred = video.preferred,
        initialized = video.initialized,
        subtitleTracks = video.subtitleTracks.map { VideoTrackType(it.url, it.lang) },
        audioTracks = video.audioTracks.map { VideoTrackType(it.url, it.lang) },
        timestamps = video.timestamps.map { VideoTimestampType(it.start, it.end, it.name, it.type) },
        mpvArgs = video.mpvArgs.map { VideoHeaderType(it.name, it.value) },
    )
}

data class VideoHeaderType(
    val name: String,
    val value: String,
)

data class VideoTrackType(
    val url: String,
    val lang: String,
)

data class VideoTimestampType(
    @GraphQLDescription("Seconds")
    val start: Double,
    @GraphQLDescription("Seconds")
    val end: Double,
    val name: String,
    @GraphQLDescription("Aniyomi's ChapterType: Opening, Ending, Recap, MixedOp, Other")
    val type: String,
)
