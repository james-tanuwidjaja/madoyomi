package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.FakeAnimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The anime categories (Phase 6 Stage 1): `animeCategories`, the category mutations and `AnimeType.categories`. */
class AnimeCategoryApiTest : AnimeApiTest() {
    private val source = FakeAnimeSource(id = 7_272L)

    private fun categories(): List<Any?> =
        graphql("{ animeCategories { id order name default isDefaultCategory includeInUpdate } }")
            .assertNoErrors()
            .dataPath("animeCategories")
            .asList

    private fun names() = categories().map { it.path("name") }

    private fun create(
        name: String,
        extra: String = "",
    ): Int =
        graphql("""mutation { createAnimeCategory(input: {name: "$name" $extra}) { category { id } } }""")
            .assertNoErrors()
            .dataPath("createAnimeCategory", "category", "id") as Int

    private fun defaultId(): Int = categories().single { it.path("isDefaultCategory") == true }.path("id") as Int

    private fun assign(
        ids: List<Int>,
        patch: String,
    ) = graphql("""mutation { updateAnimesCategories(input: {ids: $ids, patch: {$patch}}) { animes { id categories { id } } } }""")

    private fun categoriesOf(id: Int): List<Any?> =
        graphql("{ anime(id: $id) { categories { id } } }")
            .assertNoErrors()
            .dataPath("anime", "categories")
            .asList
            .map { it.path("id") }

    private fun inCategory(id: Int): List<Any?> =
        graphql("{ animeCategory(id: $id) { animes { totalCount nodes { id } } } }")
            .assertNoErrors()
            .dataPath("animeCategory", "animes", "nodes")
            .asList
            .map { it.path("id") }

    private fun setInLibrary(
        ids: List<Int>,
        inLibrary: Boolean,
    ) = graphql("""mutation { updateAnimes(input: {ids: $ids, patch: {inLibrary: $inLibrary}}) { animes { id } } }""").assertNoErrors()

    @Test
    fun `a fresh database has only the Default category`() {
        val list = categories()
        assertEquals(1, list.size)
        val default = list.single()
        assertEquals("Default", default.path("name"))
        assertEquals(0, default.path("order"))
        assertEquals(true, default.path("isDefaultCategory"))
        assertEquals(false, default.path("default"))
        assertEquals("UNSET", default.path("includeInUpdate"))
    }

    @Test
    fun `categories are created at the end or at a position, and moved by position`() {
        val a = create("Watching")
        val b = create("Plan to watch", ", default: true, includeInUpdate: EXCLUDE")
        val c = create("Dropped", ", order: 1")
        assertEquals(listOf("Default", "Dropped", "Watching", "Plan to watch"), names())
        assertEquals(listOf(0, 1, 2, 3), categories().map { it.path("order") })
        val plan = categories().single { it.path("id") == b }
        assertEquals(true, plan.path("default"))
        assertEquals("EXCLUDE", plan.path("includeInUpdate"))

        val moved =
            graphql("""mutation { updateAnimeCategoryOrder(input: {id: $a, position: 3}) { categories { name order } } }""")
                .assertNoErrors()
                .dataPath("updateAnimeCategoryOrder", "categories")
                .asList
        assertEquals(listOf("Default", "Dropped", "Plan to watch", "Watching"), moved.map { it.path("name") })
        graphql("""mutation { updateAnimeCategoryOrder(input: {id: $c, position: 99}) { categories { id } } }""").assertNoErrors()
        assertEquals(listOf("Default", "Plan to watch", "Watching", "Dropped"), names())
        assertEquals(listOf(0, 1, 2, 3), categories().map { it.path("order") })

        val move = { id: Int, position: Int ->
            graphql("""mutation { updateAnimeCategoryOrder(input: {id: $id, position: $position}) { categories { id } } }""")
        }
        assertEquals("BAD_REQUEST", move(defaultId(), 2).errorCode)
        assertEquals("BAD_REQUEST", move(a, 0).errorCode)
    }

