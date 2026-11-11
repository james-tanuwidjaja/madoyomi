package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * The order of an episode's hosters and videos by the player settings' preferred server and quality (Phase 4 step 9),
 * applied after the source's own `sortHosters()`/`sortVideos()` (which already use the source's quality setting). Both
 * sorts are stable: what the preference doesn't tell apart keeps the source's order.
 *
 * - **Hosters:** those whose name contains the preferred server (ignoring case) go first.
 * - **Videos of a hoster:** first those of the preferred server (the hoster's name, or the video's title, contains it:
 *   a source without hosters puts the server into the title), then, within each of those groups, those at the
 *   hoster's *target* quality: the preferred one if the hoster has it, else the highest below it, else the lowest
 *   above it ([QualityPreference.HIGHEST]: its highest). A video's quality is its `resolution`, else the last
 *   "<digits>p" in its title; a video without one never matches.
 * - **[Ranked.matchesPreference]:** the video is of the preferred server or at the target quality (false without a
 *   preference). The first such video in the list is the preference's pick, which the app preselects when there is no
 *   remembered choice for the source.
 */
object VideoPreferences {
    data class Ranked<T>(
        val item: T,
        /** The position in the source's order. */
        val sourceIndex: Int,
        val matchesPreference: Boolean,
    )

    private val HEIGHT = Regex("""(?<![0-9])([0-9]{3,4})[pP](?![a-zA-Z])""")

    /** The video's height: its resolution, else the last "1080p" in its title. */
    fun heightOf(
        title: String,
        resolution: Int?,
    ): Int? =
        resolution?.takeIf { it > 0 }
            ?: HEIGHT
                .findAll(title)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

    fun serverMatches(
        name: String,
        server: String,
    ): Boolean = server.isNotBlank() && name.contains(server.trim(), ignoreCase = true)

    /** The quality to put first among [heights], or null for none. */
    fun targetHeight(
        heights: Collection<Int>,
        quality: QualityPreference,
    ): Int? {
        if (heights.isEmpty()) return null
        return when (quality) {
            QualityPreference.SOURCE -> {
                null
            }

            QualityPreference.HIGHEST -> {
                heights.max()
            }

            else -> {
                val want = quality.height!!
                heights.filter { it <= want }.maxOrNull() ?: heights.filter { it > want }.minOrNull()
            }
        }
    }

    fun <T> sortHosters(
        hosters: List<T>,
        settings: PlayerSettings,
        name: (T) -> String,
    ): List<Ranked<T>> {
        val ranked = hosters.mapIndexed { i, h -> Ranked(h, i, serverMatches(name(h), settings.preferredServer)) }
        return if (settings.preferredServer.isBlank()) ranked else ranked.sortedBy { if (it.matchesPreference) 0 else 1 }
    }

    fun <T> sortVideos(
        videos: List<T>,
        settings: PlayerSettings,
        hosterName: String,
        title: (T) -> String,
        resolution: (T) -> Int?,
    ): List<Ranked<T>> {
        if (!settings.sortsVideos) return videos.mapIndexed { i, v -> Ranked(v, i, false) }
        val server = settings.preferredServer
        val hosterMatches = serverMatches(hosterName, server)
        val heights = videos.map { heightOf(title(it), resolution(it)) }
        val target = targetHeight(heights.filterNotNull(), settings.preferredQuality)
        val keys =
            videos.indices.map { i ->
                val ofServer = hosterMatches || serverMatches(title(videos[i]), server)
                val atQuality = target != null && heights[i] == target
                Triple(i, ofServer, atQuality)
            }
        return keys
            .sortedWith(compareBy({ if (it.second) 0 else 1 }, { if (it.third) 0 else 1 }))
            .map { (i, ofServer, atQuality) -> Ranked(videos[i], i, ofServer || atQuality) }
    }
}
