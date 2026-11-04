package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Checks, without running anything, that every class, method and field an extension jar uses from outside itself
 * exists on the host: the API (`eu.kanade.tachiyomi.*`), AndroidCompat, the libraries and the JDK.
 *
 * Extensions link by name, and the JVM only resolves a reference when the code using it first runs, so a gap would
 * otherwise show up as a `NoClassDefFoundError`/`NoSuchMethodError` deep inside some source call. The check reads
 * the jar's bytecode and resolves each reference the way the JVM does (the class, then its superclasses and
 * interfaces), with the host's class files read as bytes (nothing is loaded or initialised). A member that resolves
 * to a body that only throws `RuntimeException("Stub!")` (the android-jar API stubs, used where AndroidCompat has no
 * real implementation) is reported as [Kind.STUB].
 */
object ExtensionLinkCheck {
    enum class Kind {
        /** A class the jar references that the host doesn't have. */
        MISSING_CLASS,

        /** A method or field that neither the class nor any of its supertypes declares. */
        MISSING_MEMBER,

        /** A method that exists, but only as a stub that throws "Stub!". */
        STUB,
    }

    /** One unresolved [target] (`a.b.C`, or `a.b.C.name(desc)` for a member), with the jar classes that use it. */
    data class Issue(
        val kind: Kind,
        val target: String,
        val referencedFrom: Set<String>,
    )

    data class Report(
        val issues: List<Issue>,
        /** Distinct classes from outside the jar the jar references. */
        val externalClasses: Int,
        /** Distinct methods and fields from outside the jar the jar references. */
        val externalMembers: Int,
        /**
         * The jar's classes that extend `android.app.Activity` (dotted names). Extensions declare one for deep links
         * (`UrlActivity`); Madoyomi never starts an activity, so what only they use never runs.
         */
        val activityClasses: Set<String> = emptySet(),
    ) {
        val ok get() = issues.isEmpty()

        fun of(kind: Kind) = issues.filter { it.kind == kind }

        /** [issue] is only referenced from [activityClasses], code Madoyomi never runs. */
        fun onlyFromActivities(issue: Issue) = issue.referencedFrom.isNotEmpty() && issue.referencedFrom.all { it in activityClasses }

        /** The issues that can be reached: everything not [onlyFromActivities]. */
        val reachable: List<Issue> get() = issues.filterNot(::onlyFromActivities)
    }

    /** Checks [jar] against the classes [host] can see (normally the loader extensions are loaded under). */
    fun check(
        jar: Path,
        host: ClassLoader,
    ): Report {
        val jarClasses = readJar(jar)
        val resolver = Resolver(jarClasses, host)
        val refs = References()
        jarClasses.values.forEach { it.collect(refs) }

        val issues = mutableListOf<Issue>()
        val missingClasses = mutableMapOf<String, MutableSet<String>>()
        var externalClasses = 0
        refs.classes.forEach { (name, from) ->
            if (name in jarClasses) return@forEach
            externalClasses++
            if (resolver.info(name) == null) missingClasses.getOrPut(name) { mutableSetOf() } += from
        }

        var externalMembers = 0
        refs.members.forEach { (member, from) ->
            if (member.owner.startsWith("[") || member.owner in SIGNATURE_POLYMORPHIC) return@forEach
            val result = resolver.resolve(member)
            if (result !is Resolution.InJar) externalMembers++
            when (result) {
                is Resolution.InJar, is Resolution.OnHost -> {}

                is Resolution.MissingClass -> {
                    missingClasses.getOrPut(result.name) { mutableSetOf() } += from
                }

                Resolution.NotFound -> {
                    issues += Issue(Kind.MISSING_MEMBER, member.display(), from.dotted())
                }

                is Resolution.Stub -> {
                    issues += Issue(Kind.STUB, member.copy(owner = result.declaringClass).display(), from.dotted())
                }
            }
        }
        missingClasses.forEach { (name, from) -> issues += Issue(Kind.MISSING_CLASS, name.replace('/', '.'), from.dotted()) }

        return Report(
            issues.sortedWith(compareBy({ it.kind }, { it.target })),
            externalClasses = externalClasses,
            externalMembers = externalMembers,
            activityClasses = jarClasses.keys.filterTo(sortedSetOf()) { resolver.extends(it, ACTIVITY) }.dotted(),
        )
    }

