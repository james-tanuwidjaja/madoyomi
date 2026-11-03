package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionLifecycle
import madoyomi.core.extension.ExtensionUnloader
import madoyomi.core.extension.SignatureTrust
import madoyomi.core.extension.SignatureTrustOrigin
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** The outcome of refreshing one repo. */
data class RepoRefreshResult(
    val indexUrl: String,
    val kind: ExtensionKind,
    val ok: Boolean,
    val error: String? = null,
    /** Listings the refresh found (0 when it failed). */
    val extensionCount: Int = 0,
)

/**
 * Repo add/remove/refresh for both extension kinds (Mihon/Aniyomi trust model, see server/UPSTREAM.md "Repo manager").
 *
 * - **Add** fetches the repo, checks that its packages are of the repo's kind, stores it (`EXTENSIONSTORE`) and
 *   trusts its signing key (`signing_key_trusted`, plus a `TRUSTED_SIGNATURE` row of origin STORE unless that hash is
 *   already trusted), then lists its extensions in `EXTENSION`.
 * - **Remove** deletes the repo; the FK cascade drops its trust row. A key shared with another trusted repo stays
 *   trusted (its row is re-pointed at that repo), and USER trust is never touched. The repo's uninstalled listings
 *   go; installed extensions whose signature is no longer trusted are marked unloaded and unloaded.
 * - **Refresh** re-fetches every repo of a kind. A failed repo keeps its rows and gets `last_error` (last-good
 *   cache); listings missing from the index are only pruned when every repo of that kind fetched. A changed
 *   signing key is never applied by a refresh (as in Mihon): it fails until the user re-adds the repo.
 *
 * Anime repos live only in the DB. Manga repos are also mirrored to upstream's `extensionStores` setting.
 */
