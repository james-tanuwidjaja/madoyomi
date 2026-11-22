package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.library.AnimeHistory
import madoyomi.core.player.EpisodeProgress
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * History and the library counts: what stamps the anime and manga history, `animeHistory` and
 * `mangaHistory` (one item per entry, newest first, the resume target, paging), delete and clear (the marks and
 * positions stay), `AnimeType.unwatchedCount`/`lastSeenAt` and upstream's `MangaType.unreadCount`.
 */
class HistoryApiTest : AnimeApiTest() {
    private companion object {
        /** `admin`'s id: upstream's single user. */
        const val USER_ID = 1
    }

    private val source = FakeAnimeSource(id = 7_272L)

    private fun anime(
        url: String,
        episodes: Int,
    ): Pair<Int, List<Int>> {
        val id = animeRow(source.id, url)
        return id to (1..episodes).map { episodeRow(id, "$url/ep/$it", order = it) }
    }

    private fun watched(vararg ids: Int) =
        dbTransaction { ids.forEach { id -> EpisodeTable.update({ EpisodeTable.id eq id }) { it[watched] = true } } }

    private fun episode(id: Int) = dbTransaction { EpisodeTable.selectAll().where { EpisodeTable.id eq id }.single() }

    private val animeHistoryQuery =
        """query(${'$'}limit: Int, ${'$'}offset: Int) { animeHistory(limit: ${'$'}limit, offset: ${'$'}offset) {
            hasMore items { lastSeenAt anime { id } episode { id } resume { id } } } }"""

    private fun animeHistory(
        limit: Int? = null,
        offset: Int? = null,
    ): Pair<List<Triple<Any?, Any?, Any?>>, Any?> {
        val page = graphql(animeHistoryQuery, mapOf("limit" to limit, "offset" to offset)).assertNoErrors().dataPath("animeHistory")
        val items = page.path("items").asList.map { Triple(it.path("anime", "id"), it.path("episode", "id"), it.path("resume", "id")) }
        return items to page.path("hasMore")
    }

    @Test
    fun `the player's saves and a position given stamp the watch history, watched marks alone don't`() {
        register(source)
        val (_, eps) = anime("/a", 4)
        val before = Instant.now().epochSecond

        EpisodeProgress.save(eps[0], 1_000, 60_000)
        graphql(
            """mutation { updateEpisode(input: {id: ${eps[1]}, patch: {lastSecondSeen: "5000"}}) { episode { id } } }""",
        ).assertNoErrors()
        graphql("""mutation { updateEpisodes(input: {ids: [${eps[2]}], patch: {isWatched: true}}) { episodes { id } } }""").assertNoErrors()
        graphql("""mutation { updateEpisode(input: {id: ${eps[3]}, patch: {isWatched: false, lastSecondSeen: "0"}}) { episode { id } } }""")
            .assertNoErrors()

        val stamped = AnimeHistory.all()
        assertEquals(setOf(eps[0], eps[1]), stamped.keys)
        assertTrue(stamped.values.all { it >= before })
        // the progress itself is as before
        assertEquals(1_000L, episode(eps[0])[EpisodeTable.lastSecondSeen])
        assertTrue(episode(eps[2])[EpisodeTable.watched])
    }

    @Test
    fun `animeHistory has one item per anime, newest first, with what to resume, paged`() {
        register(source)
        val (a, aEps) = anime("/a", 3)
        val (b, bEps) = anime("/b", 2)
        val (c, cEps) = anime("/c", 2)
        AnimeHistory.stamp(aEps[0], 100)
        AnimeHistory.stamp(aEps[1], 300) // a's latest, watched: resume the first unwatched after it
        watched(aEps[1])
        AnimeHistory.stamp(bEps[1], 200) // b's latest is its last episode, watched: resume it again
        watched(bEps[1])
        AnimeHistory.stamp(cEps[0], 50) // c's, not watched: resume it

        val (items, hasMore) = animeHistory()
        assertEquals(
            listOf(Triple(a, aEps[1], aEps[2]), Triple(b, bEps[1], bEps[1]), Triple(c, cEps[0], cEps[0])),
            items,
        )
        assertEquals(false, hasMore)
        val lastSeen = graphql(animeHistoryQuery).assertNoErrors().dataPath("animeHistory", "items", "0", "lastSeenAt")
        assertEquals("300", lastSeen.toString())

        val (first, more) = animeHistory(limit = 2)
        assertEquals(listOf(a, b), first.map { it.first })
        assertEquals(true, more)
        val (rest, noMore) = animeHistory(limit = 2, offset = 2)
        assertEquals(listOf(c), rest.map { it.first })
        assertEquals(false, noMore)
    }

