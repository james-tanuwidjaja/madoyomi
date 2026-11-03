package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.repo.RepoIndexFormat
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.columns.unlimitedVarchar

object ExtensionStoreTable : IntIdTable() {
    val indexUrl = varchar("index_url", 2048).uniqueIndex()
    val name = varchar("name", 256)
    val badgeLabel = varchar("badge_label", 32)
    val signingKey = varchar("signing_key", 512)
    val contactWebsite = varchar("contact_website", 2048)
    val contactDiscord = varchar("contact_discord", 2048).nullable()
    val isLegacy = bool("is_legacy").default(false)
    val extensionListUrl = varchar("extension_list_url", 2048).nullable()

    // Madoyomi: repo kind, index format override, refresh state and signing-key trust (migration M0069)
    val type = enumerationByName("type", 16, ExtensionKind::class).default(ExtensionKind.MANGA)
    val format = enumerationByName("format", 16, RepoIndexFormat::class).default(RepoIndexFormat.AUTO)
    val lastRefreshedAt = long("last_refreshed_at").default(0)
    val lastError = unlimitedVarchar("last_error").nullable()
    val signingKeyTrusted = bool("signing_key_trusted").default(false)
}
