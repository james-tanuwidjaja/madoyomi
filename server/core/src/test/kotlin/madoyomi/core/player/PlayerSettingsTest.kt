package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The player settings' file (Phase 4 Stage 5): defaults, changes, validation, and a file edited by hand. */
class PlayerSettingsTest {
    private val dir: File = Files.createTempDirectory("player-settings").toFile()
    private val file = File(dir, PlayerSettingsStore.FILE_NAME)

    @BeforeTest
    fun useScratchFile() {
        PlayerSettingsStore.fileOverride = file
        PlayerSettingsStore.reload()
    }

    @AfterTest
    fun cleanUp() {
        PlayerSettingsStore.fileOverride = null
        PlayerSettingsStore.reload()
        dir.deleteRecursively()
    }

    @Test
    fun `without a file the defaults - 85 percent, the source's order and tracks, no mpv path`() {
        assertEquals(PlayerSettings(), PlayerSettingsStore.current())
        assertEquals(0.85, PlayerSettingsStore.current().watchedThreshold)
        assertFalse(PlayerSettings().sortsVideos)
        assertFalse(file.exists())
    }

    @Test
    fun `a change is written and read back, and the fields not given stay`() {
        PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = 0.9, preferredQuality = QualityPreference.P720))
        val after =
            PlayerSettingsStore.update(
                PlayerSettings.Patch(preferredServer = "  VidStreaming ", subtitleLanguage = "OFF", mpvPath = "\"C:\\mpv\\mpv.exe\""),
            )
        val expected =
            PlayerSettings(
                watchedThreshold = 0.9,
                preferredQuality = QualityPreference.P720,
                preferredServer = "VidStreaming",
                subtitleLanguage = "off",
                mpvPath = "C:\\mpv\\mpv.exe",
            )
        assertEquals(expected, after)
        assertTrue(file.readText().contains("\"preferredQuality\": \"P720\""), file.readText())
        assertFalse(File(dir, "${file.name}.tmp").exists())

        PlayerSettingsStore.reload()
        assertEquals(expected, PlayerSettingsStore.current())
        assertTrue(expected.sortsVideos)
    }

    @Test
    fun `the threshold is 50 to 95 percent, rounded to a step of 5`() {
        assertEquals(0.85, PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = 0.86)).watchedThreshold)
        assertEquals(0.5, PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = 0.5)).watchedThreshold)
        assertEquals(0.95, PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = 0.95)).watchedThreshold)
        listOf(0.45, 0.96, 1.0, Double.NaN).forEach { v ->
            val e = assertFailsWith<MadoyomiException> { PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = v)) }
            assertEquals(ErrorCode.BAD_REQUEST, e.code)
            assertEquals("watchedThreshold", e.details["field"])
        }
        assertEquals(0.95, PlayerSettingsStore.current().watchedThreshold)
    }

    @Test
    fun `a wrong field changes nothing, not even the right ones given with it`() {
        val e =
            assertFailsWith<MadoyomiException> {
                PlayerSettingsStore.update(PlayerSettings.Patch(watchedThreshold = 0.6, audioLanguage = "xx"))
            }
        assertEquals("audioLanguage", e.details["field"])
        assertFailsWith<MadoyomiException> { PlayerSettingsStore.update(PlayerSettings.Patch(audioLanguage = "off")) }
        assertFailsWith<MadoyomiException> { PlayerSettingsStore.update(PlayerSettings.Patch(preferredServer = "a\nb")) }
        assertFailsWith<MadoyomiException> { PlayerSettingsStore.update(PlayerSettings.Patch(mpvPath = "C:\\mpv\r\n.exe")) }
        assertFailsWith<MadoyomiException> { PlayerSettingsStore.update(PlayerSettings.Patch(preferredServer = "x".repeat(101))) }
        assertEquals(PlayerSettings(), PlayerSettingsStore.current())
        assertFalse(file.exists())
    }

    @Test
    fun `a file edited by hand - unknown keys ignored, wrong values the defaults, unreadable the defaults`() {
        file.writeText(
            """{"watchedThreshold": 2.0, "preferredQuality": "P9999", "preferredServer": "Filemoon", "audioLanguage": "ja",
               "subtitleLanguage": "klingon", "somethingNew": true}""",
        )
        PlayerSettingsStore.reload()
        assertEquals(PlayerSettings(preferredServer = "Filemoon", audioLanguage = "ja"), PlayerSettingsStore.current())

        file.writeText("{not json")
        PlayerSettingsStore.reload()
        assertEquals(PlayerSettings(), PlayerSettingsStore.current())
        // the next change replaces it
        PlayerSettingsStore.update(PlayerSettings.Patch(audioLanguage = "en"))
        PlayerSettingsStore.reload()
        assertEquals("en", PlayerSettingsStore.current().audioLanguage)
    }
}
