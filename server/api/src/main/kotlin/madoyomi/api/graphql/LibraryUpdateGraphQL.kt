package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.library.LibraryUpdateMarks
import madoyomi.core.library.LibraryUpdateScheduler
import madoyomi.core.library.LibraryUpdateSettings
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.library.LibraryUpdater
import madoyomi.core.library.LibraryUpdatesFeed
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

/*
 * The library update check (Phase 6 Stage 3, madoyomi.core.library): start and stop it, its status (the query and the
 * `libraryUpdates` subscription, the WS "library.updates" event), its settings, the Updates feed of each kind, and the
 * entries' "new" marks. Named apart from upstream's manga-only `updateLibrary`/`libraryUpdateStatus`, which stay.
 */

enum class LibraryCheckTrigger { MANUAL, SCHEDULED }

@GraphQLDescription("A library entry a check looked at")
data class LibraryCheckEntryType(
    val kind: ExtensionKind,
    val id: Int,
    val title: String,
) {
    constructor(e: LibraryUpdater.Entry) : this(e.kind, e.id, e.title)
}

data class LibraryCheckFailureType(
    val entry: LibraryCheckEntryType,
    @GraphQLDescription("Why the entry couldn't be checked (the source's error)")
    val error: String,
)

data class LibraryCheckFoundType(
    val entry: LibraryCheckEntryType,
    @GraphQLDescription("New episodes or chapters this check inserted")
    val newCount: Int,
)

@GraphQLDescription("The library update check that runs, else the last one")
data class LibraryCheckType(
    val running: Boolean,
    @GraphQLDescription("Null before the first check")
    val trigger: LibraryCheckTrigger?,
    @GraphQLDescription("Epoch milliseconds; 0 before the first check")
    val startedAt: Long,
    @GraphQLDescription("Epoch milliseconds; 0 while it runs and before the first check")
    val finishedAt: Long,
    val total: Int,
    @GraphQLDescription("Entries checked, failed ones included")
    val done: Int,
    @GraphQLDescription("Entries of the chosen categories the settings or their update strategy left out")
    val skipped: Int,
    @GraphQLDescription("The entries being checked now (one per source at most)")
    val checking: List<LibraryCheckEntryType>,
    val failures: List<LibraryCheckFailureType>,
    @GraphQLDescription("The entries the check found new items in")
    val found: List<LibraryCheckFoundType>,
    val newEpisodes: Int,
    val newChapters: Int,
    @GraphQLDescription("Stopped before it was done")
    val cancelled: Boolean,
    @GraphQLDescription("Epoch milliseconds of the next scheduled check; null while the schedule is off")
    val nextScheduledAt: Long?,
) {
    constructor(s: LibraryUpdater.Status) : this(
        running = s.running,
        trigger = s.trigger?.let { LibraryCheckTrigger.valueOf(it.name) },
        startedAt = s.startedAt,
        finishedAt = s.finishedAt,
        total = s.total,
        done = s.done,
        skipped = s.skipped,
        checking = s.checking.map(::LibraryCheckEntryType),
        failures = s.failures.map { LibraryCheckFailureType(LibraryCheckEntryType(it.entry), it.error) },
        found = s.found.map { LibraryCheckFoundType(LibraryCheckEntryType(it.entry), it.newCount) },
        newEpisodes = s.newCount(ExtensionKind.ANIME),
        newChapters = s.newCount(ExtensionKind.MANGA),
        cancelled = s.cancelled,
        nextScheduledAt = LibraryUpdateScheduler.nextAt,
    )
}

@GraphQLDescription("The library update settings (Settings → Updates), kept in the backend's data dir, for both kinds")
data class LibraryUpdateSettingsType(
    @GraphQLDescription("Hours between scheduled checks: 0 (off), 6, 12 or 24")
    val intervalHours: Int,
    @GraphQLDescription("Skip entries whose status is completed")
    val skipCompleted: Boolean,
    @GraphQLDescription("Skip entries with unwatched episodes or unread chapters")
    val skipUnseen: Boolean,
    @GraphQLDescription("Skip entries with nothing watched or read yet")
    val skipNotStarted: Boolean,
    @GraphQLDescription("The app shows a Windows toast when a check finds new items")
    val notify: Boolean,
) {
    constructor(s: LibraryUpdateSettings) : this(s.intervalHours, s.skipCompleted, s.skipUnseen, s.skipNotStarted, s.notify)
}

