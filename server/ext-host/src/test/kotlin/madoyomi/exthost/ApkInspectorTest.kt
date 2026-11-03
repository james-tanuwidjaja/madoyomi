package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ApkInspector] on the real extension APKs in `tools/apks/` (fetched by `tools/fetch-test-apks.ps1`). */
class ApkInspectorTest {
    @TempDir
    lateinit var tmp: Path

    private fun apk(glob: String): Path {
        val dir = Path(System.getProperty("madoyomi.testApks") ?: "../../tools/apks")
        val found = if (dir.isDirectory()) dir.listDirectoryEntries(glob).maxOrNull() else null
        assumeTrue(found != null, "No $glob in ${dir.toAbsolutePath().normalize()}: run tools/fetch-test-apks.ps1")
        return found!!
    }

    private fun assertSigned(inspection: ApkInspection) {
        val signature = inspection.signature
        assertTrue(signature.verified, "not verified: ${signature.errors}")
        assertTrue(signature.errors.isEmpty(), signature.errors.toString())
        assertTrue(signature.schemes.isNotEmpty())
        assertEquals(1, signature.signerCertSha256.size)
        assertTrue(Regex("[0-9a-f]{64}").matches(signature.signatureHash!!))
        println(
            "${inspection.packageName} v${inspection.versionName} (${inspection.versionCode}): schemes ${signature.schemes}, minSdk ${inspection.minSdk}, signer ${signature.signatureHash}",
        )
    }

    @Test
    fun `anime APKs verify and describe themselves`() {
        val animegg = ApkInspector.inspect(apk("aniyomi-en.animegg-v*.apk"))
        assertSigned(animegg)
        assertEquals("eu.kanade.tachiyomi.animeextension.en.animegg", animegg.packageName)
        assertEquals(ExtensionKind.ANIME, animegg.kind)
        assertEquals(".ExtensionGenerated", animegg.metaData[ExtensionKind.ANIME.classKey])
        assertEquals("AnimeGG", animegg.metaData[ExtensionKind.NAME_KEY])
        assertEquals("0", animegg.metaData[ExtensionKind.ANIME.torrentKey])
        assertEquals(animegg.versionName.substringBefore('.'), animegg.libVersion())

        val animepahe = ApkInspector.inspect(apk("aniyomi-en.animepahe-v*.apk"))
        assertSigned(animepahe)
        assertEquals(ExtensionKind.ANIME, animepahe.kind)
        assertEquals("16", animepahe.libVersion())
        // one key signs every yuzono extension
        assertEquals(animegg.signature.signatureHash, animepahe.signature.signatureHash)
    }

    @Test
    fun `manga APK verifies and describes itself`() {
        val mangapill = ApkInspector.inspect(apk("tachiyomi-en.mangapill-v*.apk"))
        assertSigned(mangapill)
        assertEquals("eu.kanade.tachiyomi.extension.en.mangapill", mangapill.packageName)
        assertEquals(ExtensionKind.MANGA, mangapill.kind)
        assertNotNull(mangapill.metaData[ExtensionKind.MANGA.classKey])
        assertEquals("1.6", mangapill.libVersion())
        assertTrue(mangapill.libVersion().toDouble() in ExtensionKind.MANGA.libRange)
        println("mangapill meta-data: ${mangapill.metaData}, features ${mangapill.usesFeatures}, label ${mangapill.label}")
    }

    @Test
    fun `a byte changed inside the APK fails verification`() {
        val tampered = tmp.resolve("tampered.apk")
        apk("aniyomi-en.animegg-v*.apk").copyTo(tampered)
        flipByteInStoredEntry(tampered)

        val signature = ApkInspector.verify(tampered)
        assertFalse(signature.verified)
        assertTrue(signature.errors.isNotEmpty())
        assertNull(signature.signatureHash)
    }

    @Test
    fun `an APK without signatures fails verification`() {
        val unsigned = tmp.resolve("unsigned.apk")
        stripSignatures(apk("tachiyomi-en.mangapill-v*.apk"), unsigned)

        val inspection = ApkInspector.inspect(unsigned)
        assertEquals("eu.kanade.tachiyomi.extension.en.mangapill", inspection.packageName)
        assertFalse(inspection.signature.verified)
        assertTrue(inspection.signature.schemes.isEmpty())
        assertNull(inspection.signature.signatureHash)
    }

    @Test
    fun `a file that is not an APK does not verify`() {
        val junk = tmp.resolve("junk.apk")
        junk.outputStream().use { it.write("not a zip".toByteArray()) }
        assertFalse(ApkInspector.verify(junk).verified)
    }

    @Test
    fun `typed manifest values are decoded`() {
        assertEquals("1.6", ApkInspector.decodeTypedValue("{4:1070386381}"))
        assertEquals("1.5", ApkInspector.decodeTypedValue("{4:${1.5f.toRawBits()}}"))
        assertEquals("16", ApkInspector.decodeTypedValue("{16:16}"))
        assertEquals(".ExtensionGenerated", ApkInspector.decodeTypedValue(".ExtensionGenerated"))
        assertEquals("14", ApkInspector.decodeTypedValue("14"))
    }

    companion object {
        /** Flips one byte in the data of a STORED (uncompressed) entry, so the zip still parses but no digest matches. */
        fun flipByteInStoredEntry(apk: Path) {
            val offset =
                ZipFile.builder().setPath(apk).get().use { zip ->
                    val entry =
                        zip.entries.toList().first { it.method == ZipEntry.STORED && it.size > 16 && !it.isDirectory }
                    entry.dataOffset + entry.size / 2
                }
            RandomAccessFile(apk.toFile(), "rw").use { file ->
                file.seek(offset)
                val b = file.read()
                file.seek(offset)
                file.write(b xor 0xFF)
            }
        }

        /** Re-zips [apk] without `META-INF/` (v1) and, since the zip is rewritten, without the v2/v3 signing block. */
        fun stripSignatures(
            apk: Path,
            out: Path,
        ) {
            ZipInputStream(apk.inputStream()).use { input ->
                ZipOutputStream(out.outputStream()).use { output ->
                    generateSequence { input.nextEntry }.forEach { entry ->
                        if (!entry.name.startsWith("META-INF/")) {
                            output.putNextEntry(ZipEntry(entry.name))
                            input.copyTo(output)
                            output.closeEntry()
                        }
                    }
                }
            }
        }
    }
}
