package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.schema.DataFetchingEnvironment
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.getNodeListFromDataLoaders
import suwayomi.tachidesk.graphql.types.ExtensionNodeList.Companion.toNodeList
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import java.util.concurrent.CompletableFuture

class ExtensionStoreType(
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val contactWebsite: String,
    val contactDiscord: String?,
    val indexUrl: String,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
    // Madoyomi: the repo kind and index format, whether its signing key is trusted (Mihon model: adding a repo trusts
    // its key), and the last refresh (epoch seconds) and its error, if it failed (EXTENSIONSTORE columns from M0069)
    val type: ExtensionKind,
    val format: RepoIndexFormat,
    val signingKeyTrusted: Boolean,
    val lastRefreshedAt: Long,
    val lastError: String?,
) : Node {
    constructor(row: ResultRow) : this(
        name = row[ExtensionStoreTable.name],
        badgeLabel = row[ExtensionStoreTable.badgeLabel],
        signingKey = row[ExtensionStoreTable.signingKey],
        contactWebsite = row[ExtensionStoreTable.contactWebsite],
        contactDiscord = row[ExtensionStoreTable.contactDiscord],
        indexUrl = row[ExtensionStoreTable.indexUrl],
        isLegacy = row[ExtensionStoreTable.isLegacy],
        extensionListUrl = row[ExtensionStoreTable.extensionListUrl],
        type = row[ExtensionStoreTable.type], // Madoyomi: the five fields above
        format = row[ExtensionStoreTable.format],
        signingKeyTrusted = row[ExtensionStoreTable.signingKeyTrusted],
        lastRefreshedAt = row[ExtensionStoreTable.lastRefreshedAt],
        lastError = row[ExtensionStoreTable.lastError],
    )

    fun extensions(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ExtensionNodeList> =
        dataFetchingEnvironment.getNodeListFromDataLoaders("ExtensionsForExtensionStore", "ExtensionCountForExtensionStore", indexUrl) {
            emptyList<ExtensionType>().toNodeList().copy(totalCount = it)
        }
}

data class ExtensionStoreNodeList(
    override val nodes: List<ExtensionStoreType>,
    override val edges: List<ExtensionStoreEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class ExtensionStoreEdge(
        override val cursor: Cursor,
        override val node: ExtensionStoreType,
    ) : Edge()

    companion object {
        fun List<ExtensionStoreType>.toNodeList(): ExtensionStoreNodeList =
            ExtensionStoreNodeList(
                nodes = this,
                edges = getEdges(),
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = Cursor(0.toString()),
                        endCursor = Cursor(lastIndex.toString()),
                    ),
                totalCount = size,
            )

        private fun List<ExtensionStoreType>.getEdges(): List<ExtensionStoreEdge> {
            if (isEmpty()) return emptyList()
            return listOf(
                ExtensionStoreEdge(
                    cursor = Cursor("0"),
                    node = first(),
                ),
                ExtensionStoreEdge(
                    cursor = Cursor(lastIndex.toString()),
                    node = last(),
                ),
            )
        }
    }
}
