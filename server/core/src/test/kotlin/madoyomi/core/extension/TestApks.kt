package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.repo.ListingSource
import madoyomi.core.repo.StubRepoHttp
import madoyomi.exthost.ApkInspection
import madoyomi.exthost.ApkInspector
import madoyomi.exthost.ExtensionKind
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.RandomAccessFile
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.zip.ZipEntry
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * The real extension APKs in `tools/apks/` (fetched by `tools/fetch-test-apks.ps1`; never committed). Tests that need
 * one skip with a message when it is missing. Listings are built from each APK's own manifest, so a newer pinned APK
 * needs no test change.
 */
object TestApks {
    const val ANIMEGG = "aniyomi-en.animegg-v*.apk"
    const val ANIMEPAHE = "aniyomi-en.animepahe-v*.apk"
    const val MANGAPILL = "tachiyomi-en.mangapill-v*.apk"

    val dir: Path = Path(System.getProperty("madoyomi.testApks") ?: "../../tools/apks")

    fun find(glob: String): Path {
        val found = if (dir.isDirectory()) dir.listDirectoryEntries(glob).maxOrNull() else null
        assumeTrue(found != null, "No $glob in ${dir.toAbsolutePath().normalize()}: run tools/fetch-test-apks.ps1")
        return found!!
    }

    /** An APK with its inspection, cached per file. */
    class Apk(
        val path: Path,
    ) {
        val inspection: ApkInspection by lazy { ApkInspector.inspect(path) }
        val pkg get() = inspection.packageName
        val hash get() = inspection.signature.signatureHash!!
        val fileName get() = path.name
        val bytes: ByteArray by lazy { path.readBytes() }
    }

    fun apk(glob: String) = Apk(find(glob))

    /**
     * Loads and links every class of [jar] (`getDeclaredMethods` forces linking, which runs the bytecode verifier) and
     * returns the `VerifyError`s. Other linkage errors are printed, not returned (an anime class may reference an
     * Android API the stubs lack; that is Phase 2's business).
     */
    fun verifyErrors(jar: Path): List<String> =
        URLClassLoader(arrayOf(jar.toUri().toURL()), TestApks::class.java.classLoader).use { loader ->
            java.util.zip.ZipFile(jar.toFile()).use { zip ->
                zip
                    .entries()
                    .toList()
                    .filter { it.name.endsWith(".class") }
                    .mapNotNull { entry ->
                        val className = entry.name.removeSuffix(".class").replace('/', '.')
                        try {
                            Class.forName(className, false, loader).declaredMethods
                            null
                        } catch (e: VerifyError) {
                            "$className: " +
                                e.message
                                    ?.lineSequence()
                                    ?.take(6)
                                    ?.joinToString(" ")
                        } catch (e: LinkageError) {
                            println("$className does not link: $e")
                            null
                        }
                    }
            }
        }

    /** Flips one byte in the data of a STORED entry: the zip still parses, but no signature digest matches. */
    fun tampered(
        bytes: ByteArray,
        scratch: Path,
    ): ByteArray {
        val file = scratch.resolve("tampered-${System.nanoTime()}.apk").toFile()
        file.writeBytes(bytes)
        val offset =
            ZipFile.builder().setFile(file).get().use { zip ->
                val entry = zip.entries.toList().first { it.method == ZipEntry.STORED && it.size > 16 && !it.isDirectory }
                entry.dataOffset + entry.size / 2
            }
        RandomAccessFile(file, "rw").use {
            it.seek(offset)
            val b = it.read()
            it.seek(offset)
            it.write(b xor 0xFF)
        }
        return file.readBytes()
    }
}

/**
 * A legacy JSON repo (`index.min.json` + `repo.json`) served by a [StubRepoHttp], with its APKs at `<base>/apk/`.
 */
class TestRepo(
    val http: StubRepoHttp,
    val kind: ExtensionKind,
    val base: String,
    var signingKey: String,
) {
    val listUrl = "$base/index.min.json"
    val repoUrl = "$base/repo.json"

    data class Entry(
        val apk: TestApks.Apk,
        val pkg: String = apk.pkg,
        val versionCode: Long = apk.inspection.versionCode,
        val versionName: String = apk.inspection.versionName,
        val sources: List<ListingSource> = emptyList(),
        val torrent: Boolean = false,
        /** What the APK URL serves (default: the real APK). */
        val apkBytes: ByteArray = apk.bytes,
    )

    fun serve(vararg entries: Entry) {
        val prefix = if (kind == ExtensionKind.ANIME) "Aniyomi: " else "Tachiyomi: "
        val list =
            entries.joinToString(",", "[", "]") { e ->
                val sources =
                    e.sources.joinToString(",", "[", "]") {
                        """{"name": "${it.name}", "lang": "${it.lang}", "id": "${it.id}", "baseUrl": "${it.baseUrl}"}"""
                    }
                """{"name": "$prefix${e.pkg.substringAfterLast('.')}", "pkg": "${e.pkg}", "apk": "${e.apk.fileName}", "lang": "en",
                    |"code": ${e.versionCode}, "version": "${e.versionName}", "nsfw": 0, "torrent": ${if (e.torrent) 1 else 0},
                    |"sources": $sources}
                """.trimMargin()
            }
        http.routes[listUrl] = list.toByteArray()
        http.routes[repoUrl] =
            """{"meta": {"name": "Test ${kind.name.lowercase()} repo", "website": "https://example.org",
                |"signingKeyFingerprint": "$signingKey"}}
            """.trimMargin().toByteArray()
        entries.forEach { http.routes["$base/apk/${it.apk.fileName}"] = it.apkBytes }
    }
}
