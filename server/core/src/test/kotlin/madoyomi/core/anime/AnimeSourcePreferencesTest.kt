package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import kotlinx.coroutines.runBlocking
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.TestApks
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.graphql.mutations.SourceMutation.SourcePreferenceChange
import suwayomi.tachidesk.graphql.types.EditTextPreference
import suwayomi.tachidesk.graphql.types.ListPreference
import suwayomi.tachidesk.graphql.types.Preference
import suwayomi.tachidesk.graphql.types.SwitchPreference
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [AnimeSourcePreferences] on AnimePahe's real settings screen (3 lists, 3 switches, 1 edit text). */
class AnimeSourcePreferencesTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            ApplicationTest.testingSetup()
        }
    }

    @TempDir
    lateinit var tmp: Path

    private val real by lazy { RealAnimeSources(tmp) }

    @AfterEach
    fun closeLoaders() {
        real.close()
    }

    /** AnimePahe, registered as the cached source of its id, with empty preferences; cleaned up after [block]. */
    private fun withAnimePahe(block: (ConfigurableAnimeSource) -> Unit) {
        val source = real.testApk(TestApks.ANIMEPAHE).single() as ConfigurableAnimeSource
        val prefs = source.getSourcePreferences()
        prefs.edit().clear().apply()
        AnimeGetSource.registerSource(source)
        try {
            block(source)
        } finally {
            AnimeGetSource.unregisterSource(source.id)
            prefs.edit().clear().apply()
        }
    }

    private fun update(
        source: ConfigurableAnimeSource,
        change: SourcePreferenceChange,
    ): List<Preference> = runBlocking { AnimeSourcePreferences.update(source.id, change) }

    @Test
    fun `the screen maps to upstream's preference union`() =
        withAnimePahe { source ->
            val preferences = runBlocking { AnimeSourcePreferences.preferences(source.id) }
            println("AnimePahe preferences: " + preferences.joinToString { "${it.javaClass.simpleName}(${keyOf(it)})" })

            assertEquals(7, preferences.size)
            assertEquals(3, preferences.count { it is ListPreference })
            assertEquals(3, preferences.count { it is SwitchPreference })
            assertEquals(1, preferences.count { it is EditTextPreference })
            preferences.filterIsInstance<ListPreference>().forEach {
                assertTrue(it.key!!.isNotEmpty())
                assertTrue(it.entries.isNotEmpty(), "${it.key} has entries")
                assertEquals(it.entries.size, it.entryValues.size)
                assertEquals(it.default, it.currentValue, "${it.key}: nothing stored yet, so the default")
            }
        }

    @Test
    fun `a change is persisted in the source's preferences, visible on the next read, and drops the cached source`() =
        withAnimePahe { source ->
            // no read of the preferences before the change (upstream's cached screen needed one)
            val before = AnimeSourcePreferences.preferencesOf(source)
            val listIndex = before.indexOfFirst { it is ListPreference }
            val list = before[listIndex] as ListPreference
            val newValue = list.entryValues.first { it != list.currentValue }

            val after = update(source, SourcePreferenceChange(position = listIndex, listState = newValue))

            assertEquals(newValue, (after[listIndex] as ListPreference).currentValue)
            assertEquals(newValue, source.getSourcePreferences().getString(list.key, null))
            assertNull(AnimeGetSource.cached(source.id), "the next use re-instantiates the source")
            assertEquals(newValue, (AnimeSourcePreferences.preferencesOf(source)[listIndex] as ListPreference).currentValue)

            AnimeGetSource.registerSource(source)
            val switchIndex = after.indexOfFirst { it is SwitchPreference }
            val switch = after[switchIndex] as SwitchPreference
            val wasOn = switch.currentValue ?: switch.default
            val toggled = update(source, SourcePreferenceChange(position = switchIndex, switchState = !wasOn))
            assertEquals(!wasOn, (toggled[switchIndex] as SwitchPreference).currentValue)
            assertEquals(!wasOn, source.getSourcePreferences().getBoolean(switch.key, switch.default))

            AnimeGetSource.registerSource(source)
            val textIndex = after.indexOfFirst { it is EditTextPreference }
            val text = update(source, SourcePreferenceChange(position = textIndex, editTextState = "https://animepahe.example"))
            assertEquals("https://animepahe.example", (text[textIndex] as EditTextPreference).currentValue)
        }

    @Test
    fun `a change that doesn't fit is BAD_REQUEST and changes nothing`() =
        withAnimePahe { source ->
            val preferences = AnimeSourcePreferences.preferencesOf(source)
            val switchIndex = preferences.indexOfFirst { it is SwitchPreference }

            val outOfRange =
                assertFailsWith<MadoyomiException> { update(source, SourcePreferenceChange(position = 99, switchState = true)) }
            val wrongField =
                assertFailsWith<MadoyomiException> { update(source, SourcePreferenceChange(position = switchIndex, listState = "x")) }

            assertEquals(ErrorCode.BAD_REQUEST, outOfRange.code)
            assertEquals(ErrorCode.BAD_REQUEST, wrongField.code)
            assertEquals(switchIndex, wrongField.details["position"])
            assertSame(source, AnimeGetSource.cached(source.id), "nothing changed, so the source stays")
            assertEquals(preferences, AnimeSourcePreferences.preferencesOf(source))
        }

    @Test
    fun `a source without settings has no preferences and refuses changes`() {
        val plain = FakeAnimeSource(id = 7_341L)
        AnimeGetSource.registerSource(plain)
        try {
            assertTrue(runBlocking { AnimeSourcePreferences.preferences(plain.id) }.isEmpty())
            val e =
                assertFailsWith<MadoyomiException> {
                    runBlocking { AnimeSourcePreferences.update(plain.id, SourcePreferenceChange(position = 0, switchState = true)) }
                }
            assertEquals(ErrorCode.BAD_REQUEST, e.code)
        } finally {
            AnimeGetSource.unregisterSource(plain.id)
        }
    }

    private fun keyOf(preference: Preference): String? =
        when (preference) {
            is ListPreference -> preference.key
            is SwitchPreference -> preference.key
            is EditTextPreference -> preference.key
            else -> null
        }
}
