package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.OPTIONAL
import dev.iiahmed.lowbyte.api.ApiRewrite
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** `Optional.isEmpty`, which is `!isPresent()` and nothing else. */
object OptionalIsEmptyRewrite : ApiRewrite {

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
object OptionalOrElseThrowRewrite : ApiRewrite {

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
