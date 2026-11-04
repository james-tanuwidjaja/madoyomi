package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.core.extension.ExtensionCompatLog.IssueKind
import madoyomi.core.extension.ExtensionCompatLog.Origin
import madoyomi.exthost.ExtensionLinkCheck
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.ChildFirstURLClassLoader
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarOutputStream
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ExtensionCompatLog], [ExtensionIncompatibility] and the class loader hook feeding the log. */
class ExtensionCompatLogTest {
    @TempDir
    lateinit var tmp: Path

    // the log is global: other test classes in this JVM (e.g. AnimeExtensionLoaderTest) record into it too
    @BeforeEach
    @AfterEach
    fun reset() {
        ExtensionCompatLog.clearAll()
    }

    @Test
    fun `an issue is kept once per kind and target, and only its count grows`() {
        val t0 = Instant.ofEpochSecond(100)
        val t1 = Instant.ofEpochSecond(200)
        ExtensionCompatLog.record("a.pkg", IssueKind.STUB, "android.widget.TextView.setError", context = "popular", now = t0)
        ExtensionCompatLog.record("a.pkg", IssueKind.STUB, "android.widget.TextView.setError", context = "search", now = t1)
        ExtensionCompatLog.record("a.pkg", IssueKind.MISSING_CLASS, "android.widget.Button", now = t1)
        ExtensionCompatLog.record("b.pkg", IssueKind.STUB, "android.widget.TextView.setError", now = t1)

        val issues = ExtensionCompatLog.issues("a.pkg")
        assertEquals(listOf(IssueKind.STUB, IssueKind.MISSING_CLASS), issues.map { it.kind })
        val stub = issues.first()
        assertEquals(2, stub.count)
        assertEquals(t0, stub.firstSeen)
        assertEquals(t1, stub.lastSeen)
        assertEquals("popular", stub.context, "the first context is kept")
        assertEquals(Origin.RUNTIME, stub.origin)
        assertEquals(setOf("a.pkg", "b.pkg"), ExtensionCompatLog.all().keys)

        ExtensionCompatLog.clear("a.pkg")
        assertEquals(emptyList(), ExtensionCompatLog.issues("a.pkg"))
        assertEquals(1, ExtensionCompatLog.issues("b.pkg").size)
    }

    @Test
    fun `the log of a package is bounded and counts what it dropped`() {
        repeat(ExtensionCompatLog.MAX_ISSUES_PER_PKG + 5) {
            ExtensionCompatLog.record("a.pkg", IssueKind.CLASS_NOT_FOUND, "x.C$it")
        }
        assertEquals(ExtensionCompatLog.MAX_ISSUES_PER_PKG, ExtensionCompatLog.issues("a.pkg").size)
        assertEquals(5, ExtensionCompatLog.dropped("a.pkg"))
        // a known issue still counts when the log is full
        ExtensionCompatLog.record("a.pkg", IssueKind.CLASS_NOT_FOUND, "x.C0")
        assertEquals(2, ExtensionCompatLog.issues("a.pkg").first().count)
    }

    @Test
    fun `static issues are replaced by the next report and Activity-only ones are left out`() {
        val reachable = ExtensionLinkCheck.Issue(ExtensionLinkCheck.Kind.STUB, "android.os.LocaleList.size()I", setOf("a.b"))
        val activityOnly = ExtensionLinkCheck.Issue(ExtensionLinkCheck.Kind.STUB, "android.app.Activity.finish()V", setOf("a.UrlActivity"))
        val report = ExtensionLinkCheck.Report(listOf(activityOnly, reachable), 10, 20, setOf("a.UrlActivity"))
        ExtensionCompatLog.record("a.pkg", IssueKind.STUB, "runtime.Stub")

        ExtensionCompatLog.recordStatic("a.pkg", report)
        val static = ExtensionCompatLog.issues("a.pkg").filter { it.origin == Origin.STATIC }
        assertEquals(listOf("android.os.LocaleList.size()I"), static.map { it.target })
        assertEquals("a.b", static.single().context)
        assertEquals(report, ExtensionCompatLog.staticReport("a.pkg"))

        ExtensionCompatLog.recordStatic("a.pkg", ExtensionLinkCheck.Report(emptyList(), 10, 20))
        assertEquals(listOf("runtime.Stub"), ExtensionCompatLog.issues("a.pkg").map { it.target }, "runtime issues stay")
    }

