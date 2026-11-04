package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.SignatureTrust
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * Whether an anime source may be loaded, and why not: the anime counterpart of `SourceAccess`, with the same codes
 * (`NOT_FOUND`, `UNTRUSTED` with `details.signatureHash`) plus `LOAD_FAILED` from the loader.
 */
object AnimeSourceAccess {
    /** The loaded source [sourceId], or the reason it isn't available as a [MadoyomiException]. */
    suspend fun require(sourceId: Long): AnimeSource = AnimeGetSource.getSource(sourceId)

    /** The `EXTENSION` row of the anime source [sourceId] if it is installed, trusted and of the anime kind. */
    fun loadableExtension(sourceId: Long): ResultRow =
        dbTransaction {
            val details = mutableMapOf<String, Any?>("sourceId" to sourceId.toString())
            val source =
                AnimeSourceTable.selectAll().where { AnimeSourceTable.id eq sourceId }.firstOrNull()
                    ?: run {
                        val isManga = !SourceTable.selectAll().where { SourceTable.id eq sourceId }.empty()
                        val hint = if (isManga) " ($sourceId is a manga source)" else ""
                        throw MadoyomiException(ErrorCode.NOT_FOUND, "No anime source $sourceId$hint", details)
                    }
            val extension =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.id eq source[AnimeSourceTable.extension] }
                    .firstOrNull()
            val pkg = extension?.get(ExtensionTable.pkgName)
            details["pkgName"] = pkg
            when {
                extension == null || !extension[ExtensionTable.isInstalled] || extension[ExtensionTable.type] != ExtensionKind.ANIME -> {
                    throw MadoyomiException(ErrorCode.NOT_FOUND, "The extension of anime source $sourceId is not installed", details)
                }

                extension[ExtensionTable.signatureHash] == null -> {
                    throw MadoyomiException(
                        ErrorCode.UNTRUSTED,
                        "$pkg has no verified signature (it was installed before Madoyomi checked signatures); reinstall it",
                        details,
                    )
                }

                !SignatureTrust.isTrusted(extension[ExtensionTable.signatureHash]) -> {
                    val hash = extension[ExtensionTable.signatureHash]
                    details["signatureHash"] = hash
                    throw MadoyomiException(
                        ErrorCode.UNTRUSTED,
                        "$pkg is signed by an untrusted certificate ($hash); trust it first",
                        details,
                    )
                }

                else -> {
                    extension
                }
            }
        }

    /** The extension loaded, but none of its sources has the id [sourceId] (its `ANIME_SOURCE` row is gone now). */
    fun notProvided(
        sourceId: Long,
        extension: ResultRow,
    ) = MadoyomiException(
        ErrorCode.NOT_FOUND,
        "${extension[ExtensionTable.pkgName]} no longer provides anime source $sourceId",
        mapOf("sourceId" to sourceId.toString(), "pkgName" to extension[ExtensionTable.pkgName]),
    )
}
