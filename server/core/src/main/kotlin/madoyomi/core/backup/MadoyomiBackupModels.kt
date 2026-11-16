package madoyomi.core.backup

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupCategory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupSource

/**
 * A `.madoyomi-backup` (Phase 6 Stage 6): a gzipped protobuf. The manga part keeps upstream's (Suwayomi's and
 * Mihon's) field numbers and types, so upstream's handlers write and read it and a Mihon/Suwayomi `.tachibk` decodes
 * as a backup with only that part. Madoyomi's additions use field numbers from [FIRST_MADOYOMI_FIELD] up, which none of
 * the others use: Mihon has 1, 2, 101 and 104–106; Aniyomi 1, 2, 101, 104–106 and 500–506 (its anime part);
 * TachiyomiSY the 600s; Suwayomi 9000–9002. Unknown fields are skipped when decoding, so each app reads what it knows.
 *
 * Upstream's server and user settings (9001, 9002) are left out: they hold the SOCKS proxy, login and database
 * passwords in plain text, and settings Madoyomi manages itself (`server.conf`).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MadoyomiBackup(
    @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(101) val backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(9000) val meta: Map<String, String> = emptyMap(),
    // Madoyomi
    @ProtoNumber(20000) val info: BackupInfo? = null,
    @ProtoNumber(20001) val anime: List<AnimeBackup> = emptyList(),
    @ProtoNumber(20002) val animeCategories: List<AnimeCategoryBackup> = emptyList(),
    @ProtoNumber(20003) val animeSources: List<BackupSource> = emptyList(),
    @ProtoNumber(20004) val mangaCategoryFlags: List<CategoryFlagsBackup> = emptyList(),
    @ProtoNumber(20005) val repos: List<RepoBackup> = emptyList(),
    @ProtoNumber(20006) val trustedCertificates: List<CertificateBackup> = emptyList(),
    @ProtoNumber(20007) val extensions: List<ExtensionBackup> = emptyList(),
    @ProtoNumber(20008) val sourcePreferences: List<SourcePreferencesBackup> = emptyList(),
    @ProtoNumber(20009) val settingsFiles: List<SettingsFileBackup> = emptyList(),
) {
    companion object {
        const val FIRST_MADOYOMI_FIELD = 20000
        const val FORMAT_VERSION = 1
    }
}

/** What wrote the backup. Its presence is what makes a file a Madoyomi backup rather than a `.tachibk`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupInfo(
    @ProtoNumber(1) @EncodeDefault val formatVersion: Int = MadoyomiBackup.FORMAT_VERSION,
    @ProtoNumber(2) val appVersion: String = "",
    /** Epoch milliseconds. */
    @ProtoNumber(3) val createdAt: Long = 0,
    @ProtoNumber(4) val automatic: Boolean = false,
)

