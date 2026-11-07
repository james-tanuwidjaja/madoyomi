package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import java.io.File

/**
 * Finds the mpv executable (Phase 4 §9, "Where mpv comes from"):
 * 1. an explicit path (the player settings' override, Stage 5);
 * 2. the system property `madoyomi.player.mpv` (tests);
 * 3. the env `MADOYOMI_MPV`, which the Tauri shell sets to the repo's `tools/bin/mpv/mpv.exe` in dev or the bundled
 *    `resources/mpv/mpv.exe` in release, when one is there;
 * 4. `mpv.exe` (`mpv` elsewhere) on `PATH`.
 *
 * A path given by 1–3 that isn't a file is an error, not a reason to look further: the user (or the shell) asked for
 * that one. Nothing found is `PLAYER_NOT_FOUND`.
 */
object MpvLocator {
    const val ENV = "MADOYOMI_MPV"
    const val PROPERTY = "madoyomi.player.mpv"

    fun locate(
        override: String? = null,
        property: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
        windows: Boolean = isWindows,
    ): File {
        val explicit =
            listOf(
                "the player settings" to override,
                "the system property $PROPERTY" to property(PROPERTY),
                ENV to env(ENV),
            ).firstOrNull { !it.second.isNullOrBlank() }
        if (explicit != null) {
            val file = File(explicit.second!!.trim()).absoluteFile
            if (file.isFile) return file
            throw MadoyomiException(
                ErrorCode.PLAYER_NOT_FOUND,
                "mpv was not found at ${file.path} (set by ${explicit.first})",
                mapOf("path" to file.path, "origin" to explicit.first),
            )
        }
        val name = if (windows) "mpv.exe" else "mpv"
        val dirs =
            env("PATH")
                .orEmpty()
                .split(File.pathSeparatorChar)
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        dirs
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?.let { return it.absoluteFile }
        throw MadoyomiException(
            ErrorCode.PLAYER_NOT_FOUND,
            "mpv was not found: install mpv and put it on PATH, or set $ENV",
            mapOf("searched" to name),
        )
    }

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
