package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.downloads.AnimeDownloads
import madoyomi.core.downloads.DownloadItem
import madoyomi.core.downloads.DownloadItemState
import madoyomi.core.downloads.DownloadProgressUnit
import madoyomi.core.downloads.DownloadQueue
import madoyomi.core.downloads.DownloadSettings
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.downloads.DownloadTools
import madoyomi.core.downloads.MangaDownloads
import madoyomi.exthost.ExtensionKind
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.requireUser
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.CompletableFuture

/*
 * The download queue (Phase 6 Stage 4, madoyomi.core.downloads), for both kinds (manga in Stage 4, anime in Stage 5):
 * the queue and the completed list, queueing, pause, resume, retry, cancel, reorder, deleting downloaded chapters and
 * episodes, the settings with the tools' check (Stage 5), playing a downloaded episode (Stage 5), and the
 * `downloadsChanged` subscription (the WS "downloads" event). Named apart from upstream's
 * `enqueueChapterDownloads`/`downloadStatus`, which stay but the app doesn't use.
 */

@GraphQLDescription("An episode or chapter in the download queue or the completed list")
data class DownloadItemType(
    val id: Int,
    val kind: ExtensionKind,
    val state: DownloadItemState,
    @GraphQLDescription("The manga or anime")
    val entryId: Int,
    @GraphQLDescription("The chapter or episode")
    val itemId: Int,
    val entryTitle: String,
    val itemName: String,
    val sourceId: Long,
    @GraphQLDescription("How far it got, of progressTotal (0 before that is known), in progressUnit")
    val progressDone: Int,
    val progressTotal: Int,
    @GraphQLDescription(
        "What progressDone counts: PAGES (a chapter), BYTES (a file: thousandths, with totalBytes), SECONDS (ffmpeg, of " +
            "the duration), SEGMENTS (yt-dlp's fragments)",
    )
    val progressUnit: DownloadProgressUnit,
    @GraphQLDescription("Bytes written so far (the file's size once done)")
    val bytes: Long,
    @GraphQLDescription("The size it will have, when known; 0 if not")
    val totalBytes: Long,
    @GraphQLDescription("Failed attempts since it was queued or retried")
    val tries: Int,
    @GraphQLDescription("Why the last attempt failed")
    val error: String?,
    @GraphQLDescription("Epoch milliseconds")
    val addedAt: Long,
    @GraphQLDescription("Epoch milliseconds; 0 until done")
    val finishedAt: Long,
) {
    companion object {
        /** The items with their entries' titles and their chapters' or episodes' names (one query per kind). */
        fun of(items: List<DownloadItem>): List<DownloadItemType> {
            if (items.isEmpty()) return emptyList()
            val (manga, anime) = items.partition { it.kind == ExtensionKind.MANGA }
            val names =
                dbTransaction {
                    val mangaTitles =
                        MangaTable
                            .select(MangaTable.id, MangaTable.title)
                            .where { MangaTable.id inList manga.map { it.entryId }.distinct() }
                            .associate { it[MangaTable.id].value to it[MangaTable.title] }
                    val chapterNames =
                        ChapterTable
                            .select(ChapterTable.id, ChapterTable.name)
                            .where { ChapterTable.id inList manga.map { it.itemId } }
                            .associate { it[ChapterTable.id].value to it[ChapterTable.name] }
                    val animeTitles =
                        AnimeTable
                            .select(AnimeTable.id, AnimeTable.title)
                            .where { AnimeTable.id inList anime.map { it.entryId }.distinct() }
                            .associate { it[AnimeTable.id].value to it[AnimeTable.title] }
                    val episodeNames =
                        EpisodeTable
                            .select(EpisodeTable.id, EpisodeTable.name)
                            .where { EpisodeTable.id inList anime.map { it.itemId } }
                            .associate { it[EpisodeTable.id].value to it[EpisodeTable.name] }
                    mapOf(
                        ExtensionKind.MANGA to (mangaTitles to chapterNames),
                        ExtensionKind.ANIME to (animeTitles to episodeNames),
                    )
                }
            return items.map { item ->
                val (titles, itemNames) = names.getValue(item.kind)
                DownloadItemType(
                    id = item.id,
                    kind = item.kind,
                    state = item.state,
                    entryId = item.entryId,
                    itemId = item.itemId,
                    entryTitle = titles[item.entryId].orEmpty(),
                    itemName = itemNames[item.itemId].orEmpty(),
                    sourceId = item.sourceId,
                    progressDone = item.progressDone,
                    progressTotal = item.progressTotal,
                    progressUnit = item.progressUnit,
                    bytes = item.bytes,
                    totalBytes = item.totalBytes,
                    tries = item.tries,
                    error = item.error,
                    addedAt = item.addedAt,
                    finishedAt = item.finishedAt,
                )
            }
        }
    }
}

