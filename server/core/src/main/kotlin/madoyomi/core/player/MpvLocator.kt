package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Finds the mpv executable (Phase 4 §9, "Where mpv comes from"):
 * 1. the player settings' path (`PlayerSettings.mpvPath`, Settings → Player; Stage 5);
 * 2. the system property `madoyomi.player.mpv` (tests);
 * 3. the env `MADOYOMI_MPV`, which the Tauri shell sets to the repo's `tools/bin/mpv/mpv.exe` in dev or the bundled
 *    `resources/mpv/mpv.exe` in release, when one is there;
 * 4. `mpv.exe` (`mpv` elsewhere) on `PATH`.
 *
 * A path given by 1–3 that isn't a file is an error, not a reason to look further: the user (or the shell) asked for
 * that one, and the error says which of them it was. Nothing found is `PLAYER_NOT_FOUND`. [check] also runs
 * `mpv --version`, for the settings' check.
 */
object MpvLocator {
    const val ENV = "MADOYOMI_MPV"
    const val PROPERTY = "madoyomi.player.mpv"

    /** Where the mpv in use came from. */
    enum class Origin(
        val label: String,
    ) {
        SETTINGS("Settings → Player"),
        SYSTEM_PROPERTY("the system property $PROPERTY"),
        ENV(MpvLocator.ENV),
        PATH("PATH"),
    }

    data class Located(
        val file: File,
        val origin: Origin,
    )

    fun locate(
        override: String? = null,
        property: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
        windows: Boolean = isWindows,
    ): File = find(override, property, env, windows).file

    fun find(
        override: String? = null,
        property: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
        windows: Boolean = isWindows,
    ): Located {
        val explicit =
            listOf(
                Origin.SETTINGS to override,
                Origin.SYSTEM_PROPERTY to property(PROPERTY),
                Origin.ENV to env(ENV),
            ).firstOrNull { !it.second.isNullOrBlank() }
        if (explicit != null) {
            val file = File(explicit.second!!.trim().removeSurrounding("\"")).absoluteFile
            if (file.isFile) return Located(file, explicit.first)
            throw MadoyomiException(
                ErrorCode.PLAYER_NOT_FOUND,
                "mpv was not found at ${file.path} (set in ${explicit.first.label})",
                mapOf("path" to file.path, "origin" to explicit.first.name),
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
            ?.let { return Located(it.absoluteFile, Origin.PATH) }
        throw MadoyomiException(
            ErrorCode.PLAYER_NOT_FOUND,
            "mpv was not found: install mpv and put it on PATH, or set its path in Settings → Player",
            mapOf("searched" to name),
        )
    }

    /** What [check] found: [ok] when it ran and said it is mpv; else [errorCode] and [message] say why. */
    data class Check(
        val ok: Boolean,
        val path: String?,
        val origin: Origin?,
        /** The first line of `mpv --version`, "mpv v0.40.0 Copyright © …". */
        val version: String?,
        val errorCode: ErrorCode?,
        val message: String?,
    )

    /**
     * Checks [path] (as the settings' path), or without one the mpv [find] picks now: that the file is there, and
     * that `<it> --version` exits within [timeoutMillis] and prints a line starting with "mpv". Never throws.
     */
    fun check(
        path: String?,
        settingsPath: String? = null,
        timeoutMillis: Long = 10_000,
        property: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
    ): Check {
        val located =
            try {
                if (!path.isNullOrBlank()) find(path, { null }, { null }) else find(settingsPath, property, env)
            } catch (e: MadoyomiException) {
                val p = (e.details["path"] as? String)
                val origin = (e.details["origin"] as? String)?.let { runCatching { Origin.valueOf(it) }.getOrNull() }
                return Check(false, p, origin, null, e.code, e.message)
            }
        return version(listOf(located.file.path), timeoutMillis).let { (version, problem) ->
            Check(
                ok = version != null,
                path = located.file.path,
                origin = located.origin,
                version = version,
                errorCode = if (version == null) ErrorCode.PLAYER_FAILED else null,
                message = problem,
            )
        }
    }

    /** Runs `<command> --version`: its "mpv …" line, or null and why not. */
    internal fun version(
        command: List<String>,
        timeoutMillis: Long,
    ): Pair<String?, String?> {
        val process =
            try {
                ProcessBuilder(command + "--version").redirectErrorStream(true).start()
            } catch (e: IOException) {
                return null to "${command.first()} could not be started: ${e.message}"
            }
        runCatching { process.outputStream.close() }
        val output = StringBuilder()
        val reader =
            Thread({
                runCatching {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        var line = r.readLine()
                        while (line != null && output.length < 4096) {
                            synchronized(output) { output.appendLine(line) }
                            line = r.readLine()
                        }
                    }
                }
            }, "mpv-version").apply { isDaemon = true }
        reader.start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null to "${command.first()} --version didn't answer within ${timeoutMillis / 1000} s"
        }
        reader.join(2_000)
        val lines = synchronized(output) { output.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        val mpvLine = lines.firstOrNull { it.startsWith("mpv ", ignoreCase = true) }
        return when {
            process.exitValue() != 0 -> null to "${command.first()} --version exited with code ${process.exitValue()}"
            mpvLine == null -> null to "${command.first()} doesn't look like mpv: ${lines.firstOrNull() ?: "it printed nothing"}"
            else -> mpvLine to null
        }
    }

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
