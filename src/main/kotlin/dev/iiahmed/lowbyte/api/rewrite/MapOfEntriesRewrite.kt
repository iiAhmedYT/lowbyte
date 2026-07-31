package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.ENTRY_ARRAY
import dev.iiahmed.lowbyte.api.ApiBytecode.LINKED_HASH_MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.MAP_ENTRY
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** `Map.ofEntries`, refusing a null entry, a null key or value, and a repeated key. */
object MapOfEntriesRewrite : ApiRewrite {

    override val name = "Map.ofEntries"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == MAP && name == "ofEntries" && descriptor == "($ENTRY_ARRAY)Ljava/util/Map;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.newCollection(mv, LINKED_HASH_MAP, slots.collection)

        ApiBytecode.arrayLoop(mv, slots, LINKED_HASH_MAP) {
            ApiBytecode.loadArrayElement(mv, slots)
            mv.visitTypeInsn(Opcodes.CHECKCAST, MAP_ENTRY)
            mv.visitVarInsn(Opcodes.ASTORE, slots.entry)

            mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
            ApiBytecode.entryPart(mv, slots, "getKey")
            ApiBytecode.entryPart(mv, slots, "getValue")
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, LINKED_HASH_MAP, "put", "($OBJECT$OBJECT)$OBJECT", false
            )
            mv.visitInsn(Opcodes.POP)
        }

        ApiBytecode.requireSize(
            mv, slots, LINKED_HASH_MAP, slots.frame(LINKED_HASH_MAP, Opcodes.INTEGER),
            expected = {
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
            },
            message = "duplicate key"
        )

        ApiBytecode.returnUnmodifiable(mv, slots, "unmodifiableMap", MAP)
    }
}
