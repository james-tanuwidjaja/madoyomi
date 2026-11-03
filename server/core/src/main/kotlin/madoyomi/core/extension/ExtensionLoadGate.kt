package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

/**
 * The trust gate in front of the only place extension code is loaded (upstream's
 * `PackageTools.loadExtensionSourcesWithLockHeld`, reached from `GetSource` and from install).
 *
 * A jar may only be loaded when it has a *permit*, the signing-certificate hash of the APK it was converted from,
 * and that hash is in `TRUSTED_SIGNATURE` at the moment of loading. Madoyomi's install records the permit from
 * apksig's result; `GetSource` records it from the installed row's `signature_hash`. A jar nobody vouched for
 * (an upstream install with no hash, or any path that bypasses Madoyomi's install) is refused.
 */
object ExtensionLoadGate {
    private val permits = ConcurrentHashMap<String, String>()

    /** Records that [jar] was converted from an APK signed by [hash]. A null hash records nothing (and drops any). */
    fun permit(
        jar: Path,
        hash: String?,
    ) {
        val key = jar.absolutePathString()
        if (hash == null) permits.remove(key) else permits[key] = hash
    }

    fun revoke(jar: Path) {
        permits.remove(jar.absolutePathString())
    }

    /** Throws [ErrorCode.UNTRUSTED] unless [jar] may be loaded now. */
    fun check(jar: Path) {
        val path = jar.absolutePathString()
        val hash =
            permits[path]
                ?: throw MadoyomiException(
                    ErrorCode.UNTRUSTED,
                    "Refusing to load $path: it has no verified signature (reinstall the extension)",
                    mapOf("jar" to path),
                )
        if (!SignatureTrust.isTrusted(hash)) {
            throw MadoyomiException(
                ErrorCode.UNTRUSTED,
                "Refusing to load $path: its signing certificate $hash is not trusted",
                mapOf("jar" to path, "signatureHash" to hash),
            )
        }
    }

    /** For tests (core's and api's). */
    fun clear() = permits.clear()
}
