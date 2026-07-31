package dev.iiahmed.lowbyte.api

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Where a rewrite's own locals go, and how to describe them in a frame.
 *
 * The arguments occupy the slots below [collection], however many there are and
 * whatever their width, so the scratch space starts after them. Hardcoding a
 * slot instead works only while every rewrite takes the same number of
 * arguments, and stops the moment one does not: parking a collection in slot 1
 * of `Map.of(a, b, c, d)` overwrites `b`.
 */
class ApiSlots(descriptor: String) {

    private val arguments: List<Type> = Type.getArgumentTypes(descriptor).toList()

    /** First slot past the arguments, where a collection under construction goes. */
    val collection: Int = arguments.sumOf { it.size }

    /** The loop counter. */
    val index: Int = collection + 1

    /**
     * A full frame covering the arguments plus whatever the rewrite has stored.
     *
     * Objects are named, arrays are spelled as descriptors, and the small
     * integral types all collapse to `INTEGER`, which is how the verifier sees
     * them.
     */
    fun frame(vararg extra: Any): Array<Any> =
        (arguments.map(::frameType) + extra.toList()).toTypedArray()

    private fun frameType(type: Type): Any = when (type.sort) {
        Type.OBJECT -> type.internalName
        Type.ARRAY -> type.descriptor
        Type.LONG -> Opcodes.LONG
        Type.FLOAT -> Opcodes.FLOAT
        Type.DOUBLE -> Opcodes.DOUBLE
        else -> Opcodes.INTEGER
    }
}
