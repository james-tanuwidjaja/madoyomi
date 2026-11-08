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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Where mpv comes from (Phase 4 §9): the override, the property, `MADOYOMI_MPV`, then `PATH`; and its config dir. */
class MpvLocatorTest {
    private val root: File = Files.createTempDirectory("mpv-locator").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun mpvIn(
        dir: String,
        name: String = "mpv.exe",
    ): File = File(root, dir).apply { mkdirs() }.resolve(name).apply { writeText("") }

    private fun noProperty(name: String): String? = null

    @Test
    fun `an explicit path wins - the settings, then the property, then MADOYOMI_MPV`() {
        val settings = mpvIn("settings")
        val property = mpvIn("property")
        val env = mpvIn("env")
        val onPath = mpvIn("path")
        val envOf = mapOf("MADOYOMI_MPV" to env.path, "PATH" to onPath.parent)

        assertEquals(settings, MpvLocator.locate(settings.path, { property.path }, envOf::get, windows = true))
        assertEquals(property, MpvLocator.locate(null, { property.path }, envOf::get, windows = true))
        assertEquals(env, MpvLocator.locate(" ", ::noProperty, envOf::get, windows = true))
    }

    @Test
    fun `an explicit path that isn't there is an error, with where it came from`() {
        val missing = File(root, "nope/mpv.exe").path
        val e =
            assertFailsWith<MadoyomiException> {
                MpvLocator.locate(null, ::noProperty, mapOf("MADOYOMI_MPV" to missing, "PATH" to mpvIn("path").parent)::get, true)
            }
        assertEquals(ErrorCode.PLAYER_NOT_FOUND, e.code)
        assertEquals(File(missing).absolutePath, e.details["path"])
        assertEquals("MADOYOMI_MPV", e.details["origin"])
    }

    @Test
    fun `otherwise mpv exe is looked for on PATH, in order, quoted entries too`() {
        val first = mpvIn("Program Files/MPV Player")
        mpvIn("later")
        mpvIn("console", "mpv.com")
        val path =
            listOf(File(root, "console").path, "\"${first.parent}\"", "", File(root, "later").path)
                .joinToString(File.pathSeparator)
        assertEquals(first, MpvLocator.locate(null, ::noProperty, mapOf("PATH" to path)::get, windows = true))
        // elsewhere the executable is plain mpv (PATH is split by the platform's separator either way)
        val unix = mpvIn("bin", "mpv")
        assertEquals(unix, MpvLocator.locate(null, ::noProperty, mapOf("PATH" to unix.parent)::get, windows = false))
    }

    @Test
    fun `no mpv anywhere is PLAYER_NOT_FOUND`() {
        val e =
            assertFailsWith<MadoyomiException> {
                MpvLocator.locate(null, ::noProperty, mapOf("PATH" to File(root, "empty").path)::get, windows = true)
            }
        assertEquals(ErrorCode.PLAYER_NOT_FOUND, e.code)
        assertEquals("mpv.exe", e.details["searched"])
    }

    @Test
    fun `the config dir is MADOYOMI_PLAYER_DIR when it exists, else one in the data dir`() {
        val player = File(root, "repo/player").apply { mkdirs() }
        val data = File(root, "data")
        assertEquals(player.absoluteFile, PlayerDirs.configDir(data, mapOf("MADOYOMI_PLAYER_DIR" to player.path)::get))

        val fallback = PlayerDirs.configDir(data, mapOf("MADOYOMI_PLAYER_DIR" to File(root, "missing").path)::get)
        assertEquals(File(data, "player").absoluteFile, fallback)
        assertEquals(true, fallback.isDirectory)
    }

    @Test
    fun `mpv doesn't inherit the API token`() {
        val env = mutableMapOf("MADOYOMI_TOKEN" to "secret", "Madoyomi_Token" to "secret", "PATH" to "C:/bin")
        PlaybackSessions.scrubEnvironment(env)
        assertEquals(mapOf("PATH" to "C:/bin"), env)
    }
}
