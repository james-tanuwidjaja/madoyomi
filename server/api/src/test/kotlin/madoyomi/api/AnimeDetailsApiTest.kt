package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.network.HttpException
import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.FakeAnimeSource.Companion.anime
import madoyomi.core.anime.FakeAnimeSource.Companion.episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `fetchAnimeAndEpisodes`, `anime`, `animes`, `episodes` and the nested fields (data loaders). */
class AnimeDetailsApiTest : AnimeApiTest() {
    private val source = FakeAnimeSource(id = 5_151L)

    private fun refresh(
        id: Int,
        fetchAnime: Boolean = true,
        fetchEpisodes: Boolean = true,
    ) = graphql(
        """mutation { fetchAnimeAndEpisodes(input: {id: $id, fetchAnime: $fetchAnime, fetchEpisodes: $fetchEpisodes}) {
            anime { id title description initialized fetchType lastFetchedAt }
            episodes { id name episodeNumber sourceOrder animeId isWatched }
            seasons { id url parentId seasonSourceOrder } } }""",
    )

    @Test
    fun `details and episodes come back with the stored data, episodes oldest first`() {
        register(source)
        val id = animeRow(source.id, "/show")
        source.details = { anime ->
            anime.apply {
                description = "A show"
                genre = "Drama"
            }
        }
        source.episodes = { listOf(episode("/e3", 3f), episode("/e2", 2f), episode("/e1", 1f)) }

        val payload = refresh(id).assertNoErrors().dataPath("fetchAnimeAndEpisodes")
        assertEquals("A show", payload.path("anime", "description"))
        assertEquals(true, payload.path("anime", "initialized"))
        assertEquals("Episodes", payload.path("anime", "fetchType"))
        val episodes = payload.path("episodes").asList
        assertEquals(listOf("Episode 1.0", "Episode 2.0", "Episode 3.0"), episodes.map { it.path("name") })
        assertEquals(listOf(1, 2, 3), episodes.map { it.path("sourceOrder") })
        assertEquals(id, episodes[0].path("animeId"))
        assertTrue(payload.path("seasons").asList.isEmpty())

        val stored =
            graphql(
                """{ anime(id: $id) { title genre source { id name extension { pkgName } }
                    episodes { totalCount nodes { name anime { id } } } } }""",
            ).assertNoErrors().dataPath("anime")
        assertEquals(listOf("Drama"), stored.path("genre"))
        assertEquals(source.id.toString(), stored.path("source", "id"))
        assertEquals("test.animeextension.s${source.id}", stored.path("source", "extension", "pkgName"))
        assertEquals(3, stored.path("episodes", "totalCount"))
        assertEquals(id, stored.path("episodes", "nodes", "0", "anime", "id"))

        val newestFirst =
            graphql(
                """{ episodes(condition: {animeId: $id}, order: [{by: SOURCE_ORDER, byType: DESC}], first: 2) {
                    totalCount pageInfo { hasNextPage } nodes { name } } }""",
            ).assertNoErrors().dataPath("episodes")
        assertEquals(3, newestFirst.path("totalCount"))
        assertEquals(true, newestFirst.path("pageInfo", "hasNextPage"))
        assertEquals(listOf("Episode 3.0", "Episode 2.0"), newestFirst.path("nodes").asList.map { it.path("name") })
    }

