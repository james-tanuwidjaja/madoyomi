package madoyomi.core.backup

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import madoyomi.core.anime.AnimeCategories
import madoyomi.core.anime.model.table.AnimeCategoryAnimeTable
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeHistoryTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionActions
import madoyomi.core.extension.SignatureTrust
import madoyomi.core.library.LibraryUpdateScheduler
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.player.PlayerSettingsStore
import madoyomi.core.reader.ReaderSettingsStore
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.core.repo.RepoService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupImport
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Merge adds what is missing and keeps what is here (the user's default); replace makes the library the backup's. */
enum class RestoreMode { MERGE, REPLACE }

enum class RestoreState { IDLE, RUNNING, SUCCESS, FAILURE }

enum class RestoreStep { SETTINGS, REPOS, CERTIFICATES, EXTENSIONS, SOURCE_PREFERENCES, CATEGORIES, ANIME, MANGA }

/**
 * A restore of the backup file [name]. Nothing that trusts or installs anything happens unless the user picked it in
 * the trust prompt: [repos] (index URLs; adding a repo trusts its signing key), [certificates] (hashes) and
 * [extensions] (package names, reinstalled from their repos). [library] restores the entries, categories, progress
 * and history; [settings] the settings files and the sources' preferences.
 */
data class RestoreRequest(
    val name: String,
    val mode: RestoreMode = RestoreMode.MERGE,
    val library: Boolean = true,
    val settings: Boolean = true,
    val repos: List<String> = emptyList(),
    val certificates: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
)

data class RestoreStatus(
    val state: RestoreState = RestoreState.IDLE,
    val name: String? = null,
    val mode: RestoreMode? = null,
    val step: RestoreStep? = null,
    /** Within the step. */
    val current: Int = 0,
    val total: Int = 0,
    val title: String? = null,
    /** What couldn't be restored, one line each (a repo that didn't answer, an extension no repo lists …). */
    val problems: List<String> = emptyList(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
)

/**
 * Restores a [MadoyomiBackup] (Phase 6 Stage 6), one at a time, in the background; [status] follows it (the
 * `madoyomiRestoreStatus` subscription). The steps, in this order so the sources exist before the library needs them:
 * the settings files, the repos, the certificates, the extensions, the sources' preferences, then the library: the
 * anime categories and entries (mapped by source and URL, as upstream maps manga), then the manga part through
 * upstream's own restore (`ProtoBackupImport`), which merges (read or unread, the furthest page, the latest read).
 *
 * Replace first takes out of the library the entries the backup doesn't have, deletes the categories it doesn't have,
 * and resets the backup's entries' marks, positions and memberships and all history, so the merge that follows gives
 * the backup's state. Repos, certificates and extensions are only ever added.
 */
object MadoyomiBackupRestore {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** The settings files as the stores write them (the live check: a restored file came back on one line). */
    private val prettyJson = Json { prettyPrint = true }
    private val running = AtomicBoolean(false)

    private val statusFlow = MutableStateFlow(RestoreStatus())
    val status: StateFlow<RestoreStatus> = statusFlow.asStateFlow()

    /** Tests: the repo service the restore adds repos through. */
    @Volatile
    var repoService: () -> RepoService = { RepoService.default }

    fun validate(name: String): BackupReport {
        val (backup, format) = BackupFiles.decode(BackupFiles.read(name))
        return MadoyomiBackupReport.of(name, backup, format)
    }

    /** Checks the file, then starts the restore in the background; `BAD_REQUEST` while another one runs. */
    fun start(
        userId: Int,
        request: RestoreRequest,
    ): RestoreStatus {
        val (backup, format) = BackupFiles.decode(BackupFiles.read(request.name))
        if (!running.compareAndSet(false, true)) {
            throw MadoyomiException(ErrorCode.BAD_REQUEST, "A restore is running", mapOf("name" to (statusFlow.value.name ?: "")))
        }
        statusFlow.value =
            RestoreStatus(RestoreState.RUNNING, request.name, request.mode, startedAt = System.currentTimeMillis())
        scope.launch {
            try {
                run(userId, request, backup, format)
            } finally {
                running.set(false)
            }
        }
        return statusFlow.value
    }

    /** Tests: waits for the restore to end and returns its status. */
    suspend fun awaitEnd(): RestoreStatus = status.first { it.state == RestoreState.SUCCESS || it.state == RestoreState.FAILURE }

    private suspend fun run(
        userId: Int,
        request: RestoreRequest,
        backup: MadoyomiBackup,
        format: BackupFormat,
    ) {
        val problems = mutableListOf<String>()
        logger.info { "restore of ${request.name} (${format.name}, ${request.mode.name}) started" }
        try {
            if (request.settings) {
                step(RestoreStep.SETTINGS, backup.settingsFiles.size)
                restoreSettingsFiles(backup.settingsFiles, problems)
            }
            restoreRepos(backup, request, problems)
            restoreCertificates(backup, request)
            restoreExtensions(backup, request, problems)
            if (request.settings) restoreSourcePreferences(backup.sourcePreferences, request.mode)
            if (request.library) {
                if (request.mode == RestoreMode.REPLACE) replacePrep(userId, backup, format)
                step(RestoreStep.CATEGORIES, backup.animeCategories.size)
                val categories = restoreAnimeCategories(backup.animeCategories, request.mode)
                restoreAnime(backup.anime, categories, request.mode)
                restoreManga(userId, backup, request.mode, problems)
            }
            finish(RestoreState.SUCCESS, problems)
            logger.info { "restore of ${request.name} done; problems: ${problems.size}" }
        } catch (e: Exception) {
            logger.error(e) { "restore of ${request.name} failed" }
            problems += "The restore stopped: ${e.message ?: e.javaClass.simpleName}"
            finish(RestoreState.FAILURE, problems)
        }
    }

    private fun step(
        step: RestoreStep,
        total: Int,
        current: Int = 0,
        title: String? = null,
    ) {
        statusFlow.value = statusFlow.value.copy(step = step, total = total, current = current, title = title)
    }

    private fun finish(
        state: RestoreState,
        problems: List<String>,
    ) {
        statusFlow.value =
            statusFlow.value.copy(state = state, problems = problems.toList(), finishedAt = System.currentTimeMillis(), title = null)
    }

    // --- settings ---

    private fun restoreSettingsFiles(
        files: List<SettingsFileBackup>,
        problems: MutableList<String>,
    ) {
        val targets = MadoyomiBackupExport.settingsFiles()
        files.forEachIndexed { index, file ->
            step(RestoreStep.SETTINGS, files.size, index + 1, file.name)
            val target = targets[file.name] ?: return@forEachIndexed
            val parsed = runCatching { json.parseToJsonElement(file.json) as? JsonObject }.getOrNull()
            if (parsed == null) {
                problems += "Settings ${file.name}: not readable"
                return@forEachIndexed
            }
            write(target, prettyJson.encodeToString(JsonObject.serializer(), parsed))
        }
        PlayerSettingsStore.reload()
        ReaderSettingsStore.reload()
        LibraryUpdateSettingsStore.reload()
        DownloadSettingsStore.reload()
        BackupSettingsStore.reload()
        runCatching { LibraryUpdateScheduler.reschedule() }
        runCatching { BackupScheduler.reschedule() }
        DownloadSettingsStore.onChange?.invoke(DownloadSettingsStore.current())
    }

    private fun write(
        file: File,
        text: String,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text + "\n", Charsets.UTF_8)
        java.nio.file.Files
            .move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    // --- repos, certificates, extensions ---

    private suspend fun restoreRepos(
        backup: MadoyomiBackup,
        request: RestoreRequest,
        problems: MutableList<String>,
    ) {
        val picked = backup.repos.filter { it.indexUrl in request.repos }
        val added = dbTransaction { ExtensionStoreTable.selectAll().map { it[ExtensionStoreTable.indexUrl] }.toSet() }
        picked.forEachIndexed { index, repo ->
            step(RestoreStep.REPOS, picked.size, index + 1, repo.name.ifBlank { repo.indexUrl })
            if (repo.indexUrl in added) return@forEachIndexed
            try {
                val format = RepoIndexFormat.entries.firstOrNull { it.name == repo.format } ?: RepoIndexFormat.AUTO
                repoService().add(repo.indexUrl, MadoyomiBackupReport.kindOf(repo.kind), format)
            } catch (e: Exception) {
                problems += "Repo ${repo.name.ifBlank { repo.indexUrl }}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun restoreCertificates(
        backup: MadoyomiBackup,
        request: RestoreRequest,
    ) {
        val picked = backup.trustedCertificates.filter { it.hash in request.certificates }
        picked.forEachIndexed { index, certificate ->
            step(RestoreStep.CERTIFICATES, picked.size, index + 1, certificate.hash.take(16))
            SignatureTrust.trust(certificate.hash, Instant.now().epochSecond)
        }
    }

    private suspend fun restoreExtensions(
        backup: MadoyomiBackup,
        request: RestoreRequest,
        problems: MutableList<String>,
    ) {
        val picked = backup.extensions.filter { it.pkgName in request.extensions }
        picked.forEachIndexed { index, extension ->
            step(RestoreStep.EXTENSIONS, picked.size, index + 1, extension.name.ifBlank { extension.pkgName })
            val kind = MadoyomiBackupReport.kindOf(extension.kind)
            val listing =
                dbTransaction {
                    ExtensionTable
                        .selectAll()
                        .where { (ExtensionTable.pkgName eq extension.pkgName) and (ExtensionTable.type eq kind) }
                        .firstOrNull()
                }
            when {
                listing == null -> {
                    problems += "Extension ${extension.name.ifBlank { extension.pkgName }}: no added repo lists it"
                }

                listing[ExtensionTable.isInstalled] -> {
                    Unit
                }

                else -> {
                    try {
                        ExtensionActions.install(extension.pkgName, kind)
                    } catch (e: Exception) {
                        problems += "Extension ${extension.name.ifBlank { extension.pkgName }}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    private fun restoreSourcePreferences(
        prefs: List<SourcePreferencesBackup>,
        mode: RestoreMode,
    ) {
        val application = Injekt.get<Application>()
        prefs.forEachIndexed { index, source ->
            step(RestoreStep.SOURCE_PREFERENCES, prefs.size, index + 1, source.sourceId.toString())
            val editor = application.getSharedPreferences("source_${source.sourceId}", Context.MODE_PRIVATE).edit()
            if (mode == RestoreMode.REPLACE) editor.clear()
            source.values.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }
    }

    // --- replace ---

    private fun replacePrep(
        userId: Int,
        backup: MadoyomiBackup,
        format: BackupFormat,
    ) = dbTransaction {
        if (format == BackupFormat.MADOYOMI) {
            val keep = backup.anime.map { it.source to it.url }.toSet()
            val library =
                AnimeTable.selectAll().where { AnimeTable.inLibrary eq true }.filter {
                    (it[AnimeTable.sourceReference] to it[AnimeTable.url]) !in keep
                }
            if (library.isNotEmpty()) {
                AnimeTable.update({ AnimeTable.id inList library.map { it[AnimeTable.id].value } }) { it[inLibrary] = false }
            }
            val names = backup.animeCategories.map { it.name.lowercase() }.toSet()
            AnimeTable
                .selectAll()
                .filter { (it[AnimeTable.sourceReference] to it[AnimeTable.url]) in keep }
                .map { it[AnimeTable.id].value }
                .let { ids ->
                    if (ids.isNotEmpty()) {
                        AnimeCategoryAnimeTable.deleteWhere { AnimeCategoryAnimeTable.anime inList ids }
                        EpisodeTable.update({ EpisodeTable.anime inList ids }) {
                            it[watched] = false
                            it[bookmark] = false
                            it[lastSecondSeen] = 0
                        }
                    }
                }
            AnimeCategoryTable
                .selectAll()
                .filter { !it[AnimeCategoryTable.isDefaultCategory] && it[AnimeCategoryTable.name].lowercase() !in names }
                .forEach { row -> AnimeCategoryTable.deleteWhere { AnimeCategoryTable.id eq row[AnimeCategoryTable.id].value } }
            AnimeHistoryTable.deleteAll()
        }

        val keepManga = backup.backupManga.map { it.source to it.url }.toSet()
        val mangaRows = MangaTable.getWithUserData(userId).selectAll().toList()
        val drop =
            mangaRows.filter {
                it[MangaUserTable.inLibrary] == true && (it[MangaTable.sourceReference] to it[MangaTable.url]) !in keepManga
            }
        if (drop.isNotEmpty()) {
            MangaUserTable.update(
                { (MangaUserTable.user eq userId) and (MangaUserTable.manga inList drop.map { it[MangaTable.id].value }) },
            ) {
                it[inLibrary] = false
            }
        }
        val kept = mangaRows.filter { (it[MangaTable.sourceReference] to it[MangaTable.url]) in keepManga }.map { it[MangaTable.id].value }
        if (kept.isNotEmpty()) {
            CategoryMangaTable.deleteWhere { (CategoryMangaTable.manga inList kept) and (CategoryMangaTable.user eq userId) }
            val chapters = ChapterTable.select(ChapterTable.id).where { ChapterTable.manga inList kept }
            ChapterUserTable.update({ (ChapterUserTable.user eq userId) and (ChapterUserTable.chapter inSubQuery chapters) }) {
                it[isRead] = false
                it[isBookmarked] = false
                it[lastPageRead] = 0
            }
        }
        ChapterUserTable.update({ ChapterUserTable.user eq userId }) { it[lastReadAt] = 0 }
        val mangaNames = backup.backupCategories.map { it.name.lowercase() }.toSet()
        CategoryTable
            .selectAll()
            .where { CategoryTable.user eq userId }
            .filter { !it[CategoryTable.isDefaultCategory] && it[CategoryTable.name].lowercase() !in mangaNames }
            .forEach { Category.removeCategory(userId, it[CategoryTable.id].value) }
    }

    // --- anime ---

    /** The anime categories, by lower-case name → id (Default under ""). */
    private fun restoreAnimeCategories(
        categories: List<AnimeCategoryBackup>,
        mode: RestoreMode,
    ): Map<String, Int> =
        dbTransaction {
            val defaultId = AnimeCategories.defaultId()
            val existing =
                AnimeCategoryTable
                    .selectAll()
                    .filter { !it[AnimeCategoryTable.isDefaultCategory] }
                    .associateBy { it[AnimeCategoryTable.name].lowercase() }
            val result = mutableMapOf("" to defaultId)
            existing.forEach { (name, row) -> result[name] = row[AnimeCategoryTable.id].value }
            val created = mutableListOf<Int>()
            categories.sortedBy { it.order }.forEach { category ->
                if (category.isDefaultCategory) {
                    writeCategory(
                        defaultId,
                        category,
                        mode,
                        AnimeCategories.metaOf(AnimeCategories.row(defaultId)),
                        flags =
                            mode == RestoreMode.REPLACE,
                    )
                    return@forEach
                }
                val key = category.name.lowercase()
                val row = existing[key]
                if (row == null) {
                    val id =
                        AnimeCategoryTable
                            .insertAndGetId {
                                it[name] = category.name.trim().take(AnimeCategories.MAX_NAME_LENGTH)
                                it[order] = Int.MAX_VALUE
                            }.value
                    writeCategory(id, category, RestoreMode.REPLACE, emptyMap(), flags = true)
                    result[key] = id
                    created += id
                } else {
                    val id = row[AnimeCategoryTable.id].value
                    writeCategory(id, category, mode, AnimeCategories.metaOf(row), flags = mode == RestoreMode.REPLACE)
                }
            }
            // positions: replace takes the backup's order; merge keeps this install's and appends the new ones
            val others =
                AnimeCategoryTable
                    .selectAll()
                    .filter { !it[AnimeCategoryTable.isDefaultCategory] }
                    .sortedWith(compareBy({ it[AnimeCategoryTable.order] }, { it[AnimeCategoryTable.id].value }))
                    .map { it[AnimeCategoryTable.id].value }
            val ordered =
                if (mode == RestoreMode.REPLACE) {
                    val byBackup =
                        categories
                            .filter { !it.isDefaultCategory }
                            .sortedBy { it.order }
                            .mapNotNull { result[it.name.lowercase()] }
                    byBackup + others.filterNot(byBackup::contains)
                } else {
                    others.filterNot(created::contains) + created
                }
            AnimeCategoryTable.update({ AnimeCategoryTable.id eq defaultId }) { it[order] = 0 }
            ordered.forEachIndexed { index, id -> AnimeCategoryTable.update({ AnimeCategoryTable.id eq id }) { it[order] = index + 1 } }
            result
        }

    private fun writeCategory(
        id: Int,
        category: AnimeCategoryBackup,
        mode: RestoreMode,
        currentMeta: Map<String, String>,
        flags: Boolean,
    ) {
        val meta = if (mode == RestoreMode.REPLACE) category.meta else category.meta + currentMeta
        AnimeCategoryTable.update({ AnimeCategoryTable.id eq id }) {
            if (flags) {
                if (!category.isDefaultCategory) it[isDefault] = category.newEntries
                it[includeInUpdate] = category.includeInUpdate
                it[includeInDownload] = category.includeInDownload
            }
            it[AnimeCategoryTable.meta] = JsonObject(meta.mapValues { (_, v) -> JsonPrimitive(v) })
        }
    }

    private fun restoreAnime(
        entries: List<AnimeBackup>,
        categories: Map<String, Int>,
        mode: RestoreMode,
    ) {
        val ordered = parentsFirst(entries)
        val ids = mutableMapOf<Pair<Long, String>, Int>()
        ordered.forEachIndexed { index, entry ->
            step(RestoreStep.ANIME, ordered.size, index + 1, entry.title)
            dbTransaction {
                val id = restoreEntry(entry, ids, mode)
                ids[entry.source to entry.url] = id
                restoreEpisodes(id, entry.episodes, mode)
                val categoryIds = entry.categories.mapNotNull { categories[it.lowercase()] }.filter { it != categories[""] }
                val current =
                    AnimeCategoryAnimeTable
                        .selectAll()
                        .where { AnimeCategoryAnimeTable.anime eq id }
                        .map { it[AnimeCategoryAnimeTable.category].value }
                        .toSet()
                categoryIds.filterNot(current::contains).distinct().forEach { categoryId ->
                    AnimeCategoryAnimeTable.insert {
                        it[category] = categoryId
                        it[anime] = id
                    }
                }
            }
        }
    }

    private fun parentsFirst(entries: List<AnimeBackup>): List<AnimeBackup> {
        val keys = entries.map { it.source to it.url }.toSet()
        val done = mutableSetOf<Pair<Long, String>>()
        val result = mutableListOf<AnimeBackup>()
        var left = entries
        while (left.isNotEmpty()) {
            val (ready, waiting) =
                left.partition { e -> e.parentUrl == null || (e.source to e.parentUrl) !in keys || (e.source to e.parentUrl) in done }
            if (ready.isEmpty()) {
                result += waiting
                break
            }
            result += ready
            done += ready.map { it.source to it.url }
            left = waiting
        }
        return result
    }

    private fun restoreEntry(
        entry: AnimeBackup,
        ids: Map<Pair<Long, String>, Int>,
        mode: RestoreMode,
    ): Int {
        val db =
            AnimeTable
                .selectAll()
                .where { (AnimeTable.sourceReference eq entry.source) and (AnimeTable.url eq entry.url) }
                .firstOrNull()
        val parent =
            entry.parentUrl?.let { url ->
                ids[entry.source to url] ?: AnimeTable
                    .selectAll()
                    .where { (AnimeTable.sourceReference eq entry.source) and (AnimeTable.url eq url) }
                    .firstOrNull()
                    ?.get(AnimeTable.id)
                    ?.value
            }
        val memo = runCatching { json.parseToJsonElement(entry.memo) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val strategy = AnimeUpdateStrategy.entries.firstOrNull { it.name == entry.updateStrategy } ?: AnimeUpdateStrategy.ALWAYS_UPDATE
        val fetchType = FetchType.entries.firstOrNull { it.name == entry.fetchType } ?: FetchType.Episodes
        if (db == null) {
            return AnimeTable
                .insertAndGetId {
                    it[url] = entry.url
                    it[title] = entry.title
                    it[artist] = entry.artist
                    it[author] = entry.author
                    it[description] = entry.description
                    it[genre] = entry.genre
                    it[status] = entry.status
                    it[thumbnailUrl] = entry.thumbnailUrl
                    it[backgroundUrl] = entry.backgroundUrl
                    it[inLibrary] = entry.inLibrary
                    it[initialized] = entry.initialized
                    it[sourceReference] = entry.source
                    it[addedAt] = entry.addedAt
                    it[lastFetchedAt] = entry.lastFetchedAt
                    it[updateStrategy] = strategy
                    it[AnimeTable.fetchType] = fetchType
                    it[seasonNumber] = entry.seasonNumber
                    it[AnimeTable.memo] = memo
                    it[parentId] = parent
                    it[seasonSourceOrder] = entry.seasonSourceOrder
                }.value
        }
        val id = db[AnimeTable.id].value
        val replace = mode == RestoreMode.REPLACE
        AnimeTable.update({ AnimeTable.id eq id }) {
            it[artist] = entry.artist ?: db[artist]
            it[author] = entry.author ?: db[author]
            it[description] = entry.description ?: db[description]
            it[genre] = entry.genre ?: db[genre]
            it[thumbnailUrl] = entry.thumbnailUrl ?: db[thumbnailUrl]
            it[backgroundUrl] = entry.backgroundUrl ?: db[backgroundUrl]
            it[initialized] = db[initialized] || entry.initialized
            if (replace || !db[inLibrary]) {
                it[inLibrary] = if (replace) entry.inLibrary else entry.inLibrary || db[inLibrary]
                it[addedAt] = entry.addedAt
            }
            if (replace) {
                it[updateStrategy] = strategy
                it[AnimeTable.memo] = memo
            }
            if (db[parentId] == null && parent != null) {
                it[parentId] = parent
                it[seasonSourceOrder] = entry.seasonSourceOrder
            }
        }
        return id
    }

    private fun restoreEpisodes(
        animeId: Int,
        episodes: List<EpisodeBackup>,
        mode: RestoreMode,
    ) {
        if (episodes.isEmpty()) return
        val db = EpisodeTable.selectAll().where { EpisodeTable.anime eq animeId }.associateBy { it[EpisodeTable.url] }
        val seen =
            AnimeHistoryTable
                .selectAll()
                .where { AnimeHistoryTable.episode inList db.values.map { it[EpisodeTable.id].value } }
                .associate { it[AnimeHistoryTable.episode].value to it[AnimeHistoryTable.lastSeen] }
        episodes.forEach { e ->
            val row = db[e.url]
            val episodeId =
                if (row == null) {
                    EpisodeTable
                        .insertAndGetId {
                            it[url] = e.url
                            it[name] = e.name
                            it[episodeNumber] = e.episodeNumber
                            it[dateUpload] = e.dateUpload
                            it[scanlator] = e.scanlator
                            it[watched] = e.watched
                            it[bookmark] = e.bookmark
                            it[lastSecondSeen] = e.lastSecondSeen
                            it[totalSeconds] = e.totalSeconds
                            it[sourceOrder] = e.sourceOrder
                            it[fetchedAt] = e.fetchedAt
                            it[anime] = animeId
                            it[fillermark] = e.fillermark
                            it[summary] = e.summary
                            it[previewUrl] = e.previewUrl
                            it[memo] = runCatching { json.parseToJsonElement(e.memo) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
                        }.value
                } else {
                    val id = row[EpisodeTable.id].value
                    val newer = mode == RestoreMode.REPLACE || e.lastSeen > (seen[id] ?: 0)
                    EpisodeTable.update({ EpisodeTable.id eq id }) {
                        if (mode == RestoreMode.REPLACE) {
                            it[watched] = e.watched
                            it[bookmark] = e.bookmark
                            it[fillermark] = e.fillermark
                        } else {
                            it[watched] = row[watched] || e.watched
                            it[bookmark] = row[bookmark] || e.bookmark
                            it[fillermark] = row[fillermark] || e.fillermark
                        }
                        if (newer) {
                            it[lastSecondSeen] = e.lastSecondSeen
                            if (e.totalSeconds > 0) it[totalSeconds] = e.totalSeconds
                        }
                    }
                    id
                }
            if (e.lastSeen > 0 && e.lastSeen > (seen[episodeId] ?: 0)) {
                AnimeHistoryTable.upsert(AnimeHistoryTable.episode) {
                    it[episode] = episodeId
                    it[lastSeen] = e.lastSeen
                }
            }
        }
    }

    // --- manga ---

    private suspend fun restoreManga(
        userId: Int,
        backup: MadoyomiBackup,
        mode: RestoreMode,
        problems: MutableList<String>,
    ) {
        if (backup.backupManga.isEmpty() && backup.backupCategories.isEmpty()) return
        val before =
            dbTransaction {
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.user eq userId }
                    .map { it[CategoryTable.name].lowercase() }
                    .toSet()
            }
        val upstream =
            Backup(backup.backupManga, backup.backupCategories, backup.backupSources, backup.meta)
        val bytes = upstreamBytes(upstream)
        val restoreId = "madoyomi-${System.currentTimeMillis()}"
        step(RestoreStep.MANGA, backup.backupManga.size)
        val progress =
            scope.launch {
                while (true) {
                    when (val state = ProtoBackupImport.getRestoreState(restoreId)) {
                        is ProtoBackupImport.BackupRestoreState.RestoringManga -> {
                            step(RestoreStep.MANGA, state.totalManga, state.current, state.title)
                        }

                        else -> {
                            Unit
                        }
                    }
                    delay(200)
                }
            }
        try {
            ProtoBackupImport.restoreLegacy(userId, bytes.inputStream(), restoreId, MadoyomiBackupExport.MANGA_FLAGS)
        } finally {
            progress.cancel()
        }
        if (ProtoBackupImport.getRestoreState(restoreId) == ProtoBackupImport.BackupRestoreState.Failure) {
            problems += "Manga: upstream's restore failed (see the log)"
        }
        // the flags upstream's BackupCategory doesn't carry; merge sets them only on the categories it created
        dbTransaction {
            backup.mangaCategoryFlags.forEach { flags ->
                val key = flags.name.lowercase()
                if (mode == RestoreMode.MERGE && key in before) return@forEach
                CategoryTable.update({ (CategoryTable.user eq userId) and (CategoryTable.name eq flags.name) }) {
                    if (!flags.name.equals(Category.DEFAULT_CATEGORY_NAME, ignoreCase = true)) it[isDefault] = flags.newEntries
                    it[includeInUpdate] = flags.includeInUpdate
                    it[includeInDownload] = flags.includeInDownload
                }
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun upstreamBytes(backup: Backup): ByteArray {
        val raw =
            kotlinx.serialization.protobuf.ProtoBuf
                .encodeToByteArray(Backup.serializer(), backup)
        return java.io
            .ByteArrayOutputStream()
            .also { out ->
                java.util.zip
                    .GZIPOutputStream(out)
                    .use { it.write(raw) }
            }.toByteArray()
    }
}
