package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import madoyomi.core.anime.FakeAnimeSource.Companion.anime
import madoyomi.core.anime.FakeAnimeSource.Companion.episode
import madoyomi.core.anime.model.table.AnimeHistoryTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AnimeDetails]: details written, episodes reconciled by url, seasons linked to their parent. */
class AnimeDetailsTest {
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

    private val source = FakeAnimeSource(id = 7_311L)

    @BeforeEach
    fun setUp() {
        FakeAnimeSource.clearRows(source.id)
        AnimeGetSource.registerSource(source)
    }

    @AfterEach
    fun tearDown() {
        AnimeGetSource.unregisterSource(source.id)
        FakeAnimeSource.clearRows(source.id)
    }

    /** Browses [anime] so it has a row, and returns its id. */
    private fun browsed(anime: SAnime): Int {
        source.popular = { AnimesPage(listOf(anime), false) }
        return runBlocking { AnimeBrowse.fetch(source.id, AnimeBrowse.Type.POPULAR, 1) }.animeIds.single()
    }

    private fun refresh(
        animeId: Int,
        details: Boolean = true,
        children: Boolean = true,
    ) = runBlocking { AnimeDetails.fetch(animeId, details, children) }

    private fun animeRow(id: Int) = dbTransaction { AnimeTable.selectAll().where { AnimeTable.id eq id }.single() }

    private fun episodes(animeId: Int) =
        dbTransaction {
            EpisodeTable
                .selectAll()
                .where { EpisodeTable.anime eq animeId }
                .associateBy { it[EpisodeTable.url] }
        }

    @Test
    fun `details are written over the browse fields and the url stays the DB's`() {
        val id = browsed(anime("/a", "Browse title") { thumbnail_url = "https://example.org/t.jpg" })
        source.details = {
            // a details answer usually has no url
            SAnime.create().apply {
                title = "Full title"
                description = "Plot"
                genre = "Action, Drama"
                status = SAnime.COMPLETED
                memo = buildJsonObject { put("id", 42) }
            }
        }

        val result = refresh(id, children = false)

        assertEquals(id, result.animeId)
        val row = animeRow(id)
        assertEquals("/a", row[AnimeTable.url])
        assertEquals("Full title", row[AnimeTable.title])
        assertEquals("Plot", row[AnimeTable.description])
        assertEquals(SAnime.COMPLETED, row[AnimeTable.status])
        assertEquals("https://example.org/t.jpg", row[AnimeTable.thumbnailUrl], "kept: the details had none")
        assertEquals(buildJsonObject { put("id", 42) }, row[AnimeTable.memo])
        assertTrue(row[AnimeTable.initialized])
        assertTrue(row[AnimeTable.lastFetchedAt] > 0)
    }

    @Test
    fun `episodes are inserted, updated and deleted by url, numbered oldest first`() {
        val id = browsed(anime("/a"))
        source.episodes = { listOf(episode("/e3", 3f), episode("/e2", 2f), episode("/e1", 1f)) }

        val first = refresh(id)

        assertEquals(FetchType.Episodes, first.fetchType)
        assertEquals(3, first.episodeIds.size)
        var rows = episodes(id)
        assertEquals(listOf(1, 2, 3), listOf("/e1", "/e2", "/e3").map { rows.getValue(it)[EpisodeTable.sourceOrder] })
        assertEquals(first.episodeIds, AnimeDetails.currentEpisodeIds(id), "newest first, as listed")

        // the source renames e2, drops e1 and adds e4
        source.episodes = { listOf(episode("/e4", 4f), episode("/e3", 3f), episode("/e2", 2f) { name = "Renamed" }) }
        val second = refresh(id)

        rows = episodes(id)
        assertEquals(setOf("/e2", "/e3", "/e4"), rows.keys)
        assertEquals(first.episodeIds[0], second.episodeIds[1], "/e3 kept its id")
        assertEquals("Renamed", rows.getValue("/e2")[EpisodeTable.name])
        assertEquals(listOf(1, 2, 3), listOf("/e2", "/e3", "/e4").map { rows.getValue(it)[EpisodeTable.sourceOrder] })
        assertEquals(listOf("/e3", "/e2", "/e1"), source.lastStoredEpisodes.map { it.url }, "the source got the stored list")
    }

