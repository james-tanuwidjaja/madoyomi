package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.protobuf.ProtoBuf
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning

/**
 * The v2 extension-store index as protobuf (`index.pb`), e.g. Keiyoushi (manga) and yuzono (anime).
 *
 * Manga uses upstream's Mihon DTO ([NetworkExtensionStore]), anime the Aniyomi variant ([AnimeNetworkExtensionStore]).
 * If the kind's schema doesn't decode, the other one is tried, so a repo added as the wrong type is reported as a
 * type mismatch (its package names) rather than as an unreadable index. Unknown fields are skipped.
 */
class ProtobufIndexParser(
    private val protoBuf: ProtoBuf = ProtoBuf,
) : RepoIndexParser {
    override val format = RepoIndexFormat.PROTOBUF

    override fun parseIndex(
        body: ByteArray,
        indexUrl: String,
        kind: ExtensionKind,
    ): ParsedIndex.Store =
        decodeEither(
            decompressIfGzipped(body),
            kind,
            manga = { protoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), it).toParsedStore() },
            anime = { protoBuf.decodeFromByteArray(AnimeNetworkExtensionStore.serializer(), it).toParsedStore() },
            what = "protobuf index ($indexUrl)",
        )

    override fun parseExtensionList(
        body: ByteArray,
        kind: ExtensionKind,
    ): List<ExtensionListing> =
        decodeEither(
            decompressIfGzipped(body),
            kind,
            manga = { protoBuf.decodeFromByteArray(NetworkExtensionStore.ExtensionList.serializer(), it).toListings() },
            anime = { protoBuf.decodeFromByteArray(AnimeNetworkExtensionStore.ExtensionList.serializer(), it).toListings() },
            what = "protobuf extension list",
        )

    private fun <T> decodeEither(
        bytes: ByteArray,
        kind: ExtensionKind,
        manga: (ByteArray) -> T,
        anime: (ByteArray) -> T,
        what: String,
    ): T {
        val (first, second) = if (kind == ExtensionKind.ANIME) anime to manga else manga to anime
        // SerializationException extends IllegalArgumentException, but truncated input can also surface as
        // IndexOutOfBoundsException and friends, so any RuntimeException means "not this schema"
        val firstError =
            try {
                return first(bytes)
            } catch (e: RuntimeException) {
                e
            }
        return try {
            second(bytes)
        } catch (_: RuntimeException) {
            throw MadoyomiException(ErrorCode.REPO_FORMAT_MISMATCH, "Not a $what: ${firstError.message}", cause = firstError)
        }
    }
}

internal fun NetworkExtensionStore.ContentWarning.toContentWarning(): ContentWarning =
    when (this) {
        NetworkExtensionStore.ContentWarning.SAFE,
        NetworkExtensionStore.ContentWarning.UNSPECIFIED,
        -> ContentWarning.SAFE

        NetworkExtensionStore.ContentWarning.MIXED -> ContentWarning.MIXED

        NetworkExtensionStore.ContentWarning.NSFW -> ContentWarning.NSFW
    }

/** `"all"` unless every source has the same language (upstream's rule). */
private fun languageOf(sourceLanguages: List<String>): String = sourceLanguages.toSet().singleOrNull() ?: "all"

private fun NetworkExtensionStore.Contact.toMeta(
    name: String,
    badgeLabel: String,
    signingKey: String,
) = RepoStoreMeta(name = name, badgeLabel = badgeLabel, signingKey = signingKey, website = website, discord = discord)

internal fun NetworkExtensionStore.toParsedStore() =
    ParsedIndex.Store(
        meta = contact.toMeta(name, badgeLabel, signingKey),
        extensions = extensionList?.toListings(),
        extensionListUrl = extensionListUrl,
    )

internal fun AnimeNetworkExtensionStore.toParsedStore() =
    ParsedIndex.Store(
        meta = contact.toMeta(name, badgeLabel, signingKey),
        extensions = extensionList?.toListings(),
        extensionListUrl = extensionListUrl,
    )

internal fun NetworkExtensionStore.ExtensionList.toListings(): List<ExtensionListing> =
    extensions.map { ext ->
        ExtensionListing(
            pkg = ext.packageName,
            name = ext.name,
            versionCode = ext.versionCode,
            versionName = ext.versionName,
            lang = languageOf(ext.sources.map { it.language }),
            apkUrl = ext.resources.apkUrl,
            iconUrl = ext.resources.iconUrl,
            jarUrl = ext.resources.jarUrl,
            lib = ext.extensionLib,
            contentWarning = ext.contentWarning.toContentWarning(),
            isTorrent = false,
            sources = ext.sources.map { ListingSource(it.id, it.name, it.language, it.homeUrl, it.message) },
        )
    }

internal fun AnimeNetworkExtensionStore.ExtensionList.toListings(): List<ExtensionListing> =
    extensions.map { ext ->
        ExtensionListing(
            pkg = ext.packageName,
            name = ext.name,
            versionCode = ext.versionCode,
            versionName = ext.versionName,
            lang = languageOf(ext.sources.map { it.language }),
            apkUrl = ext.resources.apkUrl,
            iconUrl = ext.resources.iconUrl,
            jarUrl = ext.resources.jarUrl,
            lib = ext.extensionLib,
            contentWarning = ext.contentWarning.toContentWarning(),
            isTorrent = ext.isTorrent,
            sources = ext.sources.map { ListingSource(it.id, it.name, it.language, it.homeUrl, it.message) },
        )
    }
