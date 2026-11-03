package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.repo.ExtensionListing
import madoyomi.core.repo.NetworkRepoHttp
import madoyomi.core.repo.RepoHttp
import madoyomi.core.repo.RepoIndexFetcher
import madoyomi.core.repo.matchesPackage
import madoyomi.exthost.ApkInspection
import madoyomi.exthost.ApkInspector
import madoyomi.exthost.Dex2jarRepair
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbSuspendTransaction
import suwayomi.tachidesk.server.database.dbTransaction
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

/**
 * Install, update, uninstall and trust for both extension kinds (Mihon/Aniyomi trust model; see server/UPSTREAM.md
 * "Extension lifecycle").
 *
 * - **Install** always downloads the listing's APK (Keiyoushi's prebuilt `jarUrl` is ignored), verifies it with apksig,
 *   checks it is the package, version code and kind the repo listed, records its signing-certificate hash
 *   (`signature_hash`) and converts it with dex2jar into `<data>/extensions/{manga|anime}/{pkg}/{pkg}-v{version}.jar`.
 * - **Trust** is `signature_hash ∈ TRUSTED_SIGNATURE`. Only a trusted *manga* extension is loaded (its sources come
 *   from the live code, upstream's `setupJar`). An untrusted one is never instantiated: its source rows come from the
 *   repo listing. Anime extensions are never loaded in Phase 1 (`is_loaded` stays false); their `ANIME_SOURCE` rows
 *   always come from the listing.
 * - **Update** re-runs install. A new certificate that isn't trusted is refused with [ErrorCode.SIGNATURE_CHANGED]
 *   and the installed version is kept.
 * - Every install/update reports its steps on [progress].
 *
 * One [Mutex] serialises all of it.
 */
