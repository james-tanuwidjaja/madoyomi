package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [AnimeExtensionLoader.instantiate]: what an extension's main class may be, and how its failures are reported. */
class AnimeExtensionLoaderTest {
    private class Fake(
        override val id: Long,
        override val name: String = "Fake $id",
        override val lang: String = "en",
    ) : AnimeSource {
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

        override suspend fun getRelatedAnimeList(anime: SAnime) = emptyList<eu.kanade.tachiyomi.animesource.model.AnimeRelation>()
    }

    private fun failure(load: (String) -> Any): MadoyomiException =
        assertFailsWith<MadoyomiException> { AnimeExtensionLoader.instantiate("a.pkg", "a.pkg.Main", load) }

    @Test
    fun `a source is one source and a factory gives all of its sources`() {
        val single = Fake(1)
        assertEquals(listOf(single), AnimeExtensionLoader.instantiate("a.pkg", "a.pkg.Main") { single })

        val factory =
            object : AnimeSourceFactory {
                override fun createSources() = listOf(Fake(2), Fake(3, lang = "fr"))
            }
        assertEquals(listOf(2L, 3L), AnimeExtensionLoader.instantiate("a.pkg", "a.pkg.Main") { factory }.map { it.id })
    }

    @Test
    fun `a class that is not an anime source is LOAD_FAILED`() {
        val e = failure { "a string" }
        assertEquals(ErrorCode.LOAD_FAILED, e.code)
        assertEquals("a.pkg.Main", e.details["className"])
    }

    @Test
    fun `a missing host class or member is LOAD_FAILED with what is missing`() {
        val noClass = failure { throw NoClassDefFoundError("android/widget/Button") }
        assertEquals(ErrorCode.LOAD_FAILED, noClass.code)
        assertEquals(NoClassDefFoundError::class.java.name, noClass.details["cause"])
        assertEquals("android.widget.Button", noClass.details["missing"])
        assertEquals("a.pkg", noClass.details["pkgName"])

        val noMethod = failure { throw NoSuchMethodError("'void android.widget.Toast.setDuration(int)'") }
        assertEquals("'void android.widget.Toast.setDuration(int)'", noMethod.details["missing"])
    }

    @Test
    fun `a constructor that throws is LOAD_FAILED with the extension's own exception`() {
        val e = failure { throw InvocationTargetException(IllegalStateException("boom")) }
        assertEquals(ErrorCode.LOAD_FAILED, e.code)
        assertEquals(IllegalStateException::class.java.name, e.details["cause"])
        assertEquals("a.pkg could not be loaded: IllegalStateException: boom", e.message)
    }

    @Test
    fun `a source whose id can't be computed is LOAD_FAILED`() {
        val broken =
            object : AnimeSource by Fake(1) {
                override val id: Long get() = throw ExceptionInInitializerError(RuntimeException("static init"))
            }
        val e = failure { broken }
        assertEquals(RuntimeException::class.java.name, e.details["cause"])
    }

    @Test
    fun `the trust gate's refusal passes through unchanged`() {
        val untrusted = MadoyomiException(ErrorCode.UNTRUSTED, "no")
        assertSame(untrusted, failure { throw untrusted })
    }

    @Test
    fun `a lazy load that an unload overtook registers nothing`() {
        val extensionId = 990_001
        val source = Fake(990_001_001L)
        try {
            // what a lazy load (AnimeGetSource.getSource -> loadInstalled) reads before it instantiates the code
            val before = AnimeGetSource.unloadCount(extensionId)
            // an uninstall or update unloads the extension meanwhile
            AnimeGetSource.unregisterExtension(extensionId)
            assertFalse(AnimeGetSource.register(extensionId, listOf(source), ifUnloadCount = before))
            assertNull(AnimeGetSource.cached(source.id), "the unloaded code doesn't come back")

            // a load that no unload overtook registers, as do the eager ones (install, trust), which pass no count
            assertTrue(AnimeGetSource.register(extensionId, listOf(source), ifUnloadCount = AnimeGetSource.unloadCount(extensionId)))
            assertSame(source, AnimeGetSource.cached(source.id))
            assertTrue(AnimeGetSource.register(extensionId, listOf(source)))
        } finally {
            AnimeGetSource.unregisterExtension(extensionId)
        }
    }
}
