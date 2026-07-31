package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.STREAM
import dev.iiahmed.lowbyte.api.InlineRewrite
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Stream.ofNullable`, which is the null check the JDK itself writes.
 *
 * Not an approximation of it either: `ofNullable` returns `Stream.empty()` for
 * null and otherwise builds the same single-element stream `Stream.of(T)` does,
 * so the two branches here are the method's own body. Both halves are Java 8.
 *
 * `Stream` is an interface, so its static methods are called with the interface
 * flag set, which Java 8 class files already allow.
 */
object StreamOfNullableRewrite : InlineRewrite() {

    override val name = "Stream.ofNullable"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == STREAM && name == "ofNullable" && descriptor == "($OBJECT)L$STREAM;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val empty = Label()

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitJumpInsn(Opcodes.IFNULL, empty)

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM, "of", "($OBJECT)L$STREAM;", true)
        mv.visitInsn(Opcodes.ARETURN)

        mv.visitLabel(empty)
        // Nothing was stored and the stack is empty, so the frame is unchanged
        // from the one the method started with.
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM, "empty", "()L$STREAM;", true)
        mv.visitInsn(Opcodes.ARETURN)
    }
}
