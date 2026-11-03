package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.NetworkLegacyExtension
import suwayomi.tachidesk.manga.impl.extension.github.NetworkLegacyExtensionRepo
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning

/**
 * The JSON repo documents:
 * - legacy `index.min.json` (a list, `[`), e.g. yuzono's anime index, with Aniyomi's extra `torrent` flag;
 * - legacy `repo.json` (`{` with `meta`), optionally pointing at a v2 index through `index_v2`;
 * - the v2 extension-store message as JSON (`{` without `meta`), in the [kind][ExtensionKind]'s schema.
 *
 * Unknown keys are ignored, as upstream does.
 */
class JsonIndexParser(
    private val json: Json = defaultJson,
) : RepoIndexParser {
    override val format = RepoIndexFormat.JSON

    override fun parseIndex(
        body: ByteArray,
        indexUrl: String,
        kind: ExtensionKind,
    ): ParsedIndex {
        val bytes = decompressIfGzipped(body)
        val text = bytes.toText()
        return when (firstSignificantByte(bytes)) {
            JSON_ARRAY_START -> {
                ParsedIndex.LegacyList(parseLegacyList(text, indexUrl.removeSuffix("/index.min.json")))
            }

            JSON_OBJECT_START -> {
                try {
                    json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), text).toLegacyRepo()
                } catch (_: IllegalArgumentException) {
                    decode("JSON index ($indexUrl)") { parseV2Store(text, kind) }
                }
            }

            else -> {
                throw MadoyomiException(ErrorCode.REPO_FORMAT_MISMATCH, "Not a JSON index ($indexUrl)")
            }
        }
    }

    override fun parseExtensionList(
        body: ByteArray,
        kind: ExtensionKind,
    ): List<ExtensionListing> {
        val text = decompressIfGzipped(body).toText()
        return decode("JSON extension list") {
            when (kind) {
                ExtensionKind.MANGA -> json.decodeFromString(NetworkExtensionStore.ExtensionList.serializer(), text).toListings()
                ExtensionKind.ANIME -> json.decodeFromString(AnimeNetworkExtensionStore.ExtensionList.serializer(), text).toListings()
            }
        }
    }

    /** A legacy `index.min.json`; APK and icon URLs are relative to [storeBaseUrl]. */
    fun parseLegacyList(
        text: String,
        storeBaseUrl: String,
    ): List<ExtensionListing> =
        decode("legacy index.min.json") {
            json.decodeFromString(ListSerializer(NetworkLegacyExtension.serializer()), text).map { it.toListing(storeBaseUrl) }
        }

    private fun parseV2Store(
        text: String,
        kind: ExtensionKind,
    ): ParsedIndex.Store =
        when (kind) {
            ExtensionKind.MANGA -> json.decodeFromString(NetworkExtensionStore.serializer(), text).toParsedStore()
            ExtensionKind.ANIME -> json.decodeFromString(AnimeNetworkExtensionStore.serializer(), text).toParsedStore()
        }

    private fun ByteArray.toText(): String = toString(Charsets.UTF_8).removePrefix("﻿")

    private inline fun <T> decode(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw MadoyomiException(ErrorCode.REPO_FORMAT_MISMATCH, "Not a $what: ${e.message}", cause = e)
        }

    companion object {
        /** The same settings as upstream's injected `Json` (`AppModule`). */
        val defaultJson =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

internal fun NetworkLegacyExtensionRepo.toLegacyRepo() =
    ParsedIndex.LegacyRepo(
        meta =
            RepoStoreMeta(
                name = meta.name,
                badgeLabel = meta.shortName ?: meta.name,
                signingKey = meta.signingKeyFingerprint,
                website = meta.website,
            ),
        indexV2 = indexV2,
    )

/** Upstream's mapping (`NetworkLegacyExtension.toExtensionInfo`), plus Aniyomi's name prefix and torrent flag. */
internal fun NetworkLegacyExtension.toListing(storeBaseUrl: String): ExtensionListing {
    val contentWarning = if (nsfw == 1) ContentWarning.MIXED else ContentWarning.SAFE
    val listedSources = sources
    return ExtensionListing(
        pkg = pkg,
        name = name.substringAfter("Tachiyomi: ").substringAfter("Aniyomi: "),
        versionCode = code,
        versionName = version,
        lang = lang,
        apkUrl = "$storeBaseUrl/apk/$apk",
        iconUrl = "$storeBaseUrl/icon/$pkg.png",
        jarUrl = null,
        lib = version.substringBeforeLast('.'),
        contentWarning = contentWarning,
        isTorrent = torrent == 1,
        sources =
            if (listedSources.isNullOrEmpty()) {
                // upstream names the single fallback source after the raw (prefixed) extension name
                listOf(ListingSource(id = 0, name = name, lang = lang, baseUrl = ""))
            } else {
                listedSources.map { ListingSource(id = it.id, name = it.name, lang = it.lang, baseUrl = it.baseUrl) }
            },
    )
}
