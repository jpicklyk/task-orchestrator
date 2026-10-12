package io.github.jpicklyk.mcptask.current.architecture

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet

/** One request class a tool declares: [label] is `<tool>/<select or op name>`, [serializer] decodes it. */
internal data class RequestRoot(
    val label: String,
    val serializer: KSerializer<*>
)

/** A declared request field that no scanned code reads: JVM binary [owner], wire [field] name, reaching [labels]. */
internal data class UnreadField(
    val owner: String,
    val field: String,
    val labels: List<String>
) {
    val key: String
        get() = "$owner#${this.field}"
}

/**
 * Static bytecode analysis behind the request-field consumption guard: every field of a request class (and of any
 * project-owned class nested in it) must be read by production code, else the API accepts a parameter and silently
 * ignores it.
 *
 * Read rule: field F of owner O is read iff some method of a scanned class invokes a getter-shaped method of O that
 * returns F (`getX`, `isX`, value-class-mangled `getX-hash`, `componentN` - all recognised by their body
 * `aload_0; getfield O.F; return`) or does GETFIELD O.F. Never counted: O's `<init>`/`<clinit>`, its getter-shaped
 * methods, `copy`, `copy$default`, `equals`, `hashCode`, `toString`, `write$Self*`, and every `$$serializer` class.
 * O's other member functions do count.
 *
 * Limits (known, accepted: the guard proves presence of a read, not correct per-handler use):
 * - A request class shared by several handlers is checked once: DocAttachRequest serves both attach and detach
 *   (DocsWriteTool.kt:93, :106), so a field read by only one of the two passes.
 * - Reads in dead branches and in uncalled member functions count.
 * - A read in a log line, an exception message or a member validate() counts (presence, not effect).
 * - Interface dispatch is not followed, and a field read only by re-serializing the object comes out unread.
 * - Fail-open edge: a class-level @SerialName outside the include prefix, or a root whose descriptor is not a CLASS
 *   (sealed, polymorphic, custom primitive), is skipped without error; the guard test asserts every root still
 *   walks at least one owner via [walkedOwners], which catches a fully skipped root but not a skipped nested class.
 *
 * Fail-closed: an included descriptor with no class file, an element with no field of that name, or an empty scan
 * throws [IllegalStateException].
 */
internal object RequestFieldConsumption {
    /** One root per select or op of every tool, labelled `<tool>/<name>`. */
    fun roots(specs: List<ToolSpec>): List<RequestRoot> =
        specs.flatMap { spec ->
            when (spec) {
                is SelectToolSpec -> spec.selects.map { RequestRoot("${spec.name}/${it.name}", it.request) }
                is OpsToolSpec<*> -> spec.ops.map { RequestRoot("${spec.name}/${it.name}", it.request) }
            }
        }

    /**
     * The unread fields of [roots], read from the class files under [classRoot] whose binary name passes [include]
     * (the same predicate decides which descriptors are project-owned and therefore walked). Sorted by key.
     */
    fun unreadFields(
        roots: List<RequestRoot>,
        classRoot: Path,
        include: (String) -> Boolean = { true }
    ): List<UnreadField> {
        val classes = scan(classRoot, include)
        check(classes.isNotEmpty()) { "No class files under $classRoot passed the include filter; the scan would prove nothing." }

        val fieldsByOwner = LinkedHashMap<String, LinkedHashSet<String>>()
        val labelsByOwner = HashMap<String, TreeSet<String>>()
        for (root in roots) {
            val visited = HashSet<String>()
            walk(root.serializer.descriptor, classes, include, visited) { owner, fields ->
                fieldsByOwner.getOrPut(owner) { LinkedHashSet() }.addAll(fields)
                labelsByOwner.getOrPut(owner) { TreeSet() }.add(root.label)
            }
        }

        val reads = reads(classes)
        return fieldsByOwner
            .flatMap { (owner, fields) ->
                fields
                    .filter { (owner to it) !in reads }
                    .map { UnreadField(owner, it, labelsByOwner.getValue(owner).toList()) }
            }.sortedBy { it.key }
    }

