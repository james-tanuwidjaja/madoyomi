package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.exthost.Dex2jarRepair
import madoyomi.exthost.ExtensionLinkCheck
import madoyomi.exthost.ExtensionLinkCheck.Kind
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ExtensionLinkCheck] on the real extension jars (every reference into the host API must resolve) and on a synthetic
 * jar with known gaps.
 */
class ExtensionLinkCheckTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }

        /** The host API the extensions are compiled against: a gap here is a Madoyomi bug, not a missing Android stub. */
        private val API_PACKAGES = listOf("eu.kanade.tachiyomi.", "uy.kohesive.injekt.", "okhttp3.", "org.jsoup.", "rx.")
    }

    @TempDir
    lateinit var tmp: Path

    private val host: ClassLoader = PackageTools::class.java.classLoader

    private fun convert(apk: TestApks.Apk): Path {
        val jar = tmp / apk.fileName.replace(".apk", ".jar")
        PackageTools.dex2jar(apk.path, jar)
        Dex2jarRepair.repair(apk.path, jar)
        return jar
    }

    private fun checkRealExtension(glob: String): ExtensionLinkCheck.Report {
        val apk = TestApks.apk(glob)
        val report = ExtensionLinkCheck.check(convert(apk), host)
        println("${apk.fileName}: ${report.externalClasses} external classes, ${report.externalMembers} external members")
        report.issues.forEach { println("  ${it.kind} ${it.target} <- ${it.referencedFrom.take(3)}") }
        assertTrue(report.externalMembers > 100, "the check saw the jar's references")
        val apiGaps = report.issues.filter { issue -> API_PACKAGES.any { issue.target.startsWith(it) } }
        assertEquals(emptyList(), apiGaps, "every reference into the extension API resolves")
        return report
    }

    @Test
    fun `AnimeGG (lib 14) links against the anime API with no gaps`() {
        val report = checkRealExtension(TestApks.ANIMEGG)
        assertEquals(emptyList(), report.of(Kind.MISSING_CLASS))
        assertEquals(emptyList(), report.of(Kind.MISSING_MEMBER))
    }

    @Test
    fun `AnimePahe (lib 16) links against the anime API`() {
        val report = checkRealExtension(TestApks.ANIMEPAHE)
        assertEquals(emptyList(), report.of(Kind.MISSING_CLASS))
        assertEquals(emptyList(), report.of(Kind.MISSING_MEMBER))
    }

    @Test
    fun `MangaPill links against the manga API`() {
        val report = checkRealExtension(TestApks.MANGAPILL)
        assertEquals(emptyList(), report.of(Kind.MISSING_CLASS))
        assertEquals(emptyList(), report.of(Kind.MISSING_MEMBER))
    }

    @Test
    fun `missing classes, missing members and stubs are reported, inherited members resolve`() {
        val jar = tmp / "synthetic.jar"
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("t/Probe.class"))
            out.write(probeClass())
            out.closeEntry()
            out.putNextEntry(JarEntry("t/Child.class"))
            out.write(childClass())
            out.closeEntry()
        }

        val report = ExtensionLinkCheck.check(jar, host)
        report.issues.forEach { println("${it.kind} ${it.target} <- ${it.referencedFrom}") }

        assertEquals(listOf("does.not.Exist"), report.of(Kind.MISSING_CLASS).map { it.target })
        assertEquals(setOf("t.Probe"), report.of(Kind.MISSING_CLASS).single().referencedFrom)
        assertEquals(
            listOf("java.lang.String.noSuchMethod()V", "java.lang.System.nope:I"),
            report.of(Kind.MISSING_MEMBER).map { it.target },
        )
        assertEquals(listOf("android.widget.Button.<init>(Landroid/content/Context;)V"), report.of(Kind.STUB).map { it.target })
    }

    /** `t.Probe.run()` uses one of each: a missing class, two missing members, a stub and resolvable references. */
    private fun probeClass(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "t/Probe", null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "does/not/Exist", "f", "()V", false)
            visitLdcInsn("x")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "noSuchMethod", "()V", false)
            visitLdcInsn("x")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
            visitInsn(Opcodes.POP)
            visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "nope", "I")
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, "android/widget/Button")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "android/widget/Button", "<init>", "(Landroid/content/Context;)V", false)
            visitInsn(Opcodes.POP)
            // a real AndroidCompat implementation, not a stub
            visitLdcInsn("tag")
            visitLdcInsn("msg")
            visitMethodInsn(Opcodes.INVOKESTATIC, "android/util/Log", "d", "(Ljava/lang/String;Ljava/lang/String;)I", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(4, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** `t.Child extends AnimeHttpSource` and calls members it only inherits. */
    private fun childClass(): ByteArray {
        val superName = "eu/kanade/tachiyomi/animesource/online/AnimeHttpSource"
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "t/Child", null, superName, null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "use", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "t/Child", "getClient", "()Lokhttp3/OkHttpClient;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "t/Child",
                "getFilterList",
                "()Leu/kanade/tachiyomi/animesource/model/AnimeFilterList;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "t/Child", "hashCode", "()I", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
