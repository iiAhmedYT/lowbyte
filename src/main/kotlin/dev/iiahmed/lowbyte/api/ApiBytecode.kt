package dev.iiahmed.lowbyte.api

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * The pieces every [ApiRewrite] builds out of.
 *
 * Frames are written by hand, as everywhere else in Lowbyte, because
 * COMPUTE_FRAMES would make ASM load the classes being processed. The shapes
 * here are arranged so that is possible: a collection is parked in a local
 * rather than left on the stack, which keeps the operand stack empty at every
 * branch target. Which local is [ApiSlots]' business, not a constant.
 */
object ApiBytecode {

    const val OBJECT = "Ljava/lang/Object;"
    const val OBJECT_ARRAY = "[Ljava/lang/Object;"

    const val LIST = "java/util/List"
    const val SET = "java/util/Set"
    const val MAP = "java/util/Map"
    const val COLLECTION = "java/util/Collection"
    const val OPTIONAL = "java/util/Optional"
    const val OBJECTS = "java/util/Objects"

    /** An interface, so its static methods are called with the interface flag set. */
    const val STREAM = "java/util/stream/Stream"

    const val ARRAY_LIST = "java/util/ArrayList"
    const val LINKED_HASH_SET = "java/util/LinkedHashSet"
    const val COLLECTIONS = "java/util/Collections"
    const val SIMPLE_IMMUTABLE_ENTRY = "java/util/AbstractMap\$SimpleImmutableEntry"
    const val ILLEGAL_ARGUMENT = "java/lang/IllegalArgumentException"

    /**
     * Whether a factory call is every-argument-an-element, or the single array
     * the varargs overload passes.
     *
     * `List.of` and `Set.of` share both shapes: up to ten elements javac calls a
     * fixed-arity overload, and past that the varargs one.
     */
    fun isFactoryShape(descriptor: String): Boolean {
        val arguments = Type.getArgumentTypes(descriptor)
        return arguments.all { it.descriptor == OBJECT } || isVarargs(descriptor)
    }

    /** Whether the call passes its elements as one array. */
    fun isVarargs(descriptor: String): Boolean {
        val arguments = Type.getArgumentTypes(descriptor)
        return arguments.size == 1 && arguments[0].descriptor == OBJECT_ARRAY
    }

    /** Builds a collection and parks it in [slot]. */
    fun newCollection(mv: MethodVisitor, type: String, slot: Int) {
        mv.visitTypeInsn(Opcodes.NEW, type)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ASTORE, slot)
    }

    /** Null-checks whatever is on the stack, leaving it there. */
    fun requireNonNull(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, OBJECTS, "requireNonNull", "($OBJECT)$OBJECT", false
        )
    }

    /** Loads an argument, having refused null the way the JDK factories do. */
    fun loadChecked(mv: MethodVisitor, slot: Int) {
        mv.visitVarInsn(Opcodes.ALOAD, slot)
        requireNonNull(mv)
    }

    /** Loads `array[i]` from the argument in slot 0, null-checked. */
    fun loadArrayElement(mv: MethodVisitor, slots: ApiSlots) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, slots.index)
        mv.visitInsn(Opcodes.AALOAD)
        requireNonNull(mv)
    }

    fun throwIllegalArgument(mv: MethodVisitor, message: String) {
        mv.visitTypeInsn(Opcodes.NEW, ILLEGAL_ARGUMENT)
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn(message)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ILLEGAL_ARGUMENT, "<init>", "(Ljava/lang/String;)V", false)
        mv.visitInsn(Opcodes.ATHROW)
    }

    /** Wraps the collection and returns it. */
    fun returnUnmodifiable(mv: MethodVisitor, slots: ApiSlots, wrapper: String, type: String) {
        mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, COLLECTIONS, wrapper, "(L$type;)L$type;", false)
        mv.visitInsn(Opcodes.ARETURN)
    }

    /**
     * `for (int i = 0; i < limit; i++)`.
     *
     * Frames are full rather than incremental: a loop head is reached from two
     * places, and spelling the locals out at both is easier to be sure of than
     * working out what a delta would mean at each.
     */
    fun countedLoop(
        mv: MethodVisitor,
        slots: ApiSlots,
        frameLocals: Array<Any>,
        limit: () -> Unit,
        body: () -> Unit
    ) {
        val loop = Label()
        val done = Label()

        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, slots.index)

        mv.visitLabel(loop)
        mv.visitFrame(Opcodes.F_FULL, frameLocals.size, frameLocals, 0, emptyArray())
        mv.visitVarInsn(Opcodes.ILOAD, slots.index)
        limit()
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done)

        body()

        mv.visitIincInsn(slots.index, 1)
        mv.visitJumpInsn(Opcodes.GOTO, loop)

        mv.visitLabel(done)
        mv.visitFrame(Opcodes.F_FULL, frameLocals.size, frameLocals, 0, emptyArray())
    }

    /** Walks the array argument in slot 0. */
    fun arrayLoop(mv: MethodVisitor, slots: ApiSlots, collection: String, body: () -> Unit) {
        countedLoop(
            mv, slots, slots.frame(collection, Opcodes.INTEGER),
            limit = {
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
            },
            body = body
        )
    }

    /** Walks the `ArrayList` the rewrite has built. */
    fun listLoop(mv: MethodVisitor, slots: ApiSlots, body: () -> Unit) {
        countedLoop(
            mv, slots, slots.frame(ARRAY_LIST, Opcodes.INTEGER),
            limit = {
                mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "size", "()I", false)
            },
            body = body
        )
    }

    /** `arrayList.get(i)`, left on the stack. */
    fun loadListElement(mv: MethodVisitor, slots: ApiSlots) {
        mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
        mv.visitVarInsn(Opcodes.ILOAD, slots.index)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "get", "(I)$OBJECT", false)
    }

    /**
     * Throws unless the collection ended up the size it should be.
     *
     * That is how a duplicate is spotted, without a branch per element.
     */
    fun requireSize(
        mv: MethodVisitor,
        slots: ApiSlots,
        collection: String,
        frameLocals: Array<Any>,
        expected: () -> Unit,
        message: String
    ) {
        mv.visitVarInsn(Opcodes.ALOAD, slots.collection)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, collection, "size", "()I", false)
        expected()

        val ok = Label()
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok)
        throwIllegalArgument(mv, message)

        mv.visitLabel(ok)
        mv.visitFrame(Opcodes.F_FULL, frameLocals.size, frameLocals, 0, emptyArray())
    }

    /** Copies the collection argument into an `ArrayList`, refusing nulls. */
    fun copyArgumentIntoList(mv: MethodVisitor, slots: ApiSlots) {
        mv.visitTypeInsn(Opcodes.NEW, ARRAY_LIST)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        requireNonNull(mv)
        mv.visitTypeInsn(Opcodes.CHECKCAST, COLLECTION)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "(L$COLLECTION;)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, slots.collection)

        listLoop(mv, slots) {
            loadListElement(mv, slots)
            requireNonNull(mv)
            mv.visitInsn(Opcodes.POP)
        }
    }
}
