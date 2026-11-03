package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.ExtensionKind
import java.util.zip.GZIPInputStream

/**
 * Turns the bytes of one repo document into [ParsedIndex]. Parsers do no I/O: [RepoIndexFetcher] downloads the
 * documents, picks the parser ([RepoIndexFormat]) and follows the redirects a document asks for.
 *
 * The [ExtensionKind] selects the schema where Mihon (manga) and Aniyomi (anime) differ (see [AnimeNetworkExtensionStore]).
 */
interface RepoIndexParser {
    /** [RepoIndexFormat.JSON] or [RepoIndexFormat.PROTOBUF]. */
    val format: RepoIndexFormat

    /** Parses an index document fetched from [indexUrl] (gzip is detected and undone). */
    fun parseIndex(
        body: ByteArray,
        indexUrl: String,
        kind: ExtensionKind,
    ): ParsedIndex

    /** Parses the separate extension-list document of a v2 index (`extension_list_url`). */
    fun parseExtensionList(
        body: ByteArray,
        kind: ExtensionKind,
    ): List<ExtensionListing>
}

/** What a repo document turned out to be. */
sealed interface ParsedIndex {
    /** A v2 extension-store index (protobuf `index.pb` or the same message as JSON). */
    data class Store(
        val meta: RepoStoreMeta,
        /** The inline list, or null when the index points at [extensionListUrl] instead. */
        val extensions: List<ExtensionListing>?,
        val extensionListUrl: String?,
    ) : ParsedIndex

    /** A legacy `repo.json`: metadata only, optionally pointing at a v2 index. */
    data class LegacyRepo(
        val meta: RepoStoreMeta,
        val indexV2: String?,
    ) : ParsedIndex

    /** A legacy `index.min.json`: listings only (the metadata is in the sibling `repo.json`). */
    data class LegacyList(
        val extensions: List<ExtensionListing>,
    ) : ParsedIndex
}

/** Upper bound for a decompressed index document (Keiyoushi's index.pb is ~0.7 MB raw). */
private const val MAX_INDEX_BYTES = 64 * 1024 * 1024

/** Undoes gzip when the body starts with the gzip magic `1f 8b` (repos serve it without `Content-Encoding`). */
fun decompressIfGzipped(body: ByteArray): ByteArray {
    if (body.size < 2 || body[0] != 0x1f.toByte() || body[1] != 0x8b.toByte()) return body
    val inflated = GZIPInputStream(body.inputStream()).use { it.readNBytes(MAX_INDEX_BYTES + 1) }
    if (inflated.size > MAX_INDEX_BYTES) {
        throw MadoyomiException(ErrorCode.REPO_FORMAT_MISMATCH, "Repo index is larger than $MAX_INDEX_BYTES bytes")
    }
    return inflated
}

/** The first byte that is not whitespace or a UTF-8 byte-order mark, or null for an empty body. */
internal fun firstSignificantByte(body: ByteArray): Byte? {
    var i = 0
    if (body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()) i = 3
    while (i < body.size && body[i].toInt().toChar() in " \t\r\n") i++
    return body.getOrNull(i)
}

internal const val JSON_ARRAY_START = '['.code.toByte()
internal const val JSON_OBJECT_START = '{'.code.toByte()