    /** Root label to the owner classes its walk reached; a root mapped to an empty set was silently skipped. */
    fun walkedOwners(
        roots: List<RequestRoot>,
        classRoot: Path,
        include: (String) -> Boolean = { true }
    ): Map<String, Set<String>> {
        val classes = scan(classRoot, include)
        val result = LinkedHashMap<String, MutableSet<String>>()
        for (root in roots) {
            val owners = result.getOrPut(root.label) { LinkedHashSet() }
            walk(root.serializer.descriptor, classes, include, HashSet()) { owner, _ -> owners += owner }
        }
        return result
    }

    /** Null when [actual] and [baseline] (keys) agree; otherwise a message naming each new and each stale entry. */
    fun ratchet(
        actual: List<UnreadField>,
        baseline: Set<String>
    ): String? {
        val actualKeys = actual.map { it.key }.toSet()
        val fresh = actual.filter { it.key !in baseline }
        val stale = baseline.filter { it !in actualKeys }.sorted()
        if (fresh.isEmpty() && stale.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (fresh.isNotEmpty()) {
            parts += "Unread request field(s) not in the baseline:\n" +
                fresh.joinToString("\n") { "  + ${it.key} (reached from ${it.labels.joinToString(", ")})" }
        }
        if (stale.isNotEmpty()) {
            parts += "Stale baseline entry(ies) - the field is now read or gone; delete the line:\n" +
                stale.joinToString("\n") { "  - $it" }
        }
        return parts.joinToString("\n\n")
    }

    // ---- reachability ------------------------------------------------------------------------------------------

    private fun walk(
        descriptor: SerialDescriptor,
        classes: Map<String, ClassInfo>,
        include: (String) -> Boolean,
        visited: MutableSet<String>,
        onOwner: (String, List<String>) -> Unit
    ) {
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (descriptor.isInline) return
                val serialName = descriptor.serialName.removeSuffix("?")
                if (!include(serialName)) return
                val binary = resolveBinaryName(serialName, classes)
                if (!visited.add(binary)) return
                val info = classes.getValue(binary)
                val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                for (name in names) {
                    check(name in info.fields) { "Owner $binary has no field '$name' for a serial element of that name." }
                }
                onOwner(binary, names)
                for (i in 0 until descriptor.elementsCount) walk(descriptor.getElementDescriptor(i), classes, include, visited, onOwner)
            }
            StructureKind.LIST, StructureKind.MAP ->
                for (i in 0 until descriptor.elementsCount) walk(descriptor.getElementDescriptor(i), classes, include, visited, onOwner)
            else -> Unit
        }
    }

    /** `pkg.Outer.Inner` (serial name) to `pkg.Outer$Inner` (binary name): the first spelling with a class file. */
    private fun resolveBinaryName(
        serialName: String,
        classes: Map<String, ClassInfo>
    ): String {
        var candidate = serialName
        while (true) {
            if (candidate in classes) return candidate
            val dot = candidate.lastIndexOf('.')
            check(dot >= 0) { "Owner $serialName: no class file found for this project-owned serial descriptor." }
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1)
        }
    }

    // ---- read detection ----------------------------------------------------------------------------------------

    /** (owner binary name, field) pairs read by non-excluded code. */
    private fun reads(classes: Map<String, ClassInfo>): Set<Pair<String, String>> {
        val getters = HashMap<Triple<String, String, String>, String>()
        for (ci in classes.values) {
            for (m in ci.methods) m.getterField?.let { getters[Triple(ci.binaryName, m.name, m.desc)] = it }
        }
        val reads = HashSet<Pair<String, String>>()
        for (ci in classes.values) {
            if (ci.binaryName.endsWith("\$\$serializer")) continue
            for (m in ci.methods) {
                for (f in m.fieldRefs) {
                    if (f.opcode == Opcodes.GETFIELD && counts(ci, m, f.owner)) reads += f.owner to f.name
                }
                for (call in m.methodRefs) {
                    val field = getters[Triple(call.owner, call.name, call.desc)] ?: continue
                    if (counts(ci, m, call.owner)) reads += call.owner to field
                }
            }
        }
        return reads
    }

    private fun counts(
        inClass: ClassInfo,
        method: MethodInfo,
        refOwner: String
    ): Boolean = !(inClass.binaryName == refOwner && (method.generated || method.getterField != null))

    // ---- class-file scan ---------------------------------------------------------------------------------------

    private class FieldRef(
        val opcode: Int,
        val owner: String,
        val name: String
    )

    private class MethodRef(
        val owner: String,
        val name: String,
        val desc: String
    )

    private class MethodInfo(
        val name: String,
        val desc: String,
        val generated: Boolean,
        val getterField: String?,
        val fieldRefs: List<FieldRef>,
        val methodRefs: List<MethodRef>
    )

    private class ClassInfo(
        val binaryName: String,
        val fields: Set<String>,
        val methods: List<MethodInfo>
    )

    private val COMPONENT = Regex("component\\d+")

    private fun scan(
        classRoot: Path,
        include: (String) -> Boolean
    ): Map<String, ClassInfo> {
        val result = LinkedHashMap<String, ClassInfo>()
        Files.walk(classRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .forEach { path ->
                    val binary =
                        classRoot
                            .relativize(path)
                            .toString()
                            .replace(File.separatorChar, '.')
                            .removeSuffix(".class")
                    if (binary != "module-info" && include(binary)) result[binary] = parse(Files.readAllBytes(path))
                }
        }
        return result
    }

    private fun parse(bytes: ByteArray): ClassInfo {
        val reader = ClassReader(bytes)
        val binaryName = reader.className.replace('/', '.')
        val fields = LinkedHashSet<String>()
        val methods = ArrayList<MethodInfo>()
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?
                ): FieldVisitor? {
                    if (access and Opcodes.ACC_STATIC == 0) fields += name
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    val ops = ArrayList<Int>()
                    val frefs = ArrayList<FieldRef>()
                    val mrefs = ArrayList<MethodRef>()
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitInsn(opcode: Int) {
                            ops += opcode
                        }

                        override fun visitVarInsn(
                            opcode: Int,
                            varIndex: Int
                        ) {
                            ops += if (opcode == Opcodes.ALOAD && varIndex == 0) opcode else -1
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String
                        ) {
                            ops += opcode
                            frefs += FieldRef(opcode, owner.replace('/', '.'), name)
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean
                        ) {
                            ops += -1
                            mrefs += MethodRef(owner.replace('/', '.'), name, descriptor)
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String?,
                            descriptor: String?,
                            bootstrapMethodHandle: Handle?,
                            vararg bootstrapMethodArguments: Any?
                        ) {
                            ops += -1
                        }

                        override fun visitLdcInsn(value: Any?) {
                            ops += -1
                        }

                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?
                        ) {
                            ops += -1
                        }

                        override fun visitIntInsn(
                            opcode: Int,
                            operand: Int
                        ) {
                            ops += -1
                        }

                        override fun visitJumpInsn(
                            opcode: Int,
                            label: Label?
                        ) {
                            ops += -1
                        }

                        override fun visitIincInsn(
                            varIndex: Int,
                            increment: Int
                        ) {
                            ops += -1
                        }

                        override fun visitTableSwitchInsn(
                            min: Int,
                            max: Int,
                            dflt: Label?,
                            vararg labels: Label?
                        ) {
                            ops += -1
                        }

                        override fun visitLookupSwitchInsn(
                            dflt: Label?,
                            keys: IntArray?,
                            labels: Array<out Label?>?
                        ) {
                            ops += -1
                        }

                        override fun visitMultiANewArrayInsn(
                            descriptor: String?,
                            numDimensions: Int
                        ) {
                            ops += -1
                        }

                        override fun visitEnd() {
                            val isStatic = access and Opcodes.ACC_STATIC != 0
                            val getterField =
                                if (!isStatic &&
                                    ops.size == 3 &&
                                    ops[0] == Opcodes.ALOAD &&
                                    ops[1] == Opcodes.GETFIELD &&
                                    ops[2] in Opcodes.IRETURN..Opcodes.ARETURN &&
                                    frefs.single().owner == binaryName
                                ) {
                                    frefs.single().name
                                } else {
                                    null
                                }
                            val generated =
                                name == "<init>" ||
                                    name == "<clinit>" ||
                                    name == "copy" ||
                                    name == "copy\$default" ||
                                    name == "equals" ||
                                    name == "hashCode" ||
                                    name == "toString" ||
                                    name.startsWith("write\$Self") ||
                                    COMPONENT.matches(name)
                            methods += MethodInfo(name, descriptor, generated, getterField, frefs, mrefs)
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )
        return ClassInfo(binaryName, fields, methods)
    }
}
