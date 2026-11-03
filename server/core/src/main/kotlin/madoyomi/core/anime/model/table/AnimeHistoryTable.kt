package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/** Watch history, one row per episode. A stub for Phase 6 (library features). */
object AnimeHistoryTable : IntIdTable("anime_history") {
    val episode = reference("episode", EpisodeTable, ReferenceOption.CASCADE).uniqueIndex()

    /** Epoch seconds when the episode was last watched. */
    val lastSeen = long("last_seen").default(0)
}