/** An anime entry: a library entry, or a season of one (its parent's [parentUrl], same source). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnimeBackup(
    @ProtoNumber(1) @EncodeDefault val source: Long = 0,
    @ProtoNumber(2) @EncodeDefault val url: String = "",
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: String? = null,
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(10) val backgroundUrl: String? = null,
    @ProtoNumber(11) val inLibrary: Boolean = false,
    /** Epoch seconds. */
    @ProtoNumber(12) val addedAt: Long = 0,
    @ProtoNumber(13) val lastFetchedAt: Long = 0,
    @ProtoNumber(14) val initialized: Boolean = false,
    @ProtoNumber(15) val updateStrategy: String = "",
    @ProtoNumber(16) val fetchType: String = "",
    @ProtoNumber(17) val seasonNumber: Double = -1.0,
    /** The extension's `memo`, as JSON text. */
    @ProtoNumber(18) val memo: String = "{}",
    @ProtoNumber(19) val parentUrl: String? = null,
    @ProtoNumber(20) val seasonSourceOrder: Int = 0,
    @ProtoNumber(21) val episodes: List<EpisodeBackup> = emptyList(),
    /** The names of its categories (Default never listed, as in the database). */
    @ProtoNumber(22) val categories: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EpisodeBackup(
    @ProtoNumber(1) @EncodeDefault val url: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val episodeNumber: Float = -1f,
    @ProtoNumber(4) val dateUpload: Long = 0,
    @ProtoNumber(5) val scanlator: String? = null,
    @ProtoNumber(6) val watched: Boolean = false,
    @ProtoNumber(7) val bookmark: Boolean = false,
    @ProtoNumber(8) val lastSecondSeen: Long = 0,
    @ProtoNumber(9) val totalSeconds: Long = 0,
    @ProtoNumber(10) val sourceOrder: Int = 0,
    @ProtoNumber(11) val fetchedAt: Long = 0,
    @ProtoNumber(12) val fillermark: Boolean = false,
    @ProtoNumber(13) val summary: String? = null,
    @ProtoNumber(14) val previewUrl: String? = null,
    @ProtoNumber(15) val memo: String = "{}",
    /** `ANIME_HISTORY.last_seen` (epoch seconds), 0 when never watched. */
    @ProtoNumber(16) val lastSeen: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnimeCategoryBackup(
    @ProtoNumber(1) @EncodeDefault val name: String = "",
    @ProtoNumber(2) val order: Int = 0,
    @ProtoNumber(3) val isDefaultCategory: Boolean = false,
    /** "New entries go here" (upstream's `is_default`). */
    @ProtoNumber(4) val newEntries: Boolean = false,
    @ProtoNumber(5) val includeInUpdate: Int = -1,
    @ProtoNumber(6) val includeInDownload: Int = -1,
    @ProtoNumber(7) val meta: Map<String, String> = emptyMap(),
)

/** The flags of a manga category that upstream's `BackupCategory` doesn't carry, by name. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CategoryFlagsBackup(
    @ProtoNumber(1) @EncodeDefault val name: String = "",
    @ProtoNumber(2) val newEntries: Boolean = false,
    @ProtoNumber(3) val includeInUpdate: Int = -1,
    @ProtoNumber(4) val includeInDownload: Int = -1,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RepoBackup(
    @ProtoNumber(1) @EncodeDefault val indexUrl: String = "",
    /** `ANIME` or `MANGA`. */
    @ProtoNumber(2) val kind: String = "",
    @ProtoNumber(3) val format: String = "",
    @ProtoNumber(4) val name: String = "",
    @ProtoNumber(5) val signingKey: String = "",
    @ProtoNumber(6) val signingKeyTrusted: Boolean = false,
)

/** A signing certificate the user trusted (origin USER); a repo's own key comes back with the repo. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CertificateBackup(
    @ProtoNumber(1) @EncodeDefault val hash: String = "",
    @ProtoNumber(2) val addedAt: Long = 0,
)

/** An installed extension (not its APK): reinstalled from its repo after the trust prompt. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionBackup(
    @ProtoNumber(1) @EncodeDefault val pkgName: String = "",
    @ProtoNumber(2) val kind: String = "",
    @ProtoNumber(3) val name: String = "",
    @ProtoNumber(4) val repoUrl: String? = null,
    @ProtoNumber(5) val versionName: String = "",
    @ProtoNumber(6) val signatureHash: String? = null,
    @ProtoNumber(7) val sourceIds: List<Long> = emptyList(),
)

/** A source's `SharedPreferences` (`settings/source_<id>.xml`) as the extension stored them, private keys left out. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SourcePreferencesBackup(
    @ProtoNumber(1) @EncodeDefault val sourceId: Long = 0,
    @ProtoNumber(2) val kind: String = "",
    @ProtoNumber(3) val values: Map<String, String> = emptyMap(),
)

/** One of the backend's settings files (`player-settings.json` …), its JSON as written, schedule anchors left out. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SettingsFileBackup(
    @ProtoNumber(1) @EncodeDefault val name: String = "",
    @ProtoNumber(2) val json: String = "",
)
