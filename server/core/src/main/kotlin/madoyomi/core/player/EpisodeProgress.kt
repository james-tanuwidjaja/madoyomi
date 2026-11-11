package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * Playback progress in the existing `EPISODE` columns (Phase 4 §9): `last_second_seen` and `total_seconds` hold
 * **milliseconds**, despite their names, as the Aniyomi app stores them (its player writes `position * 1000`), so
 * backups and history agree with Aniyomi's; `watched` is the watched flag.
 */
object EpisodeProgress {
    fun save(
        episodeId: Int,
        positionMillis: Long,
        durationMillis: Long,
    ) {
        dbTransaction {
            EpisodeTable.update({ EpisodeTable.id eq episodeId }) {
                it[lastSecondSeen] = positionMillis
                // keep a known duration when mpv doesn't know it (yet)
                if (durationMillis > 0) it[totalSeconds] = durationMillis
            }
        }
    }

    /** Marks the episode watched; false if it already was or doesn't exist. */
    fun markWatched(episodeId: Int): Boolean =
        dbTransaction {
            EpisodeTable.update({ (EpisodeTable.id eq episodeId) and (EpisodeTable.watched eq false) }) {
                it[watched] = true
            } > 0
        }

    /** The episode after this one in the source's order (`source_order` + 1 and up, same anime), if any. */
    fun nextEpisode(episodeId: Int): Int? =
        dbTransaction {
            val row =
                EpisodeTable
                    .select(EpisodeTable.anime, EpisodeTable.sourceOrder)
                    .where { EpisodeTable.id eq episodeId }
                    .singleOrNull() ?: return@dbTransaction null
            EpisodeTable
                .selectAll()
                .where {
                    (EpisodeTable.anime eq row[EpisodeTable.anime]) and
                        (EpisodeTable.sourceOrder greater row[EpisodeTable.sourceOrder])
                }.orderBy(EpisodeTable.sourceOrder to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.get(EpisodeTable.id)
                ?.value
        }
}