    private const val ACTIVITY = "android/app/Activity"

    private fun Set<String>.dotted() = mapTo(sortedSetOf()) { it.replace('/', '.') }

    // MethodHandle.invoke/invokeExact and the VarHandle accessors take any descriptor
    private val SIGNATURE_POLYMORPHIC = setOf("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle")

    // --- the model ---

    private data class Member(
        val owner: String,
        val name: String,
        val desc: String,
        val isField: Boolean,
    ) {
        fun display() = "${owner.replace('/', '.')}.$name${if (isField) ":$desc" else desc}"
    }

    private class MethodInfo(
        val isStub: Boolean,
    )

    private class ClassInfo(
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val methods: Map<String, MethodInfo>,
        val fields: Set<String>,
        val bytes: ByteArray?,
    ) {
        /** Adds the classes and members this (jar) class uses to [refs]. */
        fun collect(refs: References) {
            superName?.let { refs.type(it, name) }
            interfaces.forEach { refs.type(it, name) }
            ClassReader(bytes!!).accept(ReferenceCollector(name, refs), ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }

    private sealed interface Resolution {
        data object InJar : Resolution

        data object OnHost : Resolution

        data class Stub(
            val declaringClass: String,
        ) : Resolution

        data class MissingClass(
            val name: String,
        ) : Resolution

        data object NotFound : Resolution
    }

    private class References {
        val classes = mutableMapOf<String, MutableSet<String>>()
        val members = mutableMapOf<Member, MutableSet<String>>()

        /** [internalName] may be an array or a descriptor-less internal name. */
        fun type(
            internalName: String,
            from: String,
        ) {
            val element = elementClass(internalName) ?: return
            classes.getOrPut(element) { mutableSetOf() } += from
        }

        fun typeOf(
            type: Type,
            from: String,
        ) {
            when (type.sort) {
                Type.OBJECT -> {
                    type(type.internalName, from)
                }

                Type.ARRAY -> {
                    typeOf(type.elementType, from)
                }

                Type.METHOD -> {
                    typeOf(type.returnType, from)
                    type.argumentTypes.forEach { typeOf(it, from) }
                }
            }
        }

        fun member(
            member: Member,
            from: String,
        ) {
            type(member.owner, from)
            members.getOrPut(member) { mutableSetOf() } += from
        }

        fun handle(
            handle: Handle,
            from: String,
        ) {
            val isField = handle.tag <= Opcodes.H_PUTSTATIC
            member(Member(handle.owner, handle.name, handle.desc, isField), from)
        }

        private fun elementClass(internalName: String): String? {
            if (!internalName.startsWith("[")) return internalName
            val element = Type.getType(internalName).elementType
            return if (element.sort == Type.OBJECT) element.internalName else null
        }
    }

    private class ReferenceCollector(
        private val className: String,
        private val refs: References,
    ) : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor =
            object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) = refs.member(Member(owner, name, descriptor, isField = false), className)

                override fun visitFieldInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                ) = refs.member(Member(owner, name, descriptor, isField = true), className)

                override fun visitTypeInsn(
                    opcode: Int,
                    type: String,
                ) = refs.type(type, className)

                override fun visitMultiANewArrayInsn(
                    descriptor: String,
                    numDimensions: Int,
                ) = refs.typeOf(Type.getType(descriptor), className)

                override fun visitTryCatchBlock(
                    start: org.objectweb.asm.Label?,
                    end: org.objectweb.asm.Label?,
                    handler: org.objectweb.asm.Label?,
                    type: String?,
                ) {
                    type?.let { refs.type(it, className) }
                }

                override fun visitLdcInsn(value: Any?) {
                    when (value) {
                        is Type -> refs.typeOf(value, className)
                        is Handle -> refs.handle(value, className)
                    }
                }

