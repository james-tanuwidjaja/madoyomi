package madoyomi.core.downloads

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import madoyomi.core.anime.AnimeCategories
import madoyomi.core.anime.AnimeEntries
import madoyomi.core.anime.AnimeVideos
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.library.LibraryUpdater
import madoyomi.core.player.PlaybackSessions
import madoyomi.core.player.PlayerSettingsStore
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.SafePath
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The anime side of the queue: which episodes to queue, where a downloaded episode goes and what is
 * recorded of it ([EpisodeDownloadTable]), deleting downloads, the automatic downloads after a library update check,
 * and playing a downloaded episode in mpv.
 *
 * Files: `<downloads>/anime/<source (LANG)>/<title>/<scanlator_><episode>.<ext>`, the subtitles beside it as
 * `<episode>.<lang>.<ext>` and the external audio tracks as `<episode>.<lang>.audio.<ext>` (upstream's naming for
 * chapters, `SafePath.buildValidFilename`).
 */
object AnimeDownloads {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    /** Tests: another folder than `<downloads>`. */
    @Volatile
    var downloadsRootOverride: File? = null

    fun downloadsRoot(): File = downloadsRootOverride ?: File(applicationDirs.downloadsRoot)

    fun animeRoot(): File = File(downloadsRoot(), "anime")

    /** A file beside the video, in [EpisodeDownloadTable.subtitles]: a subtitle, or an audio track (a later fix). */
    @Serializable
    data class SubtitleFile(
        val path: String,
        val lang: String,
        val kind: String = SUBTITLE,
    )

    private const val SUBTITLE = "subtitle"
    private const val AUDIO = "audio"

    /** A downloaded episode, its paths resolved. */
    data class LocalEpisode(
        val episodeId: Int,
        val animeId: Int,
        val file: File,
        val subtitles: List<Pair<File, String>>,
        val videoTitle: String,
        val method: EpisodeDownloadMethod,
        val bytes: Long,
        val downloadedAt: Long,
        /** The external audio tracks saved beside the video, with their labels. */
        val audio: List<Pair<File, String>> = emptyList(),
    )

    private fun toLocal(row: ResultRow): LocalEpisode {
        val root = downloadsRoot()
        val subs =
            runCatching { json.decodeFromString(ListSerializer(SubtitleFile.serializer()), row[EpisodeDownloadTable.subtitles]) }
                .getOrDefault(emptyList())
        return LocalEpisode(
            episodeId = row[EpisodeDownloadTable.episode].value,
            animeId = row[EpisodeDownloadTable.anime].value,
            file = File(root, row[EpisodeDownloadTable.path]),
            subtitles = subs.filter { it.kind != AUDIO }.map { File(root, it.path) to it.lang },
            audio = subs.filter { it.kind == AUDIO }.map { File(root, it.path) to it.lang },
            videoTitle = row[EpisodeDownloadTable.videoTitle],
            method = row[EpisodeDownloadTable.method],
            bytes = row[EpisodeDownloadTable.bytes],
            downloadedAt = row[EpisodeDownloadTable.downloadedAt],
        )
    }

    /** The downloaded episode [episodeId], if it is recorded (its file may have gone since: check [LocalEpisode.file]). */
    fun local(episodeId: Int): LocalEpisode? =
        dbTransaction {
            EpisodeDownloadTable
                .selectAll()
                .where { EpisodeDownloadTable.episode eq episodeId }
                .firstOrNull()
                ?.let(::toLocal)
        }

    /** Which of [episodeIds] are downloaded (recorded). */
    fun downloadedIds(episodeIds: Collection<Int>): Set<Int> {
        if (episodeIds.isEmpty()) return emptySet()
        return dbTransaction {
            EpisodeDownloadTable
                .select(EpisodeDownloadTable.episode)
                .where { EpisodeDownloadTable.episode inList episodeIds.distinct() }
                .map { it[EpisodeDownloadTable.episode].value }
                .toSet()
        }
    }

    /**
     * How many episodes of each of [animeIds] are downloaded (the library card's "↓ N"). A Seasons entry counts its
     * seasons' (one level, as [madoyomi.core.library.AnimeHistory.stats] counts them).
     */
    fun counts(animeIds: Collection<Int>): Map<Int, Int> {
        val ids = animeIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val n = EpisodeDownloadTable.id.count()
        return dbTransaction {
            val owner = HashMap<Int, MutableList<Int>>()
            ids.forEach { owner.getOrPut(it) { mutableListOf() }.add(it) }
            AnimeTable
                .select(AnimeTable.id, AnimeTable.parentId)
                .where { AnimeTable.parentId inList ids }
                .forEach { row -> owner.getOrPut(row[AnimeTable.id].value) { mutableListOf() }.add(row[AnimeTable.parentId]!!.value) }
            val counts = HashMap<Int, Int>()
            EpisodeDownloadTable
                .select(EpisodeDownloadTable.anime, n)
                .where { EpisodeDownloadTable.anime inList owner.keys.toList() }
                .groupBy(EpisodeDownloadTable.anime)
                .forEach { row ->
                    owner[row[EpisodeDownloadTable.anime].value].orEmpty().forEach { o -> counts.merge(o, row[n].toInt(), Int::plus) }
                }
            ids.associateWith { counts[it] ?: 0 }
        }
    }

