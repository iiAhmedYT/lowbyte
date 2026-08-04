package dev.iiahmed.lowbyte.classfile

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Small emission helpers for generated methods.
 *
 * Frames are written by hand here. COMPUTE_FRAMES would be easier, but it makes
 * ASM load the classes being processed so it can work out common supertypes, and
 * those aren't on the plugin's classpath. So keep generated code simple enough
 * that [sameFrame] covers it.
 */
object Bytecode {

    /** Shortest encoding that fits. */
    fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            in -1..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    /**
     * Locals unchanged, stack empty.
     *
     * Only true for code that branches without adding locals or leaving anything
     * on the stack across the jump.
     */
    fun sameFrame(mv: MethodVisitor) = mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)

    /** A branch target plus the frame it needs. See [sameFrame] for the caveat. */
    fun target(mv: MethodVisitor, label: Label) {
        mv.visitLabel(label)
        sameFrame(mv)
    }
}
