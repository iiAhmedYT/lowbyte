package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.MAP
import dev.iiahmed.lowbyte.api.ApiBytecode.OBJECT
import dev.iiahmed.lowbyte.api.ApiBytecode.SIMPLE_IMMUTABLE_ENTRY
import dev.iiahmed.lowbyte.api.InlineRewrite
import dev.iiahmed.lowbyte.api.RuntimeRewrite
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Map.of`, on the injected utility.
 *
 * Eleven overloads, one per arity, and each wants two null checks per pair plus
 * a repeated-key check across the lot. That is a loop, so it is written as Java
 * rather than emitted a pair at a time.
 *
 * There is no varargs shape to worry about: `Map.of` stops at ten pairs and
 * javac makes you write `Map.ofEntries` past that, which is
 * [MapOfEntriesRewrite].
 */
object MapOfRewrite : RuntimeRewrite("mapOf") {
    override val name = "Map.of"
}

/**
 * `Map.ofEntries`, on the injected utility.
 *
 * Same reasoning as [MapOfRewrite]: a walk over the entries with two null checks
 * apiece and a repeated-key check across the lot is a loop, and a loop is worth
 * writing as Java rather than emitting with hand-written frames.
 */
object MapOfEntriesRewrite : RuntimeRewrite("ofEntries") {
    override val name = "Map.ofEntries"
}

/**
 * `Map.entry`, as the immutable entry the JDK has had since 1.6.
 *
 * `setValue` throws on that one too, which is what the factory promises.
 */
object MapEntryRewrite : InlineRewrite() {

    override val name = "Map.entry"

    override val introducedIn = 9

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == MAP && name == "entry" && descriptor == "($OBJECT$OBJECT)Ljava/util/Map\$Entry;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        mv.visitTypeInsn(Opcodes.NEW, SIMPLE_IMMUTABLE_ENTRY)
        mv.visitInsn(Opcodes.DUP)
        ApiBytecode.loadChecked(mv, 0)
        ApiBytecode.loadChecked(mv, 1)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, SIMPLE_IMMUTABLE_ENTRY, "<init>", "($OBJECT$OBJECT)V", false
        )
        mv.visitInsn(Opcodes.ARETURN)
    }
}

/**
 * `Map.copyOf`, on the injected utility.
 *
 * Not `ofEntries` over the entry set, however alike they look: a map cannot hold
 * a repeated key, so there is nothing here to refuse as a duplicate.
 */
object MapCopyOfRewrite : RuntimeRewrite("copyOf") {
    override val name = "Map.copyOf"
}
