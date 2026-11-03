@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import eu.kanade.tachiyomi.source.local.LocalSource
import io.javalin.http.UploadedFile
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionActions
import madoyomi.core.repo.RepoService
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.directives.RequirePermissions
import suwayomi.tachidesk.graphql.types.ExtensionStoreType
import suwayomi.tachidesk.graphql.types.ExtensionType
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UserPermission
import suwayomi.tachidesk.server.user.hasPermission
import java.util.concurrent.CompletableFuture

class ExtensionMutation {
    data class UpdateExtensionPatch(
        val install: Boolean? = null,
        val update: Boolean? = null,
        val uninstall: Boolean? = null,
    )

    data class UpdateExtensionPayload(
        val clientMutationId: String?,
        val extension: ExtensionType?,
    )

    data class UpdateExtensionInput(
        val clientMutationId: String? = null,
        val id: String,
        val patch: UpdateExtensionPatch,
    )

    data class UpdateExtensionsPayload(
        val clientMutationId: String?,
        val extensions: List<ExtensionType>,
    )

    data class UpdateExtensionsInput(
        val clientMutationId: String? = null,
        val ids: List<String>,
        val patch: UpdateExtensionPatch,
    )

    private suspend fun updateExtensions(
        ids: List<String>,
        patch: UpdateExtensionPatch,
    ) {
        val extensions =
            transaction {
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName inList ids }
                    .map { ExtensionType(it) }
            }

        // Madoyomi: both kinds; each action goes to the extension's kind (upstream: Extension.*, manga only)
        if (patch.update == true) {
            extensions.filter { it.hasUpdate }.forEach {
                ExtensionActions.update(it.pkgName, it.type)
            }
        }

        if (patch.install == true) {
            extensions.filterNot { it.isInstalled }.forEach {
                ExtensionActions.install(it.pkgName, it.type)
            }
        }

        if (patch.uninstall == true) {
            extensions.filter { it.isInstalled }.forEach {
                ExtensionActions.uninstall(it.pkgName, it.type)
            }
        }
    }

    // Madoyomi: updateExtension (one id) does what the patch asks or says why not: an unknown id is NOT_FOUND, and
    // "already installed", "no update" or "not installed" are INSTALL_FAILED from the lifecycle. updateExtensions
    // keeps upstream's skip-what-doesn't-apply behaviour.
    private suspend fun updateOne(
        id: String,
        patch: UpdateExtensionPatch,
    ) {
        val extension =
            transaction {
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq id }
                    .firstOrNull()
                    ?.let { ExtensionType(it) }
            }
                ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No extension $id", mapOf("pkgName" to id))
        if (patch.update == true) ExtensionActions.update(id, extension.type)
        if (patch.install == true) ExtensionActions.install(id, extension.type)
        if (patch.uninstall == true) ExtensionActions.uninstall(id, extension.type)
    }

    private fun checkUpdatePermissions(
        permissions: List<UserPermission>,
        patch: UpdateExtensionPatch,
    ) {
        if (patch.install == true && !permissions.hasPermission(UserPermission.INSTALL_EXTENSIONS)) {
            throw ForbiddenException()
        }

        if (patch.uninstall == true && !permissions.hasPermission(UserPermission.UNINSTALL_EXTENSIONS)) {
            throw ForbiddenException()
        }
    }

    @RequireAuth
    fun updateExtension(
        @GraphQLIgnore
        permissions: List<UserPermission>,
        input: UpdateExtensionInput,
    ): CompletableFuture<UpdateExtensionPayload?> {
        val (clientMutationId, id, patch) = input

        checkUpdatePermissions(permissions, patch)

        return future {
            updateOne(id, patch) // Madoyomi (upstream: updateExtensions(listOf(id), patch))

            val extension =
                transaction {
                    ExtensionTable
                        .selectAll()
                        .where { ExtensionTable.pkgName eq id }
                        .firstOrNull()
                        ?.let { ExtensionType(it) }
                }

            UpdateExtensionPayload(
                clientMutationId = clientMutationId,
                extension = extension,
            )
        }
    }

    @RequireAuth
    fun updateExtensions(
        @GraphQLIgnore
        permissions: List<UserPermission>,
        input: UpdateExtensionsInput,
    ): CompletableFuture<UpdateExtensionsPayload?> {
        val (clientMutationId, ids, patch) = input

        checkUpdatePermissions(permissions, patch)

        return future {
            updateExtensions(ids, patch)

            val extensions =
                transaction {
                    ExtensionTable
                        .selectAll()
                        .where { ExtensionTable.pkgName inList ids }
                        .map { ExtensionType(it) }
                }

            UpdateExtensionsPayload(
                clientMutationId = clientMutationId,
                extensions = extensions,
            )
        }
    }

    data class FetchExtensionsInput(
        val clientMutationId: String? = null,
        // Madoyomi: refresh only the repos of this kind (omitted: both kinds); the payload lists that kind
        val type: ExtensionKind? = null,
    )

    data class FetchExtensionsPayload(
        val clientMutationId: String?,
        val extensions: List<ExtensionType>,
        val extensionStores: List<ExtensionStoreType>,
    )

    @RequireAuth
    fun fetchExtensions(
        @GraphQLIgnore
        permissions: List<UserPermission>,
        input: FetchExtensionsInput,
    ): CompletableFuture<FetchExtensionsPayload?> {
        val (clientMutationId, type) = input

        return future {
            // Madoyomi: the repos of [type], or of both kinds (upstream: ExtensionsList.fetchExtensions(), manga only)
            RepoService.default.refreshAll(type)

            val extensions =
                transaction {
                    val query =
                        ExtensionTable
                            .selectAll()
                            .where { ExtensionTable.name neq LocalSource.EXTENSION_NAME }
                    type?.let { query.andWhere { ExtensionTable.type eq it } } // Madoyomi

                    // hide NSFW extensions from users without the NSFW permission
                    // if (!permissions.hasPermission(UserPermission.ACCESS_NSFW)) {
                    //     query.andWhere { ExtensionTable.contentWarning less ContentWarning.MIXED.ordinal }
                    // }

                    query.map { ExtensionType(it) }
                }

            val extensionStores =
                transaction {
                    // Madoyomi: the stores of [type], or all (upstream: all, which were manga only)
                    val stores = ExtensionStoreTable.selectAll()
                    type?.let { stores.where { ExtensionStoreTable.type eq it } }
                    stores.map { ExtensionStoreType(it) }
                }

            FetchExtensionsPayload(
                clientMutationId = clientMutationId,
                extensions = extensions,
                extensionStores = extensionStores,
            )
        }
    }

    data class InstallExternalExtensionInput(
        val clientMutationId: String? = null,
        val extensionFile: UploadedFile,
    )

    data class InstallExternalExtensionPayload(
        val clientMutationId: String?,
        val extension: ExtensionType,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.INSTALL_EXTERNAL_EXTENSIONS)
    fun installExternalExtension(input: InstallExternalExtensionInput): CompletableFuture<InstallExternalExtensionPayload?> {
        val (clientMutationId, extensionFile) = input

        return future {
            val pkgName = Extension.installExternalExtension(extensionFile.content(), extensionFile.filename())

            val dbExtension =
                transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkgName }.first() }

            InstallExternalExtensionPayload(
                clientMutationId,
                extension = ExtensionType(dbExtension),
            )
        }
    }
}
