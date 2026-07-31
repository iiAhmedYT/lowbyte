package dev.iiahmed.lowbyte.api

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.security.MessageDigest

/** One JDK call the injected utility can stand in for. */
class RuntimeReplacement(
    val owner: String,
    val name: String,
    val descriptor: String,
    val introducedIn: Int,
    val instance: Boolean,
    /** The utility's own method, which takes the receiver first when [instance]. */
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

    private const val TEMPLATE =
        "/lowbyte-runtime/dev/iiahmed/lowbyte/runtime/LowbyteApi.classdata"

    private const val TEMPLATE_NAME = "dev/iiahmed/lowbyte/runtime/LowbyteApi"

    private const val INFO = "Ldev/iiahmed/lowbyte/runtime/LowbyteInfo;"

    /** Where the injected class goes unless the build says otherwise. */
    const val DEFAULT_PACKAGE = "dev/iiahmed/lowbyte/runtime"

    private val template: ByteArray? by lazy {
        RuntimeApi::class.java.getResourceAsStream(TEMPLATE)?.use { it.readBytes() }
    }

    /** Everything the utility offers, or empty if it could not be read. */
    val replacements: List<RuntimeReplacement> by lazy {
        val bytes = template ?: return@lazy emptyList()
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
            update(template ?: ByteArray(0))
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
    fun inject(className: String, keep: Set<String>): ByteArray {
        val bytes = requireNotNull(template) { "the Lowbyte runtime template is missing from the plugin jar" }

        val writer = ClassWriter(0)
        val renamer = ClassRemapper(writer, SimpleRemapper(TEMPLATE_NAME, className))
        ClassReader(bytes).accept(Trimmer(renamer, withHelpers(bytes, keep)), 0)
        return writer.toByteArray()
    }

    /**
     * [keep] plus everything those methods call inside the utility.
     *
     * The overloads delegate to shared private helpers, so keeping only what a
     * call site named would leave them calling methods that are no longer there.
     */
    private fun withHelpers(bytes: ByteArray, keep: Set<String>): Set<String> {
        val calls = mutableMapOf<String, MutableSet<String>>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val from = calls.getOrPut("${name.orEmpty()}${descriptor.orEmpty()}") { mutableSetOf() }
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String?,
                        insnName: String?,
                        insnDescriptor: String?,
                        isInterface: Boolean
                    ) {
                        if (owner == TEMPLATE_NAME) from += "${insnName.orEmpty()}${insnDescriptor.orEmpty()}"
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
                        (bootstrapMethodArguments.toList() + bootstrapMethodHandle).forEach { argument ->
                            if (argument is Handle && argument.owner == TEMPLATE_NAME) {
                                from += "${argument.name}${argument.desc}"
                            }
                        }
                    }
                }
            }
        }, 0)

        val reached = keep.toMutableSet()
        val pending = ArrayDeque(keep)
        while (pending.isNotEmpty()) {
            calls[pending.removeFirst()].orEmpty().forEach { if (reached.add(it)) pending.add(it) }
        }
        return reached
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
                            instance = values["instance"] as? Boolean ?: false,
                            method = name.orEmpty(),
                            methodDescriptor = descriptor.orEmpty()
                        )
                    }
                }
            }
        }
    }

    /** Keeps the wanted methods, drops the rest, and drops every annotation. */
    private class Trimmer(
        next: ClassVisitor,
        private val keep: Set<String>
    ) : ClassVisitor(Opcodes.ASM9, next) {

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            if ("${name.orEmpty()}${descriptor.orEmpty()}" !in keep) return null

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

    /** The descriptor a call site needs once the receiver becomes a parameter. */
    fun callDescriptor(replacement: RuntimeReplacement): String =
        if (!replacement.instance) {
            replacement.descriptor
        } else {
            Type.getMethodDescriptor(
                Type.getReturnType(replacement.descriptor),
                Type.getObjectType(replacement.owner),
                *Type.getArgumentTypes(replacement.descriptor)
            )
        }
}
