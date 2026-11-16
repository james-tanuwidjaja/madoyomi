package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import madoyomi.core.anime.FakeAnimeSource
import madoyomi.core.anime.FakeAnimeSource.Companion.episode
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.library.LibraryUpdateScheduler
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.library.LibraryUpdater
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A scripted manga source: [chapters] is what the source lists, newest first. */
private class FakeMangaSource(
    override val id: Long,
) : Source {
    override val name = "Fake manga $id"
    override val lang = "en"
    override val supportsLatest = false

    @Volatile
    var chapters: List<SChapter> = emptyList()

    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ) = MangasPage(emptyList(), false)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) = SMangaUpdate(manga, if (fetchChapters) this.chapters else chapters)

    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

    companion object {
        fun chapter(
            url: String,
            number: Float,
        ): SChapter =
            SChapter.create().apply {
                this.url = url
                name = "Chapter $number"
                chapter_number = number
            }
    }
}

/**
 * The library update check (Phase 6 Stage 3): the update diff of both kinds with the new items counted, marked and
 * listed in the Updates feed (an entry's first fetch left out, a re-listed item not new), the category and settings
 * filters, one entry at a time per source with the stagger and the parallel-source limit, stop, the subscription, the
 * settings and the schedule.
 */
class LibraryUpdateApiTest : AnimeApiTest() {
    private val now get() = Instant.now().epochSecond

    @BeforeEach
    fun freshUpdater() {
        LibraryUpdateSettingsStore.fileOverride =
            kotlin.io.path
                .createTempFile("library-update", ".json")
                .toFile()
                .also { it.delete() }
        LibraryUpdateSettingsStore.reload()
        LibraryUpdater.reset()
        LibraryUpdater.staggerMs = 0
        LibraryUpdater.parallelSourcesOverride = null
        LibraryUpdater.checkOverride = null
    }

    @AfterEach
    fun resetUpdater() {
        LibraryUpdateScheduler.stop()
        LibraryUpdateScheduler.intervalOverrideMs = null
        LibraryUpdater.reset()
        LibraryUpdater.staggerMs = 1_000
        LibraryUpdater.parallelSourcesOverride = null
        LibraryUpdater.checkOverride = null
        LibraryUpdateSettingsStore.fileOverride?.delete()
        LibraryUpdateSettingsStore.fileOverride = null
        LibraryUpdateSettingsStore.reload()
        GetSource.unregisterAllSources()
    }

    private fun libraryAnime(
        sourceId: Long,
        url: String,
        status: Int = SAnime.ONGOING,
    ): Int {
        val id = animeRow(sourceId, url, initialized = true)
        dbTransaction {
            AnimeTable.update({ AnimeTable.id eq id }) {
                it[inLibrary] = true
                it[addedAt] = now - 3_600
                it[AnimeTable.status] = status
            }
        }
        return id
    }

    private fun oldEpisode(
        animeId: Int,
        url: String,
        order: Int,
    ): Int {
        val id = episodeRow(animeId, url, order)
        dbTransaction { EpisodeTable.update({ EpisodeTable.id eq id }) { it[fetchedAt] = now - 7_200 } }
        return id
    }

    private fun libraryManga(
        sourceId: Long,
        title: String,
        status: Int = 1,
    ): Int =
        dbTransaction {
            val id =
                MangaTable
                    .insertAndGetId {
                        it[url] = "/manga/$title"
                        it[this.title] = title
                        it[initialized] = true
                        it[sourceReference] = sourceId
                        it[memo] = JsonObject(emptyMap())
                        it[MangaTable.status] = status
                    }.value
            MangaUserTable.insert {
                it[manga] = id
                it[user] = 1
                it[inLibrary] = true
                it[inLibraryAt] = now - 3_600
            }
            id
        }

