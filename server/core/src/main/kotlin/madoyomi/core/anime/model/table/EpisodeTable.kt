package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.columns.jsonObject
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar
import suwayomi.tachidesk.manga.model.table.columns.unlimitedVarchar

/** Episodes of an [AnimeTable] entry, the counterpart of upstream's `CHAPTER` table. Unique by `(url, anime)` (M0071). */
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

    /** The episode's position in the source's list, numbered like upstream's `CHAPTER.source_order` (oldest = 1). */
    val sourceOrder = integer("source_order")

    /** Epoch seconds when the episode was first fetched. */
    val fetchedAt = long("fetched_at").default(0)

    val anime = reference("anime", AnimeTable, ReferenceOption.CASCADE).index()

    // M0071: the rest of SEpisode.
    val fillermark = bool("fillermark").default(false)
    val summary = unlimitedVarchar("summary").nullable()
    val previewUrl = varchar("preview_url", 2048).nullable()

    /** The extension's own data (`SEpisode.memo`), handed back to it with the episode. */
    val memo = jsonObject("memo").default(JsonObject(emptyMap()))

    init {
        uniqueIndex("UX_EPISODE_URL_ANIME", url, anime)
    }
}
