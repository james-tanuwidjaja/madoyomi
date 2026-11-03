package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.extension.ExtensionInstallProgress
import madoyomi.core.extension.InstallState
import madoyomi.core.extension.SignatureTrustOrigin
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.core.repo.RepoRefreshResult
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.types.ExtensionStoreType
import suwayomi.tachidesk.graphql.types.ExtensionType
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.database.dbTransaction

/** An anime source (`ANIME_SOURCE`). Phase 1 lists them from the repo listing; browsing them is Phase 2. */
class AnimeSourceType(
    val id: Long,
    val name: String,
    val lang: String,
    val contentWarning: ContentWarning,
    val baseUrl: String?,
    @GraphQLDescription("The extension that provides this source")
    val extension: ExtensionType,
) : Node {
    /** A row of `ANIME_SOURCE` joined with `EXTENSION`. */
    constructor(row: ResultRow) : this(
        id = row[AnimeSourceTable.id].value,
        name = row[AnimeSourceTable.name],
        lang = row[AnimeSourceTable.lang],
        contentWarning = ContentWarning.valueOf(row[AnimeSourceTable.contentWarning]),
        baseUrl = row[AnimeSourceTable.baseUrl],
        extension = ExtensionType(row),
    )
}

data class AnimeSourceNodeList(
    override val nodes: List<AnimeSourceType>,
    override val edges: List<AnimeSourceEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class AnimeSourceEdge(
        override val cursor: Cursor,
        override val node: AnimeSourceType,
    ) : Edge()

    companion object {
        /** All of [this] as one page (the same shape as upstream's `ExtensionNodeList.toNodeList`). */
        fun List<AnimeSourceType>.toNodeList(): AnimeSourceNodeList =
            AnimeSourceNodeList(
                nodes = this,
                edges =
                    if (isEmpty()) {
                        emptyList()
                    } else {
                        listOf(AnimeSourceEdge(Cursor("0"), first()), AnimeSourceEdge(Cursor(lastIndex.toString()), last()))
                    },
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = Cursor(0.toString()),
                        endCursor = Cursor(lastIndex.toString()),
                    ),
                totalCount = size,
            )
    }
}

/** A trusted signing-certificate hash (`TRUSTED_SIGNATURE`). */
class TrustedSignatureType(
    @GraphQLDescription("Lowercase hex SHA-256 of the signing certificate")
    val hash: String,
    @GraphQLDescription("USER: trusted by the user; STORE: the signing key of an added repo (removing the repo revokes it)")
    val origin: SignatureTrustOrigin,
    @GraphQLDescription("Epoch seconds")
    val addedAt: Long,
    @GraphQLIgnore
    val storeId: Int?,
) {
    constructor(row: ResultRow) : this(
        hash = row[TrustedSignatureTable.id].value,
        origin = row[TrustedSignatureTable.origin],
        addedAt = row[TrustedSignatureTable.addedAt],
        storeId = row[TrustedSignatureTable.store]?.value,
    )

    @GraphQLDescription("The repo that vouches for this key (origin STORE)")
    fun extensionStore(): ExtensionStoreType? =
        storeId?.let { id ->
            dbTransaction {
                ExtensionStoreTable
                    .selectAll()
                    .where { ExtensionStoreTable.id eq id }
                    .firstOrNull()
                    ?.let(::ExtensionStoreType)
            }
        }

    @GraphQLDescription("The installed extensions signed with this certificate")
    fun extensions(): List<ExtensionType> = extensionsSignedWith(hash)

    companion object {
        fun find(hash: String): TrustedSignatureType? =
            dbTransaction {
                TrustedSignatureTable
                    .selectAll()
                    .where { TrustedSignatureTable.id eq hash.lowercase() }
                    .firstOrNull()
                    ?.let(::TrustedSignatureType)
            }

        fun extensionsSignedWith(hash: String): List<ExtensionType> =
            dbTransaction {
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.signatureHash eq hash.lowercase() }
                    .orderBy(ExtensionTable.pkgName)
                    .map(::ExtensionType)
            }
    }
}

/** One step of an install or update (`DOWNLOADING → VERIFYING → CONVERTING → INSTALLED | FAILED`). */
data class ExtensionInstallProgressType(
    @GraphQLDescription("The package; for a sideloaded file, its file name until the APK has been read")
    val pkgName: String,
    val type: ExtensionKind?,
    val state: InstallState,
    @GraphQLDescription("Set when state is FAILED")
    val errorCode: ErrorCode?,
    val message: String?,
) {
    constructor(progress: ExtensionInstallProgress) : this(
        pkgName = progress.pkgName,
        type = progress.kind,
        state = progress.state,
        errorCode = progress.errorCode,
        message = progress.message,
    )
}

/** The outcome of refreshing one repo. */
data class ExtensionStoreRefreshedType(
    val indexUrl: String,
    val type: ExtensionKind,
    @GraphQLDescription("False: the refresh failed and the repo keeps its last good list (see error)")
    val ok: Boolean,
    val error: String?,
    val extensionCount: Int,
) {
    constructor(result: RepoRefreshResult) : this(
        indexUrl = result.indexUrl,
        type = result.kind,
        ok = result.ok,
        error = result.error,
        extensionCount = result.extensionCount,
    )

    fun extensionStore(): ExtensionStoreType? =
        dbTransaction {
            ExtensionStoreTable
                .selectAll()
                .where { ExtensionStoreTable.indexUrl eq indexUrl }
                .firstOrNull()
                ?.let(::ExtensionStoreType)
        }
}