    private fun oldChapter(
        mangaId: Int,
        url: String,
        number: Int,
    ) = dbTransaction {
        ChapterTable.insert {
            it[ChapterTable.url] = url
            it[name] = "Chapter $number"
            it[chapter_number] = number.toFloat()
            it[sourceOrder] = number
            it[manga] = mangaId
            it[fetchedAt] = now - 7_200
            it[memo] = JsonObject(emptyMap())
        }
    }

    private fun start(input: String = ""): Map<*, *> =
        graphql(
            """mutation { startLibraryUpdate(input: {$input}) { libraryCheck { running total done skipped } } }""",
        ).assertNoErrors().dataPath("startLibraryUpdate", "libraryCheck") as Map<*, *>

    private fun finished(): Map<*, *> {
        runBlocking { LibraryUpdater.awaitIdle() }
        return graphql(
            "{ libraryCheck { running trigger total done skipped newEpisodes newChapters cancelled " +
                "found { entry { kind id title } newCount } failures { entry { id } error } } }",
        ).assertNoErrors().dataPath("libraryCheck") as Map<*, *>
    }

    private fun marks(kind: String): Map<Any?, Any?> =
        (graphql("{ updateMarks(kind: $kind) { entryId newCount markedAt } }").assertNoErrors().dataPath("updateMarks") as List<*>)
            .associate { it.path("entryId") to it.path("newCount") }

    // the ids an update checks, through checkOverride (no source calls)
    private fun checkedIds(input: String = "kind: ANIME"): Set<Int> {
        val seen = Collections.synchronizedSet(HashSet<Int>())
        LibraryUpdater.checkOverride = { entry ->
            seen.add(entry.id)
            0
        }
        start(input)
        finished()
        return seen.toSet()
    }

    @Test
    fun `an anime check inserts the new episodes, counts and marks them, and the feed lists them`() {
        val source = FakeAnimeSource(7_301L).also(::register)
        val watching = libraryAnime(source.id, "/watching")
        oldEpisode(watching, "/w/1", 1)
        val fresh = libraryAnime(source.id, "/fresh") // nothing stored: its first fetch isn't news
        source.episodes = { anime ->
            if (anime.url == "/watching") listOf(episode("/w/2", 2f), episode("/w/1", 1f)) else listOf(episode("/f/1", 1f))
        }

        assertEquals(2, start("kind: ANIME").path("total"))
        val check = finished()
        assertEquals(false, check.path("running"))
        assertEquals("MANUAL", check.path("trigger"))
        assertEquals(2, check.path("done"))
        assertEquals(1, check.path("newEpisodes"))
        assertEquals(0, check.path("newChapters"))
        assertEquals(
            listOf(mapOf("entry" to mapOf("kind" to "ANIME", "id" to watching, "title" to "Title /watching"), "newCount" to 1)),
            check.path("found"),
        )
        assertEquals(mapOf<Any?, Any?>(watching to 1), marks("ANIME"))

        val feed =
            graphql("{ animeUpdates { hasMore items { anime { id } episode { url } fetchedAt } } }")
                .assertNoErrors()
                .dataPath("animeUpdates", "items") as List<*>
        assertEquals(listOf("/w/2"), feed.map { it.path("episode", "url") }, "the fresh entry's first fetch is left out")
        assertEquals(watching, feed.single().path("anime", "id"))
        dbTransaction { assertEquals(1L, EpisodeTable.selectAll().where { EpisodeTable.anime eq fresh }.count()) }

        // a second check finding nothing adds nothing (even in the same second); the mark stays until the entry is opened
        start("kind: ANIME")
        assertEquals(0, finished().path("newEpisodes"))
        assertEquals(mapOf<Any?, Any?>(watching to 1), marks("ANIME"))

        // a re-listed episode (a new url, the same number) keeps its fetch date: not new
        dbTransaction { EpisodeTable.update({ EpisodeTable.anime eq watching }) { it[fetchedAt] = now - 7_200 } }
        source.episodes = { anime ->
            if (anime.url == "/watching") listOf(episode("/w/2b", 2f), episode("/w/1", 1f)) else listOf(episode("/f/1", 1f))
        }
        start("kind: ANIME")
        assertEquals(0, finished().path("newEpisodes"))
        dbTransaction {
            val relisted = EpisodeTable.selectAll().where { EpisodeTable.url eq "/w/2b" }.single()
            assertTrue(relisted[EpisodeTable.fetchedAt] < now - 3_600, "the re-listed episode kept the old fetch date")
        }

        assertEquals(
            1,
            graphql("""mutation { clearUpdateMarks(input: {kind: ANIME, entryIds: [$watching]}) { cleared } }""")
                .assertNoErrors()
                .dataPath("clearUpdateMarks", "cleared"),
        )
        assertEquals(emptyMap(), marks("ANIME"))
    }

