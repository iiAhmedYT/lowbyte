package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.ARRAY_LIST
import dev.iiahmed.lowbyte.api.ApiBytecode.COLLECTION
import dev.iiahmed.lowbyte.api.ApiBytecode.COLLECTIONS
import dev.iiahmed.lowbyte.api.ApiBytecode.LINKED_HASH_MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.MAP_ENTRY
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Map.copyOf`, checking every key and value the source holds.
 *
 * The entries are pulled into a list first so the check can be a counted loop
 * rather than an iterator, which keeps the frames to the one shape.
 */
object MapCopyOfRewrite : ApiRewrite {

    override val name = "Map.copyOf"

    override val introducedIn = 10

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == MAP && name == "copyOf" && descriptor == "(Ljava/util/Map;)Ljava/util/Map;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        ApiBytecode.requireNonNull(mv)
        mv.visitInsn(Opcodes.POP)

        mv.visitTypeInsn(Opcodes.NEW, ARRAY_LIST)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "(L$COLLECTION;)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, slots.collection)

        ApiBytecode.listLoop(mv, slots) {
            ApiBytecode.loadListElement(mv, slots)
            mv.visitTypeInsn(Opcodes.CHECKCAST, MAP_ENTRY)
            mv.visitVarInsn(Opcodes.ASTORE, slots.entry)
            ApiBytecode.entryPart(mv, slots, "getKey")
            mv.visitInsn(Opcodes.POP)
            ApiBytecode.entryPart(mv, slots, "getValue")
            mv.visitInsn(Opcodes.POP)
        }

        mv.visitTypeInsn(Opcodes.NEW, LINKED_HASH_MAP)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LINKED_HASH_MAP, "<init>", "(Ljava/util/Map;)V", false)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, COLLECTIONS, "unmodifiableMap", "(Ljava/util/Map;)Ljava/util/Map;", false
        )
        mv.visitInsn(Opcodes.ARETURN)
    }
}
