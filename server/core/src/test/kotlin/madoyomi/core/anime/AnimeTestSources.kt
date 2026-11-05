package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.extension.TestApks
import madoyomi.exthost.Dex2jarRepair
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import suwayomi.tachidesk.manga.impl.util.ChildFirstURLClassLoader
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.database.dbTransaction
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** A scripted anime source for the service tests: every answer is a field the test sets. */
open class FakeAnimeSource(
    override val id: Long,
    override val supportsLatest: Boolean = true,
) : AnimeSource {
    override val name = "Fake $id"
    override val lang = "en"

    var popular: (Int) -> AnimesPage = { AnimesPage(emptyList(), false) }
    var latest: (Int) -> AnimesPage = { AnimesPage(emptyList(), false) }
    var search: (Int, String, AnimeFilterList) -> AnimesPage = { _, _, _ -> AnimesPage(emptyList(), false) }
    var filters: () -> AnimeFilterList = { AnimeFilterList() }
    var details: (SAnime) -> SAnime = { it }
    var episodes: (SAnime) -> List<SEpisode> = { emptyList() }
    var seasons: (SAnime) -> List<SAnime> = { emptyList() }

    /** What the last episode/season update was handed (the stored list). */
    var lastStoredEpisodes: List<SEpisode> = emptyList()

    override fun getFilterList() = filters()

    override suspend fun getPopularAnime(page: Int) = popular(page)

    override suspend fun getLatestUpdates(page: Int) = latest(page)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = search(page, query, filters)

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        lastStoredEpisodes = episodes
        return SAnimeEpisodeUpdate(
            if (fetchDetails) details(anime) else anime,
            if (fetchEpisodes) this.episodes(anime) else episodes,
        )
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ) = SAnimeSeasonUpdate(if (fetchDetails) details(anime) else anime, if (fetchSeasons) this.seasons(anime) else seasons)

    override suspend fun getRelatedAnimeList(anime: SAnime) = emptyList<AnimeRelation>()

    companion object {
        fun anime(
            url: String,
            title: String = "Title $url",
            init: SAnime.() -> Unit = {},
        ): SAnime =
            SAnime.create().apply {
                this.url = url
                this.title = title
                init()
            }

        fun episode(
            url: String,
            number: Float,
            init: SEpisode.() -> Unit = {},
        ): SEpisode =
            SEpisode.create().apply {
                this.url = url
                name = "Episode $number"
                episode_number = number
                init()
            }

        /** Deletes the rows of [sourceId] (episodes cascade). */
        fun clearRows(sourceId: Long) {
            dbTransaction { AnimeTable.deleteWhere { AnimeTable.sourceReference eq sourceId } }
        }
    }
}

/**
 * Real extensions converted as an install does (dex2jar + `Dex2jarRepair`) and instantiated without the trust gate
 * (nothing is installed). [close] closes the class loaders, which on Windows hold the jars open.
 */
class RealAnimeSources(
    private val tmp: Path,
) : AutoCloseable {
    private val loaders = mutableListOf<URLClassLoader>()

    /** A test APK of `tools/apks` ([TestApks]). */
    fun testApk(glob: String): List<AnimeSource> = load(TestApks.find(glob))

    /** An APK of the compat lists (`tools/compat-test/apks`, fetched by `tools/compat-test/scan.ps1`). */
    fun compatApk(glob: String): List<AnimeSource> {
        val found = if (compatApks.isDirectory()) compatApks.listDirectoryEntries(glob).maxOrNull() else null
        assumeTrue(found != null, "No $glob in ${compatApks.toAbsolutePath().normalize()}: run tools/compat-test/scan.ps1")
        return load(found!!)
    }

    private fun load(path: Path): List<AnimeSource> {
        val apk = TestApks.Apk(path)
        val jar = (tmp / apk.pkg).createDirectories() / "${apk.pkg}-v${apk.inspection.versionName}.jar"
        PackageTools.dex2jar(apk.path, jar)
        Dex2jarRepair.repair(apk.path, jar)
        val className =
            apk.inspection.metaData
                .getValue(ExtensionKind.ANIME.classKey)
                .let { if (it.startsWith(".")) apk.pkg + it else it }
        val loader = ChildFirstURLClassLoader(arrayOf(jar.toUri().toURL())).also { loaders += it }
        return AnimeExtensionLoader.instantiate(apk.pkg, className) {
            Class.forName(it, false, loader).getDeclaredConstructor().newInstance()
        }
    }

    override fun close() {
        loaders.forEach { it.close() }
        loaders.clear()
    }

    companion object {
        val compatApks: Path = Path(System.getProperty("madoyomi.compatApks") ?: "../../tools/compat-test/apks")
    }
}

/**
 * A scratch in-memory H2 with the whole migration chain, the default database while it is open. The madoyomi tests
 * use their own databases (see `ExtensionLifecycleTest`) instead of `ApplicationTest`'s shared one: once
 * `ApplicationTest.databaseSetup()` has run, its database stays the explicit default, and later tests that rely on
 * `Database.connect` making their scratch database the primary one would write into the shared one instead.
 * [close] restores the previous explicit default (or none).
 */
class ScratchDatabase : AutoCloseable {
    // the explicit default only (`primaryDatabase` falls back to the newest connection): restoring that would make an
    // implicit primary explicit, and redirect every later test that relies on `Database.connect`
    private val previousDefault: Database? = TransactionManager.defaultDatabase
    val database: Database =
        Database.connect(
            "jdbc:h2:mem:madoyomi-anime-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            "org.h2.Driver",
            databaseConfig =
                DatabaseConfig {
                    useNestedTransactions = true
                    @OptIn(ExperimentalKeywordApi::class)
                    preserveKeywordCasing = false
                },
        )

    init {
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        TransactionManager.defaultDatabase = database
    }

    override fun close() {
        TransactionManager.closeAndUnregister(database)
        TransactionManager.defaultDatabase = previousDefault
    }
}