    /** Where [episodeId]'s files go, without the extension: `<anime root>/<source (LANG)>/<title>/<episode>`. */
    fun targetBase(episodeId: Int): File {
        val (episode, anime, source) =
            dbTransaction {
                val e = EpisodeTable.selectAll().where { EpisodeTable.id eq episodeId }.single()
                val a = AnimeTable.selectAll().where { AnimeTable.id eq e[EpisodeTable.anime] }.single()
                val s = AnimeSourceTable.selectAll().where { AnimeSourceTable.id eq a[AnimeTable.sourceReference] }.firstOrNull()
                Triple(e, a, s)
            }
        val sourceName =
            source?.let { "${it[AnimeSourceTable.name]} (${it[AnimeSourceTable.lang].uppercase()})" }
                ?: anime[AnimeTable.sourceReference].toString()
        val scanlator = episode[EpisodeTable.scanlator]
        val name = if (scanlator.isNullOrBlank()) episode[EpisodeTable.name] else "${scanlator}_${episode[EpisodeTable.name]}"
        val dir = File(File(animeRoot(), SafePath.buildValidFilename(sourceName)), SafePath.buildValidFilename(anime[AnimeTable.title]))
        var base = SafePath.buildValidFilename(name.ifBlank { "Episode $episodeId" })
        // another episode's download with the same name (two episodes named alike): this one gets its id
        val taken =
            dbTransaction {
                EpisodeDownloadTable
                    .selectAll()
                    .where { EpisodeDownloadTable.anime eq anime[AnimeTable.id] }
                    .filter { it[EpisodeDownloadTable.episode].value != episodeId }
                    .map { File(it[EpisodeDownloadTable.path]).nameWithoutExtension }
                    .toSet()
            }
        if (base in taken) base = "$base [$episodeId]"
        return File(dir, base)
    }

    /**
     * Moves the staged [video] and [subtitles] to [targetBase] and records the episode as downloaded (an earlier
     * download of it replaced). Returns what was recorded.
     */
    fun place(
        episodeId: Int,
        video: File,
        subtitles: List<AnimeEpisodeDownloader.StagedTrack>,
        videoTitle: String,
        method: EpisodeDownloadMethod,
        audio: List<AnimeEpisodeDownloader.StagedTrack> = emptyList(),
    ): LocalEpisode {
        local(episodeId)?.let { deleteFiles(it) }
        val base = targetBase(episodeId)
        base.parentFile.mkdirs()
        val target = File(base.parentFile, "${base.name}.${video.extension.ifBlank { "mp4" }}")
        move(video, target)

        fun placeAll(
            tracks: List<AnimeEpisodeDownloader.StagedTrack>,
            fallback: String,
            suffix: String,
        ): List<Pair<File, String>> {
            val usedLangs = mutableMapOf<String, Int>()
            return tracks.map { track ->
                val lang =
                    SafePath
                        .buildValidFilename(
                            track.lang
                                .trim()
                                .ifBlank { fallback }
                                .take(32),
                        ).ifBlank { fallback }
                val n = usedLangs.merge(lang.lowercase(), 1, Int::plus)!!
                val label = if (n == 1) lang else "$lang-$n"
                val file = File(base.parentFile, "${base.name}.$label$suffix.${track.file.extension}")
                move(track.file, file)
                file to track.lang
            }
        }
        val subs = placeAll(subtitles, "sub", "")
        val audios = placeAll(audio, "audio", ".audio")
        val bytes = target.length() + (subs + audios).sumOf { it.first.length() }
        val root = downloadsRoot()
        val animeId =
            dbTransaction {
                EpisodeTable
                    .selectAll()
                    .where { EpisodeTable.id eq episodeId }
                    .single()[EpisodeTable.anime]
                    .value
            }
        val now = System.currentTimeMillis()
        dbTransaction {
            EpisodeDownloadTable.upsert(EpisodeDownloadTable.episode) {
                it[episode] = episodeId
                it[anime] = animeId
                it[path] = relative(root, target)
                it[EpisodeDownloadTable.subtitles] =
                    json.encodeToString(
                        ListSerializer(SubtitleFile.serializer()),
                        subs.map { (f, l) -> SubtitleFile(relative(root, f), l) } +
                            audios.map { (f, l) -> SubtitleFile(relative(root, f), l, AUDIO) },
                    )
                it[EpisodeDownloadTable.videoTitle] = videoTitle.take(512)
                it[EpisodeDownloadTable.method] = method
                it[EpisodeDownloadTable.bytes] = bytes
                it[downloadedAt] = now
            }
        }
        return LocalEpisode(episodeId, animeId, target, subs, videoTitle, method, bytes, now, audios)
    }

