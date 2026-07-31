package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.LINKED_HASH_MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import dev.iiahmed.lowbyte.classfile.Bytecode
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * `Map.of`, taking its arguments as key and value pairs.
 *
 * There is no varargs shape to worry about: `Map.of` stops at ten pairs and
 * javac makes you write `Map.ofEntries` past that, which is
 * [MapOfEntriesRewrite].
 */
object MapOfRewrite : ApiRewrite {

    override val name = "Map.of"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String): Boolean {
        if (owner != MAP || name != "of") return false
        val arguments = Type.getArgumentTypes(descriptor)
        return arguments.size % 2 == 0 && arguments.all { it.descriptor == OBJECT }
    }

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        val arity = Type.getArgumentTypes(descriptor).size
        ApiBytecode.newCollection(mv, LINKED_HASH_MAP, slots.collection)

        for (pair in 0 until arity step 2) {
            mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
            ApiBytecode.loadChecked(mv, pair)
            ApiBytecode.loadChecked(mv, pair + 1)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, LINKED_HASH_MAP, "put", "($OBJECT$OBJECT)$OBJECT", false
            )
            mv.visitInsn(Opcodes.POP)
        }

        ApiBytecode.requireSize(
            mv, slots, LINKED_HASH_MAP, slots.frame(LINKED_HASH_MAP),
            expected = { Bytecode.pushInt(mv, arity / 2) },
            message = "duplicate key"
        )

        ApiBytecode.returnUnmodifiable(mv, slots, "unmodifiableMap", MAP)
    }
}
