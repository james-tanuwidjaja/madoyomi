package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.exthost.ExtensionClassLoadHook
import madoyomi.exthost.ExtensionLinkCheck
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

/**
 * What each extension needs from the host and doesn't get: the Android API gaps of Phase 2's "missing class/method
 * logger". In memory only, per package, bounded ([MAX_ISSUES_PER_PKG] distinct issues).
 *
 * Fed by:
 * - the extension class loader ([ExtensionClassLoadHook]): a class found nowhere, [IssueKind.CLASS_NOT_FOUND]. Some
 *   are probes the extension handles, so known probe names are dropped;
 * - source calls (`AnimeSourceCalls`) and loads (`AnimeExtensionLoader`): a `NoClassDefFoundError`,
 *   `NoSuchMethodError`/`NoSuchFieldError`, `AbstractMethodError` or `RuntimeException("Stub!")` that reached the
 *   host ([ExtensionIncompatibility]);
 * - the static link check at install ([staticReport], [ExtensionCompat]).
 *
 * Every distinct issue is logged once (`[compat] <pkg>: <kind> <target>`); after that only its count grows. Stage 4
 * exposes [issues] as GraphQL `extension.compatIssues`.
 */
object ExtensionCompatLog {
    private val logger = KotlinLogging.logger {}

    const val MAX_ISSUES_PER_PKG = 200

    enum class IssueKind {
        /** A class the extension uses doesn't exist on the host (`NoClassDefFoundError`, or the static check). */
        MISSING_CLASS,

        /** A method or field doesn't exist (`NoSuchMethodError`/`NoSuchFieldError`, or the static check). */
        MISSING_MEMBER,

        /** The host's class doesn't implement an abstract method the extension calls (`AbstractMethodError`). */
        ABSTRACT_METHOD,

        /** The host only has an android-jar stub that throws `"Stub!"`. */
        STUB,

        /** The extension class loader was asked for a class that exists nowhere (may be a handled probe). */
        CLASS_NOT_FOUND,
    }

    enum class Origin {
        /** Seen at runtime. */
        RUNTIME,

        /** Found by the static link check ([ExtensionLinkCheck]). */
        STATIC,
    }

    data class Issue(
        val kind: IssueKind,
        val target: String,
        val origin: Origin,
        /** Where it was seen first: a source operation (`popular`, `load`, …) or, for static issues, a jar class. */
        val context: String?,
        val count: Long,
        val firstSeen: Instant,
        val lastSeen: Instant,
    )

    private data class Key(
        val kind: IssueKind,
        val target: String,
        val origin: Origin,
    )

    private class PkgLog {
        val issues = LinkedHashMap<Key, Issue>()
        var dropped = 0L
        var staticReport: ExtensionLinkCheck.Report? = null
    }

    private val logs = ConcurrentHashMap<String, PkgLog>()

    @Volatile
    private var hookInstalled = false

    /** Connects the extension class loader's [ExtensionClassLoadHook] to this log. Idempotent. */
    fun install() {
        if (hookInstalled) return
        synchronized(this) {
            if (hookInstalled) return
            ExtensionClassLoadHook.listener = { jar, className ->
                if (!isProbe(className)) record(pkgOfJar(jar), IssueKind.CLASS_NOT_FOUND, className, Origin.RUNTIME, null)
            }
            hookInstalled = true
        }
    }

    /** Records one occurrence of an issue of [pkg]. */
    fun record(
        pkg: String,
        kind: IssueKind,
        target: String,
        origin: Origin = Origin.RUNTIME,
        context: String? = null,
        now: Instant = Instant.now(),
    ) {
        val log = logs.computeIfAbsent(pkg) { PkgLog() }
        val key = Key(kind, target, origin)
        val isNew: Boolean
        synchronized(log) {
            val existing = log.issues[key]
            isNew = existing == null
            when {
                existing != null -> {
                    log.issues[key] = existing.copy(count = existing.count + 1, lastSeen = now)
                }

                log.issues.size >= MAX_ISSUES_PER_PKG -> {
                    log.dropped++
                    return
                }

                else -> {
                    log.issues[key] = Issue(kind, target, origin, context, 1, now, now)
                }
            }
        }
        if (isNew && origin == Origin.RUNTIME) {
            logger.warn { "[compat] $pkg: $kind $target${context?.let { " ($it)" } ?: ""}" }
        }
    }

    /** The issues of [pkg] in the order they were first seen (runtime and static). */
    fun issues(pkg: String): List<Issue> = logs[pkg]?.let { log -> synchronized(log) { log.issues.values.toList() } }.orEmpty()

    /** Every package with at least one issue. */
    fun all(): Map<String, List<Issue>> = logs.keys.associateWith(::issues).filterValues { it.isNotEmpty() }

    /** How many distinct issues of [pkg] were not kept because the log was full. */
    fun dropped(pkg: String): Long = logs[pkg]?.let { synchronized(it) { it.dropped } } ?: 0

