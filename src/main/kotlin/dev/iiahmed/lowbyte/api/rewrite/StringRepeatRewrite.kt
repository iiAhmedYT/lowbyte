package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.STRING
import dev.iiahmed.lowbyte.api.ApiBytecode.STRING_BUILDER
import dev.iiahmed.lowbyte.api.ApiRewrite
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** `String.repeat`, as a builder loop, refusing a negative count as the JDK does. */
object StringRepeatRewrite : ApiRewrite {

    override val name = "String.repeat"

    override val introducedIn = 11

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == STRING && name == "repeat" && descriptor == "(I)Ljava/lang/String;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        val counted = Label()
        mv.visitJumpInsn(Opcodes.IFGE, counted)
        ApiBytecode.throwIllegalArgument(mv, "count is negative")
        mv.visitLabel(counted)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)

        mv.visitTypeInsn(Opcodes.NEW, STRING_BUILDER)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 2)

        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, 3)

        val loop = Label()
        val done = Label()
        mv.visitLabel(loop)
        mv.visitFrame(Opcodes.F_APPEND, 2, arrayOf<Any>(STRING_BUILDER, Opcodes.INTEGER), 0, null)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false
        )
        mv.visitInsn(Opcodes.POP)
        mv.visitIincInsn(3, 1)
        mv.visitJumpInsn(Opcodes.GOTO, loop)

        mv.visitLabel(done)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(Opcodes.ARETURN)
    }
}
