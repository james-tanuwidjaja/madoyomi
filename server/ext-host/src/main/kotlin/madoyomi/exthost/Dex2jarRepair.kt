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
 *    dex2jar doesn't always fold the pair: where it keeps `new T` and only the `<init>` call names `S` (mangafire,
 *    comix and mangakakalot, Phase 2 §10), the class is already right and only the call is rewritten to `T.<init>`.
 *    Such a site counts as one of `S`'s once its `<init>` turns out to be `S`'s.
 * 2. **Elided super constructors.** The same removal seen from the subclass: a constructor of `C` calls
 *    `X.<init>` on `this`, where `X` is an ancestor beyond `C`'s direct superclass `U`, whose no-op constructor R8
 *    removed (mangadex's kotlinx-serialization DTOs: `f4 extends u`, and `f4.<init>` calls `Object.<init>`). The JVM
 *    requires a constructor to call its direct superclass's (or its own class's). The call is rewritten to
 *    `U.<init>`, and `U` (and every class up to `X`) gets the constructor back.
 * 3. **Stack map frames.** dex2jar computes frames without the APK's own class hierarchy, so where two of its classes
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
        val dex = readDex(apk.readBytes())
        val classes = dex.classes
        val pairs = dex.pairs
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
        // a constructor of C whose super call skips C's direct super U (R8 removed U's no-op constructor): it is
        // rewritten to U.<init>, and U (and every class up to the called ancestor) gets the constructor back
        val superPlans = mutableMapOf<MethodKey, Pair<String, String>>()
        dex.superCalls.forEach { (key, calls) ->
            val direct = classes[key.owner]?.superName ?: return@forEach
            calls.filter { (owner, _) -> owner != key.owner && owner != direct }.forEach { (owner, desc) ->
                val chain = chain(classes, direct, owner)
                if (chain == null) {
                    skipped += "${key.owner}.<init>${key.desc}: calls $owner.<init>, which is not an ancestor of $direct within the APK"
                    return@forEach
                }
                if (key in superPlans) {
                    skipped += "${key.owner}.<init>${key.desc}: more than one super call to rewrite"
                    return@forEach
                }
                superPlans[key] = owner to direct
                chain.forEach { cls ->
                    val dexClass = classes.getValue(cls)
                    if ("<init>$desc" !in dexClass.methods) {
                        constructors.getOrPut(cls) { mutableSetOf() } += dexClass.superName!! to desc
                    }
                }
            }
        }

        val entries = readJar(jar)
        val hierarchy = Hierarchy(entries)
        var repaired = 0
        var framesRecomputed = 0
        val classNames = entries.keys.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }
        (plans.keys.map { it.owner } + superPlans.keys.map { it.owner } + constructors.keys)
            .filterNot { it in classNames }
            .distinct()
            .forEach {
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
                        val key = MethodKey(className, name, descriptor)
                        val plan = plans[key]
                        val superCall = superPlans[key]
                        if (plan == null && superCall == null) return mv
                        return NewRewriter(mv, plan.orEmpty(), superCall) { rewritten, unmatched ->
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
        /** In a constructor: the ancestor `X` whose `<init>` the dex calls on `this`, and the direct super `U` to call instead. */
        private val superCall: Pair<String, String>?,
        private val done: (Int, Map<String, String>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        private val seen = mutableMapOf<String, Int>()

        // the init owner of each real class that a plan expects (T -> S), for a `new T` dex2jar left unfolded
        private val ownerOf =
            buildMap {
                plan.forEach { (owner, targets) -> targets.filter { it != owner }.forEach { putIfAbsent(it, owner) } }
            }

        // the class created by each pending `new`, or null when it is left alone. An unfolded `new T` is (S, T) and
        // only counts as one of S's sites once its `<init>` turns out to be S's
        private val pending = ArrayDeque<Pending?>()
        private var rewritten = 0
        private var superCallRewritten = false

        private class Pending(
            val jarOwner: String,
            val target: String,
            val unfolded: Boolean,
        )

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            if (opcode != Opcodes.NEW) return super.visitTypeInsn(opcode, type)
            val targets = plan[type]
            if (targets == null) {
                // dex2jar sometimes keeps `new T` and only the `<init>` call names S (its NewTransformer didn't fold
                // the pair): the class is already right, the call is rewritten to T's when it comes
                pending.addLast(ownerOf[type]?.let { Pending(it, type, unfolded = true) })
                return super.visitTypeInsn(opcode, type)
            }
            val index = seen.getOrDefault(type, 0)
            seen[type] = index + 1
            val target = targets.getOrNull(index)
            if (target == null || target == type) {
                pending.addLast(null)
                super.visitTypeInsn(opcode, type)
            } else {
                pending.addLast(Pending(type, target, unfolded = false))
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
            if (opcode == Opcodes.INVOKESPECIAL && name == "<init>") {
                if (pending.isNotEmpty()) {
                    val site = pending.removeLast()
                    if (site != null && site.jarOwner == owner) {
                        if (site.unfolded) seen[owner] = seen.getOrDefault(owner, 0) + 1
                        rewritten++
                        return super.visitMethodInsn(opcode, site.target, name, descriptor, isInterface)
                    }
                } else if (superCall != null && owner == superCall.first && !superCallRewritten) {
                    // the constructor's own super call (no `new` pending), to an ancestor beyond the direct super
                    superCallRewritten = true
                    rewritten++
                    return super.visitMethodInsn(opcode, superCall.second, name, descriptor, isInterface)
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitEnd() {
            val unmatched =
                plan
                    .filter { (type, targets) -> seen.getOrDefault(type, 0) != targets.size }
                    .mapValues { (type, targets) -> "dex has ${targets.size} new $type, jar has ${seen.getOrDefault(type, 0)}" }
                    .toMutableMap()
            if (superCall != null && !superCallRewritten) {
                unmatched["super"] = "dex calls ${superCall.first}.<init> on this, not found in the jar"
            }
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

    private class DexInfo(
        val classes: Map<String, DexClass>,
        /** Per method, every `new-instance` + `<init>` pair in order. */
        val pairs: Map<MethodKey, List<Site>>,
        /** Per constructor, the `<init>` calls on `this` (owner, desc): the super or `this(…)` call. */
        val superCalls: Map<MethodKey, List<Pair<String, String>>>,
    )

    /** The dex's classes (super, method signatures), its `new-instance` + `<init>` pairs and its constructors' super calls. */
    private fun readDex(bytes: ByteArray): DexInfo {
        val classes = mutableMapOf<String, DexClass>()
        val pairs = mutableMapOf<MethodKey, MutableList<Site>>()
        val superCalls = mutableMapOf<MethodKey, MutableList<Pair<String, String>>>()
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
                            // a constructor's `this` is its first parameter register: the ins are the last registers
                            val ins =
                                if (method.name == "<init>" && accessFlags and ACC_STATIC == 0) {
                                    1 + method.parameterTypes.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
                                } else {
                                    null
                                }
                            return object : DexMethodVisitor() {
                                override fun visitCode(): DexCodeVisitor =
                                    PairCollector(
                                        ins,
                                        found = { pairs.getOrPut(key) { mutableListOf() } += it },
                                        superCall = { superCalls.getOrPut(key) { mutableListOf() } += it },
                                    )
                            }
                        }
                    }
                }
            },
        )
        return DexInfo(classes, pairs, superCalls)
    }

    private const val ACC_STATIC = 0x0008

    private class PairCollector(
        /** For a constructor, the number of parameter registers (with `this`); null otherwise. */
        private val ins: Int?,
        private val found: (Site) -> Unit,
        private val superCall: (Pair<String, String>) -> Unit,
    ) : DexCodeVisitor() {
        // register -> the class of the uninitialised object it holds
        private val fresh = mutableMapOf<Int, String>()

        // the registers holding the constructor's `this`
        private val self = mutableSetOf<Int>()

        override fun visitRegister(total: Int) {
            if (ins != null) self += total - ins
        }

        override fun visitTypeStmt(
            op: Op,
            a: Int,
            b: Int,
            type: String,
        ) {
            if (op == Op.NEW_INSTANCE) fresh[a] = internalName(type) else fresh.remove(a)
            self.remove(a)
        }

        override fun visitStmt2R(
            op: Op,
            a: Int,
            b: Int,
        ) {
            val moved = if (op.name.startsWith("MOVE_OBJECT")) fresh[b] else null
            if (moved != null) fresh[a] = moved else fresh.remove(a)
            if (op.name.startsWith("MOVE_OBJECT") && b in self) self += a else self.remove(a)
        }

        override fun visitConstStmt(
            op: Op,
            a: Int,
            value: Any?,
        ) {
            self.remove(a)
        }

        override fun visitStmt1R(
            op: Op,
            a: Int,
        ) {
            if (op.name.startsWith("MOVE_RESULT") || op.name.startsWith("MOVE_EXCEPTION")) self.remove(a)
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
                } else if (args[0] in self) {
                    superCall(internalName(method.owner) to method.desc)
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
