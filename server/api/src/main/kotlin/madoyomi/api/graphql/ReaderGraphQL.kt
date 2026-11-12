package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import madoyomi.core.reader.PageCache
import madoyomi.core.reader.PageCacheUsage
import madoyomi.core.reader.PageImages
import madoyomi.core.reader.ReaderSettings
import madoyomi.core.reader.ReaderSettingsStore
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/** The reader settings the backend applies (Phase 5 Stage 1, Settings → Reader). */
@GraphQLDescription("The reader settings the backend applies (Settings → Reader), kept in its data dir")
data class ReaderSettingsType(
    @GraphQLDescription("The chapter-page cache's cap in MiB: 256 to 10240 (default 1024). Lowering it trims the cache")
    val pageCacheLimitMb: Int,
) {
    constructor(s: ReaderSettings) : this(pageCacheLimitMb = s.pageCacheLimitMb)
}

@GraphQLDescription("What the chapter-page cache holds (a scan of its folder)")
data class PageCacheType(
    @GraphQLDescription("Bytes on disk")
    val bytes: Long,
    @GraphQLDescription("Cached pages")
    val files: Int,
    @GraphQLDescription("The cap in bytes; the least recently used pages go once it is passed")
    val limitBytes: Long,
    @GraphQLDescription("The cache folder: <data dir>/cache/manga")
    val path: String,
) {
    constructor(u: PageCacheUsage) : this(u.bytes, u.files, u.limitBytes, u.path)
}

class ReaderQuery {
    @RequireAuth
    @GraphQLDescription("The reader settings the backend applies")
    fun readerSettings(): ReaderSettingsType = ReaderSettingsType(ReaderSettingsStore.current())

    @RequireAuth
    @GraphQLDescription("What the chapter-page cache holds")
    fun pageCache(): CompletableFuture<PageCacheType> = future { withContext(Dispatchers.IO) { PageCacheType(PageCache.usage()) } }
}

class ReaderMutation {
    @GraphQLDescription("Only the fields given change; the rest stay")
    data class ReaderSettingsPatch(
        val pageCacheLimitMb: Int? = null,
    )

    data class SetReaderSettingsInput(
        val clientMutationId: String? = null,
        val patch: ReaderSettingsPatch,
    )

    data class SetReaderSettingsPayload(
        val clientMutationId: String?,
        val settings: ReaderSettingsType,
    )

    @RequireAuth
    @GraphQLDescription(
        "Changes reader settings (all or nothing: a wrong value is BAD_REQUEST and nothing changes). A lower cache cap " +
            "trims the cache before this answers",
    )
    fun setReaderSettings(input: SetReaderSettingsInput): CompletableFuture<SetReaderSettingsPayload> =
        future {
            val settings = ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = input.patch.pageCacheLimitMb))
            // a lower cap applies now, not at the next page, so pageCache tells what is left
            withContext(Dispatchers.IO) { PageCache.trim() }
            SetReaderSettingsPayload(input.clientMutationId, ReaderSettingsType(settings))
        }

    data class ClearPageCacheInput(
        val clientMutationId: String? = null,
    )

    data class ClearPageCachePayload(
        val clientMutationId: String?,
        val cache: PageCacheType,
    )

    @RequireAuth
    @GraphQLDescription("Removes every cached chapter page (not downloads, not covers) and returns what is left")
    fun clearPageCache(input: ClearPageCacheInput): CompletableFuture<ClearPageCachePayload> =
        future { ClearPageCachePayload(input.clientMutationId, withContext(Dispatchers.IO) { PageCacheType(PageCache.clear()) }) }

    data class PrefetchChapterPagesInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
        @GraphQLDescription("The first page to warm (0-based)")
        val from: Int,
        @GraphQLDescription("How many pages from there: 1 to 20")
        val count: Int,
    )

    data class PrefetchChapterPagesPayload(
        val clientMutationId: String?,
        val chapterId: Int,
        @GraphQLDescription("The chapter's page count")
        val pageCount: Int,
        @GraphQLDescription("The pages being fetched in the background")
        val queued: List<Int>,
        @GraphQLDescription("The pages that were cached already")
        val cached: List<Int>,
    )

    @RequireAuth
    @GraphQLDescription(
        "Warms the page cache with pages from..from+count-1 of a chapter, in the background (at most 2 requests at a " +
            "time), for the next pages of the chapter being read and the first pages of the next one. A chapter whose " +
            "page list wasn't fetched yet gets it first. A new prefetch of a chapter replaces the one still queued for it",
    )
    fun prefetchChapterPages(input: PrefetchChapterPagesInput): CompletableFuture<PrefetchChapterPagesPayload> =
        future {
            val r = PageImages.prefetch(input.chapterId, input.from, input.count)
            PrefetchChapterPagesPayload(input.clientMutationId, r.chapterId, r.pageCount, r.queued, r.cached)
        }
}
