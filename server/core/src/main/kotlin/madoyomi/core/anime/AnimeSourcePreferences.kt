package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.graphql.mutations.SourceMutation.SourcePreferenceChange
import suwayomi.tachidesk.graphql.types.Preference
import suwayomi.tachidesk.graphql.types.preferenceOf
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import androidx.preference.Preference as SourcePreference

/**
 * An anime source's settings screen in the shape of upstream's GraphQL `Preference` union, changed with upstream's
 * `SourcePreferenceChange` (both types are upstream's, and so is `preferenceOf`: anime and manga extensions build
 * their screens from the same AndroidCompat `androidx.preference` classes).
 *
 * The values live in the source's own `SharedPreferences` (`source_<id>`, `settings/source_<id>.xml`), which is what
 * the extension reads. Differences from upstream's `Source.getSourcePreferencesRaw`/`setSourcePreference`:
 * - the screen is built for every call, so a change no longer needs a read of the preferences earlier in the same
 *   process (upstream's cached screen was `null` then);
 * - a change follows Android: the extension's change listener runs first and the value is only saved if it agrees
 *   (upstream saved first and ignored the listener's answer);
 * - a change that doesn't fit the preference is `BAD_REQUEST`.
 *
 * As upstream does, a change drops the source from the cache, so its next use is a new instance that reads the new
 * values (extensions often read a preference once, e.g. into a `by lazy` base URL).
 */
object AnimeSourcePreferences {
    private val context: CustomContext by injectLazy()

    /** The preferences of the source [sourceId]; empty if it has no settings. */
    suspend fun preferences(sourceId: Long): List<Preference> = preferencesOf(AnimeSourceAccess.require(sourceId))

    fun preferencesOf(source: AnimeSource): List<Preference> {
        val configurable = source as? ConfigurableAnimeSource ?: return emptyList()
        val screen = screenOf(configurable)
        return AnimeSourceCalls.callBlocking(source, "preferences") { screen.preferences.map(::preferenceOf) }
    }

    /**
     * Applies [change] to the source [sourceId]'s preference at `change.position`, and returns the preferences
     * afterwards. A disabled preference is left as it is (upstream's rule).
     */
    suspend fun update(
        sourceId: Long,
        change: SourcePreferenceChange,
    ): List<Preference> {
        val source = AnimeSourceAccess.require(sourceId)
        val configurable =
            source as? ConfigurableAnimeSource
                ?: throw MadoyomiException(
                    ErrorCode.BAD_REQUEST,
                    "Anime source $sourceId has no preferences",
                    mapOf("sourceId" to sourceId),
                )
        val preferences = screenOf(configurable).preferences
        val preference =
            preferences.getOrNull(change.position)
                ?: throw badChange(change.position, "there is no preference at position ${change.position} (${preferences.size})")

        if (preference.isEnabled) {
            val value = valueFor(preference, change)
            AnimeSourceCalls.callBlocking(source, "setPreference") {
                if (preference.callChangeListener(value)) preference.saveNewValue(value)
            }
            AnimeGetSource.unregisterSource(sourceId)
        }
        // the old instance is fine for this: the values come from the SharedPreferences
        return preferencesOf(source)
    }

    private fun screenOf(source: ConfigurableAnimeSource): PreferenceScreen =
        AnimeSourceCalls.callBlocking(source, "preferences") {
            PreferenceScreen(context).also { screen ->
                screen.sharedPreferences = source.getSourcePreferences()
                source.setupPreferenceScreen(screen)
            }
        }

    // the field of the change that matches the preference's type, as in upstream's updateSourcePreference
    private fun valueFor(
        preference: SourcePreference,
        change: SourcePreferenceChange,
    ): Any {
        val (field, value) =
            when (preference) {
                is SwitchPreferenceCompat -> "switchState" to change.switchState
                is CheckBoxPreference -> "checkBoxState" to change.checkBoxState
                is EditTextPreference -> "editTextState" to change.editTextState
                is ListPreference -> "listState" to change.listState
                is MultiSelectListPreference -> "multiSelectState" to change.multiSelectState?.toSet()
                else -> throw badChange(change.position, "${preference.javaClass.simpleName} can't be changed")
            }
        return value ?: throw badChange(change.position, "the ${preference.javaClass.simpleName} \"${preference.key}\" needs $field")
    }

    private fun badChange(
        position: Int,
        message: String,
    ) = MadoyomiException(ErrorCode.BAD_REQUEST, "Preference change at $position: $message", mapOf("position" to position))
}
