package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.error.MadoyomiException
import madoyomi.exthost.SourceRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The instantiated anime sources, the anime counterpart of upstream's manga `GetSource` (which is typed to manga
 * `Source` and stays untouched).
 *
 * A source is loaded on first use: [getSource] checks that its extension is installed and trusted
 * ([AnimeSourceAccess]), then instantiates the whole extension behind the trust gate ([AnimeExtensionLoader]) and
 * caches every source it provides. Install and trust load eagerly and [register] what they instantiated.
 */
object AnimeGetSource {
    private val logger = KotlinLogging.logger {}

    private val sourceCache = ConcurrentHashMap<Long, AnimeSource>()

    /** The source ids each extension (`EXTENSION.id`) registered, so an unload drops all of them. */
    private val byExtension = ConcurrentHashMap<Int, Set<Long>>()

    // a User-Agent change (NetworkHelper, in ext-host) drops every source so it rebuilds its headers, as for manga
    init {
        SourceRegistry.onUnregisterAll(::unregisterAllSources)
    }

    /** The cached source [sourceId], without loading anything. */
    fun cached(sourceId: Long): AnimeSource? = sourceCache[sourceId]

    /**
     * The source [sourceId], loading its extension if needed. Throws a [MadoyomiException]: `NOT_FOUND` (no such
     * source, not installed, or the extension no longer provides it), `UNTRUSTED` or `LOAD_FAILED`.
     */
    suspend fun getSource(sourceId: Long): AnimeSource {
        sourceCache[sourceId]?.let { return it }
        val extension = AnimeSourceAccess.loadableExtension(sourceId)
        AnimeExtensionLoader.loadInstalled(extension)
        return sourceCache[sourceId] ?: throw AnimeSourceAccess.notProvided(sourceId, extension)
    }

    /** [getSource], or null (with the reason in the log). */
    suspend fun getSourceOrNull(sourceId: Long): AnimeSource? =
        try {
            getSource(sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Anime source $sourceId is not available: ${e.message}" }
            null
        }

    /** Caches [sources], all from the extension [extensionId], replacing what that extension had registered. */
    fun register(
        extensionId: Int,
        sources: List<AnimeSource>,
    ) {
        unregisterExtension(extensionId)
        sources.forEach { sourceCache[it.id] = it }
        byExtension[extensionId] = sources.map { it.id }.toSet()
    }

    /** For tests: caches a single [source] that belongs to no extension. */
    fun registerSource(source: AnimeSource) {
        sourceCache[source.id] = source
    }

    fun unregisterSource(sourceId: Long) {
        sourceCache.remove(sourceId)
    }

    /** Drops every source the extension [extensionId] registered. */
    fun unregisterExtension(extensionId: Int) {
        byExtension.remove(extensionId)?.forEach(sourceCache::remove)
    }

    fun unregisterAllSources() {
        sourceCache.clear()
        byExtension.clear()
    }
}
