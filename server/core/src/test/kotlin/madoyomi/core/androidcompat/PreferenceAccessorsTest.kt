package madoyomi.core.androidcompat

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.SharedPreferences
import android.os.LocaleList
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `androidx.preference` value accessors and `android.os.LocaleList` that AndroidCompat implements for Madoyomi
 * (android-jar/upstream only had `"Stub!"`): values live in the screen's `SharedPreferences` like on Android, and in
 * the preference object before it is on a screen.
 */
class PreferenceAccessorsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    private val context: CustomContext get() = Injekt.get()
    private lateinit var prefs: SharedPreferences
    private lateinit var screen: PreferenceScreen

    @BeforeEach
    fun setUp() {
        prefs = context.getSharedPreferences("madoyomi_test_${UUID.randomUUID()}", 0)
        screen = PreferenceScreen(context)
        screen.sharedPreferences = prefs
    }

    @AfterEach
    fun tearDown() {
        prefs.edit().clear().apply()
    }

    @Test
    fun `ListPreference reads and persists its value through the screen's preferences`() {
        val pref =
            ListPreference(context).apply {
                key = "quality"
                entries = arrayOf("1080p", "720p", "480p")
                entryValues = arrayOf("1080", "720", "480")
                setDefaultValue("720")
            }
        // not on a screen yet: the default, then a value kept in the object
        assertEquals("720", pref.value)
        pref.value = "480"
        assertEquals("480", pref.value)

        screen.addPreference(pref)
        assertEquals("480", pref.value, "nothing stored yet: the object's value")
        prefs.edit().putString("quality", "1080").apply()
        assertEquals("1080", pref.value)
        assertEquals("1080p", pref.entry)

        pref.setValueIndex(1)
        assertEquals("720", prefs.getString("quality", null))
        assertEquals("720p", pref.entry)
        assertEquals(1, pref.findIndexOfValue("720"))
        assertEquals("720", pref.currentValue, "upstream's serializer reads the same value")
    }

    @Test
    fun `MultiSelectListPreference reads and persists its values`() {
        val pref =
            MultiSelectListPreference(context).apply {
                key = "hosters"
                entries = arrayOf("A", "B", "C")
                entryValues = arrayOf("a", "b", "c")
                setDefaultValue(setOf("a"))
            }
        assertEquals(setOf("a"), pref.values)
        screen.addPreference(pref)
        pref.values = setOf("b", "c")
        assertEquals(setOf("b", "c"), prefs.getStringSet("hosters", null))
        assertEquals(setOf("b", "c"), pref.values)
        assertEquals(2, pref.findIndexOfValue("c"))
        assertEquals(-1, pref.findIndexOfValue("z"))
    }

    @Test
    fun `two-state preferences read and persist isChecked`() {
        listOf(SwitchPreferenceCompat(context), CheckBoxPreference(context)).forEachIndexed { i, pref ->
            pref.key = "flag$i"
            assertEquals(false, pref.isChecked, "TwoStatePreference defaults to false")
            pref.setDefaultValue(true)
            assertEquals(true, pref.isChecked)
            screen.addPreference(pref)
            pref.isChecked = false
            assertEquals(false, prefs.getBoolean("flag$i", true))
            assertEquals(false, pref.isChecked)
        }
    }

    @Test
    fun `a click listener is stored, not a stub`() {
        val pref = Preference(context)
        val listener = Preference.OnPreferenceClickListener { true }
        pref.onPreferenceClickListener = listener
        assertSame(listener, pref.onPreferenceClickListener)
    }

    @Test
    fun `LocaleList is backed by java util Locale`() {
        val default = LocaleList.getDefault()
        assertEquals(1, default.size())
        assertEquals(Locale.getDefault(), default.get(0))
        assertNull(default.get(5))

        val list = LocaleList.forLanguageTags("pt-BR,en")
        assertEquals(2, list.size())
        assertEquals(Locale.forLanguageTag("pt-BR"), list.get(0))
        assertEquals("pt-BR,en", list.toLanguageTags())
        assertEquals(1, list.indexOf(Locale.ENGLISH))
        assertEquals(list, LocaleList(Locale.forLanguageTag("pt-BR"), Locale.ENGLISH))
        assertEquals(Locale.forLanguageTag("en-US"), list.getFirstMatch(arrayOf("en-US", "fr")))
        assertTrue(LocaleList.getEmptyLocaleList().isEmpty)
        assertFailsWith<IllegalArgumentException> { LocaleList(Locale.ENGLISH, Locale.ENGLISH) }
    }
}