class RepoService(
    private val fetcher: RepoIndexFetcher,
    private val unloader: ExtensionUnloader = ExtensionUnloader.default,
    private val now: () -> Long = { Instant.now().epochSecond },
    /** Called with a key once [add] trusts it, to load what it now covers (default: [ExtensionLifecycle.reconcileTrust]). */
    private val onTrusted: suspend (String) -> Unit = { ExtensionLifecycle.default.reconcileTrust(it) },
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    private val startupRefreshDone = AtomicBoolean(false)

    private val refreshedFlow =
        MutableSharedFlow<RepoRefreshResult>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One result per repo each time it is refreshed (on demand or at startup), after the DB is updated. */
    val refreshed: SharedFlow<RepoRefreshResult> = refreshedFlow.asSharedFlow()

    /** How many collectors [refreshed] has (tests wait for a subscription with it). */
    val refreshedSubscribers: StateFlow<Int> get() = refreshedFlow.subscriptionCount

    /**
     * Adds (or re-adds) the repo at [indexUrl] as a [kind] repo and returns its `EXTENSIONSTORE` row. The stored URL
     * is where the index resolved to (e.g. `index.min.json` → `index.pb` with [RepoIndexFormat.AUTO]).
     *
     * [syncPrefs] = false is for upstream's own settings sync, which updates the setting itself.
     */
    suspend fun add(
        indexUrl: String,
        kind: ExtensionKind,
        format: RepoIndexFormat = RepoIndexFormat.AUTO,
        syncPrefs: Boolean = true,
    ): ResultRow {
        val row =
            mutex.withLock {
                val fetched = fetcher.fetch(indexUrl.trim(), format, kind)
                val mismatched = fetched.extensions.filterNot { kind.matchesPackage(it.pkg) }
                if (mismatched.isNotEmpty()) {
                    throw MadoyomiException(
                        ErrorCode.REPO_TYPE_MISMATCH,
                        "${mismatched.size} of ${fetched.extensions.size} extensions in ${fetched.indexUrl} are not " +
                            "${kind.name.lowercase()} extensions, e.g. ${mismatched.take(3).joinToString { it.pkg }}",
                        mapOf("indexUrl" to fetched.indexUrl, "type" to kind.name, "packages" to mismatched.take(10).map { it.pkg }),
                    )
                }

                val storeId =
                    transaction {
                        val existing = storeByUrl(fetched.indexUrl)
                        if (existing != null && existing[ExtensionStoreTable.type] != kind) {
                            throw MadoyomiException(
                                ErrorCode.REPO_TYPE_MISMATCH,
                                "${fetched.indexUrl} is already added as a ${existing[ExtensionStoreTable.type].name.lowercase()} repo",
                                mapOf("indexUrl" to fetched.indexUrl, "type" to existing[ExtensionStoreTable.type].name),
                            )
                        }
                        val id =
                            if (existing == null) {
                                ExtensionStoreTable
                                    .insertAndGetId {
                                        it[ExtensionStoreTable.indexUrl] = fetched.indexUrl
                                        it[type] = kind
                                        it.writeFetched(fetched, format)
                                    }.value
                            } else {
                                val id = existing[ExtensionStoreTable.id].value
                                ExtensionStoreTable.update({ ExtensionStoreTable.id eq id }) { it.writeFetched(fetched, format) }
                                // a re-add with a new key revokes the old one (unless another repo still vouches for it)
                                val oldHash = normalizeSigningKey(existing[ExtensionStoreTable.signingKey])
                                val newHash = fetched.meta.normalizedSigningKey()
                                if (oldHash != null && oldHash != newHash) {
                                    TrustedSignatureTable.deleteWhere {
                                        (TrustedSignatureTable.id eq oldHash) and
                                            (TrustedSignatureTable.store eq EntityID(id, ExtensionStoreTable))
                                    }
                                }
                                id
                            }
                        trustStoreKey(id, fetched.meta)
                        val previousKey = normalizeSigningKey(existing?.get(ExtensionStoreTable.signingKey))
                        previousKey?.let { SignatureTrust.rehomeStoreTrust(it, now()) }
                        id
                    }

                ExtensionsList.updateExtensionDatabase(
                    fetched.extensions.map { it.toExtensionInfo(fetched.indexUrl) },
                    kind,
                    prune = false,
                )
                transaction { ExtensionStoreTable.selectAll().where { ExtensionStoreTable.id eq storeId }.first() }
            }
        logger.info { "Added ${kind.name.lowercase()} repo ${row[ExtensionStoreTable.name]} (${row[ExtensionStoreTable.indexUrl]})" }
        // installed extensions signed with the key (e.g. installed while it was untrusted) can load now
        normalizeSigningKey(row[ExtensionStoreTable.signingKey])
            ?.takeIf { row[ExtensionStoreTable.signingKeyTrusted] }
            ?.let { onTrusted(it) }
        if (kind == ExtensionKind.MANGA && syncPrefs) ExtensionStoreService.syncDbToPrefs()
        return row
    }

    /** Removes the repo stored under [indexUrl] and returns its row as it was. */
    suspend fun remove(indexUrl: String): ResultRow {
        val (row, unloaded) =
            mutex.withLock {
                val result =
                    transaction {
                        val row =
                            storeByUrl(indexUrl)
                                ?: throw MadoyomiException(
                                    ErrorCode.NOT_FOUND,
                                    "No repo with index URL $indexUrl",
                                    mapOf("indexUrl" to indexUrl),
                                )
                        val kind = row[ExtensionStoreTable.type]
                        val storeKey = normalizeSigningKey(row[ExtensionStoreTable.signingKey])
                        ExtensionStoreTable.deleteWhere { ExtensionStoreTable.id eq row[ExtensionStoreTable.id].value }
                        storeKey?.let { SignatureTrust.rehomeStoreTrust(it, now()) }

                        ExtensionTable.deleteWhere {
                            (ExtensionTable.storeIndexUrl eq indexUrl) and (ExtensionTable.type eq kind) and
                                (ExtensionTable.isInstalled eq false)
                        }

                        // installed extensions of this repo, or signed with its key, that are no longer trusted
                        val trusted = trustedHashes()
                        val untrusted =
                            ExtensionTable
                                .selectAll()
                                .where { (ExtensionTable.type eq kind) and (ExtensionTable.isInstalled eq true) }
                                .filter {
                                    val hash = it[ExtensionTable.signatureHash]
                                    (it[ExtensionTable.storeIndexUrl] == indexUrl || (hash != null && hash == storeKey)) &&
                                        (hash == null || hash !in trusted)
                                }
                        if (untrusted.isNotEmpty()) {
                            ExtensionTable.update({ ExtensionTable.id inList untrusted.map { it[ExtensionTable.id].value } }) {
                                it[isLoaded] = false
                            }
                        }
                        row to untrusted
                    }
                result.second.forEach { extension ->
                    try {
                        unloader.unload(extension)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to unload ${extension[ExtensionTable.pkgName]}" }
                    }
                }
                result
            }
        val kind = row[ExtensionStoreTable.type]
        logger.info {
            "Removed ${kind.name.lowercase()} repo ${row[ExtensionStoreTable.name]} ($indexUrl); unloaded ${unloaded.size} extension(s)"
        }
        if (kind == ExtensionKind.MANGA) ExtensionStoreService.syncDbToPrefs()
        return row
    }

    /** Refreshes every repo of [kind], or of both kinds when null. */
    suspend fun refreshAll(kind: ExtensionKind? = null): List<RepoRefreshResult> =
        (kind?.let(::listOf) ?: ExtensionKind.entries).flatMap { k ->
            val (results, urlChanged) = refreshKind(k)
            // a manga repo that moved (e.g. it gained index_v2) must move in upstream's extensionStores setting too
            if (k == ExtensionKind.MANGA && urlChanged) ExtensionStoreService.syncDbToPrefs()
            results.forEach { refreshedFlow.tryEmit(it) }
            results
        }

    /** The startup refresh: once per process, in the background. */
    @OptIn(DelicateCoroutinesApi::class)
    fun refreshOnStartup() {
        if (!startupRefreshDone.compareAndSet(false, true)) return
        GlobalScope.launch {
            try {
                refreshAll().forEach { result ->
                    if (result.ok) {
                        logger.info { "Startup refresh of ${result.indexUrl}: ${result.extensionCount} extensions" }
                    } else {
                        logger.warn { "Startup refresh of ${result.indexUrl} failed, keeping its last good list: ${result.error}" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Startup repo refresh failed" }
            }
        }
    }

    /** The results, and whether any repo's stored URL changed. */
    private suspend fun refreshKind(kind: ExtensionKind): Pair<List<RepoRefreshResult>, Boolean> =
        mutex.withLock {
            val stores =
                transaction {
                    ExtensionStoreTable
                        .selectAll()
                        .where { ExtensionStoreTable.type eq kind }
                        .orderBy(ExtensionStoreTable.id)
                        .toList()
                }
            if (stores.isEmpty()) return@withLock emptyList<RepoRefreshResult>() to false

            val listings = mutableListOf<ExtensionListing>()
            val listingUrls = mutableListOf<String>()
            val results =
                stores.map { store ->
                    val storeId = store[ExtensionStoreTable.id].value
                    val storedUrl = store[ExtensionStoreTable.indexUrl]
                    try {
                        val fetched = fetcher.fetch(storedUrl, store[ExtensionStoreTable.format], kind)
                        refreshFailure(store, fetched)?.let { throw it }

                        val (matching, mismatched) = fetched.extensions.partition { kind.matchesPackage(it.pkg) }
                        if (mismatched.isNotEmpty()) {
                            logger.warn {
                                "Ignoring ${mismatched.size} non-${kind.name.lowercase()} extensions in $storedUrl, " +
                                    "e.g. ${mismatched.take(3).joinToString { it.pkg }}"
                            }
                        }
                        transaction {
                            ExtensionStoreTable.update({ ExtensionStoreTable.id eq storeId }) {
                                it[indexUrl] = fetched.indexUrl
                                it.writeFetched(fetched, store[ExtensionStoreTable.format])
                            }
                        }
                        listings += matching
                        repeat(matching.size) { listingUrls += fetched.indexUrl }
                        RepoRefreshResult(fetched.indexUrl, kind, ok = true, extensionCount = matching.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = (e.message ?: e.javaClass.simpleName).take(MAX_ERROR_LENGTH)
                        logger.warn(e) { "Failed to refresh repo $storedUrl, keeping its last good list" }
                        transaction {
                            ExtensionStoreTable.update({ ExtensionStoreTable.id eq storeId }) { it[lastError] = message }
                        }
                        RepoRefreshResult(storedUrl, kind, ok = false, error = message)
                    }
                }

            // last-good cache: a row last listed by a repo that failed now is only replaced by a newer version
            val failedUrls = results.filterNot { it.ok }.map { it.indexUrl }.toSet()
            val keep =
                if (failedUrls.isEmpty()) {
                    emptyMap()
                } else {
                    transaction {
                        ExtensionTable
                            .selectAll()
                            .where { ExtensionTable.type eq kind }
                            .filter { it[ExtensionTable.storeIndexUrl] in failedUrls }
                            .associate { it[ExtensionTable.pkgName] to it[ExtensionTable.versionCode] }
                    }
                }
            val infos =
                listings.indices
                    .filter { i -> keep[listings[i].pkg]?.let { listings[i].versionCode > it } ?: true }
                    .map { i -> listings[i].toExtensionInfo(listingUrls[i]) }

            ExtensionsList.updateExtensionDatabase(infos, kind, prune = results.all { it.ok })
            results to results.zip(stores).any { (result, store) -> result.ok && result.indexUrl != store[ExtensionStoreTable.indexUrl] }
        }

    /** Why a successful fetch must not be applied to [store], or null. */
    private fun refreshFailure(
        store: ResultRow,
        fetched: FetchedRepo,
    ): MadoyomiException? {
        val storedKey = normalizeSigningKey(store[ExtensionStoreTable.signingKey])
        if (storedKey != fetched.meta.normalizedSigningKey()) {
            return MadoyomiException(
                ErrorCode.REPO_FETCH_FAILED,
                "Signing key changed (${fetched.meta.signingKey.take(16)}…); remove and re-add the repo to trust the new key",
                mapOf("indexUrl" to store[ExtensionStoreTable.indexUrl], "signingKey" to fetched.meta.signingKey),
            )
        }
        if (fetched.indexUrl != store[ExtensionStoreTable.indexUrl]) {
            val taken =
                transaction {
                    ExtensionStoreTable
                        .selectAll()
                        .where {
                            (ExtensionStoreTable.indexUrl eq fetched.indexUrl) and
                                (ExtensionStoreTable.id neq store[ExtensionStoreTable.id].value)
                        }.any()
                }
            if (taken) {
                return MadoyomiException(
                    ErrorCode.REPO_FETCH_FAILED,
                    "Repo now resolves to ${fetched.indexUrl}, which is already added as another repo",
                    mapOf("indexUrl" to fetched.indexUrl),
                )
            }
        }
        return null
    }

    private fun storeByUrl(indexUrl: String): ResultRow? =
        ExtensionStoreTable.selectAll().where { ExtensionStoreTable.indexUrl eq indexUrl }.firstOrNull()

    private fun trustedHashes(): Set<String> = TrustedSignatureTable.selectAll().map { it[TrustedSignatureTable.id].value }.toSet()

    /** Marks the repo's key trusted and records it in `TRUSTED_SIGNATURE`, unless that hash is already there. */
    private fun trustStoreKey(
        storeId: Int,
        meta: RepoStoreMeta,
    ) {
        val hash = meta.normalizedSigningKey()
        ExtensionStoreTable.update({ ExtensionStoreTable.id eq storeId }) { it[signingKeyTrusted] = hash != null }
        if (hash == null) {
            logger.warn { "Repo ${meta.name} has no signing key; none of its extensions will be trusted automatically" }
            return
        }
        if (TrustedSignatureTable.selectAll().where { TrustedSignatureTable.id eq hash }.empty()) {
            TrustedSignatureTable.insert {
                it[id] = hash
                it[origin] = SignatureTrustOrigin.STORE
                it[store] = EntityID(storeId, ExtensionStoreTable)
                it[addedAt] = now()
            }
        }
    }

    private fun UpdateBuilder<*>.writeFetched(
        fetched: FetchedRepo,
        format: RepoIndexFormat,
    ) {
        this[ExtensionStoreTable.name] = fetched.meta.name.take(256)
        this[ExtensionStoreTable.badgeLabel] = fetched.meta.badgeLabel.take(32)
        this[ExtensionStoreTable.signingKey] = fetched.meta.signingKey.take(512)
        this[ExtensionStoreTable.contactWebsite] = fetched.meta.website
        this[ExtensionStoreTable.contactDiscord] = fetched.meta.discord
        this[ExtensionStoreTable.isLegacy] = fetched.isLegacy
        this[ExtensionStoreTable.extensionListUrl] = fetched.extensionListUrl
        this[ExtensionStoreTable.format] = format
        this[ExtensionStoreTable.lastRefreshedAt] = now()
        this[ExtensionStoreTable.lastError] = null
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 2000

        /** The service on the app's network stack. */
        val default by lazy { RepoService(RepoIndexFetcher.network) }
    }
}
