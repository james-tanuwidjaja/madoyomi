package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaTable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Manga added to the library through upstream's GraphQL `updateManga(s)` go into the categories flagged for new
 * entries (`MangaNewEntryCategories`), as upstream's REST `Library.addMangaToLibrary` and anime's
 * `updateAnimes` do.
 */
class MangaNewEntryCategoriesApiTest : ApiGraphQLTest() {
    private fun mangaRow(title: String): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[url] = "/manga/$title"
                    it[this.title] = title
                    // initialized, so upstream doesn't queue a library update for it
                    it[initialized] = true
                    it[sourceReference] = 9_090L
                    it[memo] = JsonObject(emptyMap())
                }.value
        }

    private fun createCategory(
        name: String,
        default: Boolean,
    ): Int =
        graphql("""mutation { createCategory(input: {name: "$name", default: $default}) { category { id } } }""")
            .assertNoErrors()
            .dataPath("createCategory", "category", "id") as Int

    private fun setInLibrary(
        ids: List<Int>,
        inLibrary: Boolean,
    ) = graphql("""mutation { updateMangas(input: {ids: $ids, patch: {inLibrary: $inLibrary}}) { mangas { id } } }""")
        .assertNoErrors()

    private fun categoriesOf(id: Int): Set<Any?> =
        (graphql("{ manga(id: $id) { categories { nodes { id } } } }").assertNoErrors().dataPath("manga", "categories", "nodes") as List<*>)
            .map { it.path("id") }
            .toSet()

    @Test
    fun `a manga added to the library goes into the flagged categories, unless it is in one`() {
        val reading = createCategory("Reading", default = true)
        val later = createCategory("Later", default = true)
        val other = createCategory("Other", default = false)
        val a = mangaRow("A")
        val b = mangaRow("B")
        val c = mangaRow("C")
        graphql("""mutation { updateMangaCategories(input: {id: $c, patch: {addToCategories: [$other]}}) { manga { id } } }""")
            .assertNoErrors()

        setInLibrary(listOf(a, b, c), true)

        assertEquals(setOf(reading, later), categoriesOf(a))
        assertEquals(setOf(reading, later), categoriesOf(b))
        // already in a category: left as it is
        assertEquals(setOf(other), categoriesOf(c))
    }

    @Test
    fun `a removed entry keeps its categories, and adding it again doesn't add the flagged ones`() {
        val reading = createCategory("Reading", default = true)
        val other = createCategory("Other", default = false)
        val a = mangaRow("A")
        setInLibrary(listOf(a), true)
        val patch = "{clearCategories: true, addToCategories: [$other]}"
        graphql("""mutation { updateMangaCategories(input: {id: $a, patch: $patch}) { manga { id } } }""").assertNoErrors()

        setInLibrary(listOf(a), false)
        assertEquals(setOf(other), categoriesOf(a))
        setInLibrary(listOf(a), true)
        assertEquals(setOf(other), categoriesOf(a))
        // updating an entry that is in the library already changes nothing either
        setInLibrary(listOf(a), true)
        assertEquals(setOf(other), categoriesOf(a))
        assertEquals(false, reading in categoriesOf(a))
    }

    @Test
    fun `with no flagged category, a new entry stays in Default`() {
        createCategory("Reading", default = false)
        val a = mangaRow("A")
        graphql("""mutation { updateManga(input: {id: $a, patch: {inLibrary: true}}) { manga { id inLibrary } } }""").assertNoErrors()
        assertEquals(emptySet(), categoriesOf(a))
    }
}
