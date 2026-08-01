package dev.iiahmed.lowbyte.api

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The pieces an [InlineRewrite] builds out of.
 *
 * Small on purpose. Everything that wanted a loop, a counter or a scratch local
 * moved to the injected utility, where it is ordinary Java rather than
 * hand-emitted bytecode with hand-written stack frames. What is left inline is
 * the handful of replacements that really are a few instructions.
 */
object ApiBytecode {

    const val OBJECT = "Ljava/lang/Object;"

    const val MAP = "java/util/Map"
    const val OPTIONAL = "java/util/Optional"

    /** An interface, so its static methods are called with the interface flag set. */
    const val STREAM = "java/util/stream/Stream"

    const val PREDICATE = "java/util/function/Predicate"

    const val SIMPLE_IMMUTABLE_ENTRY = "java/util/AbstractMap\$SimpleImmutableEntry"

    private const val OBJECTS = "java/util/Objects"

    /** Loads an argument, having refused null the way the JDK factories do. */
    fun loadChecked(mv: MethodVisitor, slot: Int) {
        mv.visitVarInsn(Opcodes.ALOAD, slot)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, OBJECTS, "requireNonNull", "($OBJECT)$OBJECT", false
        )
    }
}