@GraphQLDescription("A library entry with new items since it was last opened")
data class UpdateMarkType(
    val entryId: Int,
    @GraphQLDescription("New episodes or chapters found by checks since the entry was last opened")
    val newCount: Int,
    @GraphQLDescription("Epoch seconds of the last check that added to it")
    val markedAt: Long,
)

data class AnimeUpdateItemType(
    @GraphQLDescription("The episode's entry (a season of a library entry for a Seasons one)")
    val anime: AnimeType,
    val episode: EpisodeType,
    @GraphQLDescription("Epoch seconds when it was fetched")
    val fetchedAt: Long,
)

data class AnimeUpdatesPageType(
    val items: List<AnimeUpdateItemType>,
    val hasMore: Boolean,
)

data class MangaUpdateItemType(
    val manga: MangaType,
    val chapter: ChapterType,
    @GraphQLDescription("Epoch seconds when it was fetched")
    val fetchedAt: Long,
)

data class MangaUpdatesPageType(
    val items: List<MangaUpdateItemType>,
    val hasMore: Boolean,
)

private fun userOf(env: DataFetchingEnvironment): Int = env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()

class LibraryUpdateQuery {
    @RequireAuth
    @GraphQLDescription("The library update check that runs, else the last one")
    fun libraryCheck(): LibraryCheckType = LibraryCheckType(LibraryUpdater.status.value)

    @RequireAuth
    @GraphQLDescription("The library update settings")
    fun libraryUpdateSettings(): LibraryUpdateSettingsType = LibraryUpdateSettingsType(LibraryUpdateSettingsStore.current())

    @RequireAuth
    @GraphQLDescription(
        "New episodes of library entries, the last fetched first: fetched after the entry joined the library, its first fetch left out",
    )
    fun animeUpdates(
        limit: Int? = null,
        offset: Int? = null,
    ): CompletableFuture<AnimeUpdatesPageType> =
        future {
            val page = LibraryUpdatesFeed.anime(limit ?: DEFAULT_LIMIT, offset ?: 0)
            dbTransaction {
                val anime = AnimeType.byIds(page.items.map { it.entryId }.distinct()).associateBy { it.id }
                val episodes =
                    EpisodeTable.selectAll().where { EpisodeTable.id inList page.items.map { it.itemId } }.associate {
                        it[EpisodeTable.id].value to EpisodeType(it)
                    }
                AnimeUpdatesPageType(
                    page.items.mapNotNull { item ->
                        AnimeUpdateItemType(
                            anime = anime[item.entryId] ?: return@mapNotNull null,
                            episode = episodes[item.itemId] ?: return@mapNotNull null,
                            fetchedAt = item.fetchedAt,
                        )
                    },
                    page.hasMore,
                )
            }
        }

