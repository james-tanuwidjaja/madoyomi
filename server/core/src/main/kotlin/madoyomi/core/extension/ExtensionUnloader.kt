package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.NetworkHelper
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.impl.Search
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.io.path.absolutePathString

/** Drops the in-memory instances of an installed extension (an `EXTENSION` row), leaving its files and rows. */
fun interface ExtensionUnloader {
    fun unload(extension: ResultRow)

    companion object {
        /**
         * Unregisters the extension's sources from their cache (manga: upstream's `GetSource`; anime: [AnimeGetSource])
         * and closes its jar's class loader, the same way upstream's uninstall does, after stopping the threads the
         * extension left running ([ExtensionThreads]). The jar's load permit
         * ([ExtensionLoadGate]) goes too, so nothing can reload it without a new check.
         */
        val default =
            ExtensionUnloader { extension ->
                val jarPath = ExtensionFiles.installedJar(extension)
                jarPath?.let(ExtensionLoadGate::revoke)
                val extensionId = extension[ExtensionTable.id].value
                when (extension[ExtensionTable.type]) {
                    ExtensionKind.MANGA -> {
                        val sourceIds =
                            dbTransaction {
                                SourceTable.selectAll().where { SourceTable.extension eq extensionId }.map { it[SourceTable.id].value }
                            }
                        sourceIds.forEach(GetSource::unregisterSource)
                        // upstream keeps a source's preference screen and filter list (the extension's objects, and so
                        // its class loader) for good: GraphQL `SourceType.preferences` and the REST filters fill them
                        sourceIds.forEach { id ->
                            Source.preferenceScreenMap.remove(id)
                            Search.filterListCache.remove(id)
                        }
                    }

                    ExtensionKind.ANIME -> {
                        AnimeGetSource.unregisterExtension(extensionId)
                        val sourceIds =
                            dbTransaction {
                                AnimeSourceTable
                                    .selectAll()
                                    .where { AnimeSourceTable.extension eq extensionId }
                                    .map { it[AnimeSourceTable.id].value }
                            }
                        sourceIds.forEach(AnimeGetSource::unregisterSource)
                    }
                }

                // the jar path the source cache loaded it from (Madoyomi or upstream layout). The threads the extension
                // left running (a local video proxy, say) would keep its class loader alive: they are stopped first
                jarPath?.let { path ->
                    PackageTools.jarLoaderMap.remove(path.absolutePathString())?.let { loader ->
                        ExtensionThreads.stop(loader, extension[ExtensionTable.pkgName])
                        loader.close()
                        // an idle pooled connection opened during the extension's calls keeps it too: its TLS context
                        // captured the access-control context of the handshake (the extension's frames on the stack),
                        // for up to OkHttp's 5-minute keep-alive. Only idle ones go; the others reconnect on next use
                        runCatching {
                            Injekt
                                .get<NetworkHelper>()
                                .client.connectionPool
                                .evictAll()
                        }
                    }
                }
            }
    }
}
