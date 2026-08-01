package dev.iiahmed.lowbyte.api

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.security.MessageDigest

/** One JDK call the injected utility can stand in for. */
class RuntimeReplacement(
    val owner: String,
    val name: String,
    val descriptor: String,
    val introducedIn: Int,
    /**
     * The utility's own method.
     *
     * Where the call it replaces is an instance method, the receiver is this
     * method's first parameter, which is the order the operands are already in
     * at the call site and the shape an unbound method reference wants.
     */
    val method: String,
    val methodDescriptor: String
) {
    fun matches(owner: String, name: String, descriptor: String) =
        this.owner == owner && this.name == name && this.descriptor == descriptor

    /**
     * Identifies the utility method, name and descriptor together.
     *
     * The name alone will not do: `Map.of` has eleven overloads, and keeping one
     * of them must not drag the other ten in.
     */
    val key: String get() = "$method$methodDescriptor"
}

/**
 * The utility class Lowbyte injects, and what it can replace.
 *
 * The class is written as Java in `src/runtime`, compiled at release 8, and
 * carried inside the plugin jar. Anything needing a loop, a decoder or a helper
 * type is writable there and simply is not writable as a generated method body
 * with hand-written stack frames, which is the whole reason it exists.
 *
 * What it replaces is read off its own `@LowbyteInfo` annotations, so the
 * utility describes itself and there is no second list to keep in step.
 */
object RuntimeApi {

    private const val TEMPLATE_ROOT = "/lowbyte-runtime/"

    private const val TEMPLATE_NAME = "dev/iiahmed/lowbyte/runtime/LowbyteApi"

    private const val INFO = "Ldev/iiahmed/lowbyte/runtime/LowbyteInfo;"

    /** Where the injected class goes unless the build says otherwise. */
    const val DEFAULT_PACKAGE = "dev/iiahmed/lowbyte/runtime"

    /** Any mention of a nested template class, wherever it appears. */
    private val NESTED = Regex(Regex.escape(TEMPLATE_NAME) + "\\$[A-Za-z0-9_$]+")

