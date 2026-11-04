package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Lets the extension class loader (`ChildFirstURLClassLoader`, in `ext-host`) report classes an extension asked for
 * that exist nowhere (not on the host, not in the jar) to `core`'s compat log, which can't be seen from here.
 *
 * The loader calls [classNotFound] just before it throws the `ClassNotFoundException`. Many of those are harmless
 * probes (`Class.forName` with a fallback), so the listener filters; a reference that really breaks the extension also
 * shows up as a `NoClassDefFoundError` where the code runs.
 */
object ExtensionClassLoadHook {
    @Volatile
    var listener: ((jar: Path, className: String) -> Unit)? = null

    /** [jarUrl] is the loader's jar (its first URL), [className] the binary name that wasn't found. */
    fun classNotFound(
        jarUrl: URL?,
        className: String,
    ) {
        val l = listener ?: return
        // a broken listener must never turn a ClassNotFoundException into something else
        runCatching { l(Paths.get(jarUrl!!.toURI()), className) }
    }
}
