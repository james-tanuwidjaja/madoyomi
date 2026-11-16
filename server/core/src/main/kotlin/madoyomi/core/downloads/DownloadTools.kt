package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import madoyomi.core.player.MpvLocator
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The two programs anime downloads use (Phase 6 Stage 5, §8): ffmpeg for HLS (and DASH), yt-dlp as the fallback when
 * ffmpeg fails. Each is looked for as mpv is ([MpvLocator]):
 * 1. its path in the download settings (Settings → Downloads);
 * 2. the system property `madoyomi.downloads.ffmpeg` / `madoyomi.downloads.ytdlp` (tests);
 * 3. the env `MADOYOMI_FFMPEG` / `MADOYOMI_YTDLP`, which the Tauri shell sets to the repo's `tools/bin/ffmpeg/ffmpeg.exe`
 *    and `tools/bin/yt-dlp/yt-dlp.exe` in dev, or the bundled ones in release (Phase 7), when they are there;
 * 4. `ffmpeg.exe` / `yt-dlp.exe` on `PATH`.
 *
 * A path given by 1–3 that isn't a file is an error that names it, not a reason to look further.
 */
object DownloadTools {
    enum class Tool(
        val label: String,
        val property: String,
        val env: String,
        val exe: String,
        val versionArg: String,
    ) {
        FFMPEG("ffmpeg", "madoyomi.downloads.ffmpeg", "MADOYOMI_FFMPEG", "ffmpeg", "-version"),
        YT_DLP("yt-dlp", "madoyomi.downloads.ytdlp", "MADOYOMI_YTDLP", "yt-dlp", "--version"),
    }

    enum class Origin(
        val label: String,
    ) {
        SETTINGS("Settings → Downloads"),
        SYSTEM_PROPERTY("a system property"),
        ENV("the environment"),
        PATH("PATH"),
    }

    data class Located(
        val file: File,
        val origin: Origin,
    )

    /** A tool that couldn't be found, with the reason in its message. */
    class NotFound(
        val tool: Tool,
        message: String,
        val path: String? = null,
        val origin: Origin? = null,
    ) : IOException(message)

    /** The path of [tool] in [settings] (blank: none). */
    fun settingsPath(
        tool: Tool,
        settings: DownloadSettings = DownloadSettingsStore.current(),
    ): String =
        when (tool) {
            Tool.FFMPEG -> settings.ffmpegPath
            Tool.YT_DLP -> settings.ytDlpPath
        }

    /** Finds [tool]; [NotFound] says why not. */
    fun find(
        tool: Tool,
        override: String? = settingsPath(tool),
        property: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
        windows: Boolean = MpvLocator.isWindows,
    ): Located {
        val explicit =
            listOf(
                Origin.SETTINGS to override,
                Origin.SYSTEM_PROPERTY to property(tool.property),
                Origin.ENV to env(tool.env),
            ).firstOrNull { !it.second.isNullOrBlank() }
        if (explicit != null) {
            val file = File(explicit.second!!.trim().removeSurrounding("\"")).absoluteFile
            if (file.isFile) return Located(file, explicit.first)
            throw NotFound(tool, "${tool.label} was not found at ${file.path} (set in ${explicit.first.label})", file.path, explicit.first)
        }
        val name = if (windows) "${tool.exe}.exe" else tool.exe
        env("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?.let { return Located(it.absoluteFile, Origin.PATH) }
        throw NotFound(tool, "${tool.label} was not found: install it and put it on PATH, or set its path in Settings → Downloads")
    }

    /** What [check] found. */
    data class Check(
        val tool: Tool,
        val ok: Boolean,
        val path: String?,
        val origin: Origin?,
        /** `ffmpeg version …` (its first line), or yt-dlp's version. */
        val version: String?,
        val message: String?,
    )

    /** Finds [tool] and runs its version command (within [timeoutMillis]). Never throws. */
    fun check(
        tool: Tool,
        settings: DownloadSettings = DownloadSettingsStore.current(),
        timeoutMillis: Long = 15_000,
    ): Check {
        val located =
            try {
                find(tool, settingsPath(tool, settings))
            } catch (e: NotFound) {
                return Check(tool, false, e.path, e.origin, null, e.message)
            }
        val (code, output) =
            try {
                runBlockingCapture(listOf(located.file.path, tool.versionArg), timeoutMillis)
            } catch (e: IOException) {
                return Check(tool, false, located.file.path, located.origin, null, "${tool.label} could not be started: ${e.message}")
            }
        val first = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val looksRight =
            when (tool) {
                Tool.FFMPEG -> first?.startsWith("ffmpeg version", ignoreCase = true) == true
                Tool.YT_DLP -> first?.matches(Regex("""\d{4}\.\d{2}\.\d{2}.*""")) == true
            }
        return when {
            code == null -> {
                Check(tool, false, located.file.path, located.origin, null, "${tool.label} didn't answer in time")
            }

            code != 0 || !looksRight -> {
                Check(
                    tool,
                    false,
                    located.file.path,
                    located.origin,
                    null,
                    "${located.file.name} doesn't look like ${tool.label}: ${first ?: "exit code $code"}",
                )
            }

            else -> {
                Check(tool, true, located.file.path, located.origin, first?.take(120), null)
            }
        }
    }

    // runs [command] to its end (null exit code: it didn't finish in time), with stdout and stderr together
    private fun runBlockingCapture(
        command: List<String>,
        timeoutMillis: Long,
    ): Pair<Int?, String> {
        val process = ProcessBuilder(ProcessArgs.forPlatform(command)).redirectErrorStream(true).start()
        runCatching { process.outputStream.close() }
        val out = StringBuilder()
        val reader =
            Thread({
                runCatching {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            if (out.length < 8192) synchronized(out) { out.appendLine(line) }
                            line = r.readLine()
                        }
                    }
                }
            }, "download-tool-version").apply { isDaemon = true }
        reader.start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null to ""
        }
        reader.join(2_000)
        return process.exitValue() to synchronized(out) { out.toString() }
    }
}