    /**
     * The utility and every class nested inside it, keyed by internal name.
     *
     * A method needing a helper *type* rather than a helper method has nowhere
     * to put it but a nested class, and a nested class is its own class file. So
     * the template is a set, not a file, and which classes belong to it is read
     * off the outer class's own `InnerClasses` attribute rather than listed
     * anywhere: the utility keeps describing itself.
     */
    private val templates: Map<String, ByteArray> by lazy {
        val outer = readTemplate(TEMPLATE_NAME) ?: return@lazy emptyMap()

        val found = linkedMapOf(TEMPLATE_NAME to outer)
        val pending = ArrayDeque(nestedNames(outer))
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in found) continue
            val bytes = readTemplate(name) ?: continue
            found[name] = bytes
            pending += nestedNames(bytes)
        }
        found
    }

    private fun readTemplate(internalName: String): ByteArray? =
        RuntimeApi::class.java.getResourceAsStream("$TEMPLATE_ROOT$internalName.classdata")
            ?.use { it.readBytes() }

    private fun nestedNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) {
                if (name != null && name.startsWith("$TEMPLATE_NAME$")) names += name
            }
        }, ClassReader.SKIP_CODE)
        return names
    }

    /** Everything the utility offers, or empty if it could not be read. */
    val replacements: List<RuntimeReplacement> by lazy {
        val bytes = templates[TEMPLATE_NAME] ?: return@lazy emptyList()
        val found = mutableListOf<RuntimeReplacement>()
        ClassReader(bytes).accept(Reader(found), ClassReader.SKIP_CODE)
        found
    }

    /**
     * The name to inject under, unless the build names one itself.
     *
     * Content-addressed: the digest covers the template's own bytes and the
     * methods kept from it. Two jars that ended up with the same methods from
     * the same Lowbyte therefore get the same name *and* identical bytes, so
     * shading them together is a duplicate rather than a collision. Change
     * either the utility or the set kept, and the name moves with it.
     *
     * A plain version number would not do, because the class is trimmed: two
     * jars built by one Lowbyte can hold different methods, and sharing a name
     * would let shading drop the ones only the other jar had.
     */
    fun defaultClassName(kept: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            // Every class of the template, nested ones included, so a change to
            // a helper type moves the name just as a change to a method does.
            templates.toSortedMap().forEach { (name, bytes) ->
                update(name.toByteArray())
                update(bytes)
            }
            update(kept.sorted().joinToString(",").toByteArray())
        }.digest().take(5).joinToString("") { "%02x".format(it) }

        return "$DEFAULT_PACKAGE/LowbyteApi_$digest"
    }

    /**
     * The utility, renamed, cut down to [keep], and with the annotations gone.
     *
     * Trimming is what keeps an injected class proportional to the jar that
     * needed it. Stripping is not optional: `@LowbyteInfo` is ours, its type is
     * never injected, and leaving references to a class nobody ships is asking
     * for trouble in anything that reads annotations reflectively.
     */
    fun inject(className: String, keep: Set<String>): Map<String, ByteArray> {
        check(templates.isNotEmpty()) { "the Lowbyte runtime template is missing from the plugin jar" }

        val reached = reachable(keep)

        // A nested class travels with its outer one, so LowbyteApi$1 becomes
        // <injected>$1 and every reference to it moves at the same time.
        val renames = mutableMapOf(TEMPLATE_NAME to className)
        reached.classes.forEach { renames[it] = className + it.removePrefix(TEMPLATE_NAME) }
        val remapper = SimpleRemapper(renames)

        val injected = linkedMapOf<String, ByteArray>()
        injected[className] = rewrite(TEMPLATE_NAME, remapper, reached.methods)
        // Nested classes are kept or dropped whole: nothing outside reaches into
        // one method of one, so there is nothing to trim within them.
        reached.classes.forEach { injected[renames.getValue(it)] = rewrite(it, remapper, keep = null) }
        return injected
    }

    private fun rewrite(name: String, remapper: SimpleRemapper, keep: Set<String>?): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(templates.getValue(name)).accept(Trimmer(ClassRemapper(writer, remapper), keep), 0)
        return writer.toByteArray()
    }

    /** What survives trimming: methods of the outer class, and whole nested classes. */
    private class Reached(val methods: Set<String>, val classes: Set<String>)

    /**
     * [keep] plus everything those methods reach inside the utility.
     *
     * Two kinds of thing are reachable and they are trimmed differently, so the
     * walk carries both in one graph and sorts them out at the end. An outer
     * method is its own node, because keeping one overload must not keep the
     * other ten. A nested class is a single node, because nothing reaches into
     * one method of one.
     *
     * Missing an edge here is not a compile error later, it is a
     * `NoSuchMethodError` or `NoClassDefFoundError` on somebody else's JVM, so
     * the walk is deliberately generous: any mention of a nested name in an
     * owner, a descriptor or a handle counts as reaching it.
     */
    private fun reachable(keep: Set<String>): Reached {
        val edges = mutableMapOf<String, MutableSet<String>>()

        templates.forEach { (owner, bytes) ->
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    val from = if (owner == TEMPLATE_NAME) {
                        method("${name.orEmpty()}${descriptor.orEmpty()}")
                    } else {
                        type(owner)
                    }
                    val out = edges.getOrPut(from) { mutableSetOf() }
                    out += nested(descriptor)

                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            insnOwner: String?,
                            insnName: String?,
                            insnDescriptor: String?,
                            isInterface: Boolean
                        ) {
                            if (insnOwner == TEMPLATE_NAME) {
                                out += method("${insnName.orEmpty()}${insnDescriptor.orEmpty()}")
                            }
                            out += nested(insnOwner)
                            out += nested(insnDescriptor)
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            insnOwner: String?,
                            insnName: String?,
                            insnDescriptor: String?
                        ) {
                            out += nested(insnOwner)
                            out += nested(insnDescriptor)
                        }

                        override fun visitTypeInsn(opcode: Int, insnType: String?) {
                            out += nested(insnType)
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is org.objectweb.asm.Type) out += nested(value.descriptor)
                        }

                        /**
                         * A lambda is a call too, just not one written as an instruction.
                         *
                         * javac turns the body into a synthetic method on this same
                         * class and names it only in the bootstrap arguments. Miss it
                         * and the trimmer deletes the body out from under the method
                         * that owns it, which fails at the first call with a
                         * `BootstrapMethodError` and not before.
                         *
                         * Handles are the whole of it: the template compiles at
                         * release 8, where javac cannot emit a constant dynamic.
                         */
                        override fun visitInvokeDynamicInsn(
                            insnName: String?,
                            insnDescriptor: String?,
                            bootstrapMethodHandle: Handle?,
                            vararg bootstrapMethodArguments: Any?
                        ) {
                            out += nested(insnDescriptor)
                            (bootstrapMethodArguments.toList() + bootstrapMethodHandle).forEach { argument ->
                                if (argument !is Handle) return@forEach
                                if (argument.owner == TEMPLATE_NAME) {
                                    out += method("${argument.name}${argument.desc}")
                                }
                                out += nested(argument.owner)
                                out += nested(argument.desc)
                            }
                        }
                    }
                }
            }, 0)
        }

        val reached = keep.mapTo(mutableSetOf(), ::method)
        val pending = ArrayDeque(reached)
        while (pending.isNotEmpty()) {
            edges[pending.removeFirst()].orEmpty().forEach { if (reached.add(it)) pending.add(it) }
        }

        return Reached(
            methods = reached.filter { it.startsWith(METHOD) }.mapTo(mutableSetOf()) { it.removePrefix(METHOD) },
            classes = reached.filter { it.startsWith(TYPE) }.mapTo(mutableSetOf()) { it.removePrefix(TYPE) }
        )
    }

    private const val METHOD = "M:"
    private const val TYPE = "C:"

    private fun method(key: String) = "$METHOD$key"

    private fun type(name: String) = "$TYPE$name"

    /** Every nested template class named anywhere in [text]. */
    private fun nested(text: String?): Set<String> =
        if (text == null || !text.contains(TEMPLATE_NAME)) {
            emptySet()
        } else {
            NESTED.findAll(text).mapTo(mutableSetOf()) { type(it.value) }
        }

    private class Reader(private val into: MutableList<RuntimeReplacement>) : ClassVisitor(Opcodes.ASM9) {

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {

            override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (annotationDescriptor != INFO) return null

                val values = mutableMapOf<String, Any>()
                return object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visit(valueName: String?, value: Any?) {
                        if (valueName != null && value != null) values[valueName] = value
                    }

                    override fun visitEnd() {
                        into += RuntimeReplacement(
                            owner = values["owner"] as? String ?: return,
                            name = values["name"] as? String ?: return,
                            descriptor = values["descriptor"] as? String ?: return,
                            introducedIn = values["introducedIn"] as? Int ?: return,
                            method = name.orEmpty(),
                            methodDescriptor = descriptor.orEmpty()
                        )
                    }
                }
            }
        }
    }

    /** Keeps the wanted methods, drops the rest, and drops every annotation. */
    /** Keeps the wanted methods and drops every annotation. A null [keep] keeps them all. */
    private class Trimmer(
        next: ClassVisitor,
        private val keep: Set<String>?
    ) : ClassVisitor(Opcodes.ASM9, next) {

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            if (keep != null && "${name.orEmpty()}${descriptor.orEmpty()}" !in keep) return null

            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitAnnotation(
                    annotationDescriptor: String?,
                    visible: Boolean
                ): AnnotationVisitor? = null
            }
        }

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = null
    }
}
