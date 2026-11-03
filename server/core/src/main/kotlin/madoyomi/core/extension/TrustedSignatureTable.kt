package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable

/** Why a signing-certificate hash is trusted. */
enum class SignatureTrustOrigin {
    /** The user trusted it explicitly. */
    USER,

    /** It is the signing key of an added repo ([TrustedSignatureTable.store]). */
    STORE,
}

/**
 * Trusted extension signing-certificate hashes (Mihon/Aniyomi trust model). An installed extension is
 * only loaded when its `EXTENSION.signature_hash` is in this table.
 */
object TrustedSignatureTable : IdTable<String>("trusted_signature") {
    /** Lowercase hex SHA-256 of the signing certificate. */
    override val id = varchar("hash", 128).entityId()
    override val primaryKey = PrimaryKey(id)

    val origin = enumerationByName("origin", 8, SignatureTrustOrigin::class)

    /** The repo whose signing key this is, for [SignatureTrustOrigin.STORE]. Removing the repo revokes the key. */
    val store = optReference("store_id", ExtensionStoreTable, onDelete = ReferenceOption.CASCADE).index()

    /** Epoch seconds. */
    val addedAt = long("added_at").default(0)
}
