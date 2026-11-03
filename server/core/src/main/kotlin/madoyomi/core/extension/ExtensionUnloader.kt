package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

/** Drops the in-memory instances of an installed extension (an `EXTENSION` row), leaving its files and rows. */
fun interface ExtensionUnloader {
    fun unload(extension: ResultRow)

    companion object {
        /**
         * Manga: unregisters its sources from upstream's `GetSource` cache and closes its jar's class loader, the same
         * way upstream's uninstall does. Anime extensions are never loaded in Phase 1, so there is nothing to drop.
         */
        val default =
            object : ExtensionUnloader {
                private val applicationDirs: ApplicationDirs by injectLazy()

                override fun unload(extension: ResultRow) {
                    if (extension[ExtensionTable.type] != ExtensionKind.MANGA) return
                    val extensionId = extension[ExtensionTable.id].value
                    val sourceIds =
                        transaction {
                            SourceTable.selectAll().where { SourceTable.extension eq extensionId }.map { it[SourceTable.id].value }
                        }
                    sourceIds.forEach(GetSource::unregisterSource)

                    // same jar path as GetSource/Extension use
                    val apkName = extension[ExtensionTable.apkName] ?: return
                    val jarPath = Path(applicationDirs.extensionsRoot) / (apkName.substringBefore(".apk") + ".jar")
                    PackageTools.jarLoaderMap.remove(jarPath.absolutePathString())?.close()
                }
            }
    }
}
