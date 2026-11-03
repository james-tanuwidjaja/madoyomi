package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.googlecode.d2j.Method
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexCodeVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.googlecode.d2j.visitors.DexMethodVisitor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * Repairs what dex2jar 2.4.38 gets wrong in R8-optimised APKs (Keiyoushi's, for example), so the JVM can verify the
 * classes. Without it they fail with `VerifyError` when first used.
 *
 * 1. **Constructor elision.** R8 deletes the no-op constructor of some classes (synthetic lambdas, for example) and
 *    writes `new-instance LT;` + `invoke-direct {v}, LS;-><init>` with `S` an ancestor of `T`. ART accepts that; the
 *    JVM doesn't (an `<init>` call on a fresh object must target its own class). dex2jar translates it to `new S`, so
 *    the object even has the wrong class. This pass reads the dex to find those sites, turns each `new S` back into
 *    `new T` and its `invokespecial S.<init>` into `T.<init>`, and gives `T` (and every class between `T` and `S`)
 *    the constructor R8 removed, which only calls `super`. Sites it can't map unambiguously are skipped and reported.
 * 2. **Stack map frames.** dex2jar computes frames without the APK's own class hierarchy, so where two of its classes
 *    meet (a local holding an `a` or a `b`, both subclasses of `c`) the frame says `java/lang/Object`, and a later
 *    use as `c` fails. R8's class merging makes that common. This pass recomputes the frames of every class with a
 *    hierarchy read from the jar itself and the host classpath (nothing is loaded).
 */
object Dex2jarRepair {
    private val logger = KotlinLogging.logger {}

    /** A `new-instance` whose `<init>` call targets another class (internal names). */
    data class Site(
        val newType: String,
        val initOwner: String,
        val initDesc: String,
    )

    data class Report(
        /** `new` sites rewritten. */
        val repaired: Int,
        /** Classes whose frames were recomputed (every class of the jar, unless one failed). */
        val framesRecomputed: Int,
        /** Classes that received a constructor. */
        val constructorsAdded: List<String>,
        /** Sites or methods that could not be repaired, with the reason. */
        val skipped: List<String>,
    )

    private class DexClass(
        val superName: String?,
        val methods: MutableSet<String> = mutableSetOf(),
    )

    private data class MethodKey(
        val owner: String,
        val name: String,
        val desc: String,
    )

    fun repair(
        apk: Path,
        jar: Path,
    ): Report {
        val (classes, pairs) = readDex(apk.readBytes())
        val sitesByMethod =
            pairs.filterValues { list -> list.any { it.newType != it.initOwner } }

        val skipped = mutableListOf<String>()
        // per method: for each init owner S, the class each `new S` in the jar really creates, in order
        val plans = mutableMapOf<MethodKey, Map<String, List<String>>>()
        val constructors = mutableMapOf<String, MutableSet<Pair<String, String>>>() // T -> (super, desc)
        sitesByMethod.forEach { (key, list) ->
            val byOwner = list.groupBy { it.initOwner }.filterValues { group -> group.any { it.newType != it.initOwner } }
            val plan = mutableMapOf<String, List<String>>()
            byOwner.forEach { (owner, group) ->
                val chains = group.filter { it.newType != owner }.associateWith { chain(classes, it.newType, owner) }
                val bad = chains.filterValues { it == null }.keys
                if (bad.isNotEmpty()) {
                    skipped += "${key.owner}.${key.name}: ${bad.first().newType} does not extend $owner within the APK"
                    return@forEach
                }
                plan[owner] = group.map { it.newType }
                // every class between T and S needs the constructor R8 removed, each calling its own superclass
                chains.forEach { (site, chain) ->
                    chain!!.forEach { cls ->
                        val dexClass = classes.getValue(cls)
                        if ("<init>${site.initDesc}" !in dexClass.methods) {
                            constructors.getOrPut(cls) { mutableSetOf() } += dexClass.superName!! to site.initDesc
                        }
                    }
                }
            }
            if (plan.isNotEmpty()) plans[key] = plan
        }

        val entries = readJar(jar)
        val hierarchy = Hierarchy(entries)
        var repaired = 0
        var framesRecomputed = 0
        val classNames = entries.keys.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }
        (plans.keys.map { it.owner } + constructors.keys).filterNot { it in classNames }.distinct().forEach {
            skipped += "$it: not in the jar"
        }
        classNames.forEach { className ->
            val entryName = "$className.class"
            val bytes = entries.getValue(entryName)
            val reader = ClassReader(bytes)
            val writer = HierarchyClassWriter(hierarchy)
            var classRepaired = 0
            val methodSkips = mutableListOf<String>()
            val visitor =
                object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                        val plan = plans[MethodKey(className, name, descriptor)] ?: return mv
                        return NewRewriter(mv, plan) { rewritten, unmatched ->
                            classRepaired += rewritten
                            if (unmatched.isNotEmpty()) methodSkips += "$className.$name$descriptor: $unmatched"
                        }
                    }

                    override fun visitEnd() {
                        constructors[className]?.forEach { (superName, desc) -> addConstructor(cv, superName, desc) }
                        super.visitEnd()
                    }
                }
            try {
                reader.accept(visitor, ClassReader.SKIP_FRAMES)
                entries[entryName] = writer.toByteArray()
            } catch (e: RuntimeException) {
                // keep dex2jar's version of this class
                skipped += "$className: ${e.javaClass.simpleName}: ${e.message}"
                return@forEach
            }
            framesRecomputed++
            repaired += classRepaired
            skipped += methodSkips
        }
        writeJar(jar, entries)

        val report = Report(repaired, framesRecomputed, constructors.keys.sorted(), skipped)
        logger.info {
            "Repaired $jar: $repaired R8 constructor-elision site(s), constructors added to ${report.constructorsAdded}, " +
                "frames recomputed in $framesRecomputed of ${classNames.size} classes"
        }
        if (skipped.isNotEmpty()) logger.warn { "Could not repair in $jar: $skipped" }
        return report
    }

    /**
     * Rewrites the `new S` of [plan] (S → the real classes, in order) and the `invokespecial S.<init>` that
     * initialises each. Reports how many were rewritten and which counts didn't match the dex.
     */
    private class NewRewriter(
        mv: MethodVisitor,
        private val plan: Map<String, List<String>>,
        private val done: (Int, Map<String, String>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        private val seen = mutableMapOf<String, Int>()

        // the class created by each pending `new`, or null when it is left alone
        private val pending = ArrayDeque<Pair<String, String>?>()
        private var rewritten = 0

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            if (opcode != Opcodes.NEW) return super.visitTypeInsn(opcode, type)
            val targets = plan[type]
            if (targets == null) {
                pending.addLast(null)
                return super.visitTypeInsn(opcode, type)
            }
            val index = seen.getOrDefault(type, 0)
            seen[type] = index + 1
            val target = targets.getOrNull(index)
            if (target == null || target == type) {
                pending.addLast(null)
                super.visitTypeInsn(opcode, type)
            } else {
                pending.addLast(type to target)
                super.visitTypeInsn(opcode, target)
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            if (opcode == Opcodes.INVOKESPECIAL && name == "<init>" && pending.isNotEmpty()) {
                val site = pending.removeLast()
                if (site != null && site.first == owner) {
                    rewritten++
                    return super.visitMethodInsn(opcode, site.second, name, descriptor, isInterface)
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitEnd() {
            val unmatched =
                plan
                    .filter { (type, targets) -> seen.getOrDefault(type, 0) != targets.size }
                    .mapValues { (type, targets) -> "dex has ${targets.size} new $type, jar has ${seen.getOrDefault(type, 0)}" }
            done(rewritten, unmatched)
            super.visitEnd()
        }
    }

    /** The classes from [type] up to (excluding) its ancestor [ancestor], all defined in the dex; null if there's no such chain. */
    private fun chain(
        classes: Map<String, DexClass>,
        type: String,
        ancestor: String,
    ): List<String>? {
        val chain = mutableListOf<String>()
        var current = type
        while (current != ancestor) {
            val dexClass = classes[current] ?: return null
            chain += current
            current = dexClass.superName ?: return null
        }
        return chain
    }

    /** The constructor R8 removed: `<init>(desc) { super(args); }`. */
    private fun addConstructor(
        cv: ClassVisitor,
        superName: String,
        desc: String,
    ) {
        val mv = cv.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, "<init>", desc, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        var slot = 1
        Type.getArgumentTypes(desc).forEach { arg ->
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot)
            slot += arg.size
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", desc, false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** The dex's classes (super, method signatures) and, per method, every `new-instance` + `<init>` pair in order. */
    private fun readDex(bytes: ByteArray): Pair<Map<String, DexClass>, Map<MethodKey, List<Site>>> {
        val classes = mutableMapOf<String, DexClass>()
        val pairs = mutableMapOf<MethodKey, MutableList<Site>>()
        MultiDexFileReader.open(bytes).accept(
            object : DexFileVisitor() {
                override fun visit(
                    accessFlags: Int,
                    className: String,
                    superClass: String?,
                    interfaceNames: Array<out String>?,
                ): DexClassVisitor {
                    val owner = internalName(className)
                    val dexClass = DexClass(superClass?.let(::internalName)).also { classes[owner] = it }
                    return object : DexClassVisitor() {
                        override fun visitMethod(
                            accessFlags: Int,
                            method: Method,
                        ): DexMethodVisitor {
                            dexClass.methods += method.name + method.desc
                            val key = MethodKey(owner, method.name, method.desc)
                            return object : DexMethodVisitor() {
                                override fun visitCode(): DexCodeVisitor = PairCollector { pairs.getOrPut(key) { mutableListOf() } += it }
                            }
                        }
                    }
                }
            },
        )
        return classes to pairs
    }

    private class PairCollector(
        private val found: (Site) -> Unit,
    ) : DexCodeVisitor() {
        // register -> the class of the uninitialised object it holds
        private val fresh = mutableMapOf<Int, String>()

        override fun visitTypeStmt(
            op: Op,
            a: Int,
            b: Int,
            type: String,
        ) {
            if (op == Op.NEW_INSTANCE) fresh[a] = internalName(type) else fresh.remove(a)
        }

        override fun visitStmt2R(
            op: Op,
            a: Int,
            b: Int,
        ) {
            val moved = if (op.name.startsWith("MOVE_OBJECT")) fresh[b] else null
            if (moved != null) fresh[a] = moved else fresh.remove(a)
        }

        override fun visitMethodStmt(
            op: Op,
            args: IntArray,
            method: Method,
        ) {
            if ((op == Op.INVOKE_DIRECT || op == Op.INVOKE_DIRECT_RANGE) && method.name == "<init>" && args.isNotEmpty()) {
                val created = fresh[args[0]]
                if (created != null) {
                    found(Site(created, internalName(method.owner), method.desc))
                    fresh.keys.filter { fresh[it] == created }.forEach { fresh.remove(it) }
                }
            }
        }
    }

    private fun internalName(descriptor: String): String =
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) descriptor.substring(1, descriptor.length - 1) else descriptor

    private fun readJar(jar: Path): MutableMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(jar.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entries[it.name] = zip.readBytes() }
        }
        return entries
    }

    private fun writeJar(
        jar: Path,
        entries: Map<String, ByteArray>,
    ) {
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Super classes for frame computation, from the jar itself or the host classpath, without loading anything. */
    private class Hierarchy(
        private val jarEntries: Map<String, ByteArray>,
    ) {
        private val cache = mutableMapOf<String, Pair<String?, Boolean>?>()

        /** (super name, is interface), or null when the class can't be found. */
        fun info(name: String): Pair<String?, Boolean>? =
            cache.getOrPut(name) {
                val bytes =
                    jarEntries["$name.class"]
                        ?: javaClass.classLoader.getResourceAsStream("$name.class")?.use { it.readBytes() }
                        ?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() }
                bytes?.let { ClassReader(it) }?.let { it.superName to (it.access and Opcodes.ACC_INTERFACE != 0) }
            }

        fun ancestors(name: String): List<String> = generateSequence(name) { info(it)?.first }.toList()
    }

    private class HierarchyClassWriter(
        private val hierarchy: Hierarchy,
    ) : ClassWriter(COMPUTE_FRAMES) {
        override fun getCommonSuperClass(
            type1: String,
            type2: String,
        ): String {
            if (type1 == type2) return type1
            if (hierarchy.info(type1)?.second == true || hierarchy.info(type2)?.second == true) return OBJECT
            val first = hierarchy.ancestors(type1).toSet()
            return hierarchy.ancestors(type2).firstOrNull { it in first } ?: OBJECT
        }

        companion object {
            private const val OBJECT = "java/lang/Object"
        }
    }
}
