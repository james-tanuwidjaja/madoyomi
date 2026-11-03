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
 */
enum class ExtensionKind {
    MANGA,
    ANIME,
}
