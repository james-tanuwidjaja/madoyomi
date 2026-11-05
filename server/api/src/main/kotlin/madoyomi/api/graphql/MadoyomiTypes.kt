package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import graphql.schema.DataFetchingEnvironment
import madoyomi.core.anime.AnimeFilters
import madoyomi.core.anime.AnimeSourceAccess
import madoyomi.core.anime.AnimeSourceCalls
import madoyomi.core.anime.AnimeSourcePreferences
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
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
import suwayomi.tachidesk.graphql.types.Filter
import suwayomi.tachidesk.graphql.types.Preference
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

/**
 * An anime source (`ANIME_SOURCE`): from the live extension once it is loaded, from the repo listing before. The
 * fields that need the live source ([supportsLatest], [isConfigurable]) are null while it can't be used (untrusted,
 * not loadable: the extension's `isTrusted`/`isLoaded` say why); [filters] and [preferences] fail with the reason
 * (`NOT_FOUND`, `UNTRUSTED`, `LOAD_FAILED`).
 */
class AnimeSourceType(
    val id: Long,
    val name: String,
    val lang: String,
    val contentWarning: ContentWarning,
    val baseUrl: String?,
    @GraphQLDescription("The extension's icon (upstream's icon endpoint)")
    val iconUrl: String,
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
        iconUrl = Extension.proxyExtensionIconUrl(row[ExtensionTable.pkgName]),
        extension = ExtensionType(row),
    )

    @GraphQLDescription("Whether the source has latest updates (fetchSourceAnime LATEST); null while the source can't be used")
    fun supportsLatest(): CompletableFuture<Boolean?> =
        future { usable()?.let { source -> AnimeSourceCalls.callBlocking(source, "latest") { source.supportsLatest } } }

    @GraphQLDescription("Whether the source has preferences; null while the source can't be used")
    fun isConfigurable(): CompletableFuture<Boolean?> = future { usable()?.let { it is ConfigurableAnimeSource } }

    @GraphQLDescription("The source's search filters, as they are by default (changes go into fetchSourceAnime)")
    fun filters(): CompletableFuture<List<Filter>> = future { AnimeFilters.filtersOf(AnimeSourceAccess.require(id)) }

    @GraphQLDescription("The source's settings screen (empty if it has none)")
    fun preferences(): CompletableFuture<List<Preference>> = future { AnimeSourcePreferences.preferences(id) }

    @GraphQLDescription("The source's stored entries (browsed or in the library)")
    fun anime(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<AnimeNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader("AnimeForAnimeSourceDataLoader", id)

    private suspend fun usable(): AnimeSource? =
        try {
            AnimeSourceAccess.require(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MadoyomiException) {
            null
        }

    companion object {
        /** The source [id] with its extension, or null if there is no `ANIME_SOURCE` row. */
        fun find(id: Long): AnimeSourceType? =
            dbTransaction {
                AnimeSourceTable
                    .innerJoin(ExtensionTable)
                    .selectAll()
                    .where { AnimeSourceTable.id eq id }
                    .firstOrNull()
                    ?.let(::AnimeSourceType)
            }
    }
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
