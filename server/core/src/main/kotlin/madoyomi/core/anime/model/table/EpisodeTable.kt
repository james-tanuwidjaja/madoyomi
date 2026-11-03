package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/** Episodes of an [AnimeTable] entry, the counterpart of upstream's `CHAPTER` table. */
object EpisodeTable : IntIdTable("episode") {
    val url = varchar("url", 2048)
    val name = truncatingVarchar("name", 512)
    val episodeNumber = float("episode_number").default(-1f)

    /** Epoch milliseconds, as `SEpisode.date_upload`. */
    val dateUpload = long("date_upload").default(0)
    val scanlator = truncatingVarchar("scanlator", 256).nullable()
    val watched = bool("watched").default(false)
    val bookmark = bool("bookmark").default(false)

    /** Playback position and duration, as Aniyomi's `last_second_seen` / `total_seconds`. */
    val lastSecondSeen = long("last_second_seen").default(0)
    val totalSeconds = long("total_seconds").default(0)

    /** The episode's position in the source's list (its index). Named like `CHAPTER.source_order`. */
    val sourceOrder = integer("source_order")

    /** Epoch seconds when the episode was first fetched. */
    val fetchedAt = long("fetched_at").default(0)

    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).index()
}