    /** The last static link-check report of [pkg] (install time), if one ran since the start. */
    fun staticReport(pkg: String): ExtensionLinkCheck.Report? = logs[pkg]?.let { synchronized(it) { it.staticReport } }

    /**
     * Replaces [pkg]'s static issues with [report]'s reachable ones (issues only used by an `Activity`, which never
     * runs, are left out) and keeps the report.
     */
    fun recordStatic(
        pkg: String,
        report: ExtensionLinkCheck.Report,
        now: Instant = Instant.now(),
    ) {
        val log = logs.computeIfAbsent(pkg) { PkgLog() }
        synchronized(log) {
            log.issues.keys.removeIf { it.origin == Origin.STATIC }
            log.staticReport = report
        }
        report.reachable.forEach { issue ->
            record(pkg, issue.kind.toIssueKind(), issue.target, Origin.STATIC, issue.referencedFrom.firstOrNull(), now)
        }
    }

    /** Forgets everything about [pkg] (uninstall, or a new version installed). */
    fun clear(pkg: String) {
        logs.remove(pkg)
    }

    /** Forgets everything (tests). */
    fun clearAll() {
        logs.clear()
    }

    /**
     * The package an extension jar belongs to: `extensions/{kind}/{pkg}/{pkg}-v{version}.jar` (the Madoyomi layout),
     * else the file name without `.jar`.
     */
    fun pkgOfJar(jar: Path): String {
        val dir = jar.parent?.name
        val file = jar.name
        return if (dir != null && file.startsWith("$dir-v")) dir else file.removeSuffix(".jar")
    }

    /** The package of the extension whose class loader loaded [type], or null for a host class. */
    fun pkgOf(type: Class<*>): String? {
        val loader = type.classLoader as? URLClassLoader ?: return null
        if (loader === ClassLoader.getSystemClassLoader()) return null
        val url = loader.urLs.firstOrNull() ?: return null
        return runCatching { pkgOfJar(Paths.get(url.toURI())) }.getOrNull()
    }

    /**
     * `Class.forName` probes the extension handles itself: JavaBeans introspection, serializer and companion
     * look-ups, and platform checks of bundled libraries.
     */
    internal fun isProbe(className: String): Boolean =
        PROBE_SUFFIXES.any { className.endsWith(it) } || PROBE_PREFIXES.any { className.startsWith(it) }

    private val PROBE_SUFFIXES = listOf("BeanInfo", "Customizer", "\$serializer", "\$Companion")
    private val PROBE_PREFIXES =
        listOf(
            // OkHttp/Conscrypt/BouncyCastle platform detection in bundled copies
            "org.conscrypt.",
            "com.android.org.conscrypt.",
            "org.bouncycastle.",
            "org.openjsse.",
            "dalvik.system.",
            "android.os.Build\$VERSION_CODES",
            "sun.security.ssl.",
        )

    private fun ExtensionLinkCheck.Kind.toIssueKind() =
        when (this) {
            ExtensionLinkCheck.Kind.MISSING_CLASS -> IssueKind.MISSING_CLASS
            ExtensionLinkCheck.Kind.MISSING_MEMBER -> IssueKind.MISSING_MEMBER
            ExtensionLinkCheck.Kind.STUB -> IssueKind.STUB
        }
}

/**
 * Recognises a `Throwable` that means "the host lacks an API this extension uses", as opposed to an extension or
 * site failure.
 */
object ExtensionIncompatibility {
    private const val MAX_CAUSE_DEPTH = 16

    data class Found(
        val kind: ExtensionCompatLog.IssueKind,
        /** The missing class or member, or the stubbed method (`a.b.C.method`). */
        val target: String,
        val error: Throwable,
    )

    /** The first incompatibility in [error]'s cause chain, or null. */
    fun classify(error: Throwable): Found? =
        generateSequence(error) { e ->
            when (e) {
                is java.lang.reflect.InvocationTargetException -> e.targetException
                is ExceptionInInitializerError -> e.exception
                else -> e.cause
            }?.takeIf { it !== e }
        }.take(MAX_CAUSE_DEPTH)
            .firstNotNullOfOrNull(::classifyOne)

    private fun classifyOne(e: Throwable): Found? =
        when {
            e is NoClassDefFoundError -> {
                Found(ExtensionCompatLog.IssueKind.MISSING_CLASS, (e.message ?: "?").replace('/', '.'), e)
            }

            e is NoSuchMethodError || e is NoSuchFieldError -> {
                Found(ExtensionCompatLog.IssueKind.MISSING_MEMBER, e.message ?: "?", e)
            }

            e is AbstractMethodError -> {
                Found(ExtensionCompatLog.IssueKind.ABSTRACT_METHOD, e.message ?: "?", e)
            }

            // android-jar's stubs throw exactly `new RuntimeException("Stub!")`
            e.javaClass == RuntimeException::class.java && e.message == "Stub!" -> {
                val frame = e.stackTrace.firstOrNull()
                Found(ExtensionCompatLog.IssueKind.STUB, frame?.let { "${it.className}.${it.methodName}" } ?: "?", e)
            }

            else -> {
                null
            }
        }
}