    @Test
    fun `an update keeps the watch state, and a re-listed episode with the same number inherits it`() {
        val id = browsed(anime("/a"))
        source.episodes = { listOf(episode("/e2", 2f), episode("/e1", 1f)) }
        refresh(id)
        val e1 = episodes(id).getValue("/e1")[EpisodeTable.id]
        dbTransaction {
            EpisodeTable.update({ EpisodeTable.anime eq id }) {
                it[watched] = true
                it[lastSecondSeen] = 600
                it[totalSeconds] = 1400
            }
            AnimeHistoryTable.insert {
                it[episode] = e1
                it[lastSeen] = 1
            }
        }

        // e1 moved to a new url (the site changed its links), e2 is unchanged
        source.episodes = { listOf(episode("/e2", 2f), episode("/new-e1", 1f)) }
        refresh(id)

        val rows = episodes(id)
        assertTrue(rows.getValue("/e2")[EpisodeTable.watched])
        assertEquals(600L, rows.getValue("/e2")[EpisodeTable.lastSecondSeen])
        val moved = rows.getValue("/new-e1")
        assertTrue(moved[EpisodeTable.watched], "carried over by episode number")
        assertEquals(1400L, moved[EpisodeTable.totalSeconds])
        assertEquals(0L, dbTransaction { AnimeHistoryTable.selectAll().where { AnimeHistoryTable.episode eq e1 }.count() })
    }

    @Test
    fun `the new SEpisode fields and dates are stored, and the stored episodes go back to the source`() {
        val id = browsed(anime("/a"))
        source.episodes = {
            listOf(
                episode("/e2", 2f) { date_upload = 0 },
                episode("/e1", 1f) {
                    date_upload = 1_700_000_000_000
                    fillermark = true
                    summary = "Recap"
                    preview_url = "https://example.org/p.jpg"
                    memo = buildJsonObject { put("hash", "abc") }
                },
            )
        }

        refresh(id)

        val rows = episodes(id)
        val e1 = rows.getValue("/e1")
        assertTrue(e1[EpisodeTable.fillermark])
        assertEquals("Recap", e1[EpisodeTable.summary])
        assertEquals("https://example.org/p.jpg", e1[EpisodeTable.previewUrl])
        assertEquals(1_700_000_000_000, rows.getValue("/e2")[EpisodeTable.dateUpload], "a new episode without a date gets the older one's")

        source.episodes = { listOf(episode("/e2", 2f), episode("/e1", 1f) { date_upload = 0 }) }
        refresh(id)

        assertEquals(1_700_000_000_000, episodes(id).getValue("/e1")[EpisodeTable.dateUpload], "a date of 0 keeps the stored one")
        val stored = source.lastStoredEpisodes.associateBy(SEpisode::url)
        assertEquals(buildJsonObject { put("hash", "abc") }, stored.getValue("/e1").memo)
    }

    @Test
    fun `an empty episode list keeps what is stored`() {
        val id = browsed(anime("/a"))
        source.episodes = { listOf(episode("/e1", 1f)) }
        val stored = refresh(id).episodeIds

        source.episodes = { emptyList() }

        assertEquals(stored, refresh(id).episodeIds)
        assertEquals(setOf("/e1"), episodes(id).keys)
    }

    @Test
    fun `an entry with seasons gets its seasons linked, not episodes`() {
        val id = browsed(anime("/show"))
        source.details = { SAnime.create().apply { fetch_type = FetchType.Seasons } }
        source.episodes = { error("a Seasons entry must not be asked for episodes") }
        source.seasons = { listOf(anime("/show/s1", "Season 1"), anime("/show/s2", "Season 2")) }

        val result = refresh(id)

        assertEquals(FetchType.Seasons, result.fetchType)
        assertTrue(result.episodeIds.isEmpty())
        assertEquals(2, result.seasonIds.size)
        val s1 = animeRow(result.seasonIds[0])
        assertEquals("/show/s1", s1[AnimeTable.url])
        assertEquals(id, s1[AnimeTable.parentId]?.value)
        assertEquals(1, s1[AnimeTable.seasonSourceOrder])
        assertEquals(result.seasonIds, AnimeDetails.currentSeasonIds(id))

        // season 1 goes away: it stays (it may be in the library) but no longer belongs to the show
        source.seasons = { listOf(anime("/show/s2", "Season 2")) }
        val again = refresh(id)

        assertEquals(listOf(result.seasonIds[1]), again.seasonIds)
        assertNull(animeRow(result.seasonIds[0])[AnimeTable.parentId])
        assertEquals(1, animeRow(result.seasonIds[1])[AnimeTable.seasonSourceOrder])
    }

    @Test
    fun `details only, and children only, do just that`() {
        val id = browsed(anime("/a"))
        var detailCalls = 0
        source.details = {
            detailCalls++
            it
        }
        source.episodes = { listOf(episode("/e1", 1f)) }

        assertTrue(refresh(id, details = true, children = false).episodeIds.isEmpty())
        assertEquals(1, detailCalls)
        assertEquals(1, refresh(id, details = false, children = true).episodeIds.size)
        assertEquals(1, detailCalls)
        assertFalse(episodes(id).isEmpty())
    }

    @Test
    fun `an unknown anime is NOT_FOUND`() {
        val e = assertFailsWith<MadoyomiException> { refresh(Int.MAX_VALUE) }
        assertEquals(ErrorCode.NOT_FOUND, e.code)
    }
}
