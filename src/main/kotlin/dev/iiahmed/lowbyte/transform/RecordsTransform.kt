package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.classfile.Bytecode
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type

/** Registry entry for [RecordsTransformer]. */
object RecordsTransform : FeatureTransform {

    override val name = "records"

    override val introducedIn = 16

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = RecordsTransformer(next, onUnsupported)
}

/**
 * Turns a record into an ordinary final class.
 *
 * Three things make a record a record in the class file, and all three go:
 * the `Record` attribute, the `java/lang/Record` superclass, and the
 * `java.lang.runtime.ObjectMethods` call sites behind `equals`, `hashCode` and
 * `toString`. The superclass is handled by
 * [dev.iiahmed.lowbyte.downgrade.JdkTypeSubstitutions], which sits below this in
 * the chain; the other two are handled here.
 *
 * The accessors, the fields and the canonical constructor javac already emitted
 * are plain members that need no help, so the class keeps working as a value
 * carrier. What it loses is its reflective identity: `Class.isRecord()` returns
 * false and `getRecordComponents()` returns null, and any deconstruction pattern
 * compiled *later* against the downgraded jar won't see a record. Patterns
 * already compiled into the jar are unaffected, since javac desugared those into
 * accessor calls before we ever saw them.
 *
 * Each rewritten call site gets a `private static synthetic` method in the same
 * class reproducing what the bootstrap would have linked. No runtime class is
 * injected and nothing uses reflection, so the jar stays self-contained.
 */
