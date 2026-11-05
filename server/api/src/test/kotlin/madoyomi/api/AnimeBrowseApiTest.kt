package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.FakeAnimeSource.Companion.anime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `fetchSourceAnime` and the source fields that need the live source (`supportsLatest`, `isConfigurable`, `filters`). */
class AnimeBrowseApiTest : AnimeApiTest() {
    private class Quality : AnimeFilter.Select<String>("Quality", arrayOf("Any", "720p", "1080p"))

    private class Studio : AnimeFilter.Text("Studio")

    private class Dubbed : AnimeFilter.CheckBox("Dubbed")

    private class Genre(
        name: String,
    ) : AnimeFilter.TriState(name)

    private class Genres : AnimeFilter.Group<Genre>("Genres", listOf(Genre("Action"), Genre("Drama")))

    private val source =
        FakeAnimeSource(id = 4_242L).apply {
            filters = { AnimeFilterList(AnimeFilter.Header("Options"), Quality(), Studio(), Dubbed(), Genres()) }
        }

    private fun fetch(
        type: String,
        page: Int = 1,
        extra: String = "",
        sourceId: Long = source.id,
    ) = graphql(
        """mutation { fetchSourceAnime(input: {source: "$sourceId", type: $type, page: $page$extra}) {
            hasNextPage animes { id sourceId url title thumbnailUrl initialized status genre } } }""",
    )

    private fun ids(response: Any?): List<Any?> = response.path("animes").asList.map { it.path("id") }

    @Test
    fun `popular stores the page in page order, each entry once, with the same ids on the next call`() {
        register(source)
        source.popular = { page ->
            AnimesPage(
                listOf(
                    anime("/b") {
                        thumbnail_url = "https://img.example.org/b.jpg"
                        genre = "Action, Drama"
                        status = 2
                    },
                    anime("/a"),
                    anime("/b"),
                ),
                hasNextPage = page < 3,
            )
        }

        val first = fetch("POPULAR").assertNoErrors().dataPath("fetchSourceAnime")
        assertEquals(true, first.path("hasNextPage"))
        val animes = first.path("animes").asList
        assertEquals(listOf("/b", "/a"), animes.map { it.path("url") })
        val b = animes[0]
        assertEquals(source.id.toString(), b.path("sourceId"))
        assertEquals("/api/v1/anime/${b.path("id")}/thumbnail", b.path("thumbnailUrl"), "proxied, as upstream's manga thumbnails")
        assertEquals(listOf("Action", "Drama"), b.path("genre"))
        assertEquals("COMPLETED", b.path("status"))
        assertNull(animes[1].path("thumbnailUrl"))
        assertEquals(false, b.path("initialized"))

        val again = fetch("POPULAR", page = 3).assertNoErrors().dataPath("fetchSourceAnime")
        assertEquals(ids(first), ids(again), "upserted by (source, url)")
        assertEquals(false, again.path("hasNextPage"))
    }

    @Test
    fun `latest and search go to the source, search with the filter changes applied to a fresh list`() {
        register(source)
        source.latest = { AnimesPage(listOf(anime("/latest")), false) }
        var seen: List<Any?> = emptyList()
        var seenQuery = ""
        source.search = { _, query, filters ->
            seenQuery = query
            seen = filters.map { it.state }
            AnimesPage(listOf(anime("/found")), false)
        }

        assertEquals(
            listOf("/latest"),
            fetch("LATEST")
                .assertNoErrors()
                .dataPath("fetchSourceAnime", "animes")
                .asList
                .map { it.path("url") },
        )

        val changes =
            """, query: "frieren", filters: [{position: 1, selectState: 2}, {position: 2, textState: "MAPPA"},
               {position: 3, checkBoxState: true}, {position: 4, groupChange: {position: 1, triState: EXCLUDE}}]"""
        val found = fetch("SEARCH", extra = changes).assertNoErrors().dataPath("fetchSourceAnime", "animes")
        assertEquals(listOf("/found"), found.asList.map { it.path("url") })
        assertEquals("frieren", seenQuery)
        assertEquals(2, seen[1])
        assertEquals("MAPPA", seen[2])
        assertEquals(true, seen[3])
        assertEquals(listOf(0, 2), (seen[4] as List<*>).map { (it as AnimeFilter.TriState).state })
    }

    @Test
    fun `bad input is BAD_REQUEST, an unknown source NOT_FOUND`() {
        register(FakeAnimeSource(id = 4_243L, supportsLatest = false))
        register(source)

        assertEquals("BAD_REQUEST", fetch("POPULAR", page = 0).errorCode, "pages start at 1")
        assertEquals("BAD_REQUEST", fetch("LATEST", sourceId = 4_243L).errorCode, "no latest")
        val badFilter = fetch("SEARCH", extra = """, filters: [{position: 1, textState: "x"}]""")
        assertEquals("BAD_REQUEST", badFilter.errorCode, "a select needs selectState")
        assertEquals("1", (badFilter.errors!!.first().extensions!!["details"] as Map<*, *>)["path"])
        assertEquals("NOT_FOUND", fetch("POPULAR", sourceId = 999_999L).errorCode)
    }

    @Test
    fun `animeSources has the live source's fields and its filters`() {
        register(source)
        register(FakeAnimeSource(id = 4_243L, supportsLatest = false))

        val nodes =
            graphql(
                """{ animeSources { nodes { id name iconUrl supportsLatest isConfigurable
                    filters { __typename ... on SelectFilter { name values default }
                        ... on GroupFilter { name filters { __typename } } } } } }""",
            ).assertNoErrors().dataPath("animeSources", "nodes").asList
        val byId = nodes.associateBy { it.path("id") }
        val fake = byId.getValue(source.id.toString())
        assertEquals(true, fake.path("supportsLatest"))
        assertEquals(false, fake.path("isConfigurable"), "FakeAnimeSource has no preferences")
        assertEquals("/api/v1/extension/icon/test.animeextension.s${source.id}", fake.path("iconUrl"))
        assertEquals(false, byId.getValue("4243").path("supportsLatest"))
        val filters = fake.path("filters").asList
        assertEquals(
            listOf("HeaderFilter", "SelectFilter", "TextFilter", "CheckBoxFilter", "GroupFilter"),
            filters.map { it.path("__typename") },
        )
        assertEquals(listOf("Any", "720p", "1080p"), filters[1].path("values"))
        assertEquals(listOf("TriStateFilter", "TriStateFilter"), filters[4].path("filters").asList.map { it.path("__typename") })
    }

    @Test
    fun `a source that can't be used has null live fields and its filters fail with the reason`() {
        // listed and installed, but nothing is cached and the row has no signature: UNTRUSTED
        register(source)
        AnimeGetSource.unregisterSource(source.id)

        val response = graphql("""{ animeSources { nodes { id supportsLatest isConfigurable } } }""").assertNoErrors()
        val node = response.dataPath("animeSources", "nodes", "0")
        assertNull(node.path("supportsLatest"))
        assertNull(node.path("isConfigurable"))

        val filters = graphql("""{ animeSources { nodes { filters { __typename } } } }""")
        assertEquals("UNTRUSTED", filters.errorCode)
        assertTrue(fetch("POPULAR").errorCode == "UNTRUSTED")
    }
}