class ExtensionLifecycle(
    private val fetcher: RepoIndexFetcher,
    private val http: RepoHttp,
    private val unloader: ExtensionUnloader = ExtensionUnloader.default,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val mutex = Mutex()

    private val progressFlow =
        MutableSharedFlow<ExtensionInstallProgress>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The steps of every install and update: `DOWNLOADING → VERIFYING → CONVERTING → INSTALLED | FAILED`. */
    val progress: SharedFlow<ExtensionInstallProgress> = progressFlow.asSharedFlow()

    /** How many collectors [progress] has (tests wait for a subscription with it). */
    val progressSubscribers: StateFlow<Int> get() = progressFlow.subscriptionCount

    /** Installs the listed extension [pkg] of [kind] (an `EXTENSION` row from a repo refresh). */
    suspend fun install(
        pkg: String,
        kind: ExtensionKind,
    ): InstallResult = mutex.withLock { tracked(pkg, kind) { report -> installListed(pkg, kind, isUpdate = false, report) } }

    /** Updates the installed extension [pkg] of [kind] to the version its repo lists now. */
    suspend fun update(
        pkg: String,
        kind: ExtensionKind,
    ): InstallResult = mutex.withLock { tracked(pkg, kind) { report -> installListed(pkg, kind, isUpdate = true, report) } }

    /**
     * Installs a sideloaded file. Only signed APKs are accepted (a jar has no signature to verify); the kind comes
     * from the APK's feature. There is no listing, so an untrusted sideload has no source rows until it is trusted.
     */
    suspend fun installExternal(
        input: InputStream,
        fileName: String,
    ): InstallResult =
        mutex.withLock {
            tracked(fileName, null) { report ->
                if (!fileName.endsWith(".apk", ignoreCase = true)) {
                    input.close()
                    throw MadoyomiException(
                        ErrorCode.SIGNATURE_INVALID,
                        "Only signed APKs can be installed; $fileName has no signature to verify",
                        mapOf("fileName" to fileName),
                    )
                }
                report(InstallState.DOWNLOADING, null)
                val apk = tempApk(fileName)
                input.use { stream -> apk.outputStream().buffered().use { stream.copyTo(it) } }
                try {
                    report(InstallState.VERIFYING, null)
                    val inspection = inspect(apk)
                    val kind =
                        inspection.kind ?: throw MadoyomiException(
                            ErrorCode.PACKAGE_MISMATCH,
                            "${inspection.packageName} is not a Tachiyomi or Aniyomi extension",
                            mapOf("pkgName" to inspection.packageName, "reason" to "feature"),
                        )
                    val pkg = inspection.packageName
                    val warnings = check(inspection, kind, listing = null)
                    val row = extensionRow(pkg, kind = null)
                    if (row != null && row[ExtensionTable.type] != kind) {
                        throw MadoyomiException(
                            ErrorCode.PACKAGE_MISMATCH,
                            "$pkg is already known as a ${row[ExtensionTable.type].name.lowercase()} extension",
                            mapOf("pkgName" to pkg, "reason" to "kind"),
                        )
                    }
                    val installed = row?.get(ExtensionTable.isInstalled) == true
                    if (installed && inspection.versionCode <= row[ExtensionTable.versionCode]) {
                        throw MadoyomiException(
                            ErrorCode.INSTALL_FAILED,
                            "Extension \"$pkg (v${inspection.versionName})\" already installed",
                            mapOf("pkgName" to pkg),
                        )
                    }
                    record(apk, inspection, kind, row?.takeIf { installed }, listing = null, warnings, report)
                } finally {
                    discard(apk)
                }
            }
        }

    /** Uninstalls [pkg] of [kind]: unloads it, deletes its files and its source rows, and resets its row. */
    suspend fun uninstall(
        pkg: String,
        kind: ExtensionKind,
    ) = mutex.withLock {
        val row = extensionRow(pkg, kind) ?: throw notFound(pkg, kind)
        if (!row[ExtensionTable.isInstalled]) {
            throw MadoyomiException(ErrorCode.INSTALL_FAILED, "$pkg is not installed", mapOf("pkgName" to pkg))
        }
        logger.debug { "Uninstalling $pkg" }
        val jar = ExtensionFiles.installedJar(row)
        unloader.unload(row)
        jar?.let(ExtensionLoadGate::revoke)

        val extensionId = row[ExtensionTable.id].value
        dbTransaction {
            SourceTable.deleteWhere { SourceTable.extension eq extensionId }
            AnimeSourceTable.deleteWhere { AnimeSourceTable.extension eq extensionId }
            // upstream's rule: a row no repo lists any more goes, otherwise it becomes a plain listing again
            if (row[ExtensionTable.isObsolete] || row[ExtensionTable.apkUrl] == null) {
                ExtensionTable.deleteWhere { ExtensionTable.id eq extensionId }
            } else {
                ExtensionTable.update({ ExtensionTable.id eq extensionId }) {
                    it[isInstalled] = false
                    it[hasUpdate] = false
                    it[apkName] = null
                    it[signatureHash] = null
                    it[isLoaded] = false
                }
            }
        }
        if (jar != null && jar.exists()) Extension.tryToDeleteFile(jar)
        deleteIfEmpty(ExtensionFiles.pkgDir(kind, pkg))
    }

    /** Trusts [hash] (origin USER) and loads the installed manga extensions signed with it. */
    suspend fun trustSignature(hash: String) =
        mutex.withLock {
            SignatureTrust.trust(hash, now())
            reconcileLocked(hash.lowercase())
        }

    /** Trusts the signing certificate of the installed extension [pkg]. */
    suspend fun trustExtension(
        pkg: String,
        kind: ExtensionKind,
    ) {
        val row = extensionRow(pkg, kind) ?: throw notFound(pkg, kind)
        val hash =
            row[ExtensionTable.signatureHash] ?: throw MadoyomiException(
                ErrorCode.UNTRUSTED,
                "$pkg has no verified signature (it was installed before Madoyomi checked signatures); reinstall it",
                mapOf("pkgName" to pkg),
            )
        trustSignature(hash)
    }

    /**
     * Withdraws the user's trust in [hash] and unloads what it no longer covers. A key an added repo vouches for
     * stays trusted. Returns whether [hash] is still trusted.
     */
    suspend fun untrustSignature(hash: String): Boolean =
        mutex.withLock {
            val stillTrusted = SignatureTrust.untrust(hash, now())
            reconcileLocked(hash.lowercase())
            stillTrusted
        }

    /**
     * Brings the installed extensions signed with [hash] in line with its trust: a trusted manga extension is loaded
     * (and its `SOURCE` rows reconciled with the live sources), an untrusted one unloaded. Called after any trust
     * change, including a repo add (`RepoService`).
     */
    suspend fun reconcileTrust(hash: String) = mutex.withLock { reconcileLocked(hash) }

    private suspend fun reconcileLocked(hash: String) {
        val trusted = SignatureTrust.isTrusted(hash)
        val rows =
            dbTransaction {
                ExtensionTable
                    .selectAll()
                    .where { (ExtensionTable.signatureHash eq hash) and (ExtensionTable.isInstalled eq true) }
                    .toList()
            }
        rows.forEach { row ->
            val pkg = row[ExtensionTable.pkgName]
            try {
                when {
                    !trusted -> {
                        unloader.unload(row)
                        setLoaded(row, false)
                    }

                    row[ExtensionTable.type] == ExtensionKind.MANGA -> {
                        loadTrustedManga(row, hash)
                        logger.info { "Loaded $pkg (signature $hash is trusted)" }
                    }

                    // anime: trusted, but nothing is instantiated before Phase 2
                    else -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to apply the trust change of $hash to $pkg" }
            }
        }
    }

    // --- install pipeline ---

    private suspend fun installListed(
        pkg: String,
        kind: ExtensionKind,
        isUpdate: Boolean,
        report: (InstallState, ExtensionKind?) -> Unit,
    ): InstallResult {
        val row = extensionRow(pkg, kind) ?: throw notFound(pkg, kind)
        val installed = row[ExtensionTable.isInstalled]
        if (!isUpdate && installed) {
            throw MadoyomiException(
                ErrorCode.INSTALL_FAILED,
                "Extension \"$pkg (v${row[ExtensionTable.versionName]})\" already installed",
                mapOf("pkgName" to pkg),
            )
        }
        if (isUpdate && !installed) {
            throw MadoyomiException(ErrorCode.INSTALL_FAILED, "$pkg is not installed", mapOf("pkgName" to pkg))
        }

        report(InstallState.DOWNLOADING, kind)
        val listing = resolveListing(row, kind)
        if (isUpdate && listing.versionCode <= row[ExtensionTable.versionCode]) {
            throw MadoyomiException(
                ErrorCode.INSTALL_FAILED,
                "No update for $pkg: the repo lists version code ${listing.versionCode}, " +
                    "${row[ExtensionTable.versionCode]} is installed",
                mapOf("pkgName" to pkg),
            )
        }
        val apk = download(listing.apkUrl)
        try {
            report(InstallState.VERIFYING, kind)
            val inspection = inspect(apk)
            val warnings = check(inspection, kind, listing)
            return record(apk, inspection, kind, row.takeIf { installed }, listing, warnings, report)
        } finally {
            discard(apk)
        }
    }

    /** The repo's current listing of [row]'s package. The index is re-fetched: it is the authority for the version. */
    private suspend fun resolveListing(
        row: ResultRow,
        kind: ExtensionKind,
    ): ExtensionListing {
        val pkg = row[ExtensionTable.pkgName]
        val storeUrl = row[ExtensionTable.storeIndexUrl] ?: throw notFound(pkg, kind, "it is not listed by any repo")
        val store =
            dbTransaction {
                ExtensionStoreTable
                    .selectAll()
                    .where { (ExtensionStoreTable.indexUrl eq storeUrl) and (ExtensionStoreTable.type eq kind) }
                    .firstOrNull()
            } ?: throw notFound(pkg, kind, "its repo $storeUrl was removed")
        val fetched = fetcher.fetch(store[ExtensionStoreTable.indexUrl], store[ExtensionStoreTable.format], kind)
        val listing =
            fetched.extensions.firstOrNull { it.pkg == pkg } ?: throw notFound(pkg, kind, "the repo $storeUrl no longer lists it")
        if (listing.apkUrl.isBlank()) {
            throw MadoyomiException(ErrorCode.INSTALL_FAILED, "The repo lists no APK for $pkg", mapOf("pkgName" to pkg))
        }
        return listing
    }

    private suspend fun download(url: String): Path {
        val apk = tempApk(url.substringBefore('?').substringAfterLast('/'))
        val bytes =
            try {
                http.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MadoyomiException(
                    ErrorCode.INSTALL_FAILED,
                    "Failed to download $url: ${e.message ?: e.javaClass.simpleName}",
                    mapOf("url" to url),
                    e,
                )
            }
        apk.writeBytes(bytes)
        return apk
    }

    /** A fresh `<temp>/extensions/<uuid>/<name>`: dex2jar and the asset extraction write next to the APK. */
    private fun tempApk(name: String): Path {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "extension.apk" }
        val dir = Path(applicationDirs.tempRoot) / "extensions" / UUID.randomUUID().toString()
        dir.createDirectories()
        return dir / safe
    }

    @OptIn(ExperimentalPathApi::class)
    private fun discard(apk: Path) {
        runCatching { apk.parent.deleteRecursively() }
    }

    private fun inspect(apk: Path): ApkInspection {
        val inspection =
            try {
                ApkInspector.inspect(apk)
            } catch (e: Exception) {
                throw MadoyomiException(
                    ErrorCode.SIGNATURE_INVALID,
                    "Not a valid APK: ${e.message ?: e.javaClass.simpleName}",
                    mapOf("errors" to listOfNotNull(e.message)),
                    e,
                )
            }
        if (!inspection.signature.verified || inspection.signature.signatureHash == null) {
            throw MadoyomiException(
                ErrorCode.SIGNATURE_INVALID,
                "The APK of ${inspection.packageName} has no valid signature: " +
                    inspection.signature.errors
                        .take(3)
                        .joinToString("; ")
                        .ifEmpty { "not signed" },
                mapOf("pkgName" to inspection.packageName, "errors" to inspection.signature.errors.take(10)),
            )
        }
        return inspection
    }

    /** The checks after the signature: kind, listing, main class; returns the warnings. */
    private fun check(
        inspection: ApkInspection,
        kind: ExtensionKind,
        listing: ExtensionListing?,
    ): List<String> {
        val pkg = inspection.packageName
        if (inspection.kind != kind || !kind.matchesPackage(pkg)) {
            throw MadoyomiException(
                ErrorCode.PACKAGE_MISMATCH,
                "$pkg is not a ${kind.name.lowercase()} extension (features: ${inspection.usesFeatures})",
                mapOf("pkgName" to pkg, "reason" to "feature", "type" to kind.name),
            )
        }
        if (listing != null && pkg != listing.pkg) {
            throw MadoyomiException(
                ErrorCode.PACKAGE_MISMATCH,
                "The APK is $pkg, but the repo listed ${listing.pkg}",
                mapOf("pkgName" to listing.pkg, "apkPkgName" to pkg, "reason" to "package"),
            )
        }
        if (listing != null && inspection.versionCode != listing.versionCode) {
            throw MadoyomiException(
                ErrorCode.PACKAGE_MISMATCH,
                "The APK of $pkg has version code ${inspection.versionCode}, but the repo listed ${listing.versionCode}",
                mapOf(
                    "pkgName" to pkg,
                    "versionCode" to listing.versionCode,
                    "apkVersionCode" to inspection.versionCode,
                    "reason" to "version",
                ),
            )
        }
        if (inspection.metaData[kind.classKey].isNullOrBlank()) {
            throw MadoyomiException(
                ErrorCode.PACKAGE_MISMATCH,
                "$pkg declares no ${kind.classKey}",
                mapOf("pkgName" to pkg, "reason" to "class"),
            )
        }

        val warnings = mutableListOf<String>()
        val lib = inspection.libVersion()
        if (lib.toDoubleOrNull()?.let { it in kind.libRange } != true) {
            warnings += "extensions-lib $lib is outside the expected ${kind.libRange.start}–${kind.libRange.endInclusive}"
        }
        if (listing != null && listing.isTorrent != manifestTorrent(inspection, kind)) {
            warnings += "the repo says torrent=${listing.isTorrent}, the APK says ${manifestTorrent(inspection, kind)}; using the APK"
        }
        warnings.forEach { logger.warn { "$pkg: $it" } }
        return warnings
    }

    private fun manifestTorrent(
        inspection: ApkInspection,
        kind: ExtensionKind,
    ): Boolean = kind.torrentKey?.let { inspection.metaData[it] == "1" } ?: false

    /**
     * Applies the SIGNATURE_CHANGED rule, converts the APK and records the install. [installed] is the row of the
     * version being replaced, if any.
     */
    private suspend fun record(
        apk: Path,
        inspection: ApkInspection,
        kind: ExtensionKind,
        installed: ResultRow?,
        listing: ExtensionListing?,
        warnings: List<String>,
        report: (InstallState, ExtensionKind?) -> Unit,
    ): InstallResult {
        val pkg = inspection.packageName
        val hash = inspection.signature.signatureHash!!
        val trusted = SignatureTrust.isTrusted(hash)
        val oldHash = installed?.get(ExtensionTable.signatureHash)
        if (oldHash != null && oldHash != hash && !trusted) {
            throw MadoyomiException(
                ErrorCode.SIGNATURE_CHANGED,
                "The new version of $pkg is signed by another certificate ($hash, was $oldHash), which is not trusted; " +
                    "the installed version is kept. Trust the new certificate to update.",
                mapOf("pkgName" to pkg, "oldHash" to oldHash, "newHash" to hash),
            )
        }
        if (inspection.signature.signerCertSha256.size > 1) {
            logger.info { "$pkg has ${inspection.signature.signerCertSha256.size} signers; trusting by the first" }
        }

        report(InstallState.CONVERTING, kind)
        val jar = ExtensionFiles.jarPath(kind, pkg, inspection.versionName)
        val oldJar = installed?.let(ExtensionFiles::installedJar)
        if (oldJar != null && oldJar.absolute().normalize() == jar.absolute().normalize()) {
            throw MadoyomiException(
                ErrorCode.INSTALL_FAILED,
                "Extension can't be updated to the same version. Reinstall the extension instead",
                mapOf("pkgName" to pkg),
            )
        }
        jar.parent.createDirectories()
        try {
            jar.deleteIfExists()
        } catch (e: Exception) {
            throw MadoyomiException(
                ErrorCode.INSTALL_FAILED,
                "$jar is still in use (Windows file lock); restart Madoyomi and try again",
                mapOf("pkgName" to pkg),
                e,
            )
        }

        val metadata = installMetadata(inspection, kind, listing, apk)
        try {
            PackageTools.dex2jar(apk, jar)
            // R8-optimised dex (Keiyoushi) would otherwise leave classes the JVM refuses to verify
            val repair = Dex2jarRepair.repair(apk, jar)
            val allWarnings = warnings + repair.skipped.map { "not repaired: $it" }
            Extension.extractAssetsFromApk(apk, jar)
            Extension.extractAndCacheApkIcon(apk, pkg)
            ExtensionLoadGate.permit(jar, hash)

            val loaded =
                if (trusted && kind == ExtensionKind.MANGA) {
                    PackageTools.blockJarUsageWhile(listOfNotNull(oldJar, jar)) { loadSources ->
                        dbSuspendTransaction {
                            installed?.let(unloader::unload)
                            Extension.setupJar(
                                extensionMainClassInstance = loadSources(jar, metadata.className),
                                className = metadata.className,
                                extensionName = metadata.name,
                                extensionLibVersion = metadata.lib,
                                apkName = metadata.apkName,
                                pkgName = pkg,
                                versionName = inspection.versionName,
                                versionCode = inspection.versionCode.toInt(),
                                contentWarning = metadata.contentWarning,
                            )
                            ExtensionTable.update({ (ExtensionTable.pkgName eq pkg) and (ExtensionTable.type eq kind) }) {
                                it[signatureHash] = hash
                                it[isTorrent] = false
                                it[isLoaded] = true
                            }
                        }
                    }
                    true
                } else {
                    installed?.let(unloader::unload)
                    recordWithoutLoading(pkg, kind, inspection, metadata, hash, listing)
                    false
                }

            if (oldJar != null && oldJar.exists()) Extension.tryToDeleteFile(oldJar)
            Extension.removeDeletionOnStartupRequest(jar)
            if (oldJar != null) ExtensionLoadGate.revoke(oldJar)

            logger.info {
                "Installed ${kind.name.lowercase()} extension $pkg v${inspection.versionName} " +
                    "(signature $hash, ${if (trusted) "trusted" else "untrusted"}${if (loaded) ", loaded" else ""})"
            }
            return InstallResult(pkg, kind, inspection.versionName, inspection.versionCode, hash, trusted, loaded, allWarnings)
        } catch (e: Throwable) {
            PackageTools.jarLoaderMap.remove(jar.toAbsolutePath().toString())?.close()
            ExtensionLoadGate.revoke(jar)
            runCatching { jar.deleteIfExists() }
            throw e
        }
    }

    private class InstallMetadata(
        val name: String,
        val lib: String,
        val className: String,
        val contentWarning: Int,
        val isTorrent: Boolean,
        val apkName: String,
    )

    /** Upstream `installExtension`'s manifest reading, for both kinds. */
    private fun installMetadata(
        inspection: ApkInspection,
        kind: ExtensionKind,
        listing: ExtensionListing?,
        apk: Path,
    ): InstallMetadata {
        val meta = inspection.metaData
        val pkg = inspection.packageName
        val sourceClass = meta.getValue(kind.classKey).trim()
        val contentWarning =
            meta[ExtensionKind.CONTENT_WARNING_KEY]?.toIntOrNull()?.takeIf { it != 0 }
                ?: meta[kind.nsfwKey]?.toIntOrNull()
                ?: 0
        return InstallMetadata(
            name =
                meta[ExtensionKind.NAME_KEY]
                    ?: inspection.label?.removePrefix(kind.labelPrefix)
                    ?: listing?.name
                    ?: throw MadoyomiException(ErrorCode.PACKAGE_MISMATCH, "Could not resolve the name of $pkg", mapOf("pkgName" to pkg)),
            lib = inspection.libVersion(),
            className = if (sourceClass.startsWith(".")) pkg + sourceClass else sourceClass,
            contentWarning = contentWarning,
            isTorrent = manifestTorrent(inspection, kind),
            // upstream stores the APK file name
            apkName = apk.fileName.toString(),
        )
    }

    /** An install whose code must not (untrusted) or cannot yet (anime) run: the row and the listing's sources. */
    private fun recordWithoutLoading(
        pkg: String,
        kind: ExtensionKind,
        inspection: ApkInspection,
        metadata: InstallMetadata,
        hash: String,
        listing: ExtensionListing?,
    ) = dbTransaction {
        // a sideload has no listing: keep the language the row already has
        val rowLang =
            listing?.lang
                ?: ExtensionTable
                    .select(ExtensionTable.lang)
                    .where { ExtensionTable.pkgName eq pkg }
                    .firstOrNull()
                    ?.get(ExtensionTable.lang)
                ?: ""
        ExtensionTable.upsert(keys = arrayOf(ExtensionTable.pkgName)) {
            it[apkName] = metadata.apkName
            it[name] = metadata.name
            it[pkgName] = pkg
            it[versionName] = inspection.versionName
            it[versionCode] = inspection.versionCode
            it[extensionLib] = metadata.lib
            it[ExtensionTable.lang] = rowLang
            it[contentWarning] = metadata.contentWarning
            it[isInstalled] = true
            it[hasUpdate] = false
            it[classFQName] = metadata.className
            it[type] = kind
            it[signatureHash] = hash
            it[isTorrent] = metadata.isTorrent
            it[isLoaded] = false
        }
        val extensionId =
            ExtensionTable
                .select(ExtensionTable.id)
                .where { ExtensionTable.pkgName eq pkg }
                .first()[ExtensionTable.id]
                .value
        val sources = listing?.sources.orEmpty()
        when (kind) {
            ExtensionKind.MANGA -> {
                SourceTable.deleteWhere { SourceTable.extension eq extensionId }
                SourceTable.batchInsert(sources) { source ->
                    this[SourceTable.id] = source.id
                    this[SourceTable.name] = source.name.take(128)
                    this[SourceTable.lang] = source.lang.take(32)
                    this[SourceTable.extension] = extensionId
                    this[SourceTable.contentWarning] = metadata.contentWarning
                }
            }

            ExtensionKind.ANIME -> {
                AnimeSourceTable.deleteWhere { AnimeSourceTable.extension eq extensionId }
                AnimeSourceTable.batchInsert(sources) { source ->
                    this[AnimeSourceTable.id] = source.id
                    this[AnimeSourceTable.name] = source.name.take(128)
                    this[AnimeSourceTable.lang] = source.lang.take(32)
                    this[AnimeSourceTable.extension] = extensionId
                    this[AnimeSourceTable.contentWarning] = metadata.contentWarning
                    this[AnimeSourceTable.baseUrl] = source.baseUrl.take(2048).ifBlank { null }
                }
            }
        }
    }

    /** Loads an installed, trusted manga extension and reconciles its `SOURCE` rows with its live sources. */
    private suspend fun loadTrustedManga(
        row: ResultRow,
        hash: String,
    ) {
        val pkg = row[ExtensionTable.pkgName]
        val jar = ExtensionFiles.installedJar(row) ?: error("$pkg has no jar")
        ExtensionLoadGate.permit(jar, hash)
        PackageTools.blockJarUsageWhile(listOf(jar)) { loadSources ->
            dbSuspendTransaction {
                val className = row[ExtensionTable.classFQName]
                Extension.setupJar(
                    extensionMainClassInstance = loadSources(jar, className),
                    className = className,
                    extensionName = row[ExtensionTable.name],
                    extensionLibVersion = row[ExtensionTable.extensionLib].orEmpty(),
                    apkName = row[ExtensionTable.apkName].orEmpty(),
                    pkgName = pkg,
                    versionName = row[ExtensionTable.versionName],
                    versionCode = row[ExtensionTable.versionCode].toInt(),
                    contentWarning = row[ExtensionTable.contentWarning],
                )
                ExtensionTable.update({ ExtensionTable.id eq row[ExtensionTable.id].value }) { it[isLoaded] = true }
            }
        }
    }

    // --- helpers ---

    private suspend fun <T> tracked(
        pkg: String,
        kind: ExtensionKind?,
        block: suspend (report: (InstallState, ExtensionKind?) -> Unit) -> T,
    ): T {
        var lastKind = kind
        val report = { state: InstallState, k: ExtensionKind? ->
            lastKind = k ?: lastKind
            progressFlow.tryEmit(ExtensionInstallProgress(pkg, lastKind, state))
            Unit
        }
        try {
            val result = block(report)
            val installed = result as? InstallResult
            progressFlow.tryEmit(ExtensionInstallProgress(installed?.pkgName ?: pkg, installed?.kind ?: lastKind, InstallState.INSTALLED))
            return result
        } catch (e: Throwable) {
            val code = (e as? MadoyomiException)?.code ?: ErrorCode.INSTALL_FAILED
            progressFlow.tryEmit(ExtensionInstallProgress(pkg, lastKind, InstallState.FAILED, code, e.message))
            if (e !is CancellationException) logger.warn { "Installing $pkg failed ($code): ${e.message}" }
            throw e
        }
    }

    private fun extensionRow(
        pkg: String,
        kind: ExtensionKind?,
    ): ResultRow? =
        dbTransaction {
            ExtensionTable
                .selectAll()
                .where {
                    if (kind ==
                        null
                    ) {
                        ExtensionTable.pkgName eq pkg
                    } else {
                        (ExtensionTable.pkgName eq pkg) and (ExtensionTable.type eq kind)
                    }
                }.firstOrNull()
        }

    private fun setLoaded(
        row: ResultRow,
        loaded: Boolean,
    ) = dbTransaction { ExtensionTable.update({ ExtensionTable.id eq row[ExtensionTable.id].value }) { it[isLoaded] = loaded } }

    private fun notFound(
        pkg: String,
        kind: ExtensionKind,
        why: String? = null,
    ) = MadoyomiException(
        ErrorCode.NOT_FOUND,
        "No ${kind.name.lowercase()} extension $pkg${why?.let { ": $it" } ?: ""}",
        mapOf("pkgName" to pkg, "type" to kind.name),
    )

    private fun deleteIfEmpty(dir: Path) {
        runCatching { if (dir.isDirectory() && dir.listDirectoryEntries().isEmpty()) dir.deleteIfExists() }
    }

    companion object {
        /** The lifecycle on the app's network stack. */
        val default by lazy { ExtensionLifecycle(RepoIndexFetcher.network, NetworkRepoHttp) }
    }
}
