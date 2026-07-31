package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.LINKED_HASH_SET
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.SET
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import dev.iiahmed.lowbyte.classfile.Bytecode
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * `Set.of`, which on top of refusing nulls refuses a repeated element.
 *
 * The duplicate check counts once at the end rather than branching per element.
 *
 * Iteration order is documented as unspecified, and the JDK deliberately
 * randomises it per run. A `LinkedHashSet` settles on insertion order instead,
 * which stays inside the contract but will not shuffle. Anything depending on
 * the shuffling was already relying on what it was told not to.
 */
object SetOfRewrite : ApiRewrite {

    override val name = "Set.of"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == SET && name == "of" && ListOfRewrite.isFactoryShape(descriptor)

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.newCollection(mv, LINKED_HASH_SET, slots.collection)

        if (ListOfRewrite.isVarargs(descriptor)) {
            ApiBytecode.arrayLoop(mv, slots, LINKED_HASH_SET) {
                mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
                ApiBytecode.loadArrayElement(mv, slots)
                add(mv)
            }
            ApiBytecode.requireSize(
                mv, slots, LINKED_HASH_SET, slots.frame(LINKED_HASH_SET, Opcodes.INTEGER),
                expected = {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitInsn(Opcodes.ARRAYLENGTH)
                },
                message = "duplicate element"
            )
        } else {
            val arity = Type.getArgumentTypes(descriptor).size
            repeat(arity) { slot ->
                mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
                ApiBytecode.loadChecked(mv, slot)
                add(mv)
            }
            ApiBytecode.requireSize(
                mv, slots, LINKED_HASH_SET, slots.frame(LINKED_HASH_SET),
                expected = { Bytecode.pushInt(mv, arity) },
                message = "duplicate element"
            )
        }

        ApiBytecode.returnUnmodifiable(mv, slots, "unmodifiableSet", SET)
    }

    private fun add(mv: MethodVisitor) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LINKED_HASH_SET, "add", "($OBJECT)Z", false)
        mv.visitInsn(Opcodes.POP)
    }
}
