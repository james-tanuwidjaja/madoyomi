package madoyomi.core.backup

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import madoyomi.core.MadoyomiBuildInfo
import madoyomi.core.anime.AnimeCategories
import madoyomi.core.anime.model.table.AnimeCategoryAnimeTable
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeHistoryTable
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.extension.SignatureTrustOrigin
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.player.PlayerSettingsStore
import madoyomi.core.reader.ReaderSettingsStore
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupCategoryHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupGlobalMetaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSourceHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupSource
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Writes a [MadoyomiBackup] of everything a fresh install needs to look the same (Phase 6 Stage 6): the manga part
 * through upstream's handlers (library manga with their chapters, history, categories, meta: the per-manga reader
 * settings), and Madoyomi's own: the anime library with its seasons, episodes, positions, history and categories, the
 * manga categories' flags, the repos, the certificates the user trusted, the installed extensions (not the APKs), each
 * source's preferences and the backend's settings files. Not the downloads, the page cache, the update marks, the
 * download queue or the app's own per-device settings (the webview's localStorage).
 */
object MadoyomiBackupExport {
    /** Upstream's flags for the manga part: everything but its server and user settings (see [MadoyomiBackup]). */
    val MANGA_FLAGS =
        BackupFlags.DEFAULT.copy(includeServerSettings = false, includeUserSettings = false)

    /** Preference keys an extension keeps to itself (Mihon leaves them out of its backups too). */
    private val PRIVATE_PREFIXES = listOf("__PRIVATE_", "__APP_STATE_")

    /** The schedule anchors in the settings files: a restore starts counting afresh. */
    private val TRANSIENT_KEYS = setOf("scheduleAnchorAt")

    private val json = Json { encodeDefaults = true }

    /** The settings files carried by a backup, by name. */
    fun settingsFiles(): Map<String, File> =
        linkedMapOf(
            PlayerSettingsStore.FILE_NAME to PlayerSettingsStore.file(),
            ReaderSettingsStore.FILE_NAME to ReaderSettingsStore.file(),
            LibraryUpdateSettingsStore.FILE_NAME to LibraryUpdateSettingsStore.file(),
            DownloadSettingsStore.FILE_NAME to DownloadSettingsStore.file(),
            BackupSettingsStore.FILE_NAME to BackupSettingsStore.file(),
        )

    fun create(
        userId: Int,
        automatic: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): MadoyomiBackup =
        dbTransaction {
            val manga = BackupMangaHandler.backup(userId, MANGA_FLAGS)
            val extensions = extensions()
            val anime = anime()
            MadoyomiBackup(
                backupManga = manga,
                backupCategories = BackupCategoryHandler.backup(userId, MANGA_FLAGS),
                backupSources = BackupSourceHandler.backup(userId, manga, MANGA_FLAGS),
                meta = BackupGlobalMetaHandler.backup(userId, MANGA_FLAGS),
                info = BackupInfo(appVersion = MadoyomiBuildInfo.version, createdAt = now, automatic = automatic),
                anime = anime,
                animeCategories = animeCategories(),
                animeSources = animeSources(anime.map { it.source }.toSet()),
                mangaCategoryFlags = mangaCategoryFlags(userId),
                repos = repos(),
                trustedCertificates = certificates(),
                extensions = extensions,
                sourcePreferences = sourcePreferences(extensions),
                settingsFiles = settings(),
            )
        }

