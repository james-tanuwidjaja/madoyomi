package madoyomi.core.player

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

/** The preferred quality (Phase 4 step 9): the source's order, the highest, or a resolution. */
enum class QualityPreference(
    /** The resolution's height, for the resolutions. */
    val height: Int?,
) {
    SOURCE(null),
    HIGHEST(null),
    P2160(2160),
    P1080(1080),
    P720(720),
    P480(480),
    P360(360),
}

/**
 * The player settings (Phase 4 Stage 5, Settings → Player). They are the backend's, kept in its data dir
 * (`player-settings.json`), because the backend applies them: the watched mark ([watchedThreshold]), the order of an
 * episode's videos ([preferredQuality], [preferredServer]; [VideoPreferences]), the subtitle and audio tracks it selects
 * over IPC ([subtitleLanguage], [audioLanguage]; [TrackSelection]), and which mpv it starts ([mpvPath]; [MpvLocator]).
 * Each change is used from the next play; the threshold also by the session that plays.
 */
@Serializable
data class PlayerSettings(
    /** The share of the duration at which an episode is marked watched: [MIN_THRESHOLD] to [MAX_THRESHOLD]. */
    val watchedThreshold: Double = PlaybackTracker.DEFAULT_WATCHED_THRESHOLD,
    val preferredQuality: QualityPreference = QualityPreference.SOURCE,
    /** Hosters and videos whose name contains this (ignoring case) go first; "" for the source's order. */
    val preferredServer: String = "",
    /** A [PlayerLanguages] code, [SUBTITLES_OFF], or "" for the source's first subtitle track (Stage 2's rule). */
    val subtitleLanguage: String = "",
    /** A [PlayerLanguages] code, or "" for the video's own audio, else the source's first track. */
    val audioLanguage: String = "",
    /** The mpv executable to use first; "" for `MADOYOMI_MPV`, then PATH. */
    val mpvPath: String = "",
) {
    /** Only the fields that aren't null change ([PlayerSettingsStore.update]). */
    data class Patch(
        val watchedThreshold: Double? = null,
        val preferredQuality: QualityPreference? = null,
        val preferredServer: String? = null,
        val subtitleLanguage: String? = null,
        val audioLanguage: String? = null,
        val mpvPath: String? = null,
    )

    /** Whether a preferred server or quality reorders the videos. */
    val sortsVideos: Boolean get() = preferredQuality != QualityPreference.SOURCE || preferredServer.isNotBlank()

    companion object {
        const val MIN_THRESHOLD = 0.5
        const val MAX_THRESHOLD = 0.95
        const val THRESHOLD_STEP = 0.05
        const val SUBTITLES_OFF = "off"
        const val MAX_SERVER_LENGTH = 100
        const val MAX_PATH_LENGTH = 1024
    }
}

/**
 * Where the [PlayerSettings] live: `<data dir>/player-settings.json`, read once per data dir and written on every
 * change (to a temporary file, then moved over). A missing file is the defaults; one that can't be read is logged and
 * the defaults used (it stays as it is until the next change). Unknown keys are ignored, missing ones are defaults.
 */
