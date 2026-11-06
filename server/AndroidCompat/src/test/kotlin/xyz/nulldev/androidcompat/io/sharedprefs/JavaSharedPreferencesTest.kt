package xyz.nulldev.androidcompat.io.sharedprefs

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.SharedPreferences
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Madoyomi: change listeners are held weakly, as on Android, so an uninstalled extension's listener doesn't stay. */
class JavaSharedPreferencesTest {
    private fun prefs() = JavaSharedPreferences("madoyomi-test-${UUID.randomUUID()}")

    @Test
    fun `a registered listener hears each change until it is unregistered`() {
        val prefs = prefs()
        val heard = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key -> if (p === prefs) heard += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("a", "1").commit()
        prefs.edit().putBoolean("b", true).commit()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("a", "2").commit()
        assertEquals(listOf<String?>("a", "b"), heard)
        prefs.deleteAll()
    }

    /** Registers a listener nothing else references, as an extension's own listener is once the extension is gone. */
    private fun registerUnreferenced(prefs: SharedPreferences): WeakReference<SharedPreferences.OnSharedPreferenceChangeListener> {
        // capturing, so it is a new object (a non-capturing lambda is a cached singleton that never goes away)
        val owner = Any()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> owner.hashCode() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return WeakReference(listener)
    }

    @Test
    fun `the preferences don't keep a listener alive`() {
        val prefs = prefs()
        val ref = registerUnreferenced(prefs)
        repeat(50) {
            if (ref.get() == null) return@repeat
            System.gc()
            Thread.sleep(20)
        }
        assertNull(ref.get(), "only the registrant keeps its listener (upstream kept every one for the process' lifetime)")
        prefs.edit().putString("a", "1").commit() // and notifying after it is gone is fine
        prefs.deleteAll()
    }
}
