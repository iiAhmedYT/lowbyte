package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.COLLECTION
import dev.iiahmed.lowbyte.api.ApiBytecode.COLLECTIONS
import dev.iiahmed.lowbyte.api.ApiBytecode.LINKED_HASH_SET
import dev.iiahmed.lowbyte.api.ApiBytecode.SET
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Set.copyOf`, which is not `Set.of` twice over.
 *
 * `Set.of` refuses a repeated element with an `IllegalArgumentException`.
 * `copyOf` keeps one of them and says so: "if the given Collection contains
 * duplicate elements, an arbitrary element of the duplicates is preserved".
 * Keeping the first is within that, so there is deliberately no size check here.
 */
object SetCopyOfRewrite : ApiRewrite {

    override val name = "Set.copyOf"

    override val introducedIn = 10

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == SET && name == "copyOf" && descriptor == "(L$COLLECTION;)Ljava/util/Set;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.copyArgumentIntoList(mv, slots)

        mv.visitTypeInsn(Opcodes.NEW, LINKED_HASH_SET)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LINKED_HASH_SET, "<init>", "(L$COLLECTION;)V", false)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, COLLECTIONS, "unmodifiableSet", "(Ljava/util/Set;)Ljava/util/Set;", false
        )
        mv.visitInsn(Opcodes.ARETURN)
    }
}
