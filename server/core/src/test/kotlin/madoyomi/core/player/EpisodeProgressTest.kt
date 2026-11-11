package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.ScratchDatabase
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [EpisodeProgress] on a scratch H2: the position and duration in milliseconds, the watched mark, the next episode. */
class EpisodeProgressTest {
    companion object {
        private lateinit var db: ScratchDatabase

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
            db = ScratchDatabase()
        }

        @AfterAll
        @JvmStatic
        fun afterAll() {
            db.close()
        }
    }

    /** An anime with episodes at these source orders; returns their ids in the same order. */
    private fun episodes(vararg orders: Int): List<Int> =
        dbTransaction {
            val animeId =
                AnimeTable.insertAndGetId {
                    it[url] = "/anime/${System.nanoTime()}"
                    it[title] = "Anime"
                    it[sourceReference] = 42L
                }
            orders.map { order ->
                EpisodeTable
                    .insertAndGetId {
                        it[url] = "/episode/$order"
                        it[name] = "Episode $order"
                        it[sourceOrder] = order
                        it[anime] = animeId
                    }.value
            }
        }

    private fun row(id: Int) = dbTransaction { EpisodeTable.selectAll().where { EpisodeTable.id eq id }.single() }

    @Test
    fun `save writes the position and duration in milliseconds, and keeps a known duration when mpv has none`() {
        val (id) = episodes(1)
        EpisodeProgress.save(id, 61_250, 1_500_000)
        assertEquals(61_250, row(id)[EpisodeTable.lastSecondSeen])
        assertEquals(1_500_000, row(id)[EpisodeTable.totalSeconds])

        EpisodeProgress.save(id, 70_000, 0)
        assertEquals(70_000, row(id)[EpisodeTable.lastSecondSeen])
        assertEquals(1_500_000, row(id)[EpisodeTable.totalSeconds])
        assertFalse(row(id)[EpisodeTable.watched])
    }

    @Test
    fun `markWatched marks once and says whether it changed anything`() {
        val (id) = episodes(1)
        assertTrue(EpisodeProgress.markWatched(id))
        assertTrue(row(id)[EpisodeTable.watched])
        assertFalse(EpisodeProgress.markWatched(id), "already watched")
        assertFalse(EpisodeProgress.markWatched(Int.MAX_VALUE), "no such episode")

        dbTransaction { EpisodeTable.update({ EpisodeTable.id eq id }) { it[watched] = false } }
        assertTrue(EpisodeProgress.markWatched(id))
    }

    @Test
    fun `the next episode is the following one in the source's order, of the same anime`() {
        val ids = episodes(1, 2, 4)
        val other = episodes(3)
        assertEquals(ids[1], EpisodeProgress.nextEpisode(ids[0]))
        assertEquals(ids[2], EpisodeProgress.nextEpisode(ids[1]), "a gap in the order is skipped")
        assertNull(EpisodeProgress.nextEpisode(ids[2]))
        assertNull(EpisodeProgress.nextEpisode(other[0]))
        assertNull(EpisodeProgress.nextEpisode(Int.MAX_VALUE))
    }
}
