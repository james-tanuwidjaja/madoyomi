package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.model.table.EpisodeTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.database.dbTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `updateEpisode` and `updateEpisodes` (Phase 4 Stage 3): the watched and bookmark flags and the position (milliseconds),
 * in the shape of upstream's `updateChapter(s)`.
 */
class EpisodeUpdateApiTest : AnimeApiTest() {
    private val source = FakeAnimeSource(id = 7_171L)

    private data class Stored(
        val watched: Boolean,
        val bookmark: Boolean,
        val lastMillis: Long,
        val totalMillis: Long,
    )

    private fun stored(id: Int): Stored =
        dbTransaction {
            EpisodeTable.selectAll().where { EpisodeTable.id eq id }.single().let {
                Stored(it[EpisodeTable.watched], it[EpisodeTable.bookmark], it[EpisodeTable.lastSecondSeen], it[EpisodeTable.totalSeconds])
            }
        }

    private fun setProgress(
        id: Int,
        lastMillis: Long,
        totalMillis: Long,
        watched: Boolean = false,
    ) = dbTransaction {
        EpisodeTable.update({ EpisodeTable.id eq id }) {
            it[lastSecondSeen] = lastMillis
            it[totalSeconds] = totalMillis
            it[EpisodeTable.watched] = watched
        }
    }

    private fun episodes(n: Int): List<Int> {
        register(source)
        val anime = animeRow(source.id, "/show")
        return (1..n).map { episodeRow(anime, "/ep/$it", order = it) }
    }

    private fun update(
        id: Int,
        patch: String,
    ) = graphql(
        """mutation { updateEpisode(input: {id: $id, patch: {$patch}}) {
            episode { id isWatched isBookmarked lastSecondSeen totalSeconds } } }""",
    )

    private fun updateAll(
        ids: List<Int>,
        patch: String,
    ) = graphql(
        """mutation { updateEpisodes(input: {ids: ${ids.joinToString(",", "[", "]")}, patch: {$patch}}) {
            episodes { id isWatched lastSecondSeen } } }""",
    )

    @Test
    fun `marking watched keeps the position and the duration`() {
        val (id) = episodes(1)
        setProgress(id, 752_043, 1_500_125)

        val episode = update(id, "isWatched: true").assertNoErrors().dataPath("updateEpisode", "episode")
        assertEquals(true, episode.path("isWatched"))
        assertEquals("752043", episode.path("lastSecondSeen"))
        assertEquals("1500125", episode.path("totalSeconds"))
        assertEquals(Stored(watched = true, bookmark = false, lastMillis = 752_043, totalMillis = 1_500_125), stored(id))
    }

    @Test
    fun `marking unwatched clears the position and keeps the duration, as Aniyomi does`() {
        val (id) = episodes(1)
        setProgress(id, 1_499_958, 1_500_125, watched = true)

        val episode = update(id, "isWatched: false").assertNoErrors().dataPath("updateEpisode", "episode")
        assertEquals(false, episode.path("isWatched"))
        assertEquals("0", episode.path("lastSecondSeen"))
        assertEquals(Stored(watched = false, bookmark = false, lastMillis = 0, totalMillis = 1_500_125), stored(id))
    }

    @Test
    fun `a position in the patch is kept as given, clamped to 0 and the known duration`() {
        val (a, b) = episodes(2)
        setProgress(a, 0, 1_500_125)

        update(a, "isWatched: false, lastSecondSeen: \"60000\"").assertNoErrors()
        assertEquals(60_000, stored(a).lastMillis)
        update(a, "lastSecondSeen: \"9999999\"").assertNoErrors()
        assertEquals(1_500_125, stored(a).lastMillis)
        update(a, "lastSecondSeen: \"-5\"").assertNoErrors()
        assertEquals(0, stored(a).lastMillis)
        // no duration known: not clamped above
        update(b, "lastSecondSeen: \"9999999\"").assertNoErrors()
        assertEquals(9_999_999, stored(b).lastMillis)
    }

    @Test
    fun `bookmarks change on their own`() {
        val (id) = episodes(1)
        setProgress(id, 5_000, 10_000)

        update(id, "isBookmarked: true").assertNoErrors()
        assertEquals(Stored(watched = false, bookmark = true, lastMillis = 5_000, totalMillis = 10_000), stored(id))
        update(id, "isBookmarked: false").assertNoErrors()
        assertEquals(false, stored(id).bookmark)
    }

    @Test
    fun `updateEpisodes changes every id and returns them in the given order, each once`() {
        val (a, b, c) = episodes(3)
        setProgress(b, 40_000, 100_000)

        val marked = updateAll(listOf(c, a, c), "isWatched: true").assertNoErrors().dataPath("updateEpisodes", "episodes").asList
        assertEquals(listOf(c, a), marked.map { it.path("id") })
        assertTrue(marked.all { it.path("isWatched") == true })
        assertEquals(false, stored(b).watched)

        val unmarked =
            updateAll(listOf(a, b, c), "isWatched: false").assertNoErrors().dataPath("updateEpisodes", "episodes").asList
        assertEquals(listOf(a, b, c), unmarked.map { it.path("id") })
        assertTrue(unmarked.all { it.path("isWatched") == false && it.path("lastSecondSeen") == "0" })
        assertEquals(100_000, stored(b).totalMillis)

        val none = updateAll(emptyList(), "isWatched: true").assertNoErrors().dataPath("updateEpisodes", "episodes").asList
        assertTrue(none.isEmpty())
    }

    @Test
    fun `an unknown id is NOT_FOUND and changes nothing`() {
        val (id) = episodes(1)

        assertEquals("NOT_FOUND", update(id + 1000, "isWatched: true").errorCode)
        assertEquals("NOT_FOUND", updateAll(listOf(id, id + 1000), "isWatched: true").errorCode)
        assertEquals(false, stored(id).watched)
    }
}
