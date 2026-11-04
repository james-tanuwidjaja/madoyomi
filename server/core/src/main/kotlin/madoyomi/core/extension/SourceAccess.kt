package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.Source
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * Says *why* a manga source can't be used. Upstream's `GetSource.getSourceOrNull` swallows the reason (it logs it and
 * returns null, and `getSourceOrStub` turns that into a `StubSource` that throws "Source not installed"), so the API
 * would report a missing or untrusted source as a null pointer or an internal error.
 */
object SourceAccess {
    /** The loaded source [sourceId], or the [diagnose]d reason it isn't available. */
    suspend fun require(sourceId: Long): Source = GetSource.getSourceOrNull(sourceId) ?: throw diagnose(sourceId)

    /** Why the manga source [sourceId] could not be loaded, as a [MadoyomiException]. */
    fun diagnose(sourceId: Long): MadoyomiException =
        dbTransaction {
            val details = mutableMapOf<String, Any?>("sourceId" to sourceId.toString())
            val source = SourceTable.selectAll().where { SourceTable.id eq sourceId }.firstOrNull()
            if (source == null) {
                val isAnime = !AnimeSourceTable.selectAll().where { AnimeSourceTable.id eq sourceId }.empty()
                val hint = if (isAnime) " ($sourceId is an anime source; use the anime operations)" else ""
                return@dbTransaction MadoyomiException(ErrorCode.NOT_FOUND, "No manga source $sourceId$hint", details)
            }
            val extension =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.id eq source[SourceTable.extension] }
                    .firstOrNull()
            val pkg = extension?.get(ExtensionTable.pkgName)
            details["pkgName"] = pkg
            when {
                extension == null || !extension[ExtensionTable.isInstalled] -> {
                    MadoyomiException(ErrorCode.NOT_FOUND, "The extension of source $sourceId is not installed", details)
                }

                extension[ExtensionTable.signatureHash] == null -> {
                    MadoyomiException(
                        ErrorCode.UNTRUSTED,
                        "$pkg has no verified signature (it was installed before Madoyomi checked signatures); reinstall it",
                        details,
                    )
                }

                !SignatureTrust.isTrusted(extension[ExtensionTable.signatureHash]) -> {
                    val hash = extension[ExtensionTable.signatureHash]
                    details["signatureHash"] = hash
                    MadoyomiException(ErrorCode.UNTRUSTED, "$pkg is signed by an untrusted certificate ($hash); trust it first", details)
                }

                else -> {
                    MadoyomiException(ErrorCode.INTERNAL, "Source $sourceId ($pkg) failed to load; see the server log", details)
                }
            }
        }
}