    @Test
    fun `names are trimmed, unique ignoring case, at most 64 characters and never Default`() {
        val a = create("  Watching  ")
        assertEquals(listOf("Default", "Watching"), names())
        for (bad in listOf("", "   ", "default", "DEFAULT", "watching", "x".repeat(65))) {
            val response = graphql("""mutation { createAnimeCategory(input: {name: "$bad"}) { category { id } } }""")
            assertEquals("BAD_REQUEST", response.errorCode, bad)
        }
        assertEquals(listOf("Default", "Watching"), names())

        val b = create("Later")
        val rename = { id: Int, name: String ->
            graphql("""mutation { updateAnimeCategory(input: {id: $id, patch: {name: "$name"}}) { category { name } } }""")
        }
        assertEquals("BAD_REQUEST", rename(b, "WATCHING").errorCode)
        assertEquals("BAD_REQUEST", rename(b, "Default").errorCode)
        assertEquals("watching", rename(a, "watching").assertNoErrors().dataPath("updateAnimeCategory", "category", "name"))
        assertEquals("Soon", rename(b, " Soon ").assertNoErrors().dataPath("updateAnimeCategory", "category", "name"))
        assertEquals("BAD_REQUEST", rename(defaultId(), "Mine").errorCode)
        assertEquals("NOT_FOUND", rename(b + 100, "Other").errorCode)
        assertEquals(listOf("Default", "watching", "Soon"), names())
    }

    @Test
    fun `Default takes the update and download flags only, and can't be deleted`() {
        val default = defaultId()
        val flags =
            graphql(
                """mutation { updateAnimeCategory(input: {id: $default, patch: {includeInUpdate: INCLUDE, includeInDownload: EXCLUDE}}) {
                    category { includeInUpdate includeInDownload } } }""",
            ).assertNoErrors().dataPath("updateAnimeCategory", "category")
        assertEquals("INCLUDE", flags.path("includeInUpdate"))
        assertEquals("EXCLUDE", flags.path("includeInDownload"))
        assertEquals(
            "BAD_REQUEST",
            graphql("""mutation { updateAnimeCategory(input: {id: $default, patch: {default: true}}) { category { id } } }""").errorCode,
        )
        assertEquals(
            "BAD_REQUEST",
            graphql("""mutation { deleteAnimeCategory(input: {categoryId: $default}) { category { id } } }""").errorCode,
        )
        assertEquals(listOf("Default"), names())
    }

    @Test
    fun `entries are assigned one at a time and in bulk, and Default holds the library entries in no category`() {
        register(source)
        val a = animeRow(source.id, "/a", title = "A")
        val b = animeRow(source.id, "/b", title = "B")
        val c = animeRow(source.id, "/c", title = "C")
        val notInLibrary = animeRow(source.id, "/d", title = "D")
        setInLibrary(listOf(a, b, c), true)
        val watching = create("Watching")
        val later = create("Later")
        val default = defaultId()
        assertEquals(listOf(a, b, c), inCategory(default))

        val one =
            graphql(
                """mutation { updateAnimeCategories(input: {id: $a, patch: {addToCategories: [$later, $watching]}}) {
                    anime { id categories { id name } } } }""",
            ).assertNoErrors()
                .dataPath("updateAnimeCategories", "anime")
        assertEquals(listOf("Watching", "Later"), one.path("categories").asList.map { it.path("name") }, "by position")

        val many = assign(listOf(c, b, c, notInLibrary), "addToCategories: [$watching, $default]").assertNoErrors()
        assertEquals(listOf(c, b, notInLibrary), many.dataPath("updateAnimesCategories", "animes").asList.map { it.path("id") })
        assertEquals(listOf(watching), categoriesOf(b), "Default is ignored")
        assertEquals(listOf(a, b, c), inCategory(watching), "library entries only, by title")
        assertEquals(listOf<Any?>(), inCategory(default))

        assign(listOf(a, b), "removeFromCategories: [$watching]").assertNoErrors()
        assertEquals(listOf(later), categoriesOf(a))
        assertEquals(listOf<Any?>(), categoriesOf(b))
        assertEquals(listOf(b), inCategory(default))

        assign(listOf(a, c), "clearCategories: true, addToCategories: [$later]").assertNoErrors()
        assertEquals(listOf(later), categoriesOf(a))
        assertEquals(listOf(later), categoriesOf(c))
        assign(listOf(c), "clearCategories: true").assertNoErrors()
        assertEquals(listOf(b, c), inCategory(default))
    }

    @Test
    fun `an unknown entry or category is NOT_FOUND and changes nothing`() {
        register(source)
        val a = animeRow(source.id, "/a")
        val watching = create("Watching")
        assertEquals("NOT_FOUND", assign(listOf(a, a + 100), "addToCategories: [$watching]").errorCode)
        assertEquals("NOT_FOUND", assign(listOf(a), "addToCategories: [$watching, ${watching + 100}]").errorCode)
        assertEquals(listOf<Any?>(), categoriesOf(a))
        assertEquals("NOT_FOUND", graphql("{ animeCategory(id: ${watching + 100}) { id } }").errorCode)
        assertEquals(
            "NOT_FOUND",
            graphql("""mutation { deleteAnimeCategory(input: {categoryId: ${watching + 100}}) { category { id } } }""").errorCode,
        )
    }

