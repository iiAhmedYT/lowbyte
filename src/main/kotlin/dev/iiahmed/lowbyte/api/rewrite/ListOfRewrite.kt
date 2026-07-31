package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.ARRAY_LIST
import dev.iiahmed.lowbyte.api.ApiBytecode.LIST
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT_ARRAY
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * `List.of`, as an unmodifiable `ArrayList` that refuses nulls.
 *
 * Both shapes are here. Up to ten elements javac calls a fixed-arity overload,
 * and past that the varargs one, which takes the whole lot as an array.
 */
object ListOfRewrite : ApiRewrite {

    override val name = "List.of"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == LIST && name == "of" && isFactoryShape(descriptor)

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.newCollection(mv, ARRAY_LIST, slots.collection)

        if (isVarargs(descriptor)) {
            ApiBytecode.arrayLoop(mv, slots, ARRAY_LIST) {
                mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
                ApiBytecode.loadArrayElement(mv, slots)
                add(mv)
            }
        } else {
            repeat(Type.getArgumentTypes(descriptor).size) { slot ->
                mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
                ApiBytecode.loadChecked(mv, slot)
                add(mv)
            }
        }

        ApiBytecode.returnUnmodifiable(mv, slots, "unmodifiableList", LIST)
    }

    private fun add(mv: MethodVisitor) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "add", "($OBJECT)Z", false)
        mv.visitInsn(Opcodes.POP)
    }

    /** Every argument an element, or the one array the varargs form passes. */
    internal fun isFactoryShape(descriptor: String): Boolean {
        val arguments = Type.getArgumentTypes(descriptor)
        return arguments.all { it.descriptor == OBJECT } || isVarargs(descriptor)
    }

    internal fun isVarargs(descriptor: String): Boolean {
        val arguments = Type.getArgumentTypes(descriptor)
        return arguments.size == 1 && arguments[0].descriptor == OBJECT_ARRAY
    }
}
