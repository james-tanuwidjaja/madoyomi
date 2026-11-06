package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.lang.ref.WeakReference
import kotlin.test.assertNull

/** Collection checks: does nothing but a [WeakReference] keep an object (a source, a class loader) any more? */
object GcAssert {
    /** Runs the GC until [ref] is cleared, up to [tries] times; true if it was. */
    fun collected(
        ref: WeakReference<*>,
        tries: Int = 50,
    ): Boolean {
        repeat(tries) {
            if (ref.get() == null) return true
            System.gc()
            Thread.sleep(20)
        }
        return ref.get() == null
    }

    fun assertCollected(
        ref: WeakReference<*>,
        message: String,
    ) {
        collected(ref)
        assertNull(ref.get(), message)
    }
}
