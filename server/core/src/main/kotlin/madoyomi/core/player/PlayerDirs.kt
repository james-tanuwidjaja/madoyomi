package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.File

/**
 * mpv's config dir (`--config-dir`): the env `MADOYOMI_PLAYER_DIR`, which the Tauri shell sets to the repo's `player/`
 * in dev and the bundled `resources/player` in release, if it is a directory; else `<data dir>/player`, created empty.
 * mpv never reads the user's own `%APPDATA%\mpv` config, so key bindings and the IPC behaviour are Madoyomi's.
 */
object PlayerDirs {
    const val ENV = "MADOYOMI_PLAYER_DIR"

    fun configDir(
        dataRoot: File,
        env: (String) -> String? = System::getenv,
    ): File {
        env(ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it.trim()).absoluteFile }
            ?.takeIf { it.isDirectory }
            ?.let { return it }
        return File(dataRoot, "player").absoluteFile.apply { mkdirs() }
    }
}