    @Test
    fun `a Seasons entry checks its season list and the seasons fetched before, and is marked itself`() {
        val source = FakeAnimeSource(7_305L).also(::register)
        val parent = libraryAnime(source.id, "/series")
        dbTransaction { AnimeTable.update({ AnimeTable.id eq parent }) { it[fetchType] = FetchType.Seasons } }
        val opened = animeRow(source.id, "/s1", initialized = true)
        val never = animeRow(source.id, "/s2", initialized = true)
        dbTransaction {
            AnimeTable.update({ AnimeTable.id inList listOf(opened, never) }) { it[parentId] = parent }
        }
        oldEpisode(opened, "/s1/1", 1)
        val fetched = Collections.synchronizedList(mutableListOf<String>())
        source.seasons = { listOf(FakeAnimeSource.anime("/s1"), FakeAnimeSource.anime("/s2")) }
        source.episodes = { anime ->
            fetched.add(anime.url)
            listOf(episode("${anime.url}/2", 2f), episode("${anime.url}/1", 1f))
        }

        start("kind: ANIME")
        assertEquals(1, finished().path("newEpisodes"))
        assertEquals(listOf("/s1"), fetched.toList(), "a season never opened is left for its first visit")
        assertEquals(mapOf<Any?, Any?>(parent to 1), marks("ANIME"), "the library entry is marked, not the season")
        val feed =
            graphql(
                "{ animeUpdates { items { anime { id } episode { url } } } }",
            ).assertNoErrors().dataPath("animeUpdates", "items") as List<*>
        assertEquals(listOf(opened to "/s1/2"), feed.map { it.path("anime", "id") to it.path("episode", "url") })
    }

    @Test
    fun `a manga check inserts the new chapters through upstream's diff, counts, marks and lists them`() {
        val source = FakeMangaSource(7_302L)
        GetSource.registerSource(source.id to source)
        val reading = libraryManga(source.id, "Reading")
        oldChapter(reading, "/r/1", 1)
        source.chapters = listOf(FakeMangaSource.chapter("/r/2", 2f), FakeMangaSource.chapter("/r/1", 1f))

        start("kind: MANGA")
        val check = finished()
        assertEquals(1, check.path("newChapters"))
        assertEquals(emptyList<Any>(), check.path("failures"))
        assertEquals(mapOf<Any?, Any?>(reading to 1), marks("MANGA"))
        val feed =
            graphql("{ mangaUpdates { hasMore items { manga { id } chapter { url isRead } fetchedAt } } }")
                .assertNoErrors()
                .dataPath("mangaUpdates", "items") as List<*>
        assertEquals(listOf("/r/2"), feed.map { it.path("chapter", "url") })
        assertEquals(false, feed.single().path("chapter", "isRead"))

        // a source that fails is reported per entry and the check goes on
        source.chapters = emptyList()
        start("kind: MANGA")
        val failed = finished()
        assertEquals(1, (failed.path("failures") as List<*>).size)
        assertTrue((failed.path("failures", "0", "error") as String).contains("No chapters found"))

        assertEquals(
            1,
            graphql("""mutation { clearUpdateMarks(input: {kind: MANGA}) { cleared } }""")
                .assertNoErrors()
                .dataPath("clearUpdateMarks", "cleared"),
        )
    }

