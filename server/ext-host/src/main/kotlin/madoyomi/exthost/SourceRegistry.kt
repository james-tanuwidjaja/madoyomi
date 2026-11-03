package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets `ext-host` code (which can't see the source cache in `core`) ask whoever caches
 * instantiated sources to drop them.
 *
 * Upstream's `NetworkHelper` called `GetSource.unregisterAllSources()` directly when the default
 * User-Agent changed, so sources rebuild their headers. After the module split `GetSource` lives in
 * `core`, above `ext-host`, so it registers itself here instead (see server/UPSTREAM.md).
 */
object SourceRegistry {
    private val unregisterAllListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Registers [listener] to run on every [unregisterAllSources] call. */
    fun onUnregisterAll(listener: () -> Unit) {
        unregisterAllListeners += listener
    }

    /** Asks every registered source cache to drop its sources. */
    fun unregisterAllSources() {
        unregisterAllListeners.forEach { it() }
    }
}