    @Test
    fun `deleting and clearing the watch history keep the watched marks and positions`() {
        register(source)
        val (a, aEps) = anime("/a", 2)
        val (b, bEps) = anime("/b", 1)
        EpisodeProgress.save(aEps[0], 42_000, 60_000)
        watched(aEps[0])
        AnimeHistory.stamp(aEps[1], 10)
        AnimeHistory.stamp(bEps[0], 20)

        val removed =
            graphql("""mutation { deleteAnimeHistory(input: {animeIds: [$a]}) { removed } }""")
                .assertNoErrors()
                .dataPath("deleteAnimeHistory", "removed")
        assertEquals(2, removed)
        assertEquals(listOf<Any?>(b), animeHistory().first.map { it.first })
        assertTrue(episode(aEps[0])[EpisodeTable.watched])
        assertEquals(42_000L, episode(aEps[0])[EpisodeTable.lastSecondSeen])

        graphql("""mutation { clearAnimeHistory(input: {}) { removed } }""").assertNoErrors()
        assertEquals(emptyList(), animeHistory().first)
        assertEquals(emptyMap(), AnimeHistory.all())
    }

    @Test
    fun `unwatchedCount and lastSeenAt count an anime's episodes, and a Seasons entry its seasons'`() {
        register(source)
        val (a, aEps) = anime("/a", 3)
        val (parent, _) = anime("/series", 0)
        val (s1, s1Eps) = anime("/series/s1", 2)
        val (s2, s2Eps) = anime("/series/s2", 3)
        val (none, _) = anime("/none", 0)
        dbTransaction {
            listOf(s1, s2).forEach { s -> AnimeTable.update({ AnimeTable.id eq s }) { it[parentId] = parent } }
        }
        watched(aEps[0], s1Eps[0], s1Eps[1], s2Eps[0])
        AnimeHistory.stamp(aEps[0], 111)
        AnimeHistory.stamp(s2Eps[0], 222)
        AnimeHistory.stamp(s1Eps[1], 150)

        val rows =
            graphql("{ animes(condition: {}) { nodes { id unwatchedCount lastSeenAt } } }")
                .assertNoErrors()
                .dataPath("animes", "nodes")
                .asList
                .associate { it.path("id") to (it.path("unwatchedCount") to it.path("lastSeenAt").toString()) }
        assertEquals(2 to "111", rows[a])
        assertEquals(2 to "222", rows[parent])
        assertEquals(0 to "150", rows[s1])
        assertEquals(2 to "222", rows[s2])
        assertEquals(0 to "0", rows[none])
    }

    // manga: upstream's CHAPTER_USER.last_read_at

    private fun mangaRow(title: String): Int =
        dbTransaction {
            MangaTable
                .insertAndGetId {
                    it[url] = "/manga/$title"
                    it[this.title] = title
                    it[initialized] = true
                    it[sourceReference] = 9_191L
                    it[memo] = JsonObject(emptyMap())
                }.value
        }

    private fun chapters(
        manga: Int,
        n: Int,
    ): List<Int> =
        dbTransaction {
            (1..n).map { order ->
                ChapterTable
                    .insertAndGetId {
                        it[url] = "/manga/$manga/ch/$order"
                        it[name] = "Chapter $order"
                        it[chapter_number] = order.toFloat()
                        it[sourceOrder] = order
                        it[pageCount] = 20
                        it[this.manga] = manga
                    }.value
            }
        }

    private fun userRow(chapter: Int) =
        dbTransaction {
            ChapterUserTable
                .selectAll()
                .where { (ChapterUserTable.chapter eq chapter) and (ChapterUserTable.user eq USER_ID) }
                .singleOrNull()
        }

    private fun setUser(
        chapter: Int,
        lastReadAt: Long,
        read: Boolean = false,
        page: Int = 0,
    ) = dbTransaction {
        ChapterUserTable.upsert(ChapterUserTable.chapter, ChapterUserTable.user) {
            it[this.chapter] = chapter
            it[user] = USER_ID
            it[this.lastReadAt] = lastReadAt
            it[isRead] = read
            it[lastPageRead] = page
        }
    }

