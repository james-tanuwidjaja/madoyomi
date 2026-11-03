package suwayomi.tachidesk.server.database.migration

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Madoyomi migration: see server/UPSTREAM.md "Database migrations" (numbering, idempotency).

import de.neonew.exposed.migrations.Migration
import madoyomi.core.database.MadoyomiMigration
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT

/**
 * Lets repos and extensions of both kinds share upstream's tables, and adds the trust table:
 * - `EXTENSIONSTORE`: `type` (MANGA/ANIME), `format` (AUTO/JSON/PROTOBUF), `last_refreshed_at`,
 *   `last_error`, `signing_key_trusted`;
 * - `EXTENSION`: `type`, `signature_hash`, `is_torrent`, `is_loaded`;
 * - new `TRUSTED_SIGNATURE` (`hash` PK, `origin` USER/STORE, `store_id` FK, `added_at`).
 *
 * Existing rows become MANGA, AUTO and untrusted. Idempotent, like every Madoyomi migration.
 */
@Suppress("ClassName", "unused")
class M0069_MadoyomiExtensionKinds :
    Migration(),
    MadoyomiMigration {
    private class ExtensionStoreTable : IntIdTable()

    private class TrustedSignatureTable(
        storeTable: ExtensionStoreTable,
    ) : IdTable<String>("trusted_signature") {
        override val id = varchar("hash", 128).entityId()
        override val primaryKey = PrimaryKey(id)
        val origin = varchar("origin", 8)
        val store = optReference("store_id", storeTable, onDelete = ReferenceOption.CASCADE).index()
        val addedAt = long("added_at").default(0)
    }

    private val columnDdl
        get() =
            listOf(
                "ALTER TABLE EXTENSIONSTORE ADD COLUMN IF NOT EXISTS TYPE VARCHAR(16) DEFAULT 'MANGA' NOT NULL",
                "ALTER TABLE EXTENSIONSTORE ADD COLUMN IF NOT EXISTS FORMAT VARCHAR(16) DEFAULT 'AUTO' NOT NULL",
                "ALTER TABLE EXTENSIONSTORE ADD COLUMN IF NOT EXISTS LAST_REFRESHED_AT BIGINT DEFAULT 0 NOT NULL",
                "ALTER TABLE EXTENSIONSTORE ADD COLUMN IF NOT EXISTS LAST_ERROR $UNLIMITED_TEXT NULL",
                "ALTER TABLE EXTENSIONSTORE ADD COLUMN IF NOT EXISTS SIGNING_KEY_TRUSTED BOOLEAN DEFAULT FALSE NOT NULL",
                "ALTER TABLE EXTENSION ADD COLUMN IF NOT EXISTS TYPE VARCHAR(16) DEFAULT 'MANGA' NOT NULL",
                "ALTER TABLE EXTENSION ADD COLUMN IF NOT EXISTS SIGNATURE_HASH VARCHAR(128) NULL",
                "ALTER TABLE EXTENSION ADD COLUMN IF NOT EXISTS IS_TORRENT BOOLEAN DEFAULT FALSE NOT NULL",
                "ALTER TABLE EXTENSION ADD COLUMN IF NOT EXISTS IS_LOADED BOOLEAN DEFAULT FALSE NOT NULL",
            )

    override fun run() {
        val transaction = TransactionManager.current()
        columnDdl.forEach { transaction.exec(it) }
        // SchemaUtils.create skips tables that already exist
        SchemaUtils.create(TrustedSignatureTable(ExtensionStoreTable()))
    }
}
