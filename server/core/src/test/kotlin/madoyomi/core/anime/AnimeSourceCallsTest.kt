package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.runBlocking
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionCompatLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** [AnimeSourceCalls]: API gaps become EXTENSION_INCOMPATIBLE and land in the compat log; nothing else changes. */
class AnimeSourceCallsTest {
    private object Source : AnimeSource {
        override val id = 4242L
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularAnime(page: Int) = AnimesPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int) = AnimesPage(emptyList(), false)

        override suspend fun getSearchAnime(
            page: Int,
            query: String,
            filters: AnimeFilterList,
        ) = AnimesPage(emptyList(), false)

        override suspend fun getAnimeEpisodeUpdate(
            anime: SAnime,
            episodes: List<SEpisode>,
            fetchDetails: Boolean,
            fetchEpisodes: Boolean,
        ): SAnimeEpisodeUpdate = throw UnsupportedOperationException()

        override suspend fun getAnimeSeasonUpdate(
            anime: SAnime,
            seasons: List<SAnime>,
            fetchDetails: Boolean,
            fetchSeasons: Boolean,
        ): SAnimeSeasonUpdate = throw UnsupportedOperationException()

        override suspend fun getRelatedAnimeList(anime: SAnime) = emptyList<AnimeRelation>()
    }

    // a host class (not from an extension jar): the log falls back to the class name
    private val pkg = Source.javaClass.name

    // the log is global: other test classes in this JVM (e.g. AnimeExtensionLoaderTest) record into it too
    @BeforeEach
    @AfterEach
    fun reset() {
        ExtensionCompatLog.clearAll()
    }

    private fun incompatible(error: Throwable): MadoyomiException =
        assertFailsWith<MadoyomiException> { runBlocking { AnimeSourceCalls.call(Source, "popular") { throw error } } }

    @Test
    fun `each kind of API gap is EXTENSION_INCOMPATIBLE with what is missing, and is recorded`() {
        val cases =
            listOf(
                NoClassDefFoundError("android/widget/Button") to ("MISSING_CLASS" to "android.widget.Button"),
                NoSuchMethodError("'void a.B.c()'") to ("MISSING_MEMBER" to "'void a.B.c()'"),
                NoSuchFieldError("x") to ("MISSING_MEMBER" to "x"),
                AbstractMethodError("a.B.m()") to ("ABSTRACT_METHOD" to "a.B.m()"),
                InvocationTargetException(NoSuchMethodError("wrapped")) to ("MISSING_MEMBER" to "wrapped"),
            )
        cases.forEach { (error, expected) ->
            val e = incompatible(error)
            assertEquals(ErrorCode.EXTENSION_INCOMPATIBLE, e.code, "$error")
            assertEquals(expected.first, e.details["kind"])
            assertEquals(expected.second, e.details["missing"])
            assertEquals(pkg, e.details["pkgName"])
            assertEquals("4242", e.details["sourceId"])
            assertEquals("popular", e.details["operation"])
            assertSame(error, e.cause)
        }
        assertEquals(cases.size, ExtensionCompatLog.issues(pkg).size)
        assertEquals("popular", ExtensionCompatLog.issues(pkg).first().context)
    }

    @Test
    fun `an android-jar stub is EXTENSION_INCOMPATIBLE naming the stubbed method`() {
        val e =
            assertFailsWith<MadoyomiException> {
                AnimeSourceCalls.callBlocking(Source, "preferences") { madoyomi.core.extension.androidStub() }
            }
        assertEquals(ErrorCode.EXTENSION_INCOMPATIBLE, e.code)
        assertEquals("STUB", e.details["kind"])
        assertEquals("android.widget.TextView.<init>", e.details["missing"])
        assertEquals(501, e.code.httpStatus)
        assertEquals(listOf("android.widget.TextView.<init>"), ExtensionCompatLog.issues(pkg).map { it.target })
    }

    @Test
    fun `other failures pass through unchanged and are not recorded`() {
        listOf(
            HttpException(503),
            IOException("timeout"),
            IllegalStateException("parse"),
            MadoyomiException(ErrorCode.UNTRUSTED, "no"),
            CancellationException("cancelled"),
        ).forEach { error ->
            val thrown = assertFailsWith<Throwable> { runBlocking { AnimeSourceCalls.call(Source, "videos") { throw error } } }
            assertSame(error, thrown)
        }
        assertEquals(emptyList(), ExtensionCompatLog.issues(pkg))
        assertEquals(7, runBlocking { AnimeSourceCalls.call(Source, "popular") { 7 } })
    }

    @Test
    fun `an API gap that reaches the error model another way is EXTENSION_INCOMPATIBLE too`() {
        val classified = ErrorClassifier.classify(InvocationTargetException(NoSuchMethodError("'void a.B.c()'")))
        assertEquals(ErrorCode.EXTENSION_INCOMPATIBLE, classified.code)
        assertEquals("'void a.B.c()'", classified.details["missing"])
        assertEquals(ErrorCode.INTERNAL, ErrorClassifier.classify(IllegalStateException("x")).code)
    }
}
