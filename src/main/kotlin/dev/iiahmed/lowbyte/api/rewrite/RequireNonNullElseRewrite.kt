package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECTS
import dev.iiahmed.lowbyte.api.ApiRewrite
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Objects.requireNonNullElse`, the ternary it stands for.
 *
 * The default is null-checked too, with the same parameter name in the message,
 * which is what the JDK does when both are null.
 */
object RequireNonNullElseRewrite : ApiRewrite {

    override val name = "Objects.requireNonNullElse"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == OBJECTS && name == "requireNonNullElse" && descriptor == "($OBJECT$OBJECT)$OBJECT"

    override fun write(mv: MethodVisitor, descriptor: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        val useDefault = Label()
        mv.visitJumpInsn(Opcodes.IFNULL, useDefault)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.ARETURN)

        mv.visitLabel(useDefault)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitLdcInsn("defaultObj")
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, OBJECTS, "requireNonNull",
            "(${OBJECT}Ljava/lang/String;)$OBJECT", false
        )
        mv.visitInsn(Opcodes.ARETURN)
    }
}
