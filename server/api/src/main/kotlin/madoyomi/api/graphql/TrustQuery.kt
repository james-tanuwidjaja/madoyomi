package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.extension.TrustedSignatureTable
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.database.dbTransaction

class TrustQuery {
    /** Every trusted signing-certificate hash, from added repos (STORE) and from the user (USER). */
    @RequireAuth
    fun trustedSignatures(): List<TrustedSignatureType> =
        dbTransaction {
            TrustedSignatureTable.selectAll().orderBy(TrustedSignatureTable.id).map(::TrustedSignatureType)
        }
}