    @Test
    fun `categories decide what is checked, as upstream's rule, and a category check ignores the flags`() {
        val source = 7_303L
        val a = libraryAnime(source, "/a")
        val b = libraryAnime(source, "/b")
        val c = libraryAnime(source, "/c")
        val d = libraryAnime(source, "/d") // in no category: Default

        fun category(name: String): Int =
            graphql("""mutation { createAnimeCategory(input: {name: "$name"}) { category { id } } }""")
                .assertNoErrors()
                .dataPath("createAnimeCategory", "category", "id") as Int
        val watching = category("Watching")
        val dropped = category("Dropped")
        graphql("""mutation { updateAnimesCategories(input: {ids: [$a, $b], patch: {addToCategories: [$watching]}}) { animes { id } } }""")
            .assertNoErrors()
        graphql("""mutation { updateAnimesCategories(input: {ids: [$b, $c], patch: {addToCategories: [$dropped]}}) { animes { id } } }""")
            .assertNoErrors()

        assertEquals(setOf(a, b, c, d), checkedIds(), "every category UNSET: the whole library")

        graphql("""mutation { updateAnimeCategory(input: {id: $dropped, patch: {includeInUpdate: EXCLUDE}}) { category { id } } }""")
            .assertNoErrors()
        assertEquals(setOf(a, d), checkedIds(), "an entry in an excluded category is left out, even if in another")

        graphql("""mutation { updateAnimeCategory(input: {id: $watching, patch: {includeInUpdate: INCLUDE}}) { category { id } } }""")
            .assertNoErrors()
        assertEquals(setOf(a), checkedIds(), "with an INCLUDE category, only the included ones")

        assertEquals(setOf(b, c), checkedIds("kind: ANIME, categoryId: $dropped"), "one category whatever its flag")
        assertEquals(
            "BAD_REQUEST",
            graphql("""mutation { startLibraryUpdate(input: {categoryId: $dropped}) { libraryCheck { running } } }""").errorCode,
        )
        assertEquals(
            "NOT_FOUND",
            graphql("""mutation { startLibraryUpdate(input: {kind: ANIME, categoryId: 99999}) { libraryCheck { running } } }""").errorCode,
        )

        // manga: upstream's categories, the same rule
        val m1 = libraryManga(source, "M1")
        val m2 = libraryManga(source, "M2")
        val later =
            graphql("""mutation { createCategory(input: {name: "Later", includeInUpdate: EXCLUDE}) { category { id } } }""")
                .assertNoErrors()
                .dataPath("createCategory", "category", "id") as Int
        graphql("""mutation { updateMangaCategories(input: {id: $m2, patch: {addToCategories: [$later]}}) { manga { id } } }""")
            .assertNoErrors()
        assertEquals(setOf(m1), checkedIds("kind: MANGA"))
    }

    @Test
    fun `completed, unseen and not started entries are skipped as the settings say`() {
        val source = 7_304L
        val ongoing = libraryAnime(source, "/ongoing")
        oldEpisode(ongoing, "/o/1", 1)
        val done = libraryAnime(source, "/done", SAnime.COMPLETED)
        val mangaDone = libraryManga(source, "Done", status = 2)
        val mangaOngoing = libraryManga(source, "Ongoing")

        assertEquals(setOf(ongoing), checkedIds(), "completed is skipped by default")
        assertEquals(1, finished().path("skipped"))
        assertEquals(setOf(mangaOngoing), checkedIds("kind: MANGA"))

        graphql("mutation { setLibraryUpdateSettings(input: {skipCompleted: false}) { settings { skipCompleted } } }").assertNoErrors()
        assertEquals(setOf(ongoing, done), checkedIds())
        assertEquals(setOf(mangaOngoing, mangaDone), checkedIds("kind: MANGA"))

        graphql("mutation { setLibraryUpdateSettings(input: {skipUnseen: true}) { settings { skipUnseen } } }").assertNoErrors()
        assertEquals(setOf(done), checkedIds(), "the ongoing one has an unwatched episode")

        graphql("mutation { setLibraryUpdateSettings(input: {skipUnseen: false, skipNotStarted: true}) { settings { skipNotStarted } } }")
            .assertNoErrors()
        assertEquals(emptySet(), checkedIds(), "nothing watched in either")
        dbTransaction { EpisodeTable.update({ EpisodeTable.anime eq ongoing }) { it[watched] = true } }
        assertEquals(setOf(ongoing), checkedIds())
    }

