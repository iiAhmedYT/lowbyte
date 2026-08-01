package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode.DOUBLE_STREAM
import dev.iiahmed.lowbyte.api.ApiBytecode.INT_STREAM
import dev.iiahmed.lowbyte.api.ApiBytecode.LONG_STREAM
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.OPTIONAL
import dev.iiahmed.lowbyte.api.ApiBytecode.OPTIONAL_DOUBLE
import dev.iiahmed.lowbyte.api.ApiBytecode.OPTIONAL_INT
import dev.iiahmed.lowbyte.api.ApiBytecode.OPTIONAL_LONG
import dev.iiahmed.lowbyte.api.ApiBytecode.STREAM
import dev.iiahmed.lowbyte.api.InlineRewrite
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** `Optional.isEmpty`, which is `!isPresent()` and nothing else. */
object OptionalIsEmptyRewrite : InlineRewrite() {

    override val name = "Optional.isEmpty"

    override val introducedIn = 11

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == OPTIONAL && name == "isEmpty" && descriptor == "()Z"

    override fun write(mv: MethodVisitor, descriptor: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "isPresent", "()Z", false)

        val present = Label()
        mv.visitJumpInsn(Opcodes.IFNE, present)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(present)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
    }
}

/**
 * `Optional.orElseThrow()`, the no-argument one, which is `get()`.
 *
 * Both throw `NoSuchElementException` with the same message, so this really is
 * the same method under a newer name. The `orElseThrow(Supplier)` overload is
 * Java 8 and is left alone, which the descriptor check keeps them apart on.
 */
object OptionalOrElseThrowRewrite : InlineRewrite() {

    override val name = "Optional.orElseThrow"

    override val introducedIn = 10

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == OPTIONAL && name == "orElseThrow" && descriptor == "()$OBJECT"

    override fun write(mv: MethodVisitor, descriptor: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONAL, "get", "()$OBJECT", false)
        mv.visitInsn(Opcodes.ARETURN)
    }
}

/**
 * `Optional.stream` and the three primitive ones, which are the JDK's own body.
 *
 * `stream()` returns an empty stream when absent and a single-element one when
 * present, and both halves are Java 8 on all four. So there is no approximation
 * here, and nothing to put on the utility: the branch is six instructions.
 *
 * Mostly seen as `flatMap(Optional::stream)`, the idiom it was added for, which
 * arrives as a method reference rather than a call.
 *
 * The four differ only in which types they name, so they share a body rather
 * than being copied out four times with a chance to get one of them wrong.
 */
sealed class OptionalStreamRewrite(
    private val optional: String,
    private val getter: String,
    private val value: String,
    private val stream: String
) : InlineRewrite() {

    override val name = "${optional.substringAfterLast('/')}.stream"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == optional && name == "stream" && descriptor == "()L$stream;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val absent = Label()

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, optional, "isPresent", "()Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, absent)

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, optional, getter, "()$value", false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, stream, "of", "($value)L$stream;", true)
        mv.visitInsn(Opcodes.ARETURN)

        mv.visitLabel(absent)
        // Nothing was stored and the stack is empty, so the frame is the one the
        // method started with.
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, stream, "empty", "()L$stream;", true)
        mv.visitInsn(Opcodes.ARETURN)
    }

    object OfObject : OptionalStreamRewrite(OPTIONAL, "get", OBJECT, STREAM)

    object OfInt : OptionalStreamRewrite(OPTIONAL_INT, "getAsInt", "I", INT_STREAM)

    object OfLong : OptionalStreamRewrite(OPTIONAL_LONG, "getAsLong", "J", LONG_STREAM)

    object OfDouble : OptionalStreamRewrite(OPTIONAL_DOUBLE, "getAsDouble", "D", DOUBLE_STREAM)
}
