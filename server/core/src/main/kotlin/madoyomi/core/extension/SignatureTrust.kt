package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.repo.normalizeSigningKey
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * The trusted signing-certificate hashes (`TRUSTED_SIGNATURE`, Mihon/Aniyomi model): an extension is trusted when
 * its `signature_hash` is in the table. Rows come from added repos (origin STORE, see `RepoService`) or from the user
 * (origin USER). `hash` is the primary key, so there is one row per hash, whatever vouches for it.
 */
object SignatureTrust {
    /** Whether [hash] (lowercase hex SHA-256) is trusted. A null hash (an unverified install) never is. */
    fun isTrusted(hash: String?): Boolean =
        hash != null &&
            dbTransaction { !TrustedSignatureTable.selectAll().where { TrustedSignatureTable.id eq hash }.empty() }

    fun trustedHashes(): Set<String> =
        dbTransaction { TrustedSignatureTable.selectAll().map { it[TrustedSignatureTable.id].value }.toSet() }

    /**
     * Trusts [hash] as the user's choice: a new USER row, or an existing STORE row turned into USER (detached from its
     * repo, so removing that repo no longer revokes it).
     */
    fun trust(
        hash: String,
        now: Long,
    ) {
        val normalized = requireHash(hash)
        dbTransaction {
            val existing = TrustedSignatureTable.selectAll().where { TrustedSignatureTable.id eq normalized }.firstOrNull()
            if (existing == null) {
                TrustedSignatureTable.insert {
                    it[id] = normalized
                    it[origin] = SignatureTrustOrigin.USER
                    it[store] = null
                    it[addedAt] = now
                }
            } else if (existing[TrustedSignatureTable.origin] != SignatureTrustOrigin.USER) {
                TrustedSignatureTable.update({ TrustedSignatureTable.id eq normalized }) {
                    it[origin] = SignatureTrustOrigin.USER
                    it[store] = null
                }
            }
        }
    }

    /**
     * Withdraws the user's trust in [hash]. A key that an added repo vouches for stays trusted (as that repo's STORE
     * row): to revoke it, remove the repo. Returns whether [hash] is still trusted.
     */
    fun untrust(
        hash: String,
        now: Long,
    ): Boolean {
        val normalized = requireHash(hash)
        return dbTransaction {
            TrustedSignatureTable.deleteWhere {
                (TrustedSignatureTable.id eq normalized) and (TrustedSignatureTable.origin eq SignatureTrustOrigin.USER)
            }
            rehomeStoreTrust(normalized, now)
            isTrusted(normalized)
        }
    }

    /**
     * When [hash] has no row (its owner repo was removed or re-keyed, or the user's row was withdrawn), gives it to
     * another repo that still trusts the key, so the repos sharing a key keep their trust.
     */
    fun rehomeStoreTrust(
        hash: String,
        now: Long,
    ) = dbTransaction {
        if (!TrustedSignatureTable.selectAll().where { TrustedSignatureTable.id eq hash }.empty()) return@dbTransaction
        val heir =
            ExtensionStoreTable
                .selectAll()
                .where { ExtensionStoreTable.signingKeyTrusted eq true }
                .orderBy(ExtensionStoreTable.id)
                .firstOrNull { normalizeSigningKey(it[ExtensionStoreTable.signingKey]) == hash }
                ?: return@dbTransaction
        TrustedSignatureTable.insert {
            it[id] = hash
            it[origin] = SignatureTrustOrigin.STORE
            it[store] = heir[ExtensionStoreTable.id]
            it[addedAt] = now
        }
    }

    private fun requireHash(hash: String): String =
        requireNotNull(normalizeSigningKey(hash)?.takeIf { HASH.matches(it) }) { "Not a SHA-256 hash: $hash" }

    private val HASH = Regex("[0-9a-f]{64}")
}