    @Test
    fun `deleting a category sends its entries back to Default unless they are in another`() {
        register(source)
        val a = animeRow(source.id, "/a", title = "A")
        val b = animeRow(source.id, "/b", title = "B")
        setInLibrary(listOf(a, b), true)
        val watching = create("Watching")
        val later = create("Later")
        val dropped = create("Dropped")
        assign(listOf(a, b), "addToCategories: [$watching]").assertNoErrors()
        assign(listOf(b), "addToCategories: [$later]").assertNoErrors()

        val deleted =
            graphql("""mutation { deleteAnimeCategory(input: {categoryId: $watching}) { category { name } animes { id } } }""")
                .assertNoErrors()
                .dataPath("deleteAnimeCategory")
        assertEquals("Watching", deleted.path("category", "name"))
        assertEquals(
            setOf(a, b),
            deleted
                .path("animes")
                .asList
                .map { it.path("id") }
                .toSet(),
        )
        assertEquals(listOf("Default", "Later", "Dropped"), names())
        assertEquals(listOf(0, 1, 2), categories().map { it.path("order") })
        assertEquals(listOf(a), inCategory(defaultId()))
        assertEquals(listOf(later), categoriesOf(b))
        assertEquals(listOf(dropped), listOf(categories().last().path("id")))
    }

    @Test
    fun `an entry added to the library goes into the flagged categories unless it is in one, and keeps them when removed`() {
        register(source)
        val a = animeRow(source.id, "/a", title = "A")
        val b = animeRow(source.id, "/b", title = "B")
        val c = animeRow(source.id, "/c", title = "C")
        val watching = create("Watching", ", default: true")
        val later = create("Later")
        val also = create("Also new", ", default: true")
        assign(listOf(b), "addToCategories: [$later]").assertNoErrors()

        setInLibrary(listOf(a, b), true)
        assertEquals(listOf(watching, also), categoriesOf(a))
        assertEquals(listOf(later), categoriesOf(b), "already in a category")

        setInLibrary(listOf(a), false)
        assertEquals(listOf(watching, also), categoriesOf(a))
        graphql("""mutation { updateAnimeCategory(input: {id: $also, patch: {default: false}}) { category { default } } }""")
            .assertNoErrors()
        setInLibrary(listOf(a, c), true)
        assertEquals(listOf(watching, also), categoriesOf(a), "a re-added entry keeps its categories")
        assertEquals(listOf(watching), categoriesOf(c))
        // adding an entry that is already in the library changes nothing
        assign(listOf(c), "clearCategories: true").assertNoErrors()
        setInLibrary(listOf(c), true)
        assertEquals(listOf<Any?>(), categoriesOf(c))
    }

    @Test
    fun `meta keys are set, replaced, listed and removed`() {
        val id = create("Watching")
        val set = { key: String, value: String ->
            graphql(
                """mutation { setAnimeCategoryMeta(input: {meta: {categoryId: $id, key: "$key", value: "$value"}}) {
                    meta { categoryId key value } } }""",
            ).assertNoErrors().dataPath("setAnimeCategoryMeta", "meta")
        }
        assertEquals("title:asc", set("madoyomi.library.sort", "title:asc").path("value"))
        set("madoyomi.library.sort", "added:desc")
        set("a", "1")
        val meta =
            graphql("{ animeCategory(id: $id) { meta { key value } } }").assertNoErrors().dataPath("animeCategory", "meta").asList
        assertEquals(listOf("a" to "1", "madoyomi.library.sort" to "added:desc"), meta.map { it.path("key") to it.path("value") })

        val deleted =
            graphql(
                """mutation { deleteAnimeCategoryMeta(input: {categoryId: $id, key: "madoyomi.library.sort"}) {
                    meta { value } category { meta { key } } } }""",
            ).assertNoErrors()
                .dataPath("deleteAnimeCategoryMeta")
        assertEquals("added:desc", deleted.path("meta", "value"))
        assertEquals(listOf("a"), deleted.path("category", "meta").asList.map { it.path("key") })
        val again =
            graphql("""mutation { deleteAnimeCategoryMeta(input: {categoryId: $id, key: "missing"}) { meta { value } } }""")
                .assertNoErrors()
                .dataPath("deleteAnimeCategoryMeta")
        assertNull(again.path("meta"))
        val missing = id + 100
        val unknown =
            graphql("""mutation { setAnimeCategoryMeta(input: {meta: {categoryId: $missing, key: "k", value: "v"}}) { meta { key } } }""")
        assertTrue(unknown.errorCode == "NOT_FOUND")
    }
}
