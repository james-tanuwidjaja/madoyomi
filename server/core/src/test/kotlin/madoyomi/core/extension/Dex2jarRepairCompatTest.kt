package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.anime.RealAnimeSources
import madoyomi.exthost.Dex2jarRepair
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two R8 patterns of the Phase 2 manga sample that `Dex2jarRepair` didn't handle (Phase 2 §10, Stage 2), on the
 * real APKs of `tools/compat-test/apks/` (fetched by `tools/compat-test/scan.ps1`; skipped when missing): every class
 * links and verifies after the repair, and no site is left unrepaired.
 */
class Dex2jarRepairCompatTest {
    @TempDir
    lateinit var tmp: Path

    private fun convert(glob: String): Pair<Dex2jarRepair.Report, Path> {
        val dir = RealAnimeSources.compatApks
        val apk = if (dir.isDirectory()) dir.listDirectoryEntries(glob).maxOrNull() else null
        assumeTrue(apk != null, "No $glob in ${dir.toAbsolutePath().normalize()}: run tools/compat-test/scan.ps1")
        val jar = tmp / "${apk!!.fileName}.jar"
        PackageTools.dex2jar(apk, jar)
        return Dex2jarRepair.repair(apk, jar) to jar
    }

    private fun assertVerifies(
        report: Dex2jarRepair.Report,
        jar: Path,
    ) {
        assertEquals(emptyList(), report.skipped, "nothing left unrepaired")
        assertEquals(emptyList(), TestApks.verifyErrors(jar), "every class verifies")
    }

    /**
     * mangadex: 8 kotlinx-serialization DTOs (`f4 extends u`, …) whose sealed parent lost its no-op constructor to R8,
     * so each constructor calls `Object.<init>` on `this` directly. Rewritten to the direct superclass, which gets the
     * constructor back.
     */
    @Test
    fun `mangadex - a constructor's super call that skips the removed parent constructor`() {
        val (report, jar) = convert("tachiyomi-all.mangadex-v*.apk")
        assertTrue("u" in report.constructorsAdded, "the sealed parent u gets its <init>()V back: ${report.constructorsAdded}")
        assertTrue(report.repaired >= 8, "the 8 super calls are rewritten (${report.repaired} sites in all)")
        assertVerifies(report, jar)
    }

    /**
     * mangafire, comix, mangakakalot: one `new-instance T` + `S.<init>` each (an elided constructor) that dex2jar
     * translated to `new T` + `invokespecial S.<init>`, unfolded, instead of its usual `new S`.
     */
    @Test
    fun `mangafire - an elided constructor whose new T dex2jar kept`() {
        val (report, jar) = convert("tachiyomi-all.mangafire-v*.apk")
        assertVerifies(report, jar)
    }

    @Test
    fun `comix - an elided constructor whose new T dex2jar kept`() {
        val (report, jar) = convert("tachiyomi-en.comix-v*.apk")
        assertVerifies(report, jar)
    }

    @Test
    fun `mangakakalot - an elided constructor whose new T dex2jar kept`() {
        val (report, jar) = convert("tachiyomi-en.mangakakalot-v*.apk")
        assertVerifies(report, jar)
    }
}