    @Test
    fun `one entry at a time per source, staggered, and at most the parallel-source limit at once`() {
        val sources = listOf(7_310L, 7_311L, 7_312L)
        val ids = sources.flatMap { s -> (1..3).map { libraryAnime(s, "/$s/$it") } }
        val sourceOf = dbTransaction { AnimeTable.selectAll().associate { it[AnimeTable.id].value to it[AnimeTable.sourceReference] } }
        val inFlight = ConcurrentHashMap<Long, AtomicInteger>()
        val overall = AtomicInteger()
        val maxPerSource = AtomicInteger()
        val maxOverall = AtomicInteger()
        val spans = Collections.synchronizedList(mutableListOf<Triple<Long, Long, Long>>())
        LibraryUpdater.staggerMs = 120
        LibraryUpdater.parallelSourcesOverride = 2
        LibraryUpdater.checkOverride = { entry ->
            val source = sourceOf.getValue(entry.id)
            val started = System.currentTimeMillis()
            maxPerSource.accumulateAndGet(inFlight.getOrPut(source) { AtomicInteger() }.incrementAndGet(), ::maxOf)
            maxOverall.accumulateAndGet(overall.incrementAndGet(), ::maxOf)
            delay(60)
            inFlight.getValue(source).decrementAndGet()
            overall.decrementAndGet()
            spans.add(Triple(source, started, System.currentTimeMillis()))
            0
        }

        assertEquals(ids.size, start("kind: ANIME").path("total"))
        assertEquals(ids.size, finished().path("done"))
        assertEquals(1, maxPerSource.get(), "one entry of a source at a time")
        assertTrue(maxOverall.get() <= 2, "at most 2 sources at once, was ${maxOverall.get()}")
        assertEquals(2, maxOverall.get(), "two sources do run at once")
        spans.groupBy { it.first }.values.forEach { list ->
            list.sortedBy { it.second }.zipWithNext().forEach { (a, b) ->
                assertTrue(b.second - a.third >= 100, "the next entry of a source waits for the stagger: ${b.second - a.third} ms")
            }
        }
    }

    @Test
    fun `stop ends a check, a check started while one runs adds to it, and the subscription follows it`() {
        val source = 7_320L
        val a = libraryAnime(source, "/a")
        val b = libraryAnime(7_321L, "/b")
        val m = libraryManga(7_322L, "M")
        LibraryUpdater.parallelSourcesOverride = 3
        LibraryUpdater.checkOverride = { entry ->
            // anime and manga ids are separate sequences: a manga can have a's id
            if (entry.kind == ExtensionKind.ANIME && entry.id == a) delay(30_000) else delay(200)
            0
        }

        val seen = Collections.synchronizedList(mutableListOf<Any?>())
        runBlocking {
            withTimeout(20_000) {
                val collector =
                    launch(Dispatchers.Default) {
                        subscribe("subscription { libraryUpdates { running total done cancelled } }").collect {
                            seen.add(it.data.path("libraryUpdates"))
                        }
                    }
                while (seen.isEmpty()) delay(10)
                assertEquals(false, seen.first().path("running"), "the status at once on subscribing")
                start("kind: ANIME")
                assertEquals(3, start("kind: MANGA").path("total"), "added to the running check")
                while (LibraryUpdater.status.value.done < 2) delay(20)
                graphql("mutation { stopLibraryUpdate(input: {}) { libraryCheck { running cancelled } } }").assertNoErrors()
                // a synchronized list is iterated under its lock: the collector adds to it meanwhile
                while (synchronized(seen) { seen.none { it.path("cancelled") == true } }) delay(20)
                collector.cancel()
            }
        }
        assertTrue(seen.any { it.path("running") == true && it.path("total") == 3 }, "followed the running check: $seen")
        val check = finished()
        assertEquals(false, check.path("running"))
        assertEquals(true, check.path("cancelled"))
        assertEquals(2, check.path("done"), "$a was stopped; $b and $m were checked")
    }

