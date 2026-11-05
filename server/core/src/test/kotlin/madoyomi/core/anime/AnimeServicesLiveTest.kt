package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.os.Looper
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.extension.TestApks
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.server.LooperThread
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The anime services against the real sites (network): popular → details → episodes → videos → resolve, through
 * [AnimeBrowse], [AnimeDetails] and [AnimeVideos] with real extensions. Skipped unless `MADOYOMI_LIVE_SOURCE_TESTS=1`;
 * a site that is down fails it, one behind Cloudflare is skipped (no solver yet, Phase 2 §9). The compat harness
 * (Stage 5) does this for the whole top 20 over the sidecar.
 */
class AnimeServicesLiveTest {
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

    @TempDir
    lateinit var tmp: Path

    private val real by lazy { RealAnimeSources(tmp) }
    private val registered = mutableListOf<AnimeSource>()

    @BeforeEach
    fun onlyLive() {
        assumeTrue(System.getenv("MADOYOMI_LIVE_SOURCE_TESTS") == "1", "live source tests: set MADOYOMI_LIVE_SOURCE_TESTS=1")
        // the server starts Android's main loop in applicationSetup, which testingSetup doesn't run; extensions
        // post to it (AnimePahe's WebView interceptor does `Handler(Looper.getMainLooper())`)
        synchronized(AnimeServicesLiveTest::class.java) {
            if (Looper.getMainLooper() == null) {
                LooperThread().apply { isDaemon = true }.start()
                while (Looper.getMainLooper() == null) Thread.sleep(10)
            }
        }
    }

    @AfterEach
    fun tearDown() {
        registered.forEach {
            AnimeGetSource.unregisterSource(it.id)
            FakeAnimeSource.clearRows(it.id)
        }
        real.close()
    }

    @Test
    fun `AnimeGG from popular to a playable video`() = chain(real.testApk(TestApks.ANIMEGG).single())

    @Test
    fun `AnimePahe from popular to a playable video`() = chain(real.testApk(TestApks.ANIMEPAHE).single())

    private fun chain(source: AnimeSource) =
        runBlocking {
            AnimeGetSource.registerSource(source)
            registered += source
            FakeAnimeSource.clearRows(source.id)

            val page =
                try {
                    AnimeBrowse.fetch(source.id, AnimeBrowse.Type.POPULAR, 1)
                } catch (e: IOException) {
                    // upstream's CloudflareInterceptor without FlareSolverr; whether to add a solver is decided in Stage 5
                    assumeTrue(e.message?.contains("Cloudflare") != true, "${source.name} is behind Cloudflare: ${e.message}")
                    throw e
                }
            assertTrue(page.animeIds.isNotEmpty(), "${source.name}: popular has entries")
            val again = AnimeBrowse.fetch(source.id, AnimeBrowse.Type.POPULAR, 1)
            assertTrue(page.animeIds.intersect(again.animeIds.toSet()).isNotEmpty(), "${source.name}: ids are stable")

            val animeId = page.animeIds.first()
            val details = AnimeDetails.fetch(animeId)
            val anime = dbTransaction { AnimeTable.selectAll().where { AnimeTable.id eq animeId }.single() }
            assertTrue(details.episodeIds.isNotEmpty(), "${source.name}: ${anime[AnimeTable.title]} has episodes")

            val episodeId = details.episodeIds.first()
            val episode = dbTransaction { EpisodeTable.selectAll().where { EpisodeTable.id eq episodeId }.single() }
            val hosters = AnimeVideos.fetchEpisodeVideos(episodeId)
            val ready = hosters.filter { it.videos.isNotEmpty() }
            assertTrue(ready.isNotEmpty(), "${source.name}: ${episode[EpisodeTable.name]} has videos ($hosters)")

            val hoster = ready.first()
            val video = AnimeVideos.resolveVideo(episodeId, hoster.index, 0)
            assertTrue(video.videoUrl.startsWith("http"), "${source.name}: a playable URL (${video.videoUrl})")
            println(
                "${source.name}: ${page.animeIds.size} popular, \"${anime[AnimeTable.title]}\" ${details.episodeIds.size} episodes, " +
                    "${hosters.size} hosters (${hosters.joinToString { "${it.name}:${it.state}:${it.videos.size}" }}), " +
                    "video \"${video.videoTitle}\" ${video.videoUrl.take(60)}… headers ${video.headers.map { it.name }}, " +
                    "${video.subtitleTracks.size} subtitles, ${video.audioTracks.size} audio",
            )
        }
}
