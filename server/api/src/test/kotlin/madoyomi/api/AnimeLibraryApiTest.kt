package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.FakeAnimeSource.Companion.anime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `updateAnime` and `updateAnimes` (Phase 3 Stage 5): the library flag, in upstream's `updateManga` shape. */
class AnimeLibraryApiTest : AnimeApiTest() {
    private val source = FakeAnimeSource(id = 6_161L)

    private fun update(
        id: Int,
        inLibrary: Boolean,
    ) = graphql(
        """mutation { updateAnime(input: {id: $id, patch: {inLibrary: $inLibrary}}) {
            anime { id inLibrary inLibraryAt } } }""",
    )

    private fun library(): List<Any?> =
        graphql("""{ animes(condition: {inLibrary: true}, order: [{by: TITLE}]) { totalCount nodes { id title } } }""")
            .assertNoErrors()
            .dataPath("animes", "nodes")
            .asList
            .map { it.path("id") }

    private fun inLibraryAt(response: Any?) = (response.path("anime", "inLibraryAt") as String).toLong()

    @Test
    fun `adding sets inLibrary and inLibraryAt, and the library lists the entry`() {
        register(source)
        val id = animeRow(source.id, "/show")
        val other = animeRow(source.id, "/other")
        assertTrue(library().isEmpty())

        val before = Instant.now().epochSecond
        val payload = update(id, true).assertNoErrors().dataPath("updateAnime")
        assertEquals(id, payload.path("anime", "id"))
        assertEquals(true, payload.path("anime", "inLibrary"))
        val addedAt = inLibraryAt(payload)
        assertTrue(addedAt in before..Instant.now().epochSecond, "inLibraryAt $addedAt is not now")

        assertEquals(listOf(id), library())
        val stored = graphql("{ anime(id: $other) { inLibrary inLibraryAt } }").assertNoErrors().dataPath("anime")
        assertEquals(false, stored.path("inLibrary"))
    }

    @Test
    fun `removing clears inLibrary and keeps inLibraryAt, as upstream does`() {
        register(source)
        val id = animeRow(source.id, "/show")
        val addedAt = inLibraryAt(update(id, true).assertNoErrors().dataPath("updateAnime"))

        val removed = update(id, false).assertNoErrors().dataPath("updateAnime")
        assertEquals(false, removed.path("anime", "inLibrary"))
        assertEquals(addedAt, inLibraryAt(removed))
        assertTrue(library().isEmpty())
    }

    @Test
    fun `updateAnimes changes every id and returns them in the given order`() {
        register(source)
        val a = animeRow(source.id, "/a", title = "A")
        val b = animeRow(source.id, "/b", title = "B")
        val c = animeRow(source.id, "/c", title = "C")

        val payload =
            graphql(
                """mutation { updateAnimes(input: {ids: [$c, $a], patch: {inLibrary: true}}) { animes { id inLibrary } } }""",
            ).assertNoErrors().dataPath("updateAnimes", "animes").asList
        assertEquals(listOf(c, a), payload.map { it.path("id") })
        assertTrue(payload.all { it.path("inLibrary") == true })
        assertEquals(listOf(a, c), library())

        graphql("""mutation { updateAnimes(input: {ids: [$a, $b], patch: {inLibrary: false}}) { animes { id } } }""")
            .assertNoErrors()
        assertEquals(listOf(c), library())
    }

    @Test
    fun `an unknown id is NOT_FOUND and changes nothing`() {
        register(source)
        val id = animeRow(source.id, "/show")

        assertEquals("NOT_FOUND", update(id + 1000, true).errorCode)
        val batch =
            graphql("""mutation { updateAnimes(input: {ids: [$id, ${id + 1000}], patch: {inLibrary: true}}) { animes { id } } }""")
        assertEquals("NOT_FOUND", batch.errorCode)
        assertTrue(library().isEmpty())
    }

    @Test
    fun `a browse keeps an entry in the library as it is`() {
        register(source)
        source.popular = { AnimesPage(listOf(anime("/show") { title = "Browse title" }), false) }
        val browse =
            """mutation { fetchSourceAnime(input: {source: "${source.id}", type: POPULAR, page: 1}) {
                animes { id title inLibrary } } }"""
        val id = graphql(browse).assertNoErrors().dataPath("fetchSourceAnime", "animes", "0", "id") as Int
        update(id, true).assertNoErrors()

        source.popular = { AnimesPage(listOf(anime("/show") { title = "Renamed by the site" }), false) }
        val again = graphql(browse).assertNoErrors().dataPath("fetchSourceAnime", "animes", "0")
        assertEquals(id, again.path("id"))
        assertEquals(true, again.path("inLibrary"))
        assertEquals("Browse title", again.path("title"))
    }
}