/**
 * Command-line arguments for an external program. On Windows a process gets one command line that the program splits
 * itself (ffmpeg and yt-dlp as `CommandLineToArgvW` does), and Java's `ProcessBuilder` neither escapes a `"` inside an
 * argument nor keeps an argument with a line break whole. Header values carry both (`sec-ch-ua: "Chromium";v="130"`,
 * ffmpeg's `-headers` with CRLF between them), so every argument that needs it is quoted here with the MSVCRT rules
 * (backslashes doubled before a quote, `"` as `\"`), and Java passes an argument that is already quoted as it is.
 */
object ProcessArgs {
    /** [args] (the executable first, which Java quotes itself) ready for `ProcessBuilder`. */
    fun forPlatform(
        args: List<String>,
        windows: Boolean = MpvLocator.isWindows,
    ): List<String> = if (windows && args.isNotEmpty()) listOf(args.first()) + args.drop(1).map(::quoteWindows) else args

    /**
     * [arg] as one MSVCRT argument; unchanged when it needs no quotes. One case stays wrong: an argument with a space
     * that ends with a backslash, which Java takes for unquoted and quotes again (no header or path here ends so).
     */
    fun quoteWindows(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' || it == '\n' || it == '\r' || it == '\u000B' }) return arg
        val out = StringBuilder("\"")
        var backslashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> {
                    backslashes++
                }

                '"' -> {
                    repeat(backslashes * 2 + 1) { out.append('\\') }
                    out.append('"')
                    backslashes = 0
                }

                else -> {
                    repeat(backslashes) { out.append('\\') }
                    backslashes = 0
                    out.append(c)
                }
            }
        }
        // before the closing quote, backslashes are doubled
        repeat(backslashes * 2) { out.append('\\') }
        out.append('"')
        return out.toString()
    }
}

/**
 * Runs an external program for a download: its stdout and stderr read line by line on their own threads, the last
 * lines of stderr kept for the error, and the process (with its children) killed when the coroutine is cancelled
 * (pause, cancel, stop) or [timeoutMs] passes without a line.
 */
object ToolProcess {
    private val logger = KotlinLogging.logger {}

    data class Result(
        val exitCode: Int,
        /** The last lines of its stderr (and of stdout, for a program that reports errors there). */
        val tail: List<String>,
    )

    suspend fun run(
        command: List<String>,
        workDir: File,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        idleTimeoutMs: Long = 120_000,
    ): Result =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(ProcessArgs.forPlatform(command)).directory(workDir)
            // the API token stays in the backend (as for mpv)
            builder.environment().keys.removeIf { it.equals("MADOYOMI_TOKEN", ignoreCase = true) }
            val process =
                try {
                    builder.start()
                } catch (e: IOException) {
                    throw IOException("${File(command.first()).name} could not be started: ${e.message}", e)
                }
            runCatching { process.outputStream.close() }
            val tail = ArrayDeque<String>()
            var lastLine = System.currentTimeMillis()

            fun keep(line: String) {
                synchronized(tail) {
                    tail.addLast(line)
                    while (tail.size > TAIL_LINES) tail.removeFirst()
                }
            }

            fun reader(
                stream: java.io.InputStream,
                name: String,
                each: (String) -> Unit,
                kept: Boolean,
            ) = Thread({
                runCatching {
                    stream.bufferedReader(Charsets.UTF_8).use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            lastLine = System.currentTimeMillis()
                            if (kept && line.isNotBlank()) keep(line)
                            runCatching { each(line) }
                            line = r.readLine()
                        }
                    }
                }
            }, name).apply {
                isDaemon = true
                start()
            }

            val out = reader(process.inputStream, "download-tool-out", onStdout, kept = false)
            val err = reader(process.errorStream, "download-tool-err", onStderr, kept = true)
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    if (System.currentTimeMillis() - lastLine > idleTimeoutMs) {
                        kill(process)
                        throw IOException("${File(command.first()).name} stopped answering for ${idleTimeoutMs / 1000} s")
                    }
                }
                out.join(2_000)
                err.join(2_000)
                Result(process.exitValue(), synchronized(tail) { tail.toList() })
            } catch (e: CancellationException) {
                kill(process)
                throw e
            } catch (e: Throwable) {
                kill(process)
                throw e
            }
        }

    private fun kill(process: Process) {
        if (!process.isAlive) return
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        if (!process.waitFor(5, TimeUnit.SECONDS)) logger.warn { "a download tool (pid ${process.pid()}) didn't exit after it was killed" }
    }

    private const val TAIL_LINES = 12
}