    @Test
    fun `the package of a jar comes from the Madoyomi layout, else the file name`() {
        val pkg = "eu.kanade.tachiyomi.animeextension.en.animegg"
        assertEquals(pkg, ExtensionCompatLog.pkgOfJar(Path("data/extensions/anime/$pkg/$pkg-v14.2.jar")))
        assertEquals("aniyomi-en.animegg-v14.2", ExtensionCompatLog.pkgOfJar(Path("tmp/aniyomi-en.animegg-v14.2.jar")))
        assertNull(ExtensionCompatLog.pkgOf(String::class.java))
        assertNull(ExtensionCompatLog.pkgOf(ExtensionCompatLogTest::class.java))
    }

    @Test
    fun `probes are recognised`() {
        listOf("a.FooBeanInfo", "a.FooCustomizer", "a.Foo\$serializer", "org.conscrypt.Conscrypt", "dalvik.system.VMStack")
            .forEach { assertTrue(ExtensionCompatLog.isProbe(it), it) }
        listOf("android.widget.Button", "android.os.LocaleList").forEach { assertTrue(!ExtensionCompatLog.isProbe(it), it) }
    }

    @Test
    fun `the extension class loader reports classes it finds nowhere, but not probes`() {
        ExtensionCompatLog.install()
        val pkg = "eu.kanade.tachiyomi.animeextension.en.fake"
        val dir = (tmp / pkg).createDirectories()
        val jar = dir / "$pkg-v14.1.jar"
        JarOutputStream(jar.outputStream()).close()

        ChildFirstURLClassLoader(arrayOf(jar.toUri().toURL())).use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("android.widget.NoSuchWidget") }
            assertFailsWith<ClassNotFoundException> { loader.loadClass("a.FooBeanInfo") }
            // classes that exist anywhere are not reported
            loader.loadClass("java.lang.String")
            loader.loadClass("android.content.Context")
        }

        val issues = ExtensionCompatLog.issues(pkg)
        assertEquals(listOf("android.widget.NoSuchWidget"), issues.map { it.target })
        assertEquals(IssueKind.CLASS_NOT_FOUND, issues.single().kind)
    }

    @Test
    fun `API gaps are recognised anywhere in the cause chain, other failures are not`() {
        fun classify(e: Throwable) = ExtensionIncompatibility.classify(e)?.let { it.kind to it.target }

        assertEquals(IssueKind.MISSING_CLASS to "android.widget.Button", classify(NoClassDefFoundError("android/widget/Button")))
        assertEquals(IssueKind.MISSING_MEMBER to "x", classify(NoSuchMethodError("x")))
        assertEquals(IssueKind.MISSING_MEMBER to "f", classify(NoSuchFieldError("f")))
        assertEquals(IssueKind.ABSTRACT_METHOD to "m", classify(AbstractMethodError("m")))
        assertEquals(
            IssueKind.MISSING_MEMBER to "deep",
            classify(RuntimeException("wrapped", InvocationTargetException(IllegalStateException(NoSuchMethodError("deep"))))),
        )
        // a real android-jar stub: the target is the stubbed method
        // android.widget.TextView isn't on the compile classpath (android-jar is internal to AndroidCompat)
        val stub = assertFailsWith<InvocationTargetException> { androidStub() }.targetException
        assertEquals(IssueKind.STUB to "android.widget.TextView.<init>", classify(stub))

        assertNull(classify(RuntimeException("boom")))
        assertNull(classify(IllegalStateException("Stub!")), "only android-jar's exact RuntimeException(\"Stub!\")")
        assertNull(classify(java.io.IOException("timeout")))
    }
}

/** Runs a real android-jar stub, `new android.widget.TextView(null)`, which throws `"Stub!"` (wrapped by reflection). */
internal fun androidStub(): Any =
    Class
        .forName("android.widget.TextView")
        .getConstructor(Class.forName("android.content.Context"))
        .newInstance(null)
