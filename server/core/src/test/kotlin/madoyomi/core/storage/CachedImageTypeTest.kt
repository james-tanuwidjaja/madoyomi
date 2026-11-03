package madoyomi.core.storage

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import libcore.net.MimeUtils
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The MIME type of a cached image (pages, thumbnails) is the same as on the first, uncached fetch. */
class CachedImageTypeTest {
    @TempDir
    lateinit var dir: File

    private fun cachedType(
        name: String,
        extension: String,
    ): String {
        val base = File(dir, name).path
        val file = File("$base.$extension")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val (stream, mime) = ImageResponse.getCachedImageResponse(file.path, base)
        stream.close()
        return mime
    }

    @Test
    fun `a cached JPEG is image-jpeg, not image-jpg`() {
        assertEquals("image/jpeg", cachedType("page0", "jpg"))
    }

    @Test
    fun `every image type saveImage writes maps back to its MIME type`() {
        ImageUtil.ImageType.entries.forEach { type ->
            assertEquals(type.mime, cachedType("page-${type.name}", type.extension), type.name)
        }
    }

    @Test
    fun `an extension saveImage guessed from the source's MIME type maps back through MimeUtils`() {
        assertEquals(MimeUtils.guessMimeTypeFromExtension("bmp"), cachedType("page-bmp", "bmp"))
    }

    @Test
    fun `an unknown extension keeps upstream's image-extension fallback`() {
        assertEquals("image/zzz", cachedType("page-zzz", "zzz"))
    }

    @Test
    fun `the first fetch and the cached fetch agree for a JPEG`() {
        // A minimal JPEG header (SOI + APP0 JFIF), which ImageUtil.findImageType recognises.
        val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte()) +
                ByteArray(32)
        val base = File(dir, "page1").path
        val (savedPath, firstMime) = ImageResponse.saveImage(base, ByteArrayInputStream(jpeg), "image/jpeg")
        val (stream, cachedMime) = ImageResponse.getCachedImageResponse(savedPath, base)
        stream.close()
        assertEquals("image/jpeg", firstMime)
        assertEquals(firstMime, cachedMime)
    }
}
