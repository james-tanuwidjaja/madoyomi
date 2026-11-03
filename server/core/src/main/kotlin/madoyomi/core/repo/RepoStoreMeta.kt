package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore

/** A repo's own metadata: who publishes it and which key signs its APKs. */
data class RepoStoreMeta(
    val name: String,
    val badgeLabel: String,
    /** SHA-256 of the signing certificate, as the index states it. */
    val signingKey: String,
    val website: String,
    val discord: String? = null,
)

/** [signingKey] as stored in `TRUSTED_SIGNATURE.hash`: lowercase hex, separators removed; null when blank. */
fun normalizeSigningKey(signingKey: String?): String? =
    signingKey
        ?.lowercase()
        ?.filterNot { it == ':' || it.isWhitespace() }
        ?.ifBlank { null }

fun RepoStoreMeta.normalizedSigningKey(): String? = normalizeSigningKey(signingKey)

/** The result of fetching a repo: where it really lives, its metadata and its listings. */
data class FetchedRepo(
    /** The URL the repo resolved to (after `repo.json` and `index_v2` redirects). This is the stored index URL. */
    val indexUrl: String,
    /** A legacy (`repo.json` + `index.min.json`) repo. */
    val isLegacy: Boolean,
    /** A v2 index that keeps its list in a separate document. */
    val extensionListUrl: String?,
    val meta: RepoStoreMeta,
    val extensions: List<ExtensionListing>,
)

/** Upstream's store model, for the code paths that still use it. */
fun FetchedRepo.toExtensionStore(): ExtensionStore =
    ExtensionStore(
        indexUrl = indexUrl,
        name = meta.name,
        badgeLabel = meta.badgeLabel,
        signingKey = meta.signingKey,
        contact = ExtensionStore.Contact(website = meta.website, discord = meta.discord),
        isLegacy = isLegacy,
        extensionListUrl = extensionListUrl,
    )
