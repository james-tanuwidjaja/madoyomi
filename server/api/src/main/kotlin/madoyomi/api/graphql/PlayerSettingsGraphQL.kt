package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import madoyomi.core.error.ErrorCode
import madoyomi.core.player.MpvLocator
import madoyomi.core.player.PlayerLanguages
import madoyomi.core.player.PlayerSettings
import madoyomi.core.player.PlayerSettingsStore
import madoyomi.core.player.QualityPreference
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/** The player settings (Phase 4 Stage 5, Settings → Player), kept by the backend in its data dir. */
@GraphQLDescription("The player settings (Settings → Player), kept in the backend's data dir and used from the next play")
data class PlayerSettingsType(
    @GraphQLDescription("The share of the duration at which an episode is marked watched: 0.5 to 0.95, in steps of 0.05")
    val watchedThreshold: Double,
    @GraphQLDescription("Goes first in each hoster's videos (after the source's own sort); SOURCE: the source's order")
    val preferredQuality: QualityPreference,
    @GraphQLDescription("Hosters and videos whose name contains this (ignoring case) go first; empty: the source's order")
    val preferredServer: String,
    @GraphQLDescription("A playerLanguages code, \"off\", or empty for the source's first subtitle track")
    val subtitleLanguage: String,
    @GraphQLDescription("A playerLanguages code, or empty for the video's own audio, else the source's first track")
    val audioLanguage: String,
    @GraphQLDescription("The mpv executable to use first; empty: MADOYOMI_MPV, then mpv on PATH")
    val mpvPath: String,
) {
    constructor(s: PlayerSettings) : this(
        watchedThreshold = s.watchedThreshold,
        preferredQuality = s.preferredQuality,
        preferredServer = s.preferredServer,
        subtitleLanguage = s.subtitleLanguage,
        audioLanguage = s.audioLanguage,
        mpvPath = s.mpvPath,
    )
}

@GraphQLDescription("A language the subtitle and audio preferences can name")
data class PlayerLanguageType(
    @GraphQLDescription("ISO 639-1 (639-2 for a language without one): what the settings store")
    val code: String,
    @GraphQLDescription("Its English name")
    val name: String,
)

/** [MpvLocator.Origin] in the schema. */
enum class MpvOrigin { SETTINGS, SYSTEM_PROPERTY, ENV, PATH }

@GraphQLDescription("Whether an mpv runs: the file is there, and `mpv --version` answers as mpv")
data class MpvCheckType(
    val ok: Boolean,
    val path: String?,
    @GraphQLDescription("Where that mpv comes from")
    val origin: MpvOrigin?,
    @GraphQLDescription("The first line of `mpv --version`")
    val version: String?,
    @GraphQLDescription("PLAYER_NOT_FOUND (no such file) or PLAYER_FAILED (it didn't run as mpv)")
    val errorCode: ErrorCode?,
    val message: String?,
)

class PlayerSettingsQuery {
    @RequireAuth
    @GraphQLDescription("The player settings")
    fun playerSettings(): PlayerSettingsType = PlayerSettingsType(PlayerSettingsStore.current())

    @RequireAuth
    @GraphQLDescription("The languages the subtitle and audio preferences can name, by name")
    fun playerLanguages(): List<PlayerLanguageType> = PlayerLanguages.ALL.map { PlayerLanguageType(it.code, it.name) }

    @RequireAuth
    @GraphQLDescription(
        "Checks an mpv: path (as the settings' mpv path would be used), or without it the mpv the backend would start " +
            "now (the settings' path, MADOYOMI_MPV, then PATH). Runs `mpv --version`",
    )
    fun mpvCheck(path: String? = null): CompletableFuture<MpvCheckType> =
        future {
            val check =
                withContext(Dispatchers.IO) {
                    MpvLocator.check(path, settingsPath = PlayerSettingsStore.current().mpvPath)
                }
            MpvCheckType(
                ok = check.ok,
                path = check.path,
                origin = check.origin?.let { MpvOrigin.valueOf(it.name) },
                version = check.version,
                errorCode = check.errorCode,
                message = check.message,
            )
        }
}

class PlayerSettingsMutation {
    @GraphQLDescription("Only the fields given change; the rest stay")
    data class PlayerSettingsPatch(
        val watchedThreshold: Double? = null,
        val preferredQuality: QualityPreference? = null,
        val preferredServer: String? = null,
        val subtitleLanguage: String? = null,
        val audioLanguage: String? = null,
        val mpvPath: String? = null,
    )

    data class SetPlayerSettingsInput(
        val clientMutationId: String? = null,
        val patch: PlayerSettingsPatch,
    )

    data class SetPlayerSettingsPayload(
        val clientMutationId: String?,
        val settings: PlayerSettingsType,
    )

    @RequireAuth
    @GraphQLDescription(
        "Changes player settings (all or nothing: a wrong value is BAD_REQUEST and nothing changes). Used from the " +
            "next play; the watched threshold also by the session that plays",
    )
    fun setPlayerSettings(input: SetPlayerSettingsInput): SetPlayerSettingsPayload {
        val p = input.patch
        val settings =
            PlayerSettingsStore.update(
                PlayerSettings.Patch(
                    watchedThreshold = p.watchedThreshold,
                    preferredQuality = p.preferredQuality,
                    preferredServer = p.preferredServer,
                    subtitleLanguage = p.subtitleLanguage,
                    audioLanguage = p.audioLanguage,
                    mpvPath = p.mpvPath,
                ),
            )
        return SetPlayerSettingsPayload(input.clientMutationId, PlayerSettingsType(settings))
    }
}
