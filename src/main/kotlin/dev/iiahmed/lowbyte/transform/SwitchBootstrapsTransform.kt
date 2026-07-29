package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.classfile.Bytecode
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Registry entry for [SwitchBootstrapsTransformer]. */
object SwitchBootstrapsTransform : FeatureTransform {

    override val name = "pattern-matching switch"

    override val introducedIn = 21

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = SwitchBootstrapsTransformer(next, onUnsupported)
}

/**
 * Turns the `java.lang.runtime.SwitchBootstraps` call sites javac emits for
 * pattern switches into calls to a generated static method that does the same
 * matching with ordinary `instanceof` and `equals`.
 *
 * The contract we have to reproduce, for both bootstraps: `-1` for a null
 * selector, otherwise the index of the first label at or after `restartIndex`
 * that matches, or `labels.length` if none do.
 *
 * No runtime class gets injected and nothing uses reflection, so the jar stays
 * self-contained.
 */
class SwitchBootstrapsTransformer(
    classVisitor: ClassVisitor,
    private val onUnsupported: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    private companion object {
        const val BOOTSTRAP_OWNER = "java/lang/runtime/SwitchBootstraps"

        const val OBJECT = "java/lang/Object"
        const val NUMBER = "java/lang/Number"
        const val CHARACTER = "java/lang/Character"
        const val STRING = "java/lang/String"
        const val ENUM = "java/lang/Enum"

        /** How javac builds the constants behind a qualified enum label. */
        const val CONSTANT_BOOTSTRAPS = "java/lang/invoke/ConstantBootstraps"
        const val ENUM_DESC = "java/lang/Enum\$EnumDesc"
        const val CLASS_DESC = "java/lang/constant/ClassDesc"
    }

    /** A `case Color.RED` label, once the constant behind it has been read. */
    private class EnumLabel(val enumInternalName: String, val constantName: String) {
        val descriptor: String get() = "L$enumInternalName;"
    }

    /**
     * The two bootstraps we can rewrite.
     *
     * The only real difference is what a String label means. Under typeSwitch
     * it's a constant compared against the selector; under enumSwitch it's an
     * enum constant's name, so it goes against `selector.name()`. Integer labels
     * show up in typeSwitch only.
     */
    private enum class SwitchKind(val bootstrapName: String, val helperPrefix: String) {
        TYPE("typeSwitch", "lowbyte\$typeSwitch\$"),
        ENUM("enumSwitch", "lowbyte\$enumSwitch\$");

        companion object {
            fun of(bootstrapName: String?): SwitchKind? = values().find { it.bootstrapName == bootstrapName }
        }
    }

    private var className = ""
    private var isInterface = false

    /** Collected as we go, written out in visitEnd. */
    private val helpers = mutableListOf<Helper>()

    private class Helper(
        val name: String,
        val descriptor: String,
        val kind: SwitchKind,
        val labels: List<Any>
    )

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        className = name ?: ""
        isInterface = (access and Opcodes.ACC_INTERFACE) != 0
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return CallSiteVisitor(mv)
    }

    override fun visitEnd() {
        helpers.forEach { generateHelper(it) }
        super.visitEnd()
    }

    private inner class CallSiteVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            if (bootstrapMethodHandle?.owner != BOOTSTRAP_OWNER || descriptor == null) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val kind = SwitchKind.of(bootstrapMethodHandle.name)
            if (kind == null) {
                onUnsupported("SwitchBootstraps.${bootstrapMethodHandle.name}")
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val reason = unsupportedReason(kind, descriptor, bootstrapMethodArguments)
            if (reason != null) {
                // Left as-is. Caller decides if that is a warning or a failure.
                onUnsupported(reason)
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val helper = Helper(
                name = "${kind.helperPrefix}${helpers.size}",
                descriptor = descriptor,
                kind = kind,
                // Non-null by the check above. Order is the match order.
                labels = bootstrapMethodArguments.map { it!! }
            )
            helpers += helper

            super.visitMethodInsn(Opcodes.INVOKESTATIC, className, helper.name, helper.descriptor, isInterface)
        }
    }

    /** Why we cannot rewrite this call site, or null if we can. */
    private fun unsupportedReason(kind: SwitchKind, descriptor: String, labels: Array<out Any?>): String? {
        // Java 23+ can hand typeSwitch a primitive selector. We only do the
        // reference form.
        val arguments = Type.getArgumentTypes(descriptor)
        if (arguments.size != 2 ||
            arguments[0].sort != Type.OBJECT ||
            arguments[1].sort != Type.INT ||
            Type.getReturnType(descriptor).sort != Type.INT
        ) {
            return "a ${kind.bootstrapName} call site with an unsupported signature `$descriptor`"
        }

        val unsupported = labels.withIndex().firstOrNull { !isSupportedLabel(kind, it.value) } ?: return null
        return "a ${kind.bootstrapName} label of an unsupported kind " +
            "(${unsupported.value?.javaClass?.name ?: "null"} at index ${unsupported.index})"
    }

    private fun isSupportedLabel(kind: SwitchKind, label: Any?): Boolean = when (label) {
        is Type -> label.sort == Type.OBJECT || label.sort == Type.ARRAY
        is String -> true
        // Integer constants never appear under enumSwitch.
        is Int -> kind == SwitchKind.TYPE
        // Nor does a qualified enum constant, which arrives as a condy EnumDesc.
        is ConstantDynamic -> kind == SwitchKind.TYPE && enumLabelOf(label) != null
        else -> false
    }

    /**
     * Reads a `case Color.RED` label back out of the constant javac emitted.
     *
     * javac describes the constant symbolically rather than referring to the
     * field, as two nested `CONSTANT_Dynamic` entries:
     *
     * ```
     * ConstantBootstraps.invoke(Enum$EnumDesc.of, <ClassDesc condy>, "RED")
     *   ConstantBootstraps.invoke(ClassDesc.of, "com.example.Color")
     * ```
     *
     * Null for anything that is not exactly that, so an unfamiliar constant is
     * reported instead of guessed at.
     */
    private fun enumLabelOf(label: Any?): EnumLabel? {
        val condy = label as? ConstantDynamic ?: return null
        val factory = factoryOf(condy, ENUM_DESC, "of", arguments = 3) ?: return null

        val enumInternalName = classDescOf(condy.getBootstrapMethodArgument(1)) ?: return null
        val constantName = condy.getBootstrapMethodArgument(2) as? String ?: return null

        // The handle is only read to confirm the shape.
        check(factory.owner == ENUM_DESC)
        return EnumLabel(enumInternalName, constantName)
    }

    /** The internal name a `ClassDesc` constant stands for. */
    private fun classDescOf(argument: Any?): String? {
        val condy = argument as? ConstantDynamic ?: return null
        val factory = factoryOf(condy, CLASS_DESC, null, arguments = 2) ?: return null
        val name = condy.getBootstrapMethodArgument(1) as? String ?: return null

        return when (factory.name) {
            // ClassDesc.of takes a binary name, ClassDesc.ofDescriptor a descriptor.
            "of" -> name.replace('.', '/')
            "ofDescriptor" -> runCatching { Type.getType(name).internalName }.getOrNull()
            else -> null
        }
    }

    /**
     * The factory handle of a `ConstantBootstraps.invoke` constant, when it has
     * the owner, name and argument count we expect.
     */
    private fun factoryOf(
        condy: ConstantDynamic,
        owner: String,
        name: String?,
        arguments: Int
    ): Handle? {
        if (condy.bootstrapMethod.owner != CONSTANT_BOOTSTRAPS) return null
        if (condy.bootstrapMethod.name != "invoke") return null
        if (condy.bootstrapMethodArgumentCount != arguments) return null

        val factory = condy.getBootstrapMethodArgument(0) as? Handle ?: return null
        if (factory.owner != owner) return null
        if (name != null && factory.name != name) return null
        return factory
    }

    /**
     * Writes `static int <name>(<selector>, int restartIndex)`.
     *
     * A jump table on restartIndex drops into a fallthrough chain of per-label
     * tests. That way resuming at N skips the earlier labels instead of
     * retesting them, which is what the bootstrap does.
     */
    private fun generateHelper(helper: Helper) {
        val access = Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or
            if (isInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE

        val mv = cv.visitMethod(access, helper.name, helper.descriptor, null, null)
        mv.visitCode()

        val notNull = Label()
        val noMatch = Label()
        val caseLabels = Array(helper.labels.size) { Label() }

        // if (selector == null) return -1;
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull)
        mv.visitInsn(Opcodes.ICONST_M1)
        mv.visitInsn(Opcodes.IRETURN)
        Bytecode.target(mv, notNull)

        if (caseLabels.isNotEmpty()) {
            mv.visitVarInsn(Opcodes.ILOAD, 1)
            mv.visitTableSwitchInsn(0, caseLabels.size - 1, noMatch, *caseLabels)

            helper.labels.forEachIndexed { index, label ->
                Bytecode.target(mv, caseLabels[index])
                // On mismatch, fall through into the next label's test.
                val next = caseLabels.getOrElse(index + 1) { noMatch }
                emitLabelTest(mv, helper.kind, label, index, next)
            }

            Bytecode.target(mv, noMatch)
        }

        // No label matched: return labels.length.
        Bytecode.pushInt(mv, helper.labels.size)
        mv.visitInsn(Opcodes.IRETURN)

        // ClassWriter has COMPUTE_MAXS.
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Returns [index] on a match, jumps to [next] otherwise. */
    private fun emitLabelTest(mv: MethodVisitor, kind: SwitchKind, label: Any, index: Int, next: Label) {
        when {
            // Type pattern.
            label is Type -> {
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.INSTANCEOF, label.internalName)
                mv.visitJumpInsn(Opcodes.IFEQ, next)
            }

            // Qualified enum constant, `case Color.RED`. Enum constants are
            // singletons, so reading the field and comparing by identity is the
            // same test the resolved EnumDesc would have performed, and it
            // resolves at link time instead of on first use.
            label is ConstantDynamic -> {
                val enumLabel = requireNotNull(enumLabelOf(label))
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    enumLabel.enumInternalName,
                    enumLabel.constantName,
                    enumLabel.descriptor
                )
                mv.visitJumpInsn(Opcodes.IF_ACMPNE, next)
            }

            // Enum constant name. The checkcast keeps this verifiable whatever
            // the call site declared the selector as.
            kind == SwitchKind.ENUM -> {
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.CHECKCAST, ENUM)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENUM, "name", "()Ljava/lang/String;", false)
                mv.visitLdcInsn(label)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING, "equals", "(Ljava/lang/Object;)Z", false)
                mv.visitJumpInsn(Opcodes.IFEQ, next)
            }

            // The JDK matches an Integer label against any Number with the same
            // intValue, or a Character with the same charValue. Not just Integer.
            label is Int -> {
                val tryCharacter = Label()

                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.INSTANCEOF, NUMBER)
                mv.visitJumpInsn(Opcodes.IFEQ, tryCharacter)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.CHECKCAST, NUMBER)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NUMBER, "intValue", "()I", false)
                Bytecode.pushInt(mv, label)
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, tryCharacter)
                Bytecode.pushInt(mv, index)
                mv.visitInsn(Opcodes.IRETURN)

                Bytecode.target(mv, tryCharacter)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.INSTANCEOF, CHARACTER)
                mv.visitJumpInsn(Opcodes.IFEQ, next)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.CHECKCAST, CHARACTER)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHARACTER, "charValue", "()C", false)
                Bytecode.pushInt(mv, label)
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, next)
            }

            // String constant.
            else -> {
                mv.visitLdcInsn(label)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OBJECT, "equals", "(Ljava/lang/Object;)Z", false)
                mv.visitJumpInsn(Opcodes.IFEQ, next)
            }
        }

        Bytecode.pushInt(mv, index)
        mv.visitInsn(Opcodes.IRETURN)
    }

}