    private fun mangaHistory(): List<Triple<Any?, Any?, Any?>> =
        graphql("{ mangaHistory { hasMore items { lastReadAt manga { id } chapter { id } resume { id } } } }")
            .assertNoErrors()
            .dataPath("mangaHistory", "items")
            .asList
            .map { Triple(it.path("manga", "id"), it.path("chapter", "id"), it.path("resume", "id")) }

    @Test
    fun `saving a page stamps the reading history, marking unread resets the page without stamping (Madoyomi edit)`() {
        val m = mangaRow("M")
        val (c1, c2, c3) = chapters(m, 3)
        val before = Instant.now().epochSecond

        graphql("""mutation { updateChapter(input: {id: $c1, patch: {lastPageRead: 4}}) { chapter { id } } }""").assertNoErrors()
        assertTrue(userRow(c1)!![ChapterUserTable.lastReadAt] >= before)
        assertEquals(4, userRow(c1)!![ChapterUserTable.lastPageRead])

        setUser(c2, lastReadAt = 0, read = true, page = 7)
        graphql("""mutation { updateChapters(input: {ids: [$c2], patch: {isRead: false, lastPageRead: 0}}) { chapters { id } } }""")
            .assertNoErrors()
        assertEquals(0L, userRow(c2)!![ChapterUserTable.lastReadAt], "Mark unread isn't reading")
        assertEquals(0, userRow(c2)!![ChapterUserTable.lastPageRead])
        assertEquals(false, userRow(c2)!![ChapterUserTable.isRead])

        graphql("""mutation { updateChapters(input: {ids: [$c3], patch: {isRead: true}}) { chapters { id } } }""").assertNoErrors()
        assertEquals(0L, userRow(c3)!![ChapterUserTable.lastReadAt], "a read mark alone doesn't stamp (upstream's rule)")

        // the reader's last page: a page with the read mark stamps
        graphql("""mutation { updateChapter(input: {id: $c3, patch: {lastPageRead: 19, isRead: true}}) { chapter { id } } }""")
            .assertNoErrors()
        assertTrue(userRow(c3)!![ChapterUserTable.lastReadAt] >= before)
    }

    @Test
    fun `mangaHistory, its delete and clear, and upstream's unreadCount`() {
        val a = mangaRow("A")
        val b = mangaRow("B")
        val aCh = chapters(a, 3)
        val bCh = chapters(b, 2)
        setUser(aCh[0], 100, read = true, page = 19)
        setUser(aCh[1], 300, read = true, page = 19) // read: resume the next unread
        setUser(bCh[0], 200, page = 5) // not read: resume it

        assertEquals(listOf(Triple(a, aCh[1], aCh[2]), Triple(b, bCh[0], bCh[0])), mangaHistory())
        val unread =
            graphql("{ mangas(condition: {}) { nodes { id unreadCount lastReadChapter { id lastReadAt } } } }")
                .assertNoErrors()
                .dataPath("mangas", "nodes")
                .asList
                .associate { it.path("id") to (it.path("unreadCount") to it.path("lastReadChapter", "id")) }
        assertEquals(1 to aCh[1], unread[a])
        assertEquals(2 to bCh[0], unread[b])

        graphql("""mutation { deleteMangaHistory(input: {mangaIds: [$a]}) { removed } }""").assertNoErrors()
        assertEquals(listOf(Triple<Any?, Any?, Any?>(b, bCh[0], bCh[0])), mangaHistory())
        assertEquals(true, userRow(aCh[1])!![ChapterUserTable.isRead])
        assertEquals(19, userRow(aCh[1])!![ChapterUserTable.lastPageRead])
        assertEquals(0L, userRow(aCh[1])!![ChapterUserTable.lastReadAt])

        val cleared =
            graphql(
                """mutation { clearMangaHistory(input: {}) { removed } }""",
            ).assertNoErrors().dataPath("clearMangaHistory", "removed")
        assertEquals(1, cleared)
        assertEquals(emptyList(), mangaHistory())
        assertEquals(5, userRow(bCh[0])!![ChapterUserTable.lastPageRead])
    }
}
