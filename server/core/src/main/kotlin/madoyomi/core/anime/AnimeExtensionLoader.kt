package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.ExtensionCompatLog
import madoyomi.core.extension.ExtensionFiles
import madoyomi.core.extension.ExtensionIncompatibility
import madoyomi.core.extension.ExtensionLoadGate
import madoyomi.core.extension.ExtensionThreads
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * Instantiates anime extensions. The class is loaded by upstream's only extension loader
 * (`PackageTools.loadExtensionSources`), so the trust gate ([ExtensionLoadGate]) applies to anime exactly as to manga.
 */
object AnimeExtensionLoader {
    private val logger = KotlinLogging.logger {}

    /**
     * Loads the installed, trusted anime extension [extension] (an `EXTENSION` row), [activate]s its sources and
     * returns them. Throws `LOAD_FAILED` when its code can't be instantiated and `UNTRUSTED` from the gate.
     */
    suspend fun loadInstalled(extension: ResultRow): List<AnimeSource> {
        val pkg = extension[ExtensionTable.pkgName]
        val jar =
            ExtensionFiles.installedJar(extension)?.takeIf { it.exists() }
                ?: throw MadoyomiException(
                    ErrorCode.LOAD_FAILED,
                    "The jar of $pkg is missing; reinstall the extension",
                    mapOf("pkgName" to pkg),
                )
        val extensionId = extension[ExtensionTable.id].value
        // an uninstall or update that unloads the extension while this load runs wins: see the activate below
        val unloadsBefore = AnimeGetSource.unloadCount(extensionId)
        ExtensionLoadGate.permit(jar, extension[ExtensionTable.signatureHash])
        val className = extension[ExtensionTable.classFQName]
        val sources =
            PackageTools.blockJarUsageWhile(listOf(jar)) { loadSources ->
                instantiate(pkg, className) { loadSources(jar, it) }
            }
        if (!activate(extensionId, sources, extension[ExtensionTable.contentWarning], ifUnloadCount = unloadsBefore)) {
            // unloaded meanwhile: drop what this load created (its class loader too, if the unload had already
            // closed the one before), so nothing of the old code stays registered or loaded. Only that loader: a
            // reinstall of the same version uses the same jar path
            val loader = sources.firstOrNull()?.javaClass?.classLoader as? java.net.URLClassLoader
            if (loader != null && PackageTools.jarLoaderMap.remove(jar.absolutePathString(), loader)) {
                ExtensionThreads.stop(loader, pkg)
                loader.close()
            }
            throw MadoyomiException(ErrorCode.NOT_FOUND, "$pkg was uninstalled or updated while it loaded", mapOf("pkgName" to pkg))
        }
        logger.info { "Loaded anime extension $pkg: ${sources.joinToString { "${it.name} (${it.id})" }}" }
        return sources
    }

    /**
     * Instantiates the extension main class [className] of [pkg] with [load] (a `PackageTools` loader) and returns its
     * sources (one `AnimeSource`, or every source of an `AnimeSourceFactory`). Any failure other than the trust gate's
     * becomes `LOAD_FAILED`, including the `LinkageError`s of a class that references an API the host lacks.
     */
    fun instantiate(
        pkg: String,
        className: String,
        load: (className: String) -> Any,
    ): List<AnimeSource> =
        try {
            val sources =
                when (val instance = load(className)) {
                    is AnimeSource -> listOf(instance)

                    is AnimeSourceFactory -> instance.createSources()

                    else -> throw MadoyomiException(
                        ErrorCode.LOAD_FAILED,
                        "$className of $pkg is not an anime source (${instance.javaClass.name})",
                        mapOf("pkgName" to pkg, "className" to className),
                    )
                }
            // the id, name and lang are computed lazily by most sources: compute them here, where a failure is a load failure
            sources.forEach { source ->
                source.id
                source.name
                source.lang
            }
            sources
        } catch (e: MadoyomiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            throw loadFailed(pkg, className, e)
        }

