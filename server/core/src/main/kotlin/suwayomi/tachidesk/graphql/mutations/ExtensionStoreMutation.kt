package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.core.repo.RepoService
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.directives.RequirePermissions
import suwayomi.tachidesk.graphql.types.ExtensionStoreType
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.UserPermission
import java.util.concurrent.CompletableFuture

class ExtensionStoreMutation {
    data class AddExtensionStoreInput(
        val clientMutationId: String? = null,
        val indexUrl: String,
        // Madoyomi: the repo kind (null: MANGA, as upstream) and the index format (null: AUTO, upstream's sniffing)
        @GraphQLDescription("The repo kind; MANGA when omitted")
        val type: ExtensionKind? = null,
        @GraphQLDescription("How to read the index; AUTO (detect, follow repo.json index_v2) when omitted")
        val format: RepoIndexFormat? = null,
    )

    data class AddExtensionStorePayload(
        val clientMutationId: String?,
        val extensionStore: ExtensionStoreType,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.MANAGE_EXTENSION_STORES)
    fun addExtensionStore(input: AddExtensionStoreInput): CompletableFuture<AddExtensionStorePayload?> {
        val (clientMutationId, indexUrl, type, format) = input
        return future {
            // Madoyomi: added through RepoService, which checks the packages against the kind, trusts its signing key,
            // lists its extensions and syncs the setting (upstream: fetch + upsert + syncDbToPrefs + re-select)
            val row = RepoService.default.add(indexUrl, type ?: ExtensionKind.MANGA, format ?: RepoIndexFormat.AUTO)

            AddExtensionStorePayload(
                clientMutationId = clientMutationId,
                extensionStore = ExtensionStoreType(row),
            )
        }
    }

    data class RemoveExtensionStoreInput(
        val clientMutationId: String? = null,
        val indexUrl: String,
    )

    data class RemoveExtensionStorePayload(
        val clientMutationId: String?,
        val extensionStore: ExtensionStoreType?,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.MANAGE_EXTENSION_STORES)
    fun removeExtensionStore(input: RemoveExtensionStoreInput): CompletableFuture<RemoveExtensionStorePayload?> {
        val (clientMutationId, indexUrl) = input
        return future {
            val store =
                transaction {
                    ExtensionStoreTable
                        .selectAll()
                        .where { ExtensionStoreTable.indexUrl eq indexUrl }
                        .firstOrNull()
                        ?.let { ExtensionStoreType(it) }
                }

            // Madoyomi: removed through RepoService, which keeps a key shared with another repo trusted, drops the
            // repo's listings and unloads its untrusted extensions (upstream: deleteWhere + syncDbToPrefs)
            if (store != null) {
                RepoService.default.remove(indexUrl)
            } else {
                ExtensionStoreService.syncDbToPrefs()
            }

            RemoveExtensionStorePayload(
                clientMutationId = clientMutationId,
                // Madoyomi: the store as it was (upstream copies it field by field, which would miss Madoyomi's fields)
                extensionStore = store,
            )
        }
    }
}
