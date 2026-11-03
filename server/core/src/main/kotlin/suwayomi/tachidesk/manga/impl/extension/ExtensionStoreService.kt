package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import madoyomi.core.repo.RepoIndexFetcher
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.core.repo.RepoService
import madoyomi.core.repo.toExtensionInfo
import madoyomi.core.repo.toExtensionStore
import madoyomi.exthost.ExtensionKind
import okio.BufferedSource
import okio.buffer
import okio.gzip
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.cancellation.CancellationException

object ExtensionStoreService {
    private val logger = KotlinLogging.logger {}

    val network: NetworkHelper by injectLazy()
    val protoBuf: ProtoBuf by injectLazy()
    val json: Json by injectLazy()

    suspend fun fetch(indexUrl: String): ExtensionStore =
        try {
            // Madoyomi: delegates to madoyomi.core.repo.RepoIndexFetcher, which keeps upstream's sniffing and
            // repo.json/index_v2 redirects (format AUTO) and parses with JsonIndexParser/ProtobufIndexParser
            RepoIndexFetcher.network.fetch(indexUrl, RepoIndexFormat.AUTO, ExtensionKind.MANGA).toExtensionStore()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error(e) { "Failed to fetch extension store '$indexUrl'" }
            throw e
        }

    fun upsert(store: ExtensionStore) {
        transaction {
            val existing =
                ExtensionStoreTable
                    .selectAll()
                    .where { ExtensionStoreTable.indexUrl eq store.indexUrl }
                    .firstOrNull()

            if (existing == null) {
                ExtensionStoreTable.insert {
                    it[name] = store.name
                    it[badgeLabel] = store.badgeLabel
                    it[signingKey] = store.signingKey
                    it[contactWebsite] = store.contact.website
                    it[contactDiscord] = store.contact.discord
                    it[indexUrl] = store.indexUrl
                    it[isLegacy] = store.isLegacy
                    it[extensionListUrl] = store.extensionListUrl
                }
            } else {
                ExtensionStoreTable.update({ ExtensionStoreTable.indexUrl eq store.indexUrl }) {
                    it[name] = store.name
                    it[badgeLabel] = store.badgeLabel
                    it[signingKey] = store.signingKey
                    it[contactWebsite] = store.contact.website
                    it[contactDiscord] = store.contact.discord
                    it[isLegacy] = store.isLegacy
                    it[extensionListUrl] = store.extensionListUrl
                }
            }
        }
    }

    suspend fun getAndRefresh(): List<ExtensionStore> {
        val stores =
            transaction {
                // Madoyomi: manga stores only (upstream: ExtensionStoreTable.selectAll().toList())
                ExtensionStoreTable.selectAll().where { ExtensionStoreTable.type eq ExtensionKind.MANGA }.toList()
            }
        var needsPrefUpdate = false
        val updateStores =
            stores.mapNotNull { storeRow ->
                val oldIndexUrl = storeRow[ExtensionStoreTable.indexUrl]
                val oldName = storeRow[ExtensionStoreTable.name]
                try {
                    val store = fetch(oldIndexUrl)
                    if (store.indexUrl != oldIndexUrl) {
                        transaction {
                            ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl eq oldIndexUrl }
                        }
                        needsPrefUpdate = true
                    }
                    upsert(store)
                    store
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to fetch extension store '$oldName ($oldIndexUrl)'" }
                    null
                }
            }
        if (needsPrefUpdate) syncDbToPrefs()
        return updateStores
    }

    fun syncDbToPrefs() {
        val dbStores =
            transaction {
                ExtensionStoreTable
                    .selectAll()
                    // Madoyomi: the extensionStores setting lists manga stores only
                    .where { ExtensionStoreTable.type eq ExtensionKind.MANGA }
                    .map { it[ExtensionStoreTable.indexUrl] }
                    .toSet()
            }

        val currentPrefs = serverConfig.extensionStores.value.toSet()
        val toAdd = dbStores - currentPrefs
        val toRemove = currentPrefs - dbStores

        if (toAdd.isNotEmpty()) {
            serverConfig.extensionStores.value = (serverConfig.extensionStores.value + toAdd).distinct()
        }

        if (toRemove.isNotEmpty()) {
            serverConfig.extensionStores.value = serverConfig.extensionStores.value.filterNot { it in toRemove }
        }
    }

    suspend fun syncPrefsToDb() {
        val prefUrls = serverConfig.extensionStores.value.toSet()

        val dbStores =
            transaction {
                // Madoyomi: manga stores only, so stores of other types are never removed here
                ExtensionStoreTable
                    .selectAll()
                    .where { ExtensionStoreTable.type eq ExtensionKind.MANGA }
                    .associateBy { it[ExtensionStoreTable.indexUrl] }
            }

        val toAdd = prefUrls - dbStores.keys
        val toRemove = (dbStores.keys - prefUrls).toMutableSet()
        var needsPrefUpdate = toRemove.isNotEmpty()

        toAdd.forEach { url ->
            try {
                // Madoyomi: added through RepoService (trusts the repo's signing key, lists its extensions);
                // upstream: val store = fetch(url) ... upsert(store)
                val store = RepoService.default.add(url, ExtensionKind.MANGA, RepoIndexFormat.AUTO, syncPrefs = false)
                if (store[ExtensionStoreTable.indexUrl] != url) {
                    transaction {
                        ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl eq url }
                    }
                    needsPrefUpdate = true
                    toRemove -= store[ExtensionStoreTable.indexUrl]
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to sync preference store '$url' to database" }
            }
        }

        if (toRemove.isNotEmpty()) {
            transaction {
                // Madoyomi: and type = MANGA
                ExtensionStoreTable.deleteWhere {
                    (ExtensionStoreTable.indexUrl inList toRemove.toList()) and (ExtensionStoreTable.type eq ExtensionKind.MANGA)
                }
            }
        }
        if (needsPrefUpdate) {
            syncDbToPrefs()
        }
    }

    suspend fun getExtensions(store: ExtensionStore): List<ExtensionInfo> {
        // Madoyomi: delegates to madoyomi.core.repo.RepoIndexFetcher. JSON on a legacy store's repo.json reads its
        // index.min.json without following index_v2, as upstream did; v2 stores (inline or extensionListUrl) use AUTO
        val format = if (store.isLegacy) RepoIndexFormat.JSON else RepoIndexFormat.AUTO
        val extensions =
            RepoIndexFetcher.network
                .fetch(store.indexUrl, format, ExtensionKind.MANGA)
                .extensions
                .map { it.toExtensionInfo(store.indexUrl) }
        return extensions
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip =
            peek().use { peeked ->
                try {
                    peeked.readShort().toInt() == 0x1f8b
                } catch (_: Exception) {
                    false
                }
            }

        return if (isGzip) gzip().buffer() else this
    }
}
