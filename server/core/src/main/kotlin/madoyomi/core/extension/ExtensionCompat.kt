package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.exthost.ExtensionLinkCheck
import madoyomi.exthost.ExtensionLinkCheck.Kind
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Path

/** The static compat check of an installed jar, for install warnings and the compat log. */
object ExtensionCompat {
    private val logger = KotlinLogging.logger {}

    private const val SAMPLE = 5

    /** A link check's [report] (null if the check itself failed) and the install [warnings] it gives. */
    class Checked(
        val report: ExtensionLinkCheck.Report?,
        val warnings: List<String>,
    )

    /**
     * Link-checks [jar] of [pkg] against the classes extensions are loaded under and turns the reachable issues into
     * install warnings (none when the jar links cleanly). Never throws: a check that fails is itself one warning.
     * Nothing is recorded; [commit] does that once the install succeeded.
     */
    fun linkCheck(
        pkg: String,
        jar: Path,
        host: ClassLoader = PackageTools::class.java.classLoader,
    ): Checked =
        try {
            val report = ExtensionLinkCheck.check(jar, host)
            Checked(report, warnings(report))
        } catch (e: Exception) {
            logger.warn(e) { "Link check of $pkg failed" }
            Checked(null, listOf("link check failed: ${e.javaClass.simpleName}: ${e.message}"))
        }

    /** A new version of [pkg] is installed: its compat log starts over, with [checked]'s static issues. */
    fun commit(
        pkg: String,
        checked: Checked,
    ) {
        ExtensionCompatLog.clear(pkg)
        checked.report?.let { ExtensionCompatLog.recordStatic(pkg, it) }
    }

    /** One warning per kind of reachable issue, with the first [SAMPLE] targets. */
    fun warnings(report: ExtensionLinkCheck.Report): List<String> {
        val reachable = report.reachable
        return Kind.entries.mapNotNull { kind ->
            val targets = reachable.filter { it.kind == kind }.map { it.target }
            if (targets.isEmpty()) return@mapNotNull null
            val label =
                when (kind) {
                    Kind.MISSING_CLASS -> "missing classes"
                    Kind.MISSING_MEMBER -> "missing members"
                    Kind.STUB -> "Android stubs that throw \"Stub!\""
                }
            val more = if (targets.size > SAMPLE) ", +${targets.size - SAMPLE} more" else ""
            "link check: ${targets.size} $label (${targets.take(SAMPLE).joinToString()}$more)"
        }
    }
}