    @Test
    fun `the settings are kept in the data dir, checked, and a schedule runs the check`() {
        val defaults =
            graphql("{ libraryUpdateSettings { intervalHours skipCompleted skipUnseen skipNotStarted notify } }")
                .assertNoErrors()
                .dataPath("libraryUpdateSettings")
        assertEquals(
            mapOf("intervalHours" to 0, "skipCompleted" to true, "skipUnseen" to false, "skipNotStarted" to false, "notify" to false),
            defaults,
            "the schedule and the toast are off by default (the user's decisions)",
        )
        assertEquals(
            "BAD_REQUEST",
            graphql("mutation { setLibraryUpdateSettings(input: {intervalHours: 5}) { settings { intervalHours } } }").errorCode,
        )
        assertEquals(null, graphql("{ libraryCheck { nextScheduledAt } }").assertNoErrors().dataPath("libraryCheck", "nextScheduledAt"))

        // the schedule's maths: one interval after the anchor; an overdue check waits for the startup delay
        assertEquals(1_000L, LibraryUpdateScheduler.nextDelay(now = 10_000, anchor = 9_000, interval = 2_000, startupDelay = 500))
        assertEquals(500L, LibraryUpdateScheduler.nextDelay(now = 10_000, anchor = 1_000, interval = 2_000, startupDelay = 500))
        assertEquals(2_000L, LibraryUpdateScheduler.nextDelay(now = 10_000, anchor = 1_000, interval = 2_000, startupDelay = 60_000))

        val a = libraryAnime(7_330L, "/scheduled")
        val checked = AtomicInteger()
        LibraryUpdater.checkOverride = { entry ->
            if (entry.id == a) checked.incrementAndGet()
            0
        }
        LibraryUpdateScheduler.intervalOverrideMs = 400
        LibraryUpdateScheduler.start()
        val before = System.currentTimeMillis()
        val set =
            graphql("mutation { setLibraryUpdateSettings(input: {intervalHours: 6, notify: true}) { settings { intervalHours notify } } }")
                .assertNoErrors()
                .dataPath("setLibraryUpdateSettings", "settings")
        assertEquals(mapOf("intervalHours" to 6, "notify" to true), set)
        val next = graphql("{ libraryCheck { nextScheduledAt } }").assertNoErrors().dataPath("libraryCheck", "nextScheduledAt")
        assertTrue((next as String).toLong() in before + 300..before + 2_000, "the first check one interval after turning it on")
        assertTrue(LibraryUpdateSettingsStore.current().scheduleAnchorAt >= before)

        runBlocking { withTimeout(10_000) { while (checked.get() < 2) delay(20) } }
        assertEquals("SCHEDULED", finished().path("trigger"))
        val file = LibraryUpdateSettingsStore.file().readText()
        assertTrue(file.contains("\"intervalHours\": 6") && file.contains("\"notify\": true"), file)

        graphql("mutation { setLibraryUpdateSettings(input: {intervalHours: 0}) { settings { intervalHours } } }").assertNoErrors()
        assertEquals(null, LibraryUpdateScheduler.nextAt)
        LibraryUpdateSettingsStore.reload()
        assertEquals(0, LibraryUpdateSettingsStore.current().intervalHours)
        assertTrue(LibraryUpdateSettingsStore.current().notify)
    }
}