    @RequireAuth
    @GraphQLDescription(
        "New chapters of library entries, the last fetched first: fetched after the entry joined the library, its first fetch left out",
    )
    fun mangaUpdates(
        dataFetchingEnvironment: DataFetchingEnvironment,
        limit: Int? = null,
        offset: Int? = null,
    ): CompletableFuture<MangaUpdatesPageType> {
        val userId = userOf(dataFetchingEnvironment)
        return future {
            val page = LibraryUpdatesFeed.manga(userId, limit ?: DEFAULT_LIMIT, offset ?: 0)
            dbTransaction {
                val manga =
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id inList page.items.map { it.entryId }.distinct() }
                        .associate { it[MangaTable.id].value to MangaType(it) }
                val chapters =
                    ChapterTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { ChapterTable.id inList page.items.map { it.itemId } }
                        .associate { it[ChapterTable.id].value to ChapterType(it) }
                MangaUpdatesPageType(
                    page.items.mapNotNull { item ->
                        MangaUpdateItemType(
                            manga = manga[item.entryId] ?: return@mapNotNull null,
                            chapter = chapters[item.itemId] ?: return@mapNotNull null,
                            fetchedAt = item.fetchedAt,
                        )
                    },
                    page.hasMore,
                )
            }
        }
    }

    @RequireAuth
    @GraphQLDescription("The library entries of a kind with new items since they were last opened, the last marked first")
    fun updateMarks(
        dataFetchingEnvironment: DataFetchingEnvironment,
        kind: ExtensionKind,
    ): List<UpdateMarkType> {
        val marks =
            when (kind) {
                ExtensionKind.ANIME -> LibraryUpdateMarks.anime()
                ExtensionKind.MANGA -> LibraryUpdateMarks.manga(userOf(dataFetchingEnvironment))
            }
        return marks.map { UpdateMarkType(it.entryId, it.newCount, it.markedAt) }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

class LibraryUpdateMutation {
    data class StartLibraryUpdateInput(
        val clientMutationId: String? = null,
        @GraphQLDescription("The kind to check; null: both")
        val kind: ExtensionKind? = null,
        @GraphQLDescription("Only this category of the kind, whatever its update flag; needs kind")
        val categoryId: Int? = null,
    )

    data class LibraryCheckPayload(
        val clientMutationId: String?,
        val libraryCheck: LibraryCheckType,
    )

    data class StopLibraryUpdateInput(
        val clientMutationId: String? = null,
    )

    data class SetLibraryUpdateSettingsInput(
        val clientMutationId: String? = null,
        val intervalHours: Int? = null,
        val skipCompleted: Boolean? = null,
        val skipUnseen: Boolean? = null,
        val skipNotStarted: Boolean? = null,
        val notify: Boolean? = null,
    )

    data class SetLibraryUpdateSettingsPayload(
        val clientMutationId: String?,
        val settings: LibraryUpdateSettingsType,
    )

    data class ClearUpdateMarksInput(
        val clientMutationId: String? = null,
        val kind: ExtensionKind,
        @GraphQLDescription("The entries whose marks go; null: all of the kind")
        val entryIds: List<Int>? = null,
    )

    data class ClearUpdateMarksPayload(
        val clientMutationId: String?,
        @GraphQLDescription("How many marks went")
        val cleared: Int,
    )

    @RequireAuth
    @GraphQLDescription(
        "Starts a library update check (or adds to the one running): the kind's library, both kinds', or one category's",
    )
    fun startLibraryUpdate(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: StartLibraryUpdateInput,
    ): CompletableFuture<LibraryCheckPayload?> {
        val userId = userOf(dataFetchingEnvironment)
        return future {
            if (input.categoryId != null && input.kind == null) {
                throw MadoyomiException(ErrorCode.BAD_REQUEST, "categoryId needs a kind", mapOf("field" to "kind"))
            }
            val kinds = input.kind?.let(::listOf) ?: listOf(ExtensionKind.ANIME, ExtensionKind.MANGA)
            val targets = kinds.map { LibraryUpdater.Target(it, input.categoryId, userId) }
            LibraryCheckPayload(input.clientMutationId, LibraryCheckType(LibraryUpdater.start(targets, LibraryUpdater.Trigger.MANUAL)))
        }
    }

    @RequireAuth
    @GraphQLDescription("Stops the running library update check; what it inserted stays")
    fun stopLibraryUpdate(input: StopLibraryUpdateInput): LibraryCheckPayload =
        LibraryCheckPayload(input.clientMutationId, LibraryCheckType(LibraryUpdater.stop()))

    @RequireAuth
    @GraphQLDescription("Changes the library update settings; a new interval reschedules at once")
    fun setLibraryUpdateSettings(input: SetLibraryUpdateSettingsInput): SetLibraryUpdateSettingsPayload =
        SetLibraryUpdateSettingsPayload(
            input.clientMutationId,
            LibraryUpdateSettingsType(
                LibraryUpdateSettingsStore.update(
                    LibraryUpdateSettings.Patch(
                        intervalHours = input.intervalHours,
                        skipCompleted = input.skipCompleted,
                        skipUnseen = input.skipUnseen,
                        skipNotStarted = input.skipNotStarted,
                        notify = input.notify,
                    ),
                ),
            ),
        )

    @RequireAuth
    @GraphQLDescription("Clears the \"new\" marks of entries (opened), or all of a kind")
    fun clearUpdateMarks(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ClearUpdateMarksInput,
    ): ClearUpdateMarksPayload {
        val ids = input.entryIds?.distinct()
        val cleared =
            when (input.kind) {
                ExtensionKind.ANIME -> LibraryUpdateMarks.clearAnime(ids)
                ExtensionKind.MANGA -> LibraryUpdateMarks.clearManga(userOf(dataFetchingEnvironment), ids)
            }
        return ClearUpdateMarksPayload(input.clientMutationId, cleared)
    }
}

/** Over `graphql-transport-ws` at `/api/graphql`: the WS "library.updates" event. */
class LibraryUpdateSubscription {
    @RequireAuth
    @GraphQLDescription(
        "The library update check's status: at once on subscribing, then on every change (an entry started or done, " +
            "the end); the latest one wins when they come faster than they are read",
    )
    fun libraryUpdates(): Flow<LibraryCheckType> = LibraryUpdater.status.map(::LibraryCheckType)
}
