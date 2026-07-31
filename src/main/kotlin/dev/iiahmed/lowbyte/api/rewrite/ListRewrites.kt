package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.ARRAY_LIST
import dev.iiahmed.lowbyte.api.ApiBytecode.COLLECTION
import dev.iiahmed.lowbyte.api.ApiBytecode.LIST
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiSlots
import dev.iiahmed.lowbyte.api.InlineRewrite
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * `List.of`, as an unmodifiable `ArrayList` that refuses nulls.
 *
 * Both shapes are here. Up to ten elements javac calls a fixed-arity overload,
 * and past that the varargs one, which takes the whole lot as an array.
 */
object ListOfRewrite : InlineRewrite() {

    override val name = "List.of"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == LIST && name == "of" && ApiBytecode.isFactoryShape(descriptor)

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.newCollection(mv, ARRAY_LIST, slots.collection)

        if (ApiBytecode.isVarargs(descriptor)) {
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
}

/** `List.copyOf`, a snapshot refusing a null collection and null elements. */
object ListCopyOfRewrite : InlineRewrite() {

    override val name = "List.copyOf"

    override val introducedIn = 10

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == LIST && name == "copyOf" && descriptor == "(L$COLLECTION;)Ljava/util/List;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.copyArgumentIntoList(mv, slots)
        ApiBytecode.returnUnmodifiable(mv, slots, "unmodifiableList", LIST)
    }
}