@GraphQLDescription("The download queue: every unfinished item of both kinds, in queue order, and the completed counts")
data class DownloadQueueType(
    @GraphQLDescription("Queued, downloading, paused and failed items, the next first")
    val items: List<DownloadItemType>,
    @GraphQLDescription("Done items of each kind in the completed list")
    val mangaDone: Int,
    val animeDone: Int,
    @GraphQLDescription("Epoch milliseconds of the last item done; 0 if none is in the completed list")
    val lastFinishedAt: Long,
) {
    companion object {
        fun now(): DownloadQueueType =
            DownloadQueueType(
                items = DownloadItemType.of(DownloadQueue.items(null)),
                mangaDone = DownloadQueue.count(DownloadItemState.DONE, ExtensionKind.MANGA).toInt(),
                animeDone = DownloadQueue.count(DownloadItemState.DONE, ExtensionKind.ANIME).toInt(),
                lastFinishedAt = DownloadQueue.lastFinishedAt(null),
            )
    }
}

data class CompletedDownloadsType(
    val items: List<DownloadItemType>,
    val hasMore: Boolean,
)

@GraphQLDescription("The download settings (Settings → Downloads), kept in the backend's data dir, for both kinds")
data class DownloadSettingsType(
    @GraphQLDescription("Downloads at once, all sources together: 1 to 10")
    val maxParallel: Int,
    @GraphQLDescription("Downloads at once from one source: 1 to 3")
    val perSource: Int,
    @GraphQLDescription("Queue the new chapters a library update finds in categories with Downloads on")
    val autoDownload: Boolean,
    @GraphQLDescription("The folder downloads are saved in")
    val path: String,
    @GraphQLDescription("The ffmpeg for HLS episodes; empty: looked for (MADOYOMI_FFMPEG, then PATH)")
    val ffmpegPath: String,
    @GraphQLDescription("The yt-dlp to fall back on; empty: looked for (MADOYOMI_YTDLP, then PATH)")
    val ytDlpPath: String,
) {
    constructor(s: DownloadSettings, path: String) : this(s.maxParallel, s.perSource, s.autoDownload, path, s.ffmpegPath, s.ytDlpPath)
}

@GraphQLDescription("A program anime downloads use, as the backend finds it now")
data class DownloadToolType(
    @GraphQLDescription("FFMPEG or YT_DLP")
    val tool: DownloadTools.Tool,
    @GraphQLDescription("Whether it was found and answered its version command")
    val ok: Boolean,
    val path: String?,
    @GraphQLDescription("Where its path came from: SETTINGS, SYSTEM_PROPERTY, ENV or PATH")
    val origin: DownloadTools.Origin?,
    val version: String?,
    @GraphQLDescription("Why it isn't usable")
    val message: String?,
) {
    constructor(c: DownloadTools.Check) : this(c.tool, c.ok, c.path, c.origin, c.version, c.message)
}

private val applicationDirs: ApplicationDirs by injectLazy()

private fun settingsType(s: DownloadSettings) = DownloadSettingsType(s, File(applicationDirs.downloadsRoot).absolutePath)

private fun userOf(env: DataFetchingEnvironment): Int = env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()

class DownloadQueueQuery {
    @RequireAuth
    @GraphQLDescription("The download queue: the unfinished items of both kinds in order, and the completed counts")
    fun downloadQueue(): DownloadQueueType = DownloadQueueType.now()

    @RequireAuth
    @GraphQLDescription("The completed downloads of a kind (null: both), the last finished first")
    fun completedDownloads(
        kind: ExtensionKind? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): CompletedDownloadsType {
        val (items, more) = DownloadQueue.completed(kind, limit ?: DEFAULT_LIMIT, offset ?: 0)
        return CompletedDownloadsType(DownloadItemType.of(items), more)
    }

    @RequireAuth
    @GraphQLDescription("The download settings")
    fun downloadSettings(): DownloadSettingsType = settingsType(DownloadSettingsStore.current())

