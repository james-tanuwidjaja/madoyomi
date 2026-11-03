package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div

/**
 * Where installed extensions live under `<data>/extensions/`:
 * - Madoyomi installs (verified, with a `signature_hash`): `{manga|anime}/{pkg}/{pkg}-v{versionName}.jar`;
 * - upstream installs (no hash, never verified): upstream's flat `<apkName minus .apk>.jar`;
 * - icons, for both: upstream's cache `icon/{pkg}.*`, which upstream's icon endpoint serves.
 */
object ExtensionFiles {
    private val applicationDirs: ApplicationDirs by injectLazy()

    val extensionsRoot: Path get() = Path(applicationDirs.extensionsRoot)

    val iconDir: Path get() = extensionsRoot / "icon"

    fun pkgDir(
        kind: ExtensionKind,
        pkg: String,
    ): Path {
        require(PKG.matches(pkg)) { "Not a valid package name: $pkg" }
        return extensionsRoot / kind.dirName / pkg
    }

    fun jarPath(
        kind: ExtensionKind,
        pkg: String,
        versionName: String,
    ): Path {
        require(VERSION.matches(versionName)) { "Not a valid version name: $versionName" }
        return pkgDir(kind, pkg) / "$pkg-v$versionName.jar"
    }

    /** Upstream's flat layout (`GetSource`): the jar next to where the APK name points. */
    fun legacyJarPath(apkName: String): Path = extensionsRoot / (apkName.substringBefore(".apk") + ".jar")

    /** The jar of an installed `EXTENSION` row, or null when the row has neither layout's data. */
    fun installedJar(row: ResultRow): Path? =
        if (row[ExtensionTable.signatureHash] != null) {
            jarPath(row[ExtensionTable.type], row[ExtensionTable.pkgName], row[ExtensionTable.versionName])
        } else {
            row[ExtensionTable.apkName]?.let(::legacyJarPath)
        }

    private val PKG = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
    private val VERSION = Regex("""[A-Za-z0-9._+-]+""")
}
