package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.ExtensionKind
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.cancellation.CancellationException

/** Downloads one URL and returns the raw body. Tests replace it with a stub. */
fun interface RepoHttp {
    suspend fun get(url: String): ByteArray
}

/** [RepoHttp] on the app's shared OkHttp client (upstream's `NetworkHelper`). */
object NetworkRepoHttp : RepoHttp {
    private val network: NetworkHelper by injectLazy()

    override suspend fun get(url: String): ByteArray =
        network.client
            .newCall(GET(url))
            .awaitSuccess()
            .use { it.body.bytes() }
}

/**
 * Fetches a repo in a given [RepoIndexFormat] and resolves it to one [FetchedRepo].
 *
 * - [RepoIndexFormat.AUTO] is upstream's behaviour (`ExtensionStoreService.fetch`): sniff the first byte (`[` legacy
 *   `index.min.json`, `{` `repo.json` or JSON v2, anything else protobuf) and follow a `repo.json`'s `index_v2`.
 * - [RepoIndexFormat.JSON] only reads the JSON documents and never follows `index_v2`: a legacy repo is read from
 *   its `index.min.json` even when it also publishes an `index.pb`.
 * - [RepoIndexFormat.PROTOBUF] decodes the URL as protobuf, with no sniffing and no redirects.
 *
 * Gzip is undone first in every case. Errors are [MadoyomiException]s: [ErrorCode.REPO_FETCH_FAILED] when a
 * document can't be downloaded, [ErrorCode.REPO_FORMAT_MISMATCH] when it isn't what the format expects.
 */
class RepoIndexFetcher(
    private val http: RepoHttp,
    private val jsonParser: JsonIndexParser = JsonIndexParser(),
    private val protobufParser: ProtobufIndexParser = ProtobufIndexParser(),
) {
    suspend fun fetch(
        indexUrl: String,
        format: RepoIndexFormat,
        kind: ExtensionKind,
    ): FetchedRepo = fetch(indexUrl, format, kind, hops = 0)

    private suspend fun fetch(
        url: String,
        format: RepoIndexFormat,
        kind: ExtensionKind,
        hops: Int,
    ): FetchedRepo {
        val body = download(url)
        val parsed =
            when (format) {
                RepoIndexFormat.PROTOBUF -> {
                    protobufParser.parseIndex(body, url, kind)
                }

                RepoIndexFormat.JSON -> {
                    jsonParser.parseIndex(body, url, kind)
                }

                RepoIndexFormat.AUTO -> {
                    when (firstSignificantByte(body)) {
                        JSON_ARRAY_START, JSON_OBJECT_START -> jsonParser.parseIndex(body, url, kind)
                        else -> protobufParser.parseIndex(body, url, kind)
                    }
                }
            }

        return when (parsed) {
            is ParsedIndex.Store -> {
                FetchedRepo(
                    indexUrl = url,
                    isLegacy = false,
                    extensionListUrl = parsed.extensionListUrl,
                    meta = parsed.meta,
                    extensions = parsed.extensions ?: parsed.extensionListUrl?.let { fetchList(it, kind) }.orEmpty(),
                )
            }

            is ParsedIndex.LegacyList -> {
                if (!url.endsWith(LEGACY_LIST)) {
                    throw MadoyomiException(
                        ErrorCode.REPO_FORMAT_MISMATCH,
                        "Provided legacy store url is not valid: a legacy index must end in $LEGACY_LIST ($url)",
                    )
                }
                val repoUrl = url.removeSuffix(LEGACY_LIST) + LEGACY_REPO
                val repo =
                    jsonParser.parseIndex(download(repoUrl), repoUrl, kind) as? ParsedIndex.LegacyRepo
                        ?: throw MadoyomiException(ErrorCode.REPO_FORMAT_MISMATCH, "Not a legacy repo.json ($repoUrl)")
                if (format == RepoIndexFormat.AUTO && repo.indexV2 != null) {
                    follow(repo.indexV2, kind, hops)
                } else {
                    legacyRepo(repoUrl, repo.meta, parsed.extensions)
                }
            }

            is ParsedIndex.LegacyRepo -> {
                if (format == RepoIndexFormat.AUTO && parsed.indexV2 != null) {
                    follow(parsed.indexV2, kind, hops)
                } else {
                    val storeBaseUrl = url.removeSuffix(LEGACY_REPO)
                    val listUrl = storeBaseUrl + LEGACY_LIST
                    val text = decompressIfGzipped(download(listUrl)).toString(Charsets.UTF_8).removePrefix("")
                    legacyRepo(url, parsed.meta, jsonParser.parseLegacyList(text, storeBaseUrl))
                }
            }
        }
    }

    private suspend fun follow(
        indexV2: String,
        kind: ExtensionKind,
        hops: Int,
    ): FetchedRepo {
        if (hops >= MAX_REDIRECTS) {
            throw MadoyomiException(ErrorCode.REPO_FORMAT_MISMATCH, "Too many index_v2 redirects (last: $indexV2)")
        }
        return fetch(indexV2, RepoIndexFormat.AUTO, kind, hops + 1)
    }

    private suspend fun fetchList(
        listUrl: String,
        kind: ExtensionKind,
    ): List<ExtensionListing> {
        val body = download(listUrl)
        return when (firstSignificantByte(body)) {
            JSON_OBJECT_START -> jsonParser.parseExtensionList(body, kind)
            else -> protobufParser.parseExtensionList(body, kind)
        }
    }

    private fun legacyRepo(
        repoUrl: String,
        meta: RepoStoreMeta,
        extensions: List<ExtensionListing>,
    ) = FetchedRepo(indexUrl = repoUrl, isLegacy = true, extensionListUrl = null, meta = meta, extensions = extensions)

    private suspend fun download(url: String): ByteArray =
        try {
            decompressIfGzipped(http.get(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MadoyomiException) {
            throw e
        } catch (e: Exception) {
            throw MadoyomiException(
                ErrorCode.REPO_FETCH_FAILED,
                "Failed to fetch $url: ${e.message ?: e.javaClass.simpleName}",
                mapOf("url" to url),
                e,
            )
        }

    companion object {
        private const val LEGACY_LIST = "/index.min.json"
        private const val LEGACY_REPO = "/repo.json"

        /** `index_v2` hops followed before giving up (real repos need one). */
        const val MAX_REDIRECTS = 3

        /** The fetcher on the app's network stack. */
        val network by lazy { RepoIndexFetcher(NetworkRepoHttp) }
    }
}
