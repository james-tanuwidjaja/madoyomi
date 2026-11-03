package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.impl.extension.Extension

/**
 * Install/update/uninstall by kind, for the GraphQL mutations. Manga goes through upstream's `Extension` entry points
 * (which delegate to [ExtensionLifecycle] and keep upstream's bookkeeping, e.g. the update map); anime goes to the
 * lifecycle directly.
 */
object ExtensionActions {
    suspend fun install(
        pkg: String,
        kind: ExtensionKind,
    ) {
        when (kind) {
            ExtensionKind.MANGA -> Extension.installExtension(pkg)
            ExtensionKind.ANIME -> ExtensionLifecycle.default.install(pkg, kind)
        }
    }

    suspend fun update(
        pkg: String,
        kind: ExtensionKind,
    ) {
        when (kind) {
            ExtensionKind.MANGA -> Extension.updateExtension(pkg)
            ExtensionKind.ANIME -> ExtensionLifecycle.default.update(pkg, kind)
        }
    }

    suspend fun uninstall(
        pkg: String,
        kind: ExtensionKind,
    ) {
        when (kind) {
            ExtensionKind.MANGA -> Extension.uninstallExtension(pkg)
            ExtensionKind.ANIME -> ExtensionLifecycle.default.uninstall(pkg, kind)
        }
    }
}
