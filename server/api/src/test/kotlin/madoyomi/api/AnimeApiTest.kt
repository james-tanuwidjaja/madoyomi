package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.extension.ExtensionCompatLog
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * The anime operations over GraphQL ([ApiGraphQLTest]: the production schema and `GraphQL` object, a scratch H2 per
 * test). Sources are scripted fakes (`FakeAnimeSource` and HTTP fakes) or real APKs, cached with
 * `AnimeGetSource.registerSource` and given installed `EXTENSION`/`ANIME_SOURCE` rows, so `animeSources` lists them.
 */
abstract class AnimeApiTest : ApiGraphQLTest() {
    @BeforeEach
    fun clearCompatLog() = ExtensionCompatLog.clearAll()

    @AfterEach
    fun clearCompatLogAfter() = ExtensionCompatLog.clearAll()

    /** Caches [source] (no trust gate) and writes an installed, loaded `EXTENSION` row and its `ANIME_SOURCE` row. */
    protected fun register(
        source: AnimeSource,
        pkg: String = "test.animeextension.s${source.id}",
    ) {
        dbTransaction {
            val extension =
                ExtensionTable.insertAndGetId {
                    it[name] = source.name
                    it[pkgName] = pkg
                    it[versionName] = "1.0"
                    it[versionCode] = 1
                    it[extensionLib] = "14"
                    it[apkName] = "$pkg.apk"
                    it[lang] = source.lang
                    it[contentWarning] = 0
                    it[isInstalled] = true
                    it[type] = ExtensionKind.ANIME
                    it[isLoaded] = true
                }
            AnimeSourceTable.insert {
                it[id] = source.id
                it[name] = source.name
                it[lang] = source.lang
                it[AnimeSourceTable.extension] = extension
                it[baseUrl] = (source as? AnimeHttpSource)?.baseUrl
            }
        }
        AnimeGetSource.registerSource(source)
    }

    /** An `ANIME` row of [sourceId]. */
    protected fun animeRow(
        sourceId: Long,
        url: String,
        title: String = "Title $url",
        thumbnailUrl: String? = null,
        initialized: Boolean = false,
    ): Int =
        dbTransaction {
            AnimeTable
                .insertAndGetId {
                    it[AnimeTable.url] = url
                    it[AnimeTable.title] = title
                    it[sourceReference] = sourceId
                    it[AnimeTable.thumbnailUrl] = thumbnailUrl
                    it[AnimeTable.initialized] = initialized
                }.value
        }

    /** An `EPISODE` row of [animeId]. */
    protected fun episodeRow(
        animeId: Int,
        url: String,
        order: Int = 1,
    ): Int =
        dbTransaction {
            EpisodeTable
                .insertAndGetId {
                    it[EpisodeTable.url] = url
                    it[name] = "Episode $order"
                    it[episodeNumber] = order.toFloat()
                    it[sourceOrder] = order
                    it[anime] = animeId
                }.value
        }

    protected val Any?.asList: List<*> get() = this as List<*>
}