    /** The library anime and, recursively, their seasons. */
    private fun anime(): List<AnimeBackup> {
        val rows = AnimeTable.selectAll().where { AnimeTable.inLibrary eq true }.toMutableList()
        var frontier = rows.map { it[AnimeTable.id].value }
        val seen = frontier.toMutableSet()
        while (frontier.isNotEmpty()) {
            val children =
                AnimeTable
                    .selectAll()
                    .where { (AnimeTable.parentId inList frontier) and (AnimeTable.id notInList seen) }
                    .toList()
            rows += children
            frontier = children.map { it[AnimeTable.id].value }
            seen += frontier
        }
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[AnimeTable.id].value }
        val urlById = rows.associate { it[AnimeTable.id].value to it[AnimeTable.url] }
        val episodes =
            EpisodeTable
                .selectAll()
                .where { EpisodeTable.anime inList ids }
                .orderBy(EpisodeTable.sourceOrder to SortOrder.ASC)
                .groupBy { it[EpisodeTable.anime].value }
        val lastSeen =
            AnimeHistoryTable
                .selectAll()
                .where { AnimeHistoryTable.episode inList episodes.values.flatten().map { it[EpisodeTable.id].value } }
                .associate { it[AnimeHistoryTable.episode].value to it[AnimeHistoryTable.lastSeen] }
        val categories =
            (AnimeCategoryAnimeTable innerJoin AnimeCategoryTable)
                .selectAll()
                .where { AnimeCategoryAnimeTable.anime inList ids }
                .orderBy(AnimeCategoryTable.order to SortOrder.ASC)
                .groupBy({ it[AnimeCategoryAnimeTable.anime].value }, { it[AnimeCategoryTable.name] })
        return rows.map { row ->
            val id = row[AnimeTable.id].value
            AnimeBackup(
                source = row[AnimeTable.sourceReference],
                url = row[AnimeTable.url],
                title = row[AnimeTable.title],
                artist = row[AnimeTable.artist],
                author = row[AnimeTable.author],
                description = row[AnimeTable.description],
                genre = row[AnimeTable.genre],
                status = row[AnimeTable.status],
                thumbnailUrl = row[AnimeTable.thumbnailUrl],
                backgroundUrl = row[AnimeTable.backgroundUrl],
                inLibrary = row[AnimeTable.inLibrary],
                addedAt = row[AnimeTable.addedAt],
                lastFetchedAt = row[AnimeTable.lastFetchedAt],
                initialized = row[AnimeTable.initialized],
                updateStrategy = row[AnimeTable.updateStrategy].name,
                fetchType = row[AnimeTable.fetchType].name,
                seasonNumber = row[AnimeTable.seasonNumber],
                memo = json.encodeToString(JsonObject.serializer(), row[AnimeTable.memo]),
                parentUrl = row[AnimeTable.parentId]?.value?.let { urlById[it] ?: parentUrl(it) },
                seasonSourceOrder = row[AnimeTable.seasonSourceOrder],
                episodes =
                    episodes[id].orEmpty().map { e ->
                        EpisodeBackup(
                            url = e[EpisodeTable.url],
                            name = e[EpisodeTable.name],
                            episodeNumber = e[EpisodeTable.episodeNumber],
                            dateUpload = e[EpisodeTable.dateUpload],
                            scanlator = e[EpisodeTable.scanlator],
                            watched = e[EpisodeTable.watched],
                            bookmark = e[EpisodeTable.bookmark],
                            lastSecondSeen = e[EpisodeTable.lastSecondSeen],
                            totalSeconds = e[EpisodeTable.totalSeconds],
                            sourceOrder = e[EpisodeTable.sourceOrder],
                            fetchedAt = e[EpisodeTable.fetchedAt],
                            fillermark = e[EpisodeTable.fillermark],
                            summary = e[EpisodeTable.summary],
                            previewUrl = e[EpisodeTable.previewUrl],
                            memo = json.encodeToString(JsonObject.serializer(), e[EpisodeTable.memo]),
                            lastSeen = lastSeen[e[EpisodeTable.id].value] ?: 0,
                        )
                    },
                categories = categories[id].orEmpty(),
            )
        }
    }

    private fun parentUrl(id: Int): String? =
        AnimeTable
            .selectAll()
            .where { AnimeTable.id eq id }
            .firstOrNull()
            ?.get(AnimeTable.url)

    private fun animeCategories(): List<AnimeCategoryBackup> =
        AnimeCategories.list().map {
            AnimeCategoryBackup(
                name = it[AnimeCategoryTable.name],
                order = it[AnimeCategoryTable.order],
                isDefaultCategory = it[AnimeCategoryTable.isDefaultCategory],
                newEntries = it[AnimeCategoryTable.isDefault],
                includeInUpdate = it[AnimeCategoryTable.includeInUpdate],
                includeInDownload = it[AnimeCategoryTable.includeInDownload],
                meta = AnimeCategories.metaOf(it),
            )
        }

    private fun animeSources(ids: Set<Long>): List<BackupSource> {
        if (ids.isEmpty()) return emptyList()
        val names =
            AnimeSourceTable
                .selectAll()
                .where { AnimeSourceTable.id inList ids }
                .associate { it[AnimeSourceTable.id].value to it[AnimeSourceTable.name] }
        return ids.sorted().map { BackupSource(name = names[it] ?: "", sourceId = it) }
    }

    private fun mangaCategoryFlags(userId: Int): List<CategoryFlagsBackup> =
        CategoryTable
            .selectAll()
            .where { CategoryTable.user eq userId }
            .orderBy(CategoryTable.order to SortOrder.ASC)
            .map {
                CategoryFlagsBackup(
                    name = it[CategoryTable.name],
                    newEntries = it[CategoryTable.isDefault],
                    includeInUpdate = it[CategoryTable.includeInUpdate],
                    includeInDownload = it[CategoryTable.includeInDownload],
                )
            }

    private fun repos(): List<RepoBackup> =
        ExtensionStoreTable
            .selectAll()
            .orderBy(ExtensionStoreTable.id to SortOrder.ASC)
            .map {
                RepoBackup(
                    indexUrl = it[ExtensionStoreTable.indexUrl],
                    kind = it[ExtensionStoreTable.type].name,
                    format = it[ExtensionStoreTable.format].name,
                    name = it[ExtensionStoreTable.name],
                    signingKey = it[ExtensionStoreTable.signingKey],
                    signingKeyTrusted = it[ExtensionStoreTable.signingKeyTrusted],
                )
            }

    private fun certificates(): List<CertificateBackup> =
        TrustedSignatureTable
            .selectAll()
            .where { TrustedSignatureTable.origin eq SignatureTrustOrigin.USER }
            .orderBy(TrustedSignatureTable.addedAt to SortOrder.ASC)
            .map { CertificateBackup(hash = it[TrustedSignatureTable.id].value, addedAt = it[TrustedSignatureTable.addedAt]) }

    private fun extensions(): List<ExtensionBackup> {
        val installed =
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.isInstalled eq true }
                .orderBy(ExtensionTable.type to SortOrder.ASC, ExtensionTable.pkgName to SortOrder.ASC)
                .toList()
        if (installed.isEmpty()) return emptyList()
        val ids = installed.map { it[ExtensionTable.id].value }
        val mangaSources =
            SourceTable
                .selectAll()
                .where { SourceTable.extension inList ids }
                .groupBy({ it[SourceTable.extension].value }, { it[SourceTable.id].value })
        val animeSources =
            AnimeSourceTable
                .selectAll()
                .where { AnimeSourceTable.extension inList ids }
                .groupBy({ it[AnimeSourceTable.extension].value }, { it[AnimeSourceTable.id].value })
        return installed.map {
            val id = it[ExtensionTable.id].value
            val kind = it[ExtensionTable.type]
            ExtensionBackup(
                pkgName = it[ExtensionTable.pkgName],
                kind = kind.name,
                name = it[ExtensionTable.name],
                repoUrl = it[ExtensionTable.storeIndexUrl],
                versionName = it[ExtensionTable.versionName],
                signatureHash = it[ExtensionTable.signatureHash],
                sourceIds = (if (kind == ExtensionKind.ANIME) animeSources[id] else mangaSources[id]).orEmpty().sorted(),
            )
        }
    }

    private fun sourcePreferences(extensions: List<ExtensionBackup>): List<SourcePreferencesBackup> {
        val application = Injekt.get<Application>()
        return extensions.flatMap { extension ->
            extension.sourceIds.mapNotNull { sourceId ->
                val values =
                    application
                        .getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
                        .all
                        .filterKeys { key -> PRIVATE_PREFIXES.none(key::startsWith) }
                        .mapNotNull { (key, value) -> value?.let { key to it.toString() } }
                        .toMap()
                values.takeIf { it.isNotEmpty() }?.let { SourcePreferencesBackup(sourceId, extension.kind, it) }
            }
        }
    }

    private fun settings(): List<SettingsFileBackup> =
        settingsFiles().mapNotNull { (name, file) ->
            if (!file.isFile) return@mapNotNull null
            val parsed = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            SettingsFileBackup(name, json.encodeToString(JsonObject.serializer(), JsonObject(parsed - TRANSIENT_KEYS)))
        }
}
