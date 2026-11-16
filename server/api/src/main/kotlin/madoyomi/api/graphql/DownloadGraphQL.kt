package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.downloads.DownloadItem
import madoyomi.core.downloads.DownloadItemState
import madoyomi.core.downloads.DownloadQueue
import madoyomi.core.downloads.DownloadSettings
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.downloads.MangaDownloads
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.ExtensionKind
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
 * the queue and the completed list, queueing, pause, resume, retry, cancel, reorder, deleting downloaded chapters,
 * the settings, and the `downloadsChanged` subscription (the WS "downloads" event). Named apart from upstream's
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
    @GraphQLDescription("Pages downloaded, of progressTotal (0 before the page list is known)")
    val progressDone: Int,
    val progressTotal: Int,
    @GraphQLDescription("Bytes written so far (the file's size once done)")
    val bytes: Long,
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
                    bytes = item.bytes,
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
) {
    constructor(s: DownloadSettings, path: String) : this(s.maxParallel, s.perSource, s.autoDownload, path)
}

private val applicationDirs: ApplicationDirs by injectLazy()

private fun settingsType(s: DownloadSettings) = DownloadSettingsType(s, File(applicationDirs.downloadsRoot).absolutePath)

private fun userOf(env: DataFetchingEnvironment): Int = env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()

private fun requireManga(kind: ExtensionKind) {
    if (kind == ExtensionKind.ANIME) {
        throw MadoyomiException(ErrorCode.BAD_REQUEST, "Episode downloads aren't available yet", mapOf("field" to "kind"))
    }
}

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
    )

    data class SetDownloadSettingsPayload(
        val clientMutationId: String?,
        val settings: DownloadSettingsType,
    )

    @RequireAuth
    @GraphQLDescription("Queues chapters for download, in reading order, after what is queued")
    fun enqueueDownloads(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: EnqueueDownloadsInput,
    ): EnqueueDownloadsPayload {
        requireManga(input.kind)
        val queued = MangaDownloads.enqueue(userOf(dataFetchingEnvironment), input.itemIds)
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
    @GraphQLDescription("Deletes the downloads of chapters: queued ones are cancelled, downloaded files deleted")
    fun deleteDownloads(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteDownloadsInput,
    ): CompletableFuture<DeleteDownloadsPayload?> {
        requireManga(input.kind)
        val userId = userOf(dataFetchingEnvironment)
        return future {
            val deleted = MangaDownloads.delete(userId, input.itemIds)
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
                    ),
                ),
            ),
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
