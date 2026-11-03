package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionLifecycle
import madoyomi.core.extension.SignatureTrust
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.directives.RequirePermissions
import suwayomi.tachidesk.graphql.types.ExtensionType
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.UserPermission
import java.util.concurrent.CompletableFuture

/**
 * The trust prompt (Mihon/Aniyomi model): an installed extension is only loaded when its signing certificate is
 * trusted. Adding a repo trusts the repo's key; these mutations are the user's own trust decisions.
 */
class TrustMutation {
    private val lifecycle get() = ExtensionLifecycle.default

    data class TrustExtensionInput(
        val clientMutationId: String? = null,
        val pkgName: String,
    )

    data class TrustExtensionPayload(
        val clientMutationId: String?,
        val extension: ExtensionType,
        val trustedSignature: TrustedSignatureType?,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.INSTALL_EXTENSIONS)
    @GraphQLDescription("Trusts the signing certificate of the installed extension pkgName (either kind) and loads what it signs")
    fun trustExtension(input: TrustExtensionInput): CompletableFuture<TrustExtensionPayload?> =
        future {
            lifecycle.trustExtension(input.pkgName, extensionRow(input.pkgName).type)
            val extension = extensionRow(input.pkgName)
            TrustExtensionPayload(input.clientMutationId, extension, extension.signatureHash?.let(TrustedSignatureType::find))
        }

    data class SignatureInput(
        val clientMutationId: String? = null,
        @GraphQLDescription("Lowercase hex SHA-256 of the signing certificate (ExtensionType.signatureHash)")
        val hash: String,
    )

    data class TrustSignaturePayload(
        val clientMutationId: String?,
        val trustedSignature: TrustedSignatureType?,
        @GraphQLDescription("The installed extensions signed with the certificate")
        val extensions: List<ExtensionType>,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.INSTALL_EXTENSIONS)
    @GraphQLDescription("Trusts a signing certificate (origin USER) and loads the installed extensions it signs")
    fun trustSignature(input: SignatureInput): CompletableFuture<TrustSignaturePayload?> =
        future {
            lifecycle.trustSignature(input.hash)
            TrustSignaturePayload(
                input.clientMutationId,
                TrustedSignatureType.find(input.hash),
                TrustedSignatureType.extensionsSignedWith(input.hash),
            )
        }

    data class UntrustSignaturePayload(
        val clientMutationId: String?,
        val hash: String,
        @GraphQLDescription("Still trusted: an added repo vouches for this key (only the user's own trust was withdrawn)")
        val isTrusted: Boolean,
        val extensions: List<ExtensionType>,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.INSTALL_EXTENSIONS)
    @GraphQLDescription("Withdraws the user's trust in a signing certificate and unloads what it no longer covers")
    fun untrustSignature(input: SignatureInput): CompletableFuture<UntrustSignaturePayload?> =
        future {
            val hash = input.hash.lowercase()
            val stillTrusted = lifecycle.untrustSignature(hash)
            UntrustSignaturePayload(
                input.clientMutationId,
                hash,
                stillTrusted && SignatureTrust.isTrusted(hash),
                TrustedSignatureType.extensionsSignedWith(hash),
            )
        }

    private fun extensionRow(pkg: String): ExtensionType =
        dbTransaction {
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.pkgName eq pkg }
                .firstOrNull()
                ?.let(::ExtensionType)
        } ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "No extension $pkg", mapOf("pkgName" to pkg))
}
