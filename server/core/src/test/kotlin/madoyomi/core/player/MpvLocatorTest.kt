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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where mpv comes from (Phase 4 §9): the player settings' path, the property, `MADOYOMI_MPV`, then `PATH`; its config
 * dir; and the settings' check (Stage 5).
 */
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
        assertEquals("ENV", e.details["origin"])
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

    @Test
    fun `the settings' path comes first, quotes taken off, and a wrong one names the setting`() {
        val settings = mpvIn("settings")
        val envOf = mapOf("MADOYOMI_MPV" to mpvIn("env").path)
        val found = MpvLocator.find("\"${settings.path}\"", ::noProperty, envOf::get, windows = true)
        assertEquals(MpvLocator.Located(settings, MpvLocator.Origin.SETTINGS), found)
        assertEquals(MpvLocator.Origin.ENV, MpvLocator.find("", ::noProperty, envOf::get, windows = true).origin)

        val e =
            assertFailsWith<MadoyomiException> {
                MpvLocator.find(File(root, "gone/mpv.exe").path, ::noProperty, envOf::get, windows = true)
            }
        assertEquals(ErrorCode.PLAYER_NOT_FOUND, e.code)
        assertEquals("SETTINGS", e.details["origin"])
        assertTrue(e.message!!.contains("Settings → Player"), e.message)
    }

    @Test
    fun `the check says why an mpv won't do - missing, not starting, not mpv`() {
        val missing = MpvLocator.check(File(root, "gone/mpv.exe").path)
        assertFalse(missing.ok)
        assertEquals(ErrorCode.PLAYER_NOT_FOUND, missing.errorCode)
        assertEquals(MpvLocator.Origin.SETTINGS, missing.origin)
        assertNull(missing.version)

        // a file that isn't a program
        val text = MpvLocator.check(mpvIn("text", "mpv.txt").path)
        assertFalse(text.ok)
        assertEquals(ErrorCode.PLAYER_FAILED, text.errorCode)

        val (version, problem) = MpvLocator.version(listOf(java), 30_000)
        assertNull(version)
        assertTrue(problem!!.contains("doesn't look like mpv"), problem)
    }

    @Test
    fun `the check takes mpv's version line`() {
        // a stand-in that prints what mpv --version prints (a single-file Java program)
        val program =
            File(root, "FakeVersion.java").apply {
                writeText(
                    """
                    public class FakeVersion {
                        public static void main(String[] a) {
                            System.out.println("mpv v0.40.0-test Copyright © 2000-2025 mpv/MPlayer/mplayer2 projects");
                            System.out.println(" built on Jan  1 2025");
                        }
                    }
                    """.trimIndent(),
                )
            }
        val (version, problem) = MpvLocator.version(listOf(java, program.path), 60_000)
        assertNull(problem)
        assertTrue(version!!.startsWith("mpv v0.40.0-test"), version)
    }

    private val java =
        ProcessHandle
            .current()
            .info()
            .command()
            .get()
}