                override fun visitInvokeDynamicInsn(
                    name: String?,
                    descriptor: String?,
                    bootstrapMethodHandle: Handle,
                    vararg bootstrapMethodArguments: Any?,
                ) {
                    refs.handle(bootstrapMethodHandle, className)
                    bootstrapMethodArguments.forEach {
                        when (it) {
                            is Handle -> refs.handle(it, className)
                            is Type -> refs.typeOf(it, className)
                        }
                    }
                }
            }
    }

    /** Resolves classes and members over the jar's classes first, then the host's class files. */
    private class Resolver(
        private val jarClasses: Map<String, ClassInfo>,
        private val host: ClassLoader,
    ) {
        private val hostClasses = ConcurrentHashMap<String, Any>()

        fun info(name: String): ClassInfo? {
            jarClasses[name]?.let { return it }
            val cached = hostClasses.getOrPut(name) { readHostClass(name) ?: MISSING }
            return cached as? ClassInfo
        }

        fun resolve(member: Member): Resolution {
            val visited = mutableSetOf<String>()

            fun search(className: String): Resolution? {
                if (!visited.add(className)) return null
                val info = info(className) ?: return Resolution.MissingClass(className)
                val key = member.name + member.desc
                if (member.isField) {
                    if (key in info.fields) return if (className in jarClasses) Resolution.InJar else Resolution.OnHost
                } else {
                    info.methods[key]?.let { method ->
                        return when {
                            className in jarClasses -> Resolution.InJar
                            method.isStub -> Resolution.Stub(className)
                            else -> Resolution.OnHost
                        }
                    }
                }
                var missing: Resolution.MissingClass? = null
                val supertypes = listOfNotNull(info.superName) + info.interfaces
                for (supertype in supertypes) {
                    when (val found = search(supertype)) {
                        null, Resolution.NotFound -> {}

                        is Resolution.MissingClass -> {
                            missing = missing ?: found
                        }

                        else -> {
                            return found
                        }
                    }
                }
                return missing ?: Resolution.NotFound
            }

            return search(member.owner) ?: Resolution.NotFound
        }

        /** [className] is [ancestor] or has it among its superclasses. */
        fun extends(
            className: String,
            ancestor: String,
        ): Boolean {
            var current: String? = className
            val seen = mutableSetOf<String>()
            while (current != null && seen.add(current)) {
                if (current == ancestor) return true
                current = info(current)?.superName
            }
            return false
        }

        private fun readHostClass(name: String): ClassInfo? {
            val bytes = host.getResourceAsStream("$name.class")?.use { it.readBytes() } ?: return null
            return parse(bytes, keepBytes = false)
        }

        companion object {
            private val MISSING = Any()
        }
    }

    private fun readJar(jar: Path): Map<String, ClassInfo> =
        ZipFile(jar.toFile()).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .map { entry -> parse(zip.getInputStream(entry).use { it.readBytes() }, keepBytes = true) }
                .associateBy { it.name }
        }

    private fun parse(
        bytes: ByteArray,
        keepBytes: Boolean,
    ): ClassInfo {
        val methods = mutableMapOf<String, MethodInfo>()
        val fields = mutableSetOf<String>()
        var name = ""
        var superName: String? = null
        var interfaces = emptyList<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    className: String,
                    signature: String?,
                    superClass: String?,
                    superInterfaces: Array<out String>?,
                ) {
                    name = className
                    superName = superClass
                    interfaces = superInterfaces?.toList().orEmpty()
                }

                override fun visitField(
                    access: Int,
                    fieldName: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    fields += fieldName + descriptor
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    methodName: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val key = methodName + descriptor
                    methods[key] = MethodInfo(isStub = false)
                    return StubDetector { methods[key] = MethodInfo(isStub = true) }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return ClassInfo(name, superName, interfaces, methods, fields, if (keepBytes) bytes else null)
    }

    /**
     * A method body that only throws `new RuntimeException("Stub!")` (android-jar's stubs; a constructor first calls
     * its super constructor).
     */
    private class StubDetector(
        private val onStub: () -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private var instructions = 0
        private var stubMessage = false

        private fun count() {
            instructions++
        }

        override fun visitInsn(opcode: Int) = count()

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) = count()

        override fun visitTypeInsn(
            opcode: Int,
            type: String?,
        ) = count()

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) = count()

        override fun visitFieldInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
        ) = count()

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = count()

        override fun visitJumpInsn(
            opcode: Int,
            label: org.objectweb.asm.Label?,
        ) = count()

        override fun visitLdcInsn(value: Any?) {
            count()
            if (value == "Stub!") stubMessage = true
        }

        override fun visitEnd() {
            if (stubMessage && instructions <= STUB_MAX_INSTRUCTIONS) onStub()
        }

        companion object {
            // aload_0, (args), invokespecial super.<init>, new, dup, ldc, invokespecial, athrow
            const val STUB_MAX_INSTRUCTIONS = 12
        }
    }
}