    @Test
    fun `a Seasons entry gets its seasons, which point back at it`() {
        register(source)
        val id = animeRow(source.id, "/franchise")
        source.details = { anime -> anime.apply { fetch_type = FetchType.Seasons } }
        source.seasons = { listOf(anime("/s1", "Season 1"), anime("/s2", "Season 2")) }

        val payload = refresh(id).assertNoErrors().dataPath("fetchAnimeAndEpisodes")
        assertEquals("Seasons", payload.path("anime", "fetchType"))
        val seasons = payload.path("seasons").asList
        assertEquals(listOf("/s1", "/s2"), seasons.map { it.path("url") })
        assertEquals(listOf(id, id), seasons.map { it.path("parentId") })
        assertEquals(listOf(1, 2), seasons.map { it.path("seasonSourceOrder") })

        val season = seasons[1].path("id")
        val tree =
            graphql("""{ anime(id: $season) { title parent { id seasons { nodes { title } } } } }""").assertNoErrors().dataPath("anime")
        assertEquals(id, tree.path("parent", "id"))
        assertEquals(listOf("Season 1", "Season 2"), tree.path("parent", "seasons", "nodes").asList.map { it.path("title") })
        val children = graphql("""{ animes(condition: {parentId: $id}) { totalCount } }""").assertNoErrors()
        assertEquals(2, children.dataPath("animes", "totalCount"))
    }

    @Test
    fun `a failing source still returns the stored data, with the error and its code`() {
        register(source)
        val id = animeRow(source.id, "/broken", title = "Stored title")
        source.details = { throw HttpException(503) }

        val response = refresh(id)
        assertEquals("SOURCE_FAILED", response.errorCode)
        assertEquals("Stored title", response.dataPath("fetchAnimeAndEpisodes", "anime", "title"))
        assertTrue(response.dataPath("fetchAnimeAndEpisodes", "episodes").asList.isEmpty())
    }

    @Test
    fun `an unknown anime is NOT_FOUND`() {
        val refreshed = refresh(987_654)
        assertEquals("NOT_FOUND", refreshed.errorCode)
        assertNull(refreshed.dataPath("fetchAnimeAndEpisodes"))
        assertEquals("NOT_FOUND", graphql("{ anime(id: 987654) { id } }").errorCode)
    }

    @Test
    fun `animes filters, orders and pages like upstream's mangas`() {
        val other = FakeAnimeSource(id = 5_152L)
        listOf("Delta", "alpha", "Charlie", "bravo").forEach { animeRow(source.id, "/${it.lowercase()}", title = it) }
        animeRow(other.id, "/elsewhere", title = "Echo")

        val page1 =
            graphql(
                """{ animes(condition: {sourceId: "${source.id}"}, order: [{by: TITLE}], first: 2) {
                    totalCount pageInfo { hasNextPage endCursor } nodes { title } } }""",
            ).assertNoErrors().dataPath("animes")
        assertEquals(4, page1.path("totalCount"))
        assertEquals(listOf("Charlie", "Delta"), page1.path("nodes").asList.map { it.path("title") }, "H2 sorts uppercase first")
        assertEquals(true, page1.path("pageInfo", "hasNextPage"))

        val cursor = page1.path("pageInfo", "endCursor")
        val page2 =
            graphql(
                """{ animes(condition: {sourceId: "${source.id}"}, order: [{by: TITLE}], first: 2, after: "$cursor") {
                    pageInfo { hasNextPage } nodes { title } } }""",
            ).assertNoErrors().dataPath("animes")
        assertEquals(listOf("alpha", "bravo"), page2.path("nodes").asList.map { it.path("title") })
        assertEquals(false, page2.path("pageInfo", "hasNextPage"))

        val filtered =
            graphql(
                """{ animes(filter: {title: {includesInsensitive: "A"}, not: {sourceId: {equalTo: "${other.id}"}}}) {
                    nodes { title } } }""",
            ).assertNoErrors().dataPath("animes", "nodes")
        assertEquals(setOf("Delta", "alpha", "Charlie", "bravo"), filtered.asList.map { it.path("title") }.toSet())

        val notInLibrary = graphql("{ animes(condition: {inLibrary: true}) { totalCount } }").assertNoErrors()
        assertEquals(0, notInLibrary.dataPath("animes", "totalCount"))
        assertNotNull(graphql("{ animes(order: [{by: LAST_FETCHED_AT, byType: DESC}]) { nodes { id age } } }").assertNoErrors().data)
    }
}
