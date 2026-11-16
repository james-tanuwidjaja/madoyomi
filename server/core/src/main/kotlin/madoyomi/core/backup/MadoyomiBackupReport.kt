package madoyomi.core.backup

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.extension.SignatureTrust
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.database.dbTransaction

/** What a backup file holds, checked against this install, shown before a restore (the trust prompt's content). */
data class BackupReport(
    val name: String,
    val format: BackupFormat,
    /** Epoch milliseconds, null for a `.tachibk`. */
    val createdAt: Long?,
    val appVersion: String?,
    val automatic: Boolean,
    val anime: Int,
    val seasons: Int,
    val episodes: Int,
    val watchedEpisodes: Int,
    val animeHistory: Int,
    val animeCategories: Int,
    val manga: Int,
    val chapters: Int,
    val readChapters: Int,
    val mangaHistory: Int,
    val mangaCategories: Int,
    val repos: List<RepoReport>,
    val certificates: List<CertificateReport>,
    val extensions: List<ExtensionReport>,
    val missingSources: List<SourceReport>,
    val settingsFiles: List<String>,
    val sourcePreferences: Int,
)

data class RepoReport(
    val indexUrl: String,
    val kind: ExtensionKind,
    val name: String,
    val signingKey: String,
    /** Already added in this install. */
    val added: Boolean,
)

data class CertificateReport(
    val hash: String,
    val trusted: Boolean,
)

data class ExtensionReport(
    val pkgName: String,
    val kind: ExtensionKind,
    val name: String,
    val versionName: String,
    val repoUrl: String?,
    val signatureHash: String?,
    val installed: Boolean,
    /** A repo added here, or one in the backup, lists it: it can be reinstalled. */
    val inRepo: Boolean,
    /** Its signature is trusted here, or will be by a repo or certificate in the backup. */
    val trustedAfterRestore: Boolean,
)

/** A source the backup's entries use that isn't installed here. */
data class SourceReport(
    val id: Long,
    val kind: ExtensionKind,
    val name: String,
    val entries: Int,
    /** The backup's extension that provides it, if any (installing it brings the source back). */
    val extension: String?,
)

object MadoyomiBackupReport {
    fun of(
        name: String,
        backup: MadoyomiBackup,
        format: BackupFormat,
    ): BackupReport =
        dbTransaction {
            val addedRepos = ExtensionStoreTable.selectAll().map { it[ExtensionStoreTable.indexUrl] }.toSet()
            val trusted = SignatureTrust.trustedHashes()
            val repoKeys =
                backup.repos
                    .filter { it.signingKeyTrusted }
                    .mapNotNull { madoyomi.core.repo.normalizeSigningKey(it.signingKey) }
                    .toSet()
            val certificateHashes = backup.trustedCertificates.map { it.hash }.toSet()
            val backupRepoUrls = backup.repos.map { it.indexUrl }.toSet()
            val extensions =
                backup.extensions.map { e ->
                    val kind = kindOf(e.kind)
                    val listing =
                        ExtensionTable
                            .selectAll()
                            .where { (ExtensionTable.pkgName eq e.pkgName) and (ExtensionTable.type eq kind) }
                            .firstOrNull()
                    ExtensionReport(
                        pkgName = e.pkgName,
                        kind = kind,
                        name = e.name,
                        versionName = e.versionName,
                        repoUrl = e.repoUrl,
                        signatureHash = e.signatureHash,
                        installed = listing?.get(ExtensionTable.isInstalled) == true,
                        inRepo = listing != null || (e.repoUrl != null && e.repoUrl in backupRepoUrls),
                        trustedAfterRestore =
                            e.signatureHash != null &&
                                (e.signatureHash in trusted || e.signatureHash in repoKeys || e.signatureHash in certificateHashes),
                    )
                }
            val mangaSources = SourceTable.selectAll().map { it[SourceTable.id].value }.toSet()
            val animeSources = AnimeSourceTable.selectAll().map { it[AnimeSourceTable.id].value }.toSet()
            val providers =
                backup.extensions.flatMap { e -> e.sourceIds.map { (kindOf(e.kind) to it) to e.pkgName } }.toMap()
            val mangaNames = backup.backupSources.associate { it.sourceId to it.name }
            val animeNames = backup.animeSources.associate { it.sourceId to it.name }
            val missing =
                backup.backupManga
                    .groupingBy { it.source }
                    .eachCount()
                    .filterKeys { it !in mangaSources && it != 0L }
                    .map { (id, count) ->
                        SourceReport(id, ExtensionKind.MANGA, mangaNames[id].orEmpty(), count, providers[ExtensionKind.MANGA to id])
                    } +
                    backup.anime
                        .groupingBy { it.source }
                        .eachCount()
                        .filterKeys { it !in animeSources }
                        .map { (id, count) ->
                            SourceReport(id, ExtensionKind.ANIME, animeNames[id].orEmpty(), count, providers[ExtensionKind.ANIME to id])
                        }
            BackupReport(
                name = name,
                format = format,
                createdAt = backup.info?.createdAt,
                appVersion = backup.info?.appVersion,
                automatic = backup.info?.automatic == true,
                anime = backup.anime.count { it.inLibrary },
                seasons = backup.anime.count { !it.inLibrary },
                episodes = backup.anime.sumOf { it.episodes.size },
                watchedEpisodes = backup.anime.sumOf { a -> a.episodes.count { it.watched } },
                animeHistory = backup.anime.sumOf { a -> a.episodes.count { it.lastSeen > 0 } },
                animeCategories = backup.animeCategories.count { !it.isDefaultCategory },
                manga = backup.backupManga.count { it.favorite },
                chapters = backup.backupManga.sumOf { it.chapters.size },
                readChapters = backup.backupManga.sumOf { m -> m.chapters.count { it.read } },
                mangaHistory = backup.backupManga.sumOf { m -> m.history.count { it.lastRead > 0 } },
                mangaCategories = backup.backupCategories.count { !it.name.equals("Default", ignoreCase = true) },
                repos =
                    backup.repos.map {
                        RepoReport(it.indexUrl, kindOf(it.kind), it.name, it.signingKey, it.indexUrl in addedRepos)
                    },
                certificates = backup.trustedCertificates.map { CertificateReport(it.hash, it.hash in trusted) },
                extensions = extensions,
                missingSources = missing.sortedWith(compareBy({ it.kind }, { it.name })),
                settingsFiles = backup.settingsFiles.map { it.name },
                sourcePreferences = backup.sourcePreferences.size,
            )
        }

    fun kindOf(value: String): ExtensionKind = ExtensionKind.entries.firstOrNull { it.name == value } ?: ExtensionKind.MANGA
}
