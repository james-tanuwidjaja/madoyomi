package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.player.PlaybackSessions
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.javalinSetup
import suwayomi.tachidesk.server.applicationSetup
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.util.ExitCode
import suwayomi.tachidesk.server.util.shutdownApp
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Madoyomi-Server entry point: the backend as a sidecar of the Tauri shell.
 *
 * Contract with the shell (see server/UPSTREAM.md, "Startup contract"):
 * - `--data-dir <path>`: data directory (database, settings, extensions, logs). Default: `%APPDATA%\Madoyomi`.
 * - env `MADOYOMI_TOKEN`: API token. Every request except `GET /health` and CORS preflights must send it.
 * - binds `127.0.0.1` on a random free port and prints one line `MADOYOMI_PORT=<port>` on stdout once listening.
 * - exits when stdin closes, i.e. when the parent process goes away.
 */
fun main(args: Array<String>) {
    val startedAt = System.currentTimeMillis()
    val options = parseArgs(args)

    // Must happen before anything reads ApplicationRootDir (config, logging, AndroidCompat).
    options.dataDir?.let { dir ->
        dir.mkdirs()
        System.setProperty("$CONFIG_PREFIX.server.rootDir", dir.absolutePath)
    }

    val token = SidecarToken.fromEnvironment()

    // Trigger graphql schema generation as early as possible, takes multiple seconds (same as upstream Main)
    GraphQLSchemaProvider.init()
    applicationSetup()
    forceAuthModeNone()
    reapPlaybackOrphans()

    javalinSetup { config ->
        SidecarHttp.install(config, token, startedAt)
        AnimeHttp.install(config)
    }

    val port = JavalinSetup.connector?.localPort ?: error("HTTP connector was not created")
    // The handshake line. Flush explicitly: stdout is a pipe, not a console.
    println("MADOYOMI_PORT=$port")
    System.out.flush()

    startStdinWatchdog()
}

private data class Options(
    val dataDir: File?,
)

private fun parseArgs(args: Array<String>): Options {
    var dataDir: File? = null
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--data-dir" -> {
                dataDir = File(args.getOrNull(i + 1) ?: usageError("--data-dir needs a path")).absoluteFile
                i++
            }

            arg.startsWith("--data-dir=") -> {
                dataDir = File(arg.substringAfter('=')).absoluteFile
            }

            else -> {
                usageError("unknown argument: $arg")
            }
        }
        i++
    }
    return Options(dataDir)
}

private fun usageError(message: String): Nothing {
    System.err.println("Madoyomi-Server: $message")
    System.err.println("usage: java -jar Madoyomi-Server.jar [--data-dir <path>]   (token via env MADOYOMI_TOKEN)")
    exitProcess(2)
}

/**
 * The token filter is the only gate, so upstream's own auth stays off: authMode NONE makes every request the
 * built-in admin user. Reverted if anything (e.g. a settings mutation) changes it.
 */
private fun forceAuthModeNone() {
    val logger = KotlinLogging.logger {}
    serverConfig.subscribeTo(
        serverConfig.authMode,
        { mode ->
            if (mode != AuthMode.NONE) {
                logger.warn { "authMode $mode is not supported by Madoyomi-Server, resetting it to NONE" }
                serverConfig.authMode.value = AuthMode.NONE
            }
        },
        ignoreInitialValue = false,
    )
}

/** An mpv that a crashed backend left playing (the shell restarted us) is ended: nothing tracks it any more. */
private fun reapPlaybackOrphans() {
    runCatching { PlaybackSessions.reapOrphans() }
        .onFailure { KotlinLogging.logger {}.warn(it) { "could not check for mpv processes left by an earlier backend" } }
}

/** When the parent goes away its end of our stdin closes and read() returns -1 (backup to the Windows job object). */
private fun startStdinWatchdog() {
    thread(isDaemon = true, name = "stdin-watchdog") {
        try {
            while (System.`in`.read() != -1) {
                // ignore input
            }
        } catch (_: Exception) {
        }
        KotlinLogging.logger {}.info { "stdin closed, shutting down" }
        shutdownApp(ExitCode.Success)
    }
}
