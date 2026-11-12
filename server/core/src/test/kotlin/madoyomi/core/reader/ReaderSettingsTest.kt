package madoyomi.core.reader

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reader settings' file (Phase 5 Stage 1): the default cap, a change, its bounds, and a file edited by hand. */
class ReaderSettingsTest {
    private val dir: File = Files.createTempDirectory("reader-settings").toFile()
    private val file = File(dir, ReaderSettingsStore.FILE_NAME)

    @BeforeTest
    fun useScratchFile() {
        ReaderSettingsStore.fileOverride = file
        ReaderSettingsStore.reload()
    }

    @AfterTest
    fun cleanUp() {
        ReaderSettingsStore.fileOverride = null
        ReaderSettingsStore.reload()
        dir.deleteRecursively()
    }

    @Test
    fun `without a file the cap is 1 GiB`() {
        assertEquals(1024, ReaderSettingsStore.current().pageCacheLimitMb)
        assertEquals(1024L * 1024 * 1024, ReaderSettingsStore.current().pageCacheLimitBytes)
        assertFalse(file.exists())
    }

    @Test
    fun `a change is written and read back`() {
        assertEquals(2048, ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = 2048)).pageCacheLimitMb)
        assertTrue(file.readText().contains("\"pageCacheLimitMb\": 2048"), file.readText())
        ReaderSettingsStore.reload()
        assertEquals(2048, ReaderSettingsStore.current().pageCacheLimitMb)
        // an empty patch changes nothing
        assertEquals(2048, ReaderSettingsStore.update(ReaderSettings.Patch()).pageCacheLimitMb)
    }

    @Test
    fun `a cap below 256 MiB or above 10 GiB is refused and nothing changes`() {
        for (bad in listOf(0, 255, 10241, -1)) {
            val e = assertFailsWith<MadoyomiException> { ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = bad)) }
            assertEquals(ErrorCode.BAD_REQUEST, e.code)
            assertEquals("pageCacheLimitMb", e.details["field"])
        }
        assertEquals(256, ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = 256)).pageCacheLimitMb)
        assertEquals(10240, ReaderSettingsStore.update(ReaderSettings.Patch(pageCacheLimitMb = 10240)).pageCacheLimitMb)
    }

    @Test
    fun `a file edited by hand - a value out of range or broken JSON is the default`() {
        file.writeText("""{"pageCacheLimitMb": 5, "unknown": true}""")
        assertEquals(1024, ReaderSettingsStore.current().pageCacheLimitMb)
        ReaderSettingsStore.reload()
        file.writeText("""{"pageCacheLimitMb": 4096}""")
        assertEquals(4096, ReaderSettingsStore.current().pageCacheLimitMb)
        ReaderSettingsStore.reload()
        file.writeText("not json")
        assertEquals(ReaderSettings(), ReaderSettingsStore.current())
    }
}
