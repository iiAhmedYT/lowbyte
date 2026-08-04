package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Registry entry for [StringConcatTransformer]. */
object StringConcatTransform : FeatureTransform {

    override val name = "invokedynamic string concatenation"

    override val introducedIn = 9

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = StringConcatTransformer(next, onUnsupported)
}

/**
 * Rebuilds `a + b` as a `StringBuilder` chain, the way javac emitted it before
 * Java 9.
 *
 * Since 9 javac leaves the work to `java.lang.invoke.StringConcatFactory`, which
 * does not exist on 8, so the call site dies with a `BootstrapMethodError` the
 * first time it runs.
 *
 * The bootstrap carries a recipe: U+0001 splices in the next argument, U+0002
 * the next constant from the trailing bootstrap arguments, and anything else is
 * literal text. Constants exist so that one of those two characters appearing
 * in the source cannot be mistaken for a marker, which is the only reason they
 * are not the same thing. Both are known by the time we generate, so they are
 * folded together into one run of text.
 *
 * The chain goes in a generated static method rather than inline. By the time
 * the `invokedynamic` is reached the operands are already on the stack, and a
 * `StringBuilder` chain needs its `new`/`dup` *before* them; a static call takes
 * exactly the operands sitting there, so nothing has to be reordered.
 */