    /**
     * Caches [sources] (all of the extension [extensionId]) in [AnimeGetSource], reconciles `ANIME_SOURCE` with them
     * (rows the extension no longer provides go, new ones are added, the rest updated) and marks the extension
     * loaded. The anime counterpart of upstream's `Extension.setupJar` reconcile. With [ifUnloadCount] (see
     * [AnimeGetSource.register]) nothing happens, and false is returned, when the extension was unloaded meanwhile.
     */
    fun activate(
        extensionId: Int,
        sources: List<AnimeSource>,
        contentWarning: Int,
        ifUnloadCount: Long? = null,
    ): Boolean {
        if (!AnimeGetSource.register(extensionId, sources, ifUnloadCount)) return false
        dbTransaction {
            val live = sources.associateBy { it.id }
            val inDb =
                AnimeSourceTable
                    .select(AnimeSourceTable.id)
                    .where { AnimeSourceTable.extension eq extensionId }
                    .map { it[AnimeSourceTable.id].value }
            val (toUpdate, toDelete) = inDb.partition { it in live }
            if (toDelete.isNotEmpty()) AnimeSourceTable.deleteWhere { AnimeSourceTable.id inList toDelete }
            AnimeSourceTable.batchInsert(sources.filter { it.id !in toUpdate }) { source ->
                this[AnimeSourceTable.id] = source.id
                this[AnimeSourceTable.name] = source.name.take(128)
                this[AnimeSourceTable.lang] = source.lang.take(32)
                this[AnimeSourceTable.extension] = extensionId
                this[AnimeSourceTable.contentWarning] = contentWarning
                this[AnimeSourceTable.baseUrl] = baseUrlOf(source)
            }
            if (toUpdate.isNotEmpty()) {
                BatchUpdateStatement(AnimeSourceTable)
                    .apply {
                        toUpdate.forEach { id ->
                            val source = live.getValue(id)
                            addBatch(EntityID(id, AnimeSourceTable))
                            this[AnimeSourceTable.name] = source.name.take(128)
                            this[AnimeSourceTable.lang] = source.lang.take(32)
                            this[AnimeSourceTable.contentWarning] = contentWarning
                            this[AnimeSourceTable.baseUrl] = baseUrlOf(source)
                        }
                    }.toExecutable()
                    .execute(this@dbTransaction)
            }

            // upstream's extension language rule (setupJar): none, the one language, or "all"
            val langs = sources.map { it.lang }.toSet()
            val extensionLang =
                when (langs.size) {
                    0 -> ""
                    1 -> langs.first()
                    else -> "all"
                }
            ExtensionTable.update({ ExtensionTable.id eq extensionId }) {
                it[lang] = extensionLang
                it[isLoaded] = true
            }
        }
        return true
    }

    private fun baseUrlOf(source: AnimeSource): String? =
        (source as? AnimeHttpSource)
            ?.let { runCatching { it.baseUrl }.getOrNull() }
            ?.take(2048)
            ?.ifBlank { null }

    private fun loadFailed(
        pkg: String,
        className: String,
        error: Throwable,
    ): MadoyomiException {
        val cause = rootCause(error)
        val missing =
            when (cause) {
                is NoClassDefFoundError, is ClassNotFoundException -> cause.message?.replace('/', '.')
                is LinkageError -> cause.message
                else -> null
            }
        logger.error(error) { "Failed to load anime extension $pkg ($className)" }
        ExtensionIncompatibility.classify(error)?.let {
            ExtensionCompatLog.record(pkg, it.kind, it.target, ExtensionCompatLog.Origin.RUNTIME, "load")
        }
        val details = mutableMapOf<String, Any?>("pkgName" to pkg, "className" to className, "cause" to cause.javaClass.name)
        missing?.let { details["missing"] = it }
        return MadoyomiException(
            ErrorCode.LOAD_FAILED,
            "$pkg could not be loaded: ${cause.javaClass.simpleName}${cause.message?.let { ": $it" } ?: ""}",
            details,
            error,
        )
    }

    /** Unwraps reflection and future wrappers down to the exception the extension threw. */
    internal fun rootCause(error: Throwable): Throwable {
        var current = error
        while (true) {
            val next =
                when (current) {
                    is InvocationTargetException -> current.targetException
                    is ExceptionInInitializerError -> current.exception
                    is ExecutionException -> current.cause
                    else -> null
                } ?: return current
            current = next
        }
    }
}