    @RequireAuth
    @GraphQLDescription(
        "ffmpeg and yt-dlp as anime downloads would use them now (each found and asked its version), or why not; an " +
            "unsaved path to try in place of the settings' one with ffmpegPath/ytDlpPath",
    )
    fun downloadTools(
        ffmpegPath: String? = null,
        ytDlpPath: String? = null,
    ): CompletableFuture<List<DownloadToolType>> =
        future {
            val saved = DownloadSettingsStore.current()
            val settings = saved.copy(ffmpegPath = ffmpegPath ?: saved.ffmpegPath, ytDlpPath = ytDlpPath ?: saved.ytDlpPath)
            DownloadTools.Tool.entries.map { DownloadToolType(DownloadTools.check(it, settings)) }
        }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

class DownloadQueueMutation {
    data class EnqueueDownloadsInput(
        val clientMutationId: String? = null,
        val kind: ExtensionKind,
        @GraphQLDescription("Chapter (or episode) ids, queued in reading order")
        val itemIds: List<Int>,
    )

    data class EnqueueDownloadsPayload(
        val clientMutationId: String?,
        @GraphQLDescription("How many were queued (already downloaded, queued or unknown ones aren't)")
        val queued: Int,
        val downloadQueue: DownloadQueueType,
    )

    data class DownloadItemsInput(
        val clientMutationId: String? = null,
        @GraphQLDescription("Only this kind's items; null: both")
        val kind: ExtensionKind? = null,
        @GraphQLDescription("The download ids; null: all of the kind that can change so")
        val ids: List<Int>? = null,
    )

    data class DownloadItemsPayload(
        val clientMutationId: String?,
        @GraphQLDescription("How many items changed")
        val changed: Int,
        val downloadQueue: DownloadQueueType,
    )

    data class ReorderDownloadInput(
        val clientMutationId: String? = null,
        val id: Int,
        @GraphQLDescription("The new place among the unfinished items of its kind, from 0; past the end: last")
        val to: Int,
    )

    data class ReorderDownloadPayload(
        val clientMutationId: String?,
        val downloadQueue: DownloadQueueType,
    )

    data class ClearCompletedDownloadsInput(
        val clientMutationId: String? = null,
        val kind: ExtensionKind? = null,
    )

    data class ClearCompletedDownloadsPayload(
        val clientMutationId: String?,
        @GraphQLDescription("How many left the completed list (the files stay)")
        val cleared: Int,
    )

    data class DeleteDownloadsInput(
        val clientMutationId: String? = null,
        val kind: ExtensionKind,
        @GraphQLDescription("Chapter (or episode) ids whose downloads go, queued or done")
        val itemIds: List<Int>,
    )

    data class DeleteDownloadsPayload(
        val clientMutationId: String?,
        @GraphQLDescription("How many were downloaded or queued")
        val deleted: Int,
        val downloadQueue: DownloadQueueType,
    )

    data class SetDownloadSettingsInput(
        val clientMutationId: String? = null,
        val maxParallel: Int? = null,
        val perSource: Int? = null,
        val autoDownload: Boolean? = null,
        @GraphQLDescription("The ffmpeg to use; empty: look for one")
        val ffmpegPath: String? = null,
        @GraphQLDescription("The yt-dlp to use; empty: look for one")
        val ytDlpPath: String? = null,
    )

    data class SetDownloadSettingsPayload(
        val clientMutationId: String?,
        val settings: DownloadSettingsType,
    )

    @RequireAuth
    @GraphQLDescription("Queues chapters or episodes for download, in reading or watching order, after what is queued")
    fun enqueueDownloads(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: EnqueueDownloadsInput,
    ): EnqueueDownloadsPayload {
        val userId = userOf(dataFetchingEnvironment)
        val queued =
            when (input.kind) {
                ExtensionKind.MANGA -> MangaDownloads.enqueue(userId, input.itemIds)
                ExtensionKind.ANIME -> AnimeDownloads.enqueue(userId, input.itemIds)
            }
        return EnqueueDownloadsPayload(input.clientMutationId, queued, DownloadQueueType.now())
    }

    @RequireAuth
    @GraphQLDescription("Pauses downloads (a running one stops at once) until they are resumed")
    fun pauseDownloads(input: DownloadItemsInput): DownloadItemsPayload =
        DownloadItemsPayload(input.clientMutationId, DownloadQueue.pause(input.kind, input.ids), DownloadQueueType.now())

    @RequireAuth
    @GraphQLDescription("Queues paused downloads again, where they were")
    fun resumeDownloads(input: DownloadItemsInput): DownloadItemsPayload =
        DownloadItemsPayload(input.clientMutationId, DownloadQueue.resume(input.kind, input.ids), DownloadQueueType.now())

    @RequireAuth
    @GraphQLDescription("Queues failed downloads again, their tries reset")
    fun retryDownloads(input: DownloadItemsInput): DownloadItemsPayload =
        DownloadItemsPayload(input.clientMutationId, DownloadQueue.retry(input.kind, input.ids), DownloadQueueType.now())