class StringConcatTransformer(
    classVisitor: ClassVisitor,
    private val onUnsupported: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    private companion object {
        const val BOOTSTRAP_OWNER = "java/lang/invoke/StringConcatFactory"
        const val WITH_CONSTANTS = "makeConcatWithConstants"
        const val PLAIN = "makeConcat"

        const val STRING = "java/lang/String"
        const val STRING_BUILDER = "java/lang/StringBuilder"
        const val BUILDER_DESCRIPTOR = "Ljava/lang/StringBuilder;"

        const val ARGUMENT_TAG = '\u0001'
        const val CONSTANT_TAG = '\u0002'

        const val HELPER_PREFIX = "lowbyte\$concat\$"
    }

    /** One run of literal text, or one argument spliced in at this point. */
    private sealed class Piece {
        class Literal(val text: String) : Piece()
        object Argument : Piece()
    }

    private class Helper(
        val name: String,
        val descriptor: String,
        val pieces: List<Piece>
    )

    private var className = ""
    private var isInterface = false

    /** Collected as we go, written out in visitEnd. */
    private val helpers = mutableListOf<Helper>()

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        className = name.orEmpty()
        isInterface = (access and Opcodes.ACC_INTERFACE) != 0
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return CallSiteVisitor(mv)
    }

    override fun visitEnd() {
        helpers.forEach { generateHelper(it) }
        super.visitEnd()
    }

    private inner class CallSiteVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            if (bootstrapMethodHandle?.owner != BOOTSTRAP_OWNER || descriptor == null) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val bootstrapName = bootstrapMethodHandle.name
            if (bootstrapName != WITH_CONSTANTS && bootstrapName != PLAIN) {
                onUnsupported("StringConcatFactory.$bootstrapName")
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val arguments = Type.getArgumentTypes(descriptor)
            val pieces = piecesOf(bootstrapName, arguments.size, bootstrapMethodArguments)

            val reason = unsupportedReason(bootstrapName, descriptor, pieces)
            if (reason != null) {
                // Left as-is. Caller decides if that is a warning or a failure.
                onUnsupported(reason)
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val helper = Helper("$HELPER_PREFIX${helpers.size}", descriptor, pieces!!)
            helpers += helper

            super.visitMethodInsn(Opcodes.INVOKESTATIC, className, helper.name, helper.descriptor, isInterface)
        }
    }

    /**
     * Flattens the recipe into text and argument slots, or null if it is not a
     * shape we can read.
     *
     * `makeConcat` has no recipe at all: every operand is spliced in, with no
     * text between them.
     */
    private fun piecesOf(
        bootstrapName: String,
        argumentCount: Int,
        bootstrapArguments: Array<out Any?>
    ): List<Piece>? {
        if (bootstrapName == PLAIN) {
            if (bootstrapArguments.isNotEmpty()) return null
            return List(argumentCount) { Piece.Argument }
        }

        val recipe = bootstrapArguments.firstOrNull() as? String ?: return null
        val constants = bootstrapArguments.drop(1)

        val pieces = mutableListOf<Piece>()
        val text = StringBuilder()
        var nextConstant = 0
        var arguments = 0

        recipe.forEach { character ->
            when (character) {
                ARGUMENT_TAG -> {
                    if (text.isNotEmpty()) {
                        pieces += Piece.Literal(text.toString())
                        text.clear()
                    }
                    pieces += Piece.Argument
                    arguments++
                }

                CONSTANT_TAG -> {
                    // A constant is known now, so it joins the surrounding text
                    // instead of becoming a separate append.
                    val constant = constants.getOrNull(nextConstant++) ?: return null
                    text.append(constantText(constant) ?: return null)
                }

                else -> text.append(character)
            }
        }
        if (text.isNotEmpty()) pieces += Piece.Literal(text.toString())

        if (arguments != argumentCount) return null
        if (nextConstant != constants.size) return null

        return pieces
    }

    /**
     * A bootstrap constant as the text it stands for.
     *
     * javac only ever passes strings here. The rest are accepted because they
     * are equally constant, and turning them into text now costs nothing.
     */
    private fun constantText(constant: Any?): String? = when (constant) {
        is String -> constant
        is Int, is Long, is Float, is Double -> constant.toString()
        else -> null
    }

    /** Why we cannot rewrite this call site, or null if we can. */
    private fun unsupportedReason(bootstrapName: String, descriptor: String, pieces: List<Piece>?): String? {
        if (Type.getReturnType(descriptor).internalNameOrNull() != STRING) {
            return "a $bootstrapName call site returning something other than String, `$descriptor`"
        }
        if (Type.getArgumentTypes(descriptor).any { it.sort == Type.METHOD }) {
            return "a $bootstrapName call site with an unsupported signature `$descriptor`"
        }
        if (pieces == null) {
            return "a $bootstrapName call site whose recipe and constants do not match its arguments"
        }
        return null
    }

    private fun Type.internalNameOrNull(): String? =
        if (sort == Type.OBJECT) internalName else null

    /**
     * Writes `static String <name>(<the call site's operands>)`.
     *
     * A concatenation of nothing but text needs no builder at all, which is the
     * case javac leaves behind when only some of the operands were constant.
     */
    private fun generateHelper(helper: Helper) {
        val access = Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or
            if (isInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE

        val mv = cv.visitMethod(access, helper.name, helper.descriptor, null, null)
        mv.visitCode()

        if (helper.pieces.none { it is Piece.Argument }) {
            mv.visitLdcInsn(helper.pieces.filterIsInstance<Piece.Literal>().joinToString("") { it.text })
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            return
        }

        mv.visitTypeInsn(Opcodes.NEW, STRING_BUILDER)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false)

        val arguments = Type.getArgumentTypes(helper.descriptor)
        var argument = 0
        var slot = 0

        helper.pieces.forEach { piece ->
            when (piece) {
                is Piece.Literal -> {
                    mv.visitLdcInsn(piece.text)
                    append(mv, "Ljava/lang/String;")
                }

                is Piece.Argument -> {
                    val type = arguments[argument++]
                    mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
                    slot += type.size
                    append(mv, appendDescriptorFor(type))
                }
            }
        }

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(Opcodes.ARETURN)

        // ClassWriter has COMPUTE_MAXS.
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun append(mv: MethodVisitor, argumentDescriptor: String) {
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append", "($argumentDescriptor)$BUILDER_DESCRIPTOR", false
        )
    }

    /**
     * Which `StringBuilder.append` overload to link.
     *
     * Chosen explicitly, because string conversion of a reference is defined as
     * its `toString`. Letting a `char[]` operand pick `append(char[])` would
     * spell its contents out where the bootstrap prints a reference.
     */
    private fun appendDescriptorFor(type: Type): String = when (type.sort) {
        Type.BOOLEAN -> "Z"
        Type.CHAR -> "C"
        Type.BYTE, Type.SHORT, Type.INT -> "I"
        Type.LONG -> "J"
        Type.FLOAT -> "F"
        Type.DOUBLE -> "D"
        // append(String) and append(Object) agree on null, both giving "null".
        else -> if (type.internalNameOrNull() == STRING) "Ljava/lang/String;" else "Ljava/lang/Object;"
    }
}