class RecordsTransformer(
    classVisitor: ClassVisitor,
    private val onUnsupported: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    private companion object {
        const val BOOTSTRAP_OWNER = "java/lang/runtime/ObjectMethods"
        const val BOOTSTRAP_NAME = "bootstrap"

        const val OBJECT = "java/lang/Object"
        const val OBJECTS = "java/util/Objects"
        const val STRING = "java/lang/String"
        const val STRING_BUILDER = "java/lang/StringBuilder"
        const val BUILDER_DESCRIPTOR = "Ljava/lang/StringBuilder;"

        /**
         * The bootstrap accepts any getter handle. These are the ones that
         * become a single instruction; anything else would need the handle
         * itself, which is what we are here to remove.
         */
        val READABLE_GETTER_TAGS = setOf(
            Opcodes.H_GETFIELD, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE
        )
    }

    /**
     * The three methods `ObjectMethods.bootstrap` can produce, picked by the
     * call site's name.
     */
    private enum class RecordMethod(val callSiteName: String, val helperPrefix: String) {
        TO_STRING("toString", "lowbyte\$recordToString\$"),
        HASH_CODE("hashCode", "lowbyte\$recordHashCode\$"),
        EQUALS("equals", "lowbyte\$recordEquals\$");

        companion object {
            fun of(callSiteName: String?): RecordMethod? = values().find { it.callSiteName == callSiteName }
        }
    }

    /** One record component, as the bootstrap describes it. */
    private class Component(val name: String, val getter: Handle) {

        /** javac passes a field read, but the bootstrap accepts any getter. */
        val type: Type = if (getter.tag == Opcodes.H_GETFIELD) {
            Type.getType(getter.desc)
        } else {
            Type.getReturnType(getter.desc)
        }
    }

    private class Helper(
        val name: String,
        val descriptor: String,
        val method: RecordMethod,
        val recordType: Type,
        val components: List<Component>
    )

    private var className = ""
    private var isInterface = false

    /** Collected as we go, written out in visitEnd. */
    private val helpers = mutableListOf<Helper>()

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
        // ACC_RECORD is an ASM-only pseudo flag; it sits above the 16 bits the
        // class file actually stores, so dropping it changes no output byte.
        // Cleared anyway so nothing downstream still thinks this is a record.
        super.visit(version, access and Opcodes.ACC_RECORD.inv(), name, signature, superName, interfaces)
    }

    /**
     * Not forwarded, which is what drops the `Record` attribute: the writer only
     * emits one if it sees at least one component.
     */
    override fun visitRecordComponent(
        name: String?,
        descriptor: String?,
        signature: String?
    ): RecordComponentVisitor? = null

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

            if (bootstrapMethodHandle.name != BOOTSTRAP_NAME) {
                onUnsupported("ObjectMethods.${bootstrapMethodHandle.name}")
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val method = RecordMethod.of(name)
            if (method == null) {
                onUnsupported("an ObjectMethods call site named `$name`")
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val components = componentsOf(bootstrapMethodArguments)
            val reason = unsupportedReason(method, descriptor, bootstrapMethodArguments, components)
            if (reason != null) {
                // Left as-is. Caller decides if that is a warning or a failure.
                onUnsupported(reason)
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }

            val helper = Helper(
                name = "${method.helperPrefix}${helpers.size}",
                descriptor = descriptor,
                method = method,
                // Non-null by the check above.
                recordType = bootstrapMethodArguments[0] as Type,
                components = components!!
            )
            helpers += helper

            // The helper lands in the class we are visiting, which for javac
            // output is the record itself, but the call site is what decides.
            super.visitMethodInsn(Opcodes.INVOKESTATIC, className, helper.name, helper.descriptor, isInterface)
        }
    }

    /**
     * Pairs the bootstrap's `;`-joined component names with its getter handles,
     * or null if those arguments aren't the shape we can read.
     *
     * Arguments are `(Class recordClass, String names, MethodHandle... getters)`.
     * A record with no components has an empty name list and no getters.
     */
    private fun componentsOf(arguments: Array<out Any?>): List<Component>? {
        if (arguments.size < 2 || arguments[0] !is Type) return null

        val names = when (val raw = arguments[1]) {
            // The bootstrap treats null as "no components".
            null -> emptyList()
            is String -> if (raw.isEmpty()) emptyList() else raw.split(";")
            else -> return null
        }

        val getters = arguments.drop(2)
        if (getters.size != names.size) return null

        return names.zip(getters) { name, getter ->
            if (getter !is Handle || getter.tag !in READABLE_GETTER_TAGS) return null
            Component(name, getter)
        }
    }

    /** Why we cannot rewrite this call site, or null if we can. */
    private fun unsupportedReason(
        method: RecordMethod,
        descriptor: String,
        arguments: Array<out Any?>,
        components: List<Component>?
    ): String? {
        if (components == null) {
            return "an ObjectMethods.${method.callSiteName} call site whose bootstrap arguments " +
                "aren't a (Class, names, getters...) triple we can read"
        }

        val recordType = arguments[0] as Type
        if (recordType.sort != Type.OBJECT) {
            return "an ObjectMethods.${method.callSiteName} call site for the non-class type `$recordType`"
        }

        val argumentTypes = Type.getArgumentTypes(descriptor)
        val returnType = Type.getReturnType(descriptor)
        // Each arity check guards the indexing that follows it, and `is` guards
        // the internal name, which only a reference type has.
        val expected = when (method) {
            RecordMethod.TO_STRING -> argumentTypes.size == 1 && returnType.isType(STRING)
            RecordMethod.HASH_CODE -> argumentTypes.size == 1 && returnType.sort == Type.INT
            RecordMethod.EQUALS ->
                argumentTypes.size == 2 &&
                    argumentTypes[1].isType(OBJECT) &&
                    returnType.sort == Type.BOOLEAN
        }
        if (!expected || argumentTypes[0].sort != Type.OBJECT) {
            return "an ObjectMethods.${method.callSiteName} call site with an unsupported signature `$descriptor`"
        }

        return null
    }

    private fun Type.isType(expectedInternalName: String) =
        sort == Type.OBJECT && internalName == expectedInternalName

    /**
     * Writes the `static` method the bootstrap would have linked, with the same
     * signature the call site declared.
     */
    private fun generateHelper(helper: Helper) {
        val access = Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or
            if (isInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE

        val mv = cv.visitMethod(access, helper.name, helper.descriptor, null, null)
        mv.visitCode()

        when (helper.method) {
            RecordMethod.TO_STRING -> generateToString(mv, helper)
            RecordMethod.HASH_CODE -> generateHashCode(mv, helper)
            RecordMethod.EQUALS -> generateEquals(mv, helper)
        }

        // ClassWriter has COMPUTE_MAXS.
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * `Name[a=1, b=2]`, built with a StringBuilder.
     *
     * The literal runs between components are constant, so they get folded into
     * one append each at generation time rather than three at runtime.
     */
    private fun generateToString(mv: MethodVisitor, helper: Helper) {
        val simpleName = simpleNameOf(helper.recordType.internalName)

        if (helper.components.isEmpty()) {
            mv.visitLdcInsn("$simpleName[]")
            mv.visitInsn(Opcodes.ARETURN)
            return
        }

        mv.visitTypeInsn(Opcodes.NEW, STRING_BUILDER)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false)

        helper.components.forEachIndexed { index, component ->
            appendConstant(
                mv,
                if (index == 0) "$simpleName[${component.name}=" else ", ${component.name}="
            )
            loadComponent(mv, slot = 0, castTo = null, component = component)
            append(mv, appendDescriptorFor(component.type))
        }

        appendConstant(mv, "]")
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(Opcodes.ARETURN)
    }

    /** `result = result * 31 + hash(component)`, starting from zero. */
    private fun generateHashCode(mv: MethodVisitor, helper: Helper) {
        mv.visitInsn(Opcodes.ICONST_0)

        helper.components.forEach { component ->
            Bytecode.pushInt(mv, 31)
            mv.visitInsn(Opcodes.IMUL)
            loadComponent(mv, slot = 0, castTo = null, component = component)
            hashComponent(mv, component.type)
            mv.visitInsn(Opcodes.IADD)
        }

        mv.visitInsn(Opcodes.IRETURN)
    }

    /**
     * `instanceof` the record type, then component by component.
     *
     * The other side is re-loaded and cast per component rather than stashed in
     * a local, which keeps every frame in the method a plain [Bytecode.sameFrame].
     */
    private fun generateEquals(mv: MethodVisitor, helper: Helper) {
        val record = helper.recordType.internalName
        val notEqual = Label()

        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, record)
        mv.visitJumpInsn(Opcodes.IFEQ, notEqual)

        helper.components.forEach { component ->
            loadComponent(mv, slot = 0, castTo = null, component = component)
            loadComponent(mv, slot = 1, castTo = record, component = component)
            compareComponent(mv, component.type, notEqual)
        }

        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)

        Bytecode.target(mv, notEqual)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
    }

    private fun loadComponent(mv: MethodVisitor, slot: Int, castTo: String?, component: Component) {
        mv.visitVarInsn(Opcodes.ALOAD, slot)
        if (castTo != null) mv.visitTypeInsn(Opcodes.CHECKCAST, castTo)

        val getter = component.getter
        when (getter.tag) {
            Opcodes.H_GETFIELD ->
                mv.visitFieldInsn(Opcodes.GETFIELD, getter.owner, getter.name, getter.desc)
            Opcodes.H_INVOKEINTERFACE ->
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, getter.owner, getter.name, getter.desc, true)
            else ->
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, getter.owner, getter.name, getter.desc, false)
        }
    }

    private fun appendConstant(mv: MethodVisitor, text: String) {
        mv.visitLdcInsn(text)
        append(mv, "Ljava/lang/String;")
    }

    private fun append(mv: MethodVisitor, argumentDescriptor: String) {
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append", "($argumentDescriptor)$BUILDER_DESCRIPTOR", false
        )
    }

    /**
     * Which `StringBuilder.append` overload to link.
     *
     * Picked explicitly rather than by descriptor, so a `char[]` component goes
     * through `append(Object)` and prints as a reference like the bootstrap does,
     * instead of resolving to the overload that spells out its contents.
     */
    private fun appendDescriptorFor(type: Type): String = when (type.sort) {
        Type.BOOLEAN -> "Z"
        Type.CHAR -> "C"
        Type.BYTE, Type.SHORT, Type.INT -> "I"
        Type.LONG -> "J"
        Type.FLOAT -> "F"
        Type.DOUBLE -> "D"
        else -> "Ljava/lang/Object;"
    }

    /**
     * Leaves the component's hash on the stack.
     *
     * `byte`, `short`, `char` and `int` hash to their own value, so those need
     * no call at all; the value is already an int on the stack.
     */
    private fun hashComponent(mv: MethodVisitor, type: Type) {
        val (owner, descriptor) = when (type.sort) {
            Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> return
            Type.BOOLEAN -> "java/lang/Boolean" to "(Z)I"
            Type.LONG -> "java/lang/Long" to "(J)I"
            Type.FLOAT -> "java/lang/Float" to "(F)I"
            Type.DOUBLE -> "java/lang/Double" to "(D)I"
            else -> OBJECTS to "(Ljava/lang/Object;)I"
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "hashCode", descriptor, false)
    }

    /**
     * Consumes two component values and jumps to [notEqual] when they differ.
     *
     * `float` and `double` go through `compare`, not `==`: the bootstrap
     * compares them bitwise, so `NaN` equals itself and `0.0` does not equal
     * `-0.0`.
     */
    private fun compareComponent(mv: MethodVisitor, type: Type, notEqual: Label) {
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, notEqual)

            Type.LONG -> {
                mv.visitInsn(Opcodes.LCMP)
                mv.visitJumpInsn(Opcodes.IFNE, notEqual)
            }

            Type.FLOAT -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false)
                mv.visitJumpInsn(Opcodes.IFNE, notEqual)
            }

            Type.DOUBLE -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false)
                mv.visitJumpInsn(Opcodes.IFNE, notEqual)
            }

            else -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, OBJECTS, "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false
                )
                mv.visitJumpInsn(Opcodes.IFEQ, notEqual)
            }
        }
    }

    /**
     * What `Class.getSimpleName()` would have returned, which is the name the
     * bootstrap puts in front of the bracket.
     *
     * A record declared inside a method is compiled to `Outer$1Name`, and the
     * simple name drops that disambiguating number. No record can be anonymous
     * and no Java identifier starts with a digit, so trimming leading digits off
     * the last segment is enough.
     */
    private fun simpleNameOf(internalName: String): String =
        internalName.substringAfterLast('/')
            .substringAfterLast('$')
            .trimStart { it.isDigit() }
}