    @RequireAuth
    @GraphQLDescription("Removes unfinished downloads from the queue, with what they downloaded so far")
    fun cancelDownloads(input: DownloadItemsInput): CompletableFuture<DownloadItemsPayload?> =
        future {
            DownloadItemsPayload(input.clientMutationId, DownloadQueue.cancel(input.kind, input.ids), DownloadQueueType.now())
        }

    @RequireAuth
    @GraphQLDescription("Moves a download to another place in its kind's queue")
    fun reorderDownload(input: ReorderDownloadInput): ReorderDownloadPayload {
        DownloadQueue.reorder(input.id, input.to)
        return ReorderDownloadPayload(input.clientMutationId, DownloadQueueType.now())
    }

    @RequireAuth
    @GraphQLDescription("Empties the completed list of a kind (null: both); the downloaded files stay")
    fun clearCompletedDownloads(input: ClearCompletedDownloadsInput): ClearCompletedDownloadsPayload =
        ClearCompletedDownloadsPayload(input.clientMutationId, DownloadQueue.clearCompleted(input.kind))

    @RequireAuth
    @GraphQLDescription("Deletes the downloads of chapters or episodes: queued ones are cancelled, downloaded files deleted")
    fun deleteDownloads(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteDownloadsInput,
    ): CompletableFuture<DeleteDownloadsPayload?> {
        val userId = userOf(dataFetchingEnvironment)
        return future {
            val deleted =
                when (input.kind) {
                    ExtensionKind.MANGA -> MangaDownloads.delete(userId, input.itemIds)
                    ExtensionKind.ANIME -> AnimeDownloads.delete(input.itemIds)
                }
            DeleteDownloadsPayload(input.clientMutationId, deleted, DownloadQueueType.now())
        }
    }

    @RequireAuth
    @GraphQLDescription("Changes the download settings; new limits apply at once")
    fun setDownloadSettings(input: SetDownloadSettingsInput): SetDownloadSettingsPayload =
        SetDownloadSettingsPayload(
            input.clientMutationId,
            settingsType(
                DownloadSettingsStore.update(
                    DownloadSettings.Patch(
                        maxParallel = input.maxParallel,
                        perSource = input.perSource,
                        autoDownload = input.autoDownload,
                        ffmpegPath = input.ffmpegPath,
                        ytDlpPath = input.ytDlpPath,
                    ),
                ),
            ),
        )

    data class PlayDownloadedEpisodeInput(
        val clientMutationId: String? = null,
        val episodeId: Int,
        @GraphQLDescription("Where to start, in seconds; null or 0 is the beginning")
        val startSeconds: Double? = null,
        @GraphQLDescription("Without startSeconds: from where it stopped (not watched, between 1 % and the watched threshold)")
        val resume: Boolean? = null,
    )

    @RequireAuth
    @GraphQLDescription(
        "Plays a downloaded episode in mpv from its file (with its subtitle files), in the same session as playEpisode: " +
            "progress, the watched mark and the next episode. NOT_FOUND if it isn't downloaded or its file is gone",
    )
    fun playDownloadedEpisode(input: PlayDownloadedEpisodeInput): PlaybackMutation.PlayEpisodePayload =
        PlaybackMutation.PlayEpisodePayload(
            input.clientMutationId,
            PlaybackSessionType(AnimeDownloads.play(input.episodeId, input.startSeconds, input.resume == true)),
        )
}

/** Over `graphql-transport-ws` at `/api/graphql`: the WS "downloads" event. */
class DownloadQueueSubscription {
    @RequireAuth
    @GraphQLDescription(
        "The download queue: at once on subscribing, then on every change (queued, started, a page, done, failed, " +
            "moved, removed), at most every ${THROTTLE_MS} ms; the latest one wins",
    )
    fun downloadsChanged(): Flow<DownloadQueueType> =
        flow {
            DownloadQueue.changes.collect {
                emit(DownloadQueueType.now())
                delay(THROTTLE_MS)
            }
        }

    companion object {
        const val THROTTLE_MS = 250L
    }
}

/** Whether an episode is downloaded (Stage 5: `EpisodeType.downloaded`), for a page of episodes in one query. */
class EpisodeDownloadedDataLoader : KotlinDataLoader<Int, Boolean> {
    override val dataLoaderName = "EpisodeDownloadedDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Boolean> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val downloaded = AnimeDownloads.downloadedIds(ids)
                ids.map { it in downloaded }
            }
        }
}

/** An anime's downloaded episodes (Stage 5: `AnimeType.downloadCount`, its seasons' for a Seasons entry). */
class AnimeDownloadCountDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "AnimeDownloadCountDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val counts = AnimeDownloads.counts(ids)
                ids.map { counts[it] ?: 0 }
            }
        }
}
