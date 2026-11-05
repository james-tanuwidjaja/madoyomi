package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import madoyomi.core.anime.FakeAnimeSource.Companion.anime
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionCompatLog
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.graphql.types.FilterChange
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [AnimeBrowse]: pages upserted into ANIME by (source, url), so ids are stable. */
class AnimeBrowseTest {
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

    private val source = FakeAnimeSource(id = 7_301L)

    @BeforeEach
    fun setUp() {
        FakeAnimeSource.clearRows(source.id)
        AnimeGetSource.registerSource(source)
        ExtensionCompatLog.clearAll()
    }

    @AfterEach
    fun tearDown() {
        AnimeGetSource.unregisterSource(source.id)
        FakeAnimeSource.clearRows(source.id)
        ExtensionCompatLog.clearAll()
    }

    private fun page(vararg animes: SAnime) = AnimesPage(animes.toList(), hasNextPage = true)

    private fun browse(
        type: AnimeBrowse.Type = AnimeBrowse.Type.POPULAR,
        page: Int = 1,
        query: String? = null,
        filters: List<FilterChange>? = null,
    ) = runBlocking { AnimeBrowse.fetch(source.id, type, page, query, filters) }

    private fun row(id: Int) = dbTransaction { AnimeTable.selectAll().where { AnimeTable.id eq id }.single() }

    @Test
    fun `the same url keeps its id across pages and calls`() {
        source.popular = { p -> if (p == 1) page(anime("/a"), anime("/b")) else page(anime("/b"), anime("/c")) }

        val first = browse(page = 1)
        val second = browse(page = 2)
        val again = browse(page = 1)

        assertEquals(2, first.animeIds.size)
        assertTrue(first.hasNextPage)
        assertEquals(first.animeIds[1], second.animeIds[0], "/b is one row")
        assertEquals(first.animeIds, again.animeIds)
        assertEquals(3L, dbTransaction { AnimeTable.selectAll().where { AnimeTable.sourceReference eq source.id }.count() })
        val a = row(first.animeIds[0])
        assertEquals("/a", a[AnimeTable.url])
        assertEquals(source.id, a[AnimeTable.sourceReference])
        assertFalse(a[AnimeTable.initialized])
    }

    @Test
    fun `a url listed twice in a page is one entry, and entries without a url are left out`() {
        source.popular = { page(anime("/a"), anime("/b"), anime("/a", "Again"), SAnime.create()) }

        val ids = browse().animeIds

        assertEquals(2, ids.size)
        assertEquals("Title /a", row(ids[0])[AnimeTable.title])
    }

    @Test
    fun `a browse updates entries outside the library and keeps known fields the page doesn't have`() {
        source.popular = {
            page(
                anime("/a") {
                    description = "Old description"
                    thumbnail_url = "https://example.org/a.jpg"
                    status = SAnime.ONGOING
                    memo = buildJsonObject { put("v", 1) }
                },
            )
        }
        val id = browse().animeIds.single()
        source.popular = { page(anime("/a", "New title") { status = SAnime.COMPLETED }) }

        assertEquals(listOf(id), browse().animeIds)

        val a = row(id)
        assertEquals("New title", a[AnimeTable.title])
        assertEquals(SAnime.COMPLETED, a[AnimeTable.status])
        assertEquals("Old description", a[AnimeTable.description])
        assertEquals("https://example.org/a.jpg", a[AnimeTable.thumbnailUrl])
    }

    @Test
    fun `library entries are not overwritten by a browse`() {
        source.popular = { page(anime("/a", "Library title")) }
        val id = browse().animeIds.single()
        dbTransaction { AnimeTable.update({ AnimeTable.id eq id }) { it[inLibrary] = true } }
        source.popular = { page(anime("/a", "Browse title")) }

        assertEquals(listOf(id), browse().animeIds)
        assertEquals("Library title", row(id)[AnimeTable.title])
    }

    @Test
    fun `the new SAnime fields are stored`() {
        source.popular = {
            page(
                anime("/a") {
                    background_url = "https://example.org/bg.jpg"
                    fetch_type = FetchType.Seasons
                    season_number = 3.0
                    memo = buildJsonObject { put("token", "x") }
                },
            )
        }

        val a = row(browse().animeIds.single())

        assertEquals("https://example.org/bg.jpg", a[AnimeTable.backgroundUrl])
        assertEquals(FetchType.Seasons, a[AnimeTable.fetchType])
        assertEquals(3.0, a[AnimeTable.seasonNumber])
        assertEquals(buildJsonObject { put("token", "x") }, a[AnimeTable.memo])
    }

    @Test
    fun `concurrent browses of the same page give the same ids`() {
        source.popular = { page(anime("/a"), anime("/b"), anime("/c")) }

        val results = runBlocking { (1..4).map { async { AnimeBrowse.fetch(source.id, AnimeBrowse.Type.POPULAR, 1) } }.awaitAll() }

        assertEquals(1, results.map { it.animeIds }.toSet().size)
        assertEquals(3L, dbTransaction { AnimeTable.selectAll().where { AnimeTable.sourceReference eq source.id }.count() })
    }

    @Test
    fun `search gets the query and the filters applied to a fresh list`() {
        class Genre : AnimeFilter.CheckBox("Action")
        source.filters = { AnimeFilterList(Genre()) }
        var seen: Pair<String, Boolean>? = null
        source.search = { _, query, filters ->
            seen = query to (filters.single() as AnimeFilter.CheckBox).state
            page(anime("/found"))
        }

        val ids =
            browse(
                AnimeBrowse.Type.SEARCH,
                query = "naruto",
                filters = listOf(FilterChange(position = 0, checkBoxState = true)),
            ).animeIds

        assertEquals("naruto" to true, seen)
        assertEquals("/found", row(ids.single())[AnimeTable.url])
        // the next search starts from the source's defaults again
        browse(AnimeBrowse.Type.SEARCH, query = "")
        assertEquals("" to false, seen)
    }

    @Test
    fun `latest on a source without it and a page below 1 are BAD_REQUEST`() {
        val noLatest = FakeAnimeSource(id = 7_302L, supportsLatest = false)
        AnimeGetSource.registerSource(noLatest)
        try {
            val e = assertFailsWith<MadoyomiException> { runBlocking { AnimeBrowse.fetch(noLatest.id, AnimeBrowse.Type.LATEST, 1) } }
            assertEquals(ErrorCode.BAD_REQUEST, e.code)
        } finally {
            AnimeGetSource.unregisterSource(noLatest.id)
        }
        assertEquals(ErrorCode.BAD_REQUEST, assertFailsWith<MadoyomiException> { browse(page = 0) }.code)
    }

    @Test
    fun `an unknown source is NOT_FOUND`() {
        val e = assertFailsWith<MadoyomiException> { runBlocking { AnimeBrowse.fetch(9_999_999L, AnimeBrowse.Type.POPULAR, 1) } }
        assertEquals(ErrorCode.NOT_FOUND, e.code)
    }

    @Test
    fun `an API gap in the source is EXTENSION_INCOMPATIBLE and logged`() {
        source.popular = { throw NoSuchMethodError("okhttp3.Missing.call()") }

        val e = assertFailsWith<MadoyomiException> { browse() }

        assertEquals(ErrorCode.EXTENSION_INCOMPATIBLE, e.code)
        assertEquals("popular", e.details["operation"])
        assertTrue(
            ExtensionCompatLog
                .all()
                .values
                .flatten()
                .any { it.target.contains("okhttp3.Missing") },
        )
    }
}
