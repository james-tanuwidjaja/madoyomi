package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import suwayomi.tachidesk.manga.impl.util.BytecodeEditor
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [Dex2jarRepair] on real extension APKs converted the way upstream's `PackageTools.dex2jar` does. */
class Dex2jarRepairTest {
    @TempDir
    lateinit var tmp: Path

    private fun apk(glob: String): Path {
        val dir = Path(System.getProperty("madoyomi.testApks") ?: "../../tools/apks")
        val found = if (dir.isDirectory()) dir.listDirectoryEntries(glob).maxOrNull() else null
        assumeTrue(found != null, "No $glob in ${dir.toAbsolutePath().normalize()}: run tools/fetch-test-apks.ps1")
        return found!!
    }

    /** Upstream `PackageTools.dex2jar` (same options), which lives in core. */
    private fun dex2jar(apk: Path): Path {
        val jar = tmp.resolve(apk.fileName.toString().replace(".apk", ".jar"))
        val handler = BaksmaliBaseDexExceptionHandler()
        Dex2jar
            .from(MultiDexFileReader.open(apk.readBytes()))
            .withExceptionHandler(handler)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(true)
            .optimizeSynchronized(false)
            .printIR(false)
            .noCode(false)
            .skipExceptions(false)
            .dontSanitizeNames(true)
            .computeFrames(true)
            .to(jar)
        assertFalse(handler.hasException())
        BytecodeEditor.fixAndroidClasses(jar)
        return jar
    }

    private fun classes(jar: Path): Map<String, ByteArray> =
        ZipInputStream(jar.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .filter { it.name.endsWith(".class") }
                .associate { it.name.removeSuffix(".class") to zip.readBytes() }
        }

    /** The `new` types and `<init>` owners in [className].[method], in order. */
    private fun instructions(
        bytes: ByteArray,
        method: String,
    ): List<String> {
        val found = mutableListOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != method) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String,
                        ) {
                            if (opcode == Opcodes.NEW) found += "new $type"
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (name == "<init>") found += "init $owner"
                        }
                    }
                }
            },
            0,
        )
        return found
    }

    private fun constructors(bytes: ByteArray): List<String> {
        val found = mutableListOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == "<init>") found += descriptor
                    return null
                }
            },
            0,
        )
        return found
    }

    @Test
    fun `Keiyoushi's elided lambda constructors are restored`() {
        val apk = apk("tachiyomi-en.mangapill-v*.apk")
        val jar = dex2jar(apk)
        val before = classes(jar)
        assertTrue("new q" !in instructions(before.getValue("keiyoushi/source/Generated"), "a"))
        assertTrue(constructors(before.getValue("q")).isEmpty())

        val report = Dex2jarRepair.repair(apk, jar)
        println("MangaPill repair: $report")

        assertTrue(report.repaired >= 4, report.toString())
        assertTrue(report.skipped.isEmpty(), report.skipped.toString())
        assertTrue("q" in report.constructorsAdded)
        assertEquals(before.size, report.framesRecomputed)
        val after = classes(jar)
        val generated = instructions(after.getValue("keiyoushi/source/Generated"), "a")
        assertTrue("new q" in generated, generated.toString())
        assertTrue("init q" in generated, generated.toString())
        assertEquals(listOf("()V"), constructors(after.getValue("q")))
        // nothing else lost
        assertEquals(before.keys, after.keys)
    }

    @Test
    fun `an APK without elided constructors only gets its frames recomputed`() {
        val apk = apk("aniyomi-en.animegg-v*.apk")
        val jar = dex2jar(apk)
        val before = classes(jar)

        val report = Dex2jarRepair.repair(apk, jar)

        assertEquals(0, report.repaired)
        assertTrue(report.constructorsAdded.isEmpty())
        assertTrue(report.skipped.isEmpty(), report.skipped.toString())
        assertEquals(before.size, report.framesRecomputed)
        assertEquals(before.keys, classes(jar).keys)
    }
}