    private fun relative(
        root: File,
        file: File,
    ): String =
        root.absoluteFile
            .toPath()
            .relativize(file.absoluteFile.toPath())
            .toString()
            .replace('\\', '/')

    private fun move(
        from: File,
        to: File,
    ) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Queues [episodeIds] for [userId] in watching order (by anime, then the source's order, oldest first). Episodes
     * already downloaded (their file there) and unknown ids are left out. Returns how many were queued.
     */
    fun enqueue(
        userId: Int,
        episodeIds: List<Int>,
    ): Int {
        if (episodeIds.isEmpty()) return 0
        val rows =
            dbTransaction {
                (EpisodeTable innerJoin AnimeTable)
                    .selectAll()
                    .where { EpisodeTable.id inList episodeIds.distinct() }
                    .orderBy(EpisodeTable.anime to SortOrder.ASC, EpisodeTable.sourceOrder to SortOrder.ASC)
                    .toList()
            }
        val downloaded = downloadedIds(rows.map { it[EpisodeTable.id].value })
        val items =
            rows
                .filter { row -> row[EpisodeTable.id].value !in downloaded || local(row[EpisodeTable.id].value)?.file?.isFile != true }
                .map {
                    DownloadQueue.NewItem(
                        kind = ExtensionKind.ANIME,
                        entryId = it[EpisodeTable.anime].value,
                        itemId = it[EpisodeTable.id].value,
                        sourceId = it[AnimeTable.sourceReference],
                        userId = userId,
                    )
                }
        return DownloadQueue.enqueue(items)
    }

    /**
     * Deletes the downloads of [episodeIds]: unfinished queue items are cancelled (with what they staged), the files
     * deleted (the video and its subtitles, then the title's folder if it is empty), the records dropped and their
     * rows taken off the completed list. Returns how many episodes were downloaded or queued.
     */
    suspend fun delete(episodeIds: List<Int>): Int {
        if (episodeIds.isEmpty()) return 0
        val ids = episodeIds.distinct()
        val cancelled = DownloadQueue.cancelItems(ExtensionKind.ANIME, ids)
        val locals =
            dbTransaction {
                EpisodeDownloadTable.selectAll().where { EpisodeDownloadTable.episode inList ids }.map(::toLocal)
            }
        locals.forEach(::deleteFiles)
        if (locals.isNotEmpty()) {
            dbTransaction { EpisodeDownloadTable.deleteWhere { episode inList locals.map { it.episodeId } } }
            logger.info { "deleted ${locals.size} downloaded episodes" }
        }
        DownloadQueue.forgetDone(ExtensionKind.ANIME, ids)
        return cancelled + locals.size
    }

    private fun deleteFiles(local: LocalEpisode) {
        (listOf(local.file) + local.subtitles.map { it.first } + local.audio.map { it.first }).forEach { f ->
            if (f.exists() && !f.delete()) logger.warn { "could not delete ${f.path}" }
        }
        // the title's folder, then the source's, when nothing else is in them
        var dir = local.file.parentFile
        repeat(2) {
            if (dir != null && dir.isDirectory && dir.list()?.isEmpty() == true && dir.absoluteFile != animeRoot().absoluteFile) {
                dir.delete()
                dir = dir.parentFile
            }
        }
    }

    /**
     * After a library update check: queues the new episodes of [entry] when the setting is on and the entry is in a
     * category with Downloads on (upstream's `includeInDownload` rule, as [isInIncludedDownloadCategory]).
     */
    fun autoDownload(
        entry: LibraryUpdater.Entry,
        newEpisodeIds: List<Int>,
    ) {
        if (entry.kind != ExtensionKind.ANIME || newEpisodeIds.isEmpty()) return
        if (!DownloadSettingsStore.current().autoDownload) return
        if (!isInIncludedDownloadCategory(entry.id)) return
        val queued = enqueue(entry.userId, newEpisodeIds)
        if (queued > 0) logger.info { "auto-download: $queued new episodes of '${entry.title}' queued" }
    }

    /**
     * Upstream's rule for manga (`Manga.isInIncludedDownloadCategory`) on the anime categories: the categories with
     * Downloads INCLUDE, else the UNSET ones, are downloaded, unless the anime is also in an EXCLUDE one; an anime in no
     * category is in Default.
     */
    fun isInIncludedDownloadCategory(animeId: Int): Boolean {
        val all = AnimeCategories.list()
        val mine =
            AnimeCategories
                .categoriesOf(listOf(animeId))[animeId]
                .orEmpty()
                .map { it[AnimeCategoryTable.id].value }
                .ifEmpty { all.filter { it[AnimeCategoryTable.isDefaultCategory] }.map { it[AnimeCategoryTable.id].value } }
                .toSet()
        val byFlag = all.groupBy { IncludeOrExclude.fromValue(it[AnimeCategoryTable.includeInDownload]) }
        val included = byFlag[IncludeOrExclude.INCLUDE].orEmpty().ifEmpty { byFlag[IncludeOrExclude.UNSET].orEmpty() }
        val excluded = byFlag[IncludeOrExclude.EXCLUDE].orEmpty()
        if (excluded.any { it[AnimeCategoryTable.id].value in mine }) return false
        return included.any { it[AnimeCategoryTable.id].value in mine }
    }

    /**
     * Plays the downloaded episode [episodeId] in mpv from [startSeconds] (with [resume] and no start: from where it
     * stopped, by the app's rule, [resumePoint]): the player's session (progress saves, the watched mark, the next
     * episode), with the local file in place of the URL and its subtitle files as the external tracks. Nothing goes to
     * the source, and a failure isn't retried with fetched videos. `NOT_FOUND` when the episode isn't downloaded or its
     * file is gone; `DOWNLOADS_UNREACHABLE` when the whole downloads folder is.
     */
    fun play(
        episodeId: Int,
        startSeconds: Double?,
        resume: Boolean = false,
    ): PlaybackSessions.Session {
        val local =
            local(episodeId)
                ?: throw MadoyomiException(ErrorCode.NOT_FOUND, "Episode $episodeId isn't downloaded", mapOf("episodeId" to episodeId))
        if (!local.file.isFile) {
            // The whole folder gone (an unplugged drive) says so, rather than "the file is gone"
            if (downloadsRootOverride == null) DownloadsFolder.requireReachable()
            throw MadoyomiException(
                ErrorCode.NOT_FOUND,
                "The downloaded file is gone: ${local.file.path}",
                mapOf("episodeId" to episodeId, "path" to local.file.path),
            )
        }
        val episode = AnimeEntries.episodeRow(episodeId)
        val anime = AnimeEntries.animeRow(episode[EpisodeTable.anime].value)
        val start =
            startSeconds ?: if (resume) {
                resumePoint(
                    episode[EpisodeTable.watched],
                    episode[EpisodeTable.lastSecondSeen],
                    episode[EpisodeTable.totalSeconds],
                    PlayerSettingsStore.current().watchedThreshold,
                )
            } else {
                null
            }
        val video =
            AnimeVideos.EpisodeVideo(
                index = 0,
                videoTitle = local.videoTitle.ifBlank { local.file.name },
                videoUrl = local.file.absolutePath,
                videoPageUrl = "",
                resolution = null,
                bitrate = null,
                headers = emptyList(),
                preferred = true,
                initialized = true,
                subtitleTracks =
                    local.subtitles.filter { it.first.isFile }.map { (f, lang) ->
                        AnimeVideos.VideoTrack(f.absolutePath, lang)
                    },
                audioTracks = local.audio.filter { it.first.isFile }.map { (f, lang) -> AnimeVideos.VideoTrack(f.absolutePath, lang) },
                timestamps = emptyList(),
                mpvArgs = emptyList(),
            )
        logger.info {
            "playback: episode $episodeId from its download (${local.subtitles.size} subtitles, ${local.audio.size} audio tracks)"
        }
        return PlaybackSessions.launch(
            episodeId,
            anime[AnimeTable.id].value,
            "${anime[AnimeTable.title]} - ${episode[EpisodeTable.name]}",
            video,
            start,
            ref = null,
        )
    }

    /**
     * Where a stopped episode resumes, in seconds (the app's `resumePoint`): from its position when it is not watched
     * and stopped between 1 % and the watched [threshold] of a known duration (both in milliseconds); else null.
     */
    fun resumePoint(
        watched: Boolean,
        positionMs: Long,
        durationMs: Long,
        threshold: Double,
    ): Double? {
        if (watched || durationMs <= 0 || positionMs <= 0) return null
        val share = positionMs.toDouble() / durationMs
        return if (share >= 0.01 && share < threshold) positionMs / 1000.0 else null
    }
}
