package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource

/**
 * One extension as a repo index lists it, whatever the index format (see [RepoIndexParser]).
 * Both the anime and the manga repo manager work on this model.
 */
data class ExtensionListing(
    val pkg: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    /** The extension's language, or `"all"` when its sources cover several. */
    val lang: String,
    val apkUrl: String,
    val iconUrl: String,
    /** Keiyoushi's prebuilt jar (protobuf field 501). Kept for upstream's manga install until Stage 6 ignores it. */
    val jarUrl: String?,
    /** The extensions-lib version the index states, e.g. `"1.6"` or `"14"`. */
    val lib: String,
    val contentWarning: ContentWarning,
    /** From the legacy JSON `torrent` flag. Formats without one report false (the APK manifest is authoritative). */
    val isTorrent: Boolean,
    val sources: List<ListingSource>,
)

data class ListingSource(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val message: String? = null,
)

/** Upstream's model of a listing, as its extension-table sync expects it. */
fun ExtensionListing.toExtensionInfo(storeIndexUrl: String): ExtensionInfo =
    ExtensionInfo(
        storeIndexUrl = storeIndexUrl,
        name = name,
        pkgName = pkg,
        apkUrl = apkUrl,
        jarUrl = jarUrl,
        iconUrl = iconUrl,
        extensionLib = lib,
        versionCode = versionCode,
        versionName = versionName,
        lang = lang,
        contentWarning = contentWarning,
        sources =
            sources.map {
                ExtensionSource(
                    id = it.id,
                    name = it.name,
                    lang = it.lang,
                    homeUrl = it.baseUrl,
                    message = it.message,
                    contentWarning = contentWarning,
                )
            },
        isTorrent = isTorrent,
    )

/**
 * Whether [pkg] is an extension package of this kind. Aniyomi packages contain `.animeextension.`, Mihon packages
 * `.extension.`. The two never overlap: in `.animeextension.` the character before `extension` is `e`, not `.`.
 */
fun ExtensionKind.matchesPackage(pkg: String): Boolean =
    when (this) {
        ExtensionKind.ANIME -> ".animeextension." in pkg
        ExtensionKind.MANGA -> ".extension." in pkg
    }
