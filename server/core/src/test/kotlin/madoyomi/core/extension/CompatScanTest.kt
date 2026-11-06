package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.exthost.ApkInspector
import madoyomi.exthost.Dex2jarRepair
import madoyomi.exthost.ExtensionLinkCheck
import madoyomi.exthost.ExtensionLinkCheck.Kind
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The static compat scan (Phase 2 Stage 2): every APK of `tools/compat-test/top20.txt` and `manga-sample.txt`
 * (downloaded by `tools/compat-test/select-top.ps1 -Download` into the gitignored `tools/compat-test/apks/`) is
 * converted the way an install does (dex2jar + [Dex2jarRepair]), verified, and link-checked with
 * [ExtensionLinkCheck]. The result is `docs/research/compat-scan.md`: one row per extension and frequency tables of
 * the missing classes, missing members and stubs, which decide the order stubs get fixed in.
 *
 * Only runs with `MADOYOMI_COMPAT_SCAN=1` (`tools/compat-test/scan.ps1` sets it).
 */
@EnabledIfEnvironmentVariable(named = "MADOYOMI_COMPAT_SCAN", matches = "1")
class CompatScanTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }

        private val compatDir = Path(System.getProperty("madoyomi.compatDir") ?: "../../tools/compat-test")
        private val apkDir = Path(System.getProperty("madoyomi.compatApks") ?: "$compatDir/apks")
        private val output = Path(System.getProperty("madoyomi.compatOut") ?: "../../docs/research/compat-scan.md")
        private val prefixes = listOf("eu.kanade.tachiyomi.animeextension.", "eu.kanade.tachiyomi.extension.")

        private fun short(pkg: String) = prefixes.fold(pkg) { p, prefix -> p.removePrefix(prefix) }
    }

    @TempDir
    lateinit var tmp: Path

    private class Listed(
        val list: String,
        val rank: Int,
        val pkg: String,
        val warning: String,
        val apkFile: String,
    ) {
        val isAnime get() = list == "top20"
    }

    private class Scanned(
        val listed: Listed,
        val versionName: String = "?",
        val lib: String = "?",
        val report: ExtensionLinkCheck.Report? = null,
        val verifyErrors: List<String> = emptyList(),
        val repairSkipped: List<String> = emptyList(),
        /** Dotted jar class → its superclass and interfaces (short names), to see what code a use sits in. */
        val supertypes: Map<String, String> = emptyMap(),
        val error: String? = null,
    )

    private data class Use(
        val kind: Kind,
        val target: String,
        val scanned: Scanned,
        val from: Set<String>,
    )

    private fun readList(name: String): List<Listed> =
        (compatDir / name)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val c = line.split('\t')
                Listed(name.substringBefore('.'), c[0].toInt(), c[1], c[4], c[7].substringAfterLast('/'))
            }

    private fun scan(listed: Listed): Scanned {
        val apk = apkDir / listed.apkFile
        if (!apk.exists()) return Scanned(listed, error = "APK missing: run select-top.ps1 -Download")
        return try {
            val inspection = ApkInspector.inspect(apk)
            val jar = tmp / listed.apkFile.replace(".apk", ".jar")
            PackageTools.dex2jar(apk, jar)
            val repair = Dex2jarRepair.repair(apk, jar)
            Scanned(
                listed,
                inspection.versionName,
                inspection.libVersion(),
                ExtensionLinkCheck.check(jar, PackageTools::class.java.classLoader),
                TestApks.verifyErrors(jar),
                repair.skipped,
                supertypes(jar),
            )
        } catch (e: Exception) {
            Scanned(listed, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun supertypes(jar: Path): Map<String, String> =
        ZipFile(jar.toFile()).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".class") }
                .associate { entry ->
                    val reader = ClassReader(zip.getInputStream(entry).use { it.readBytes() })
                    val types =
                        (listOfNotNull(reader.superName?.takeIf { it != "java/lang/Object" }) + reader.interfaces)
                            .map { it.substringAfterLast('/') }
                    reader.className.replace('/', '.') to types.joinToString(", ")
                }
        }

    @Test
    fun `scan the top 20 and the manga sample`() {
        val listed = readList("top20.txt") + readList("manga-sample.txt")
        val scanned = listed.map { scan(it).also { s -> println("scanned ${s.listed.pkg}: ${s.report?.issues?.size ?: s.error}") } }
        output.parent.createDirectories()
        output.writeText(render(scanned))
        println("Compat scan written to ${output.toAbsolutePath().normalize()}")
        assertTrue(scanned.any { it.report != null }, "at least one APK was scanned")
    }

    private fun render(scanned: List<Scanned>): String =
        buildString {
            val ok = scanned.filter { it.report != null }
            appendLine("# Static compat scan")
            appendLine()
            appendLine(
                "Generated by `tools/compat-test/scan.ps1` (`CompatScanTest`, `MADOYOMI_COMPAT_SCAN=1`) on ${LocalDate.now()}. " +
                    "Do not edit by hand. Lists: `tools/compat-test/top20.txt` (anime, `top20`) and `manga-sample.txt` " +
                    "(manga, `manga`). Each APK is converted as an install does (dex2jar + `Dex2jarRepair`), every class " +
                    "is linked (`Verify`), and `ExtensionLinkCheck` resolves every external class, method and field " +
                    "against the host classpath. *Activity-only* issues are used only by an `android.app.Activity` " +
                    "subclass (the deep-link `UrlActivity`), which Madoyomi never starts; the other columns count the " +
                    "reachable ones.",
            )
            appendLine()
            perExtension(scanned, ok)
            apiUse(ok)
            byClass(ok)
            byTarget(ok)
            verifyErrors(ok)
        }

    private fun StringBuilder.perExtension(
        scanned: List<Scanned>,
        ok: List<Scanned>,
    ) {
        appendLine("## Per extension")
        appendLine()
        appendLine(
            "| List | # | Extension | Version | Lib | Warning | Ext. classes / members | Missing classes | Missing members " +
                "| Stubs | Activity-only | Verify errors | Not repaired |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        scanned.forEach { s ->
            val head = "| ${if (s.listed.isAnime) "top20" else "manga"} | ${s.listed.rank} | ${short(s.listed.pkg)}"
            val r = s.report
            if (r == null) {
                appendLine("$head | | | ${s.listed.warning} | **${s.error}** | | | | | | |")
            } else {
                val reachable = r.reachable
                appendLine(
                    "$head | ${s.versionName} | ${s.lib} | ${s.listed.warning} | ${r.externalClasses} / ${r.externalMembers} " +
                        "| ${reachable.count { it.kind == Kind.MISSING_CLASS }} | ${reachable.count { it.kind == Kind.MISSING_MEMBER }} " +
                        "| ${reachable.count { it.kind == Kind.STUB }} | ${r.issues.size - reachable.size} " +
                        "| ${s.verifyErrors.size} | ${s.repairSkipped.size} |",
                )
            }
        }
        appendLine()

        fun clean(s: Scanned) = s.report!!.reachable.isEmpty() && s.verifyErrors.isEmpty()
        val anime = ok.filter { it.listed.isAnime }
        val safe = anime.filter { it.listed.warning == "SAFE" }
        appendLine(
            "Scanned ${ok.size} of ${scanned.size}. With no reachable issue and no verify error: " +
                "anime ${anime.count(::clean)} of ${anime.size} (SAFE-only ${safe.count(::clean)} of ${safe.size}), " +
                "manga ${ok.count { !it.listed.isAnime && clean(it) }} of ${ok.count { !it.listed.isAnime }}.",
        )
        appendLine()
    }

    private fun uses(
        ok: List<Scanned>,
        reachable: Boolean,
    ): List<Use> =
        ok.flatMap { s ->
            val report = s.report!!
            report.issues
                .filter { report.onlyFromActivities(it) != reachable }
                .map { Use(it.kind, it.target, s, it.referencedFrom) }
        }

    /**
     * Which classes of the extension API the extensions' own classes extend or implement directly (a multisrc
     * theme's base class counts for the extension that bundles it): the API surface the checklist ticks rest on.
     */
    private fun StringBuilder.apiUse(ok: List<Scanned>) {
        val anime =
            listOf("AnimeCatalogueSource", "AnimeHttpSource", "ParsedAnimeHttpSource", "ConfigurableAnimeSource", "AnimeSourceFactory")
        val manga = listOf("CatalogueSource", "HttpSource", "ParsedHttpSource", "ConfigurableSource", "SourceFactory")
        appendLine("## Extension API classes used")
        appendLine()
        appendLine(
            "Supertypes the extension's own classes declare directly, so a class reached only through another one " +
                "(an `AnimeHttpSource` subclass is also an `AnimeCatalogueSource`) is not repeated.",
        )
        appendLine()
        appendLine("| List | Class | Extensions | Which |")
        appendLine("|---|---|---|---|")
        listOf("top20" to anime, "manga" to manga).forEach { (list, names) ->
            val inList = ok.filter { (if (it.listed.isAnime) "top20" else "manga") == list }
            names.forEach { name ->
                val users =
                    inList.filter { s -> s.supertypes.values.any { types -> types.split(", ").any { it == name } } }
                appendLine("| $list | `$name` | ${users.size} of ${inList.size} | ${users.joinToString { short(it.listed.pkg) }} |")
            }
        }
        appendLine()
    }

    private fun StringBuilder.byClass(ok: List<Scanned>) {
        appendLine("## By class")
        appendLine()
        appendLine("Classes with at least one reachable issue, by how many extensions hit them.")
        appendLine()
        appendLine("| Class | Kinds | Extensions (anime + manga) | Members | Extensions |")
        appendLine("|---|---|---|---|---|")
        uses(ok, reachable = true)
            .groupBy { ownerOf(it.kind, it.target) }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Use>>> { e ->
                    e.value
                        .map { it.scanned }
                        .distinct()
                        .size
                }.thenBy { it.key },
            ).forEach { (owner, list) ->
                val exts = list.map { it.scanned }.distinct()
                val anime = exts.count { it.listed.isAnime }
                appendLine(
                    "| `$owner` | ${list.map { it.kind }.distinct().joinToString()} | ${exts.size} ($anime + ${exts.size - anime}) " +
                        "| ${list.map { it.target }.distinct().size} | ${exts.joinToString { short(it.listed.pkg) }} |",
                )
            }
        appendLine()
        val activityOnly = uses(ok, reachable = false)
        if (activityOnly.isNotEmpty()) {
            val exts = activityOnly.map { it.scanned }.distinct()
            appendLine(
                "Activity-only (never run): ${activityOnly.map { it.target }.distinct().joinToString { "`$it`" }}, in " +
                    "${exts.size} extensions (${exts.count { it.listed.isAnime }} anime).",
            )
            appendLine()
        }
    }

    private fun StringBuilder.byTarget(ok: List<Scanned>) {
        appendLine("## By target")
        appendLine()
        appendLine(
            "Every reachable missing class, missing member and stub, by how many extensions use it, with up to three " +
                "referencing classes and their supertypes (obfuscated names are R8's).",
        )
        appendLine()
        appendLine("| Kind | Target | Extensions | Referenced from (sample) |")
        appendLine("|---|---|---|---|")
        uses(ok, reachable = true)
            .groupBy { it.kind to it.target }
            .entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Kind, String>, List<Use>>> { it.value.size }.thenBy { it.key.second })
            .forEach { (key, list) ->
                val from =
                    list
                        .flatMap { u ->
                            u.from.map { cls ->
                                val types = u.scanned.supertypes[cls].orEmpty()
                                "${short(u.scanned.listed.pkg)}: ${cls.substringAfterLast('.')}${if (types.isEmpty()) "" else " ($types)"}"
                            }
                        }.take(3)
                appendLine("| ${key.first} | `${key.second}` | ${list.size} | ${from.joinToString("; ")} |")
            }
        appendLine()
    }

    private fun StringBuilder.verifyErrors(ok: List<Scanned>) {
        val failing = ok.filter { it.verifyErrors.isNotEmpty() || it.repairSkipped.isNotEmpty() }
        if (failing.isEmpty()) return
        appendLine("## Verify errors and unrepaired sites")
        appendLine()
        failing.forEach { s ->
            appendLine("- ${short(s.listed.pkg)}:")
            (s.verifyErrors.map { "verify: $it" } + s.repairSkipped.map { "not repaired: $it" }).take(10).forEach {
                appendLine("  - ${it.replace("|", "\\|")}")
            }
        }
        appendLine()
    }

    /** The class a [target] belongs to: the target itself for a missing class, else the part before the member name. */
    private fun ownerOf(
        kind: Kind,
        target: String,
    ): String {
        if (kind == Kind.MISSING_CLASS) return target
        return target.substringBefore('(').substringBefore(':').substringBeforeLast('.')
    }
}
