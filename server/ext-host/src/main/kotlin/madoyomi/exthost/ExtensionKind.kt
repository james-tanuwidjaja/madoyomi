package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * The two kinds of extension Madoyomi runs: Mihon/Tachiyomi manga extensions and Aniyomi anime
 * extensions.
 *
 * Repos (`EXTENSIONSTORE.type`) and extensions (`EXTENSION.type`) store it by name. Sources don't:
 * they are typed by table (manga in `SOURCE`, anime in `ANIME_SOURCE`).
 *
 * Each kind also knows how its APKs describe themselves (the `uses-feature` and `meta-data` keys of the
 * manifest) and which extensions-lib versions Madoyomi expects.
 */
enum class ExtensionKind(
    /** The `uses-feature` every extension APK of this kind declares. */
    val feature: String,
    /** `meta-data` key of the main class (a `Source`/`SourceFactory`, relative to the package when it starts with `.`). */
    val classKey: String,
    /** Legacy `meta-data` NSFW flag (`1`), read when [CONTENT_WARNING_KEY] is missing. */
    val nsfwKey: String,
    /** `meta-data` torrent flag (`1`), Aniyomi only. */
    val torrentKey: String?,
    /** Prefix of the manifest label (`Tachiyomi: MangaPill`), stripped when [NAME_KEY] is missing. */
    val labelPrefix: String,
    /**
     * The extensions-lib versions expected (manga: upstream's `PackageTools.LIB_VERSION_MIN..MAX`; anime: the
     * versions yuzono builds against). Outside it is only a warning: the manifest lib doesn't describe the binary
     * API (Phase 0).
     */
    val libRange: ClosedFloatingPointRange<Double>,
    /** Directory under `<data>/extensions/` that holds this kind's jars. */
    val dirName: String,
) {
    MANGA(
        feature = "tachiyomi.extension",
        classKey = "tachiyomi.extension.class",
        nsfwKey = "tachiyomi.extension.nsfw",
        torrentKey = null,
        labelPrefix = "Tachiyomi: ",
        libRange = 1.3..1.6,
        dirName = "manga",
    ),
    ANIME(
        feature = "tachiyomi.animeextension",
        classKey = "tachiyomi.animeextension.class",
        nsfwKey = "tachiyomi.animeextension.nsfw",
        torrentKey = "tachiyomi.animeextension.torrent",
        labelPrefix = "Aniyomi: ",
        libRange = 12.0..16.0,
        dirName = "anime",
    ),
    ;

    companion object {
        /** `meta-data`: the extension's display name. */
        const val NAME_KEY = "tachiyomix.name"

        /** `meta-data`: the extensions-lib version the extension was built against. */
        const val LIB_KEY = "tachiyomix.extensionLib"

        /** `meta-data`: 0 safe, 1 mixed, 2 NSFW. */
        const val CONTENT_WARNING_KEY = "tachiyomix.contentWarning"

        /** The kind whose [feature] is among [features], or null (not an extension, or both). */
        fun fromFeatures(features: Collection<String>): ExtensionKind? = entries.filter { it.feature in features }.singleOrNull()
    }
}