object PlayerSettingsStore {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }

    const val FILE_NAME = "player-settings.json"

    /** Tests: another file than the data dir's. */
    @Volatile
    var fileOverride: File? = null

    private var loaded: Pair<File, PlayerSettings>? = null

    fun file(): File = fileOverride ?: File(applicationDirs.dataRoot, FILE_NAME)

    /** The settings in use (read from the file the first time for a data dir). */
    @Synchronized
    fun current(): PlayerSettings {
        val file = file().absoluteFile
        loaded?.let { (f, s) -> if (f == file) return s }
        val settings = read(file)
        loaded = file to settings
        return settings
    }

    /** Applies [patch] (validated first: nothing changes if a field is wrong), writes the file, returns the result. */
    @Synchronized
    fun update(patch: PlayerSettings.Patch): PlayerSettings {
        val now = current()
        val next =
            PlayerSettings(
                watchedThreshold = patch.watchedThreshold?.let(::threshold) ?: now.watchedThreshold,
                preferredQuality = patch.preferredQuality ?: now.preferredQuality,
                preferredServer = patch.preferredServer?.let(::server) ?: now.preferredServer,
                subtitleLanguage = patch.subtitleLanguage?.let { language(it, subtitles = true) } ?: now.subtitleLanguage,
                audioLanguage = patch.audioLanguage?.let { language(it, subtitles = false) } ?: now.audioLanguage,
                mpvPath = patch.mpvPath?.let(::path) ?: now.mpvPath,
            )
        if (next == now) return now
        write(file().absoluteFile, next)
        loaded = file().absoluteFile to next
        logger.info { "player settings changed: ${describe(now, next)}" }
        return next
    }

    /** Tests: forget what was read, so the next [current] reads the file again. */
    @Synchronized
    fun reload() {
        loaded = null
    }

    private fun read(file: File): PlayerSettings {
        if (!file.isFile) return PlayerSettings()
        return runCatching { sanitized(json.decodeFromString(PlayerSettings.serializer(), file.readText(Charsets.UTF_8))) }
            .onFailure { logger.warn(it) { "could not read ${file.path}; using the default player settings" } }
            .getOrDefault(PlayerSettings())
    }

    /** A file edited by hand: values out of range are put back to what [update] would accept, or the default. */
    private fun sanitized(s: PlayerSettings): PlayerSettings {
        val defaults = PlayerSettings()
        return PlayerSettings(
            watchedThreshold = runCatching { threshold(s.watchedThreshold) }.getOrDefault(defaults.watchedThreshold),
            preferredQuality = s.preferredQuality,
            preferredServer = runCatching { server(s.preferredServer) }.getOrDefault(""),
            subtitleLanguage = runCatching { language(s.subtitleLanguage, subtitles = true) }.getOrDefault(""),
            audioLanguage = runCatching { language(s.audioLanguage, subtitles = false) }.getOrDefault(""),
            mpvPath = runCatching { path(s.mpvPath) }.getOrDefault(""),
        )
    }

    private fun write(
        file: File,
        settings: PlayerSettings,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(PlayerSettings.serializer(), settings) + "\n", Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun bad(
        field: String,
        message: String,
        value: Any?,
    ): Nothing = throw MadoyomiException(ErrorCode.BAD_REQUEST, "$field: $message", mapOf("field" to field, "value" to value))

    /** 0.5 to 0.95 in steps of 0.05 (rounded to the step). */
    private fun threshold(v: Double): Double {
        if (!v.isFinite() || v < PlayerSettings.MIN_THRESHOLD - 1e-9 || v > PlayerSettings.MAX_THRESHOLD + 1e-9) {
            bad("watchedThreshold", "must be 0.5 to 0.95", v)
        }
        return (v / PlayerSettings.THRESHOLD_STEP).roundToInt() * 5 / 100.0
    }

    private fun server(v: String): String {
        val t = v.trim()
        if (t.length > PlayerSettings.MAX_SERVER_LENGTH) bad("preferredServer", "at most ${PlayerSettings.MAX_SERVER_LENGTH} characters", v)
        if (t.any { it.isISOControl() }) bad("preferredServer", "no line breaks or control characters", v)
        return t
    }

    private fun language(
        v: String,
        subtitles: Boolean,
    ): String {
        val t = v.trim().lowercase()
        if (t.isEmpty()) return ""
        if (subtitles && t == PlayerSettings.SUBTITLES_OFF) return t
        if (PlayerLanguages.byCode(t) == null) {
            bad(if (subtitles) "subtitleLanguage" else "audioLanguage", "not a known language code", v)
        }
        return t
    }

    /** Trimmed, surrounding quotes taken off (Explorer's "Copy as path" adds them). */
    private fun path(v: String): String {
        val t = v.trim().removeSurrounding("\"").trim()
        if (t.length > PlayerSettings.MAX_PATH_LENGTH) bad("mpvPath", "at most ${PlayerSettings.MAX_PATH_LENGTH} characters", v)
        if (t.any { it.isISOControl() }) bad("mpvPath", "no line breaks or control characters", v)
        return t
    }

    private fun describe(
        a: PlayerSettings,
        b: PlayerSettings,
    ): String =
        listOfNotNull(
            "watchedThreshold=${b.watchedThreshold}".takeIf { a.watchedThreshold != b.watchedThreshold },
            "preferredQuality=${b.preferredQuality}".takeIf { a.preferredQuality != b.preferredQuality },
            "preferredServer='${b.preferredServer}'".takeIf { a.preferredServer != b.preferredServer },
            "subtitleLanguage='${b.subtitleLanguage}'".takeIf { a.subtitleLanguage != b.subtitleLanguage },
            "audioLanguage='${b.audioLanguage}'".takeIf { a.audioLanguage != b.audioLanguage },
            "mpvPath='${b.mpvPath}'".takeIf { a.mpvPath != b.mpvPath },
        ).joinToString(", ")
}
