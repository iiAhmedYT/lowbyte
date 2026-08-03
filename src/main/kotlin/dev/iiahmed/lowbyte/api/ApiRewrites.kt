package dev.iiahmed.lowbyte.api

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import dev.iiahmed.lowbyte.api.rewrite.ArraysCompareRewrite
import dev.iiahmed.lowbyte.api.rewrite.ArraysCompareUnsignedRewrite
import dev.iiahmed.lowbyte.api.rewrite.ArraysEqualsRewrite
import dev.iiahmed.lowbyte.api.rewrite.ArraysMismatchRewrite
import dev.iiahmed.lowbyte.api.rewrite.CollectorsToUnmodifiableListRewrite
import dev.iiahmed.lowbyte.api.rewrite.CollectorsToUnmodifiableMapRewrite
import dev.iiahmed.lowbyte.api.rewrite.CollectorsToUnmodifiableSetRewrite
import dev.iiahmed.lowbyte.api.rewrite.ListCopyOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.ListOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapCopyOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapEntryRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapOfEntriesRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.ObjectsCheckFromIndexSizeRewrite
import dev.iiahmed.lowbyte.api.rewrite.ObjectsCheckFromToIndexRewrite
import dev.iiahmed.lowbyte.api.rewrite.ObjectsCheckIndexRewrite
import dev.iiahmed.lowbyte.api.rewrite.FilesMismatchRewrite
import dev.iiahmed.lowbyte.api.rewrite.FilesReadStringRewrite
import dev.iiahmed.lowbyte.api.rewrite.FilesWriteStringRewrite
import dev.iiahmed.lowbyte.api.rewrite.OptionalIsEmptyRewrite
import dev.iiahmed.lowbyte.api.rewrite.OptionalOrElseThrowRewrite
import dev.iiahmed.lowbyte.api.rewrite.OptionalStreamRewrite
import dev.iiahmed.lowbyte.api.rewrite.ReaderTransferToRewrite
import dev.iiahmed.lowbyte.api.rewrite.RequireNonNullElseRewrite
import dev.iiahmed.lowbyte.api.rewrite.PathOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.PredicateNotRewrite
import dev.iiahmed.lowbyte.api.rewrite.SetCopyOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.SetOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.StreamOfNullableRewrite
import dev.iiahmed.lowbyte.api.rewrite.StreamToListRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringFormattedRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringIndentRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringIsBlankRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringLinesRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringRepeatRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringStripIndentRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringStripLeadingRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringStripRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringStripTrailingRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringTransformRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringTranslateEscapesRewrite

/**
 * Every JDK call Lowbyte knows how to rebuild. Adding one is a class and a line.
 *
 * Deliberately short. A rewrite earns its place by keeping the contract of what
 * it replaces, and most of the three thousand members added between Java 8 and
 * 21 cannot be rebuilt that faithfully, so they are reported instead.
 */
object ApiRewrites {

    val ALL: List<ApiRewrite> = listOf(
        ListOfRewrite,
        SetOfRewrite,
        MapOfRewrite,
        MapOfEntriesRewrite,
        MapEntryRewrite,
        ListCopyOfRewrite,
        SetCopyOfRewrite,
        MapCopyOfRewrite,
        OptionalIsEmptyRewrite,
        OptionalOrElseThrowRewrite,
        OptionalStreamRewrite.OfObject,
        OptionalStreamRewrite.OfInt,
        OptionalStreamRewrite.OfLong,
        OptionalStreamRewrite.OfDouble,
        StreamOfNullableRewrite,
        StreamToListRewrite,
        PathOfRewrite.OfStrings,
        PathOfRewrite.OfUri,
        FilesReadStringRewrite,
        FilesWriteStringRewrite,
        FilesMismatchRewrite,
        ReaderTransferToRewrite,
        PredicateNotRewrite,
        CollectorsToUnmodifiableListRewrite,
        CollectorsToUnmodifiableSetRewrite,
        CollectorsToUnmodifiableMapRewrite,
        StringRepeatRewrite,
        StringIsBlankRewrite,
        StringStripRewrite,
        StringStripLeadingRewrite,
        StringStripTrailingRewrite,
        StringLinesRewrite,
        StringIndentRewrite,
        StringStripIndentRewrite,
        StringTransformRewrite,
        StringFormattedRewrite,
        StringTranslateEscapesRewrite,
        ArraysMismatchRewrite,
        ArraysEqualsRewrite,
        ArraysCompareRewrite,
        ArraysCompareUnsignedRewrite,
        RequireNonNullElseRewrite,
        ObjectsCheckIndexRewrite,
        ObjectsCheckFromToIndexRewrite,
        ObjectsCheckFromIndexSizeRewrite
    )

    /** The rewrite for a call, or null when there is none. */
    fun forCall(owner: String, name: String, descriptor: String): ApiRewrite? =
        ALL.firstOrNull { it.matches(owner, name, descriptor) }

    /**
     * Which utility methods a class will end up needing at this target.
     *
     * Asked before anything is written, because the injected class is named
     * after what it holds and a call site cannot be pointed at it until that
     * name is known. It goes through [forCall] like the rewriting does, so the
     * two cannot disagree about what gets replaced.
     */
    fun runtimeMethodsNeeded(classBytes: ByteArray, targetJava: Int): Set<String> {
        val needed = mutableSetOf<String>()

        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ) = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String?,
                    insnName: String?,
                    insnDescriptor: String?,
                    isInterface: Boolean
                ) {
                    if (owner == null || insnName == null || insnDescriptor == null) return
                    record(owner, insnName, insnDescriptor)
                }

                /**
                 * A method reference needs the same utility method a call does.
                 *
                 * `String::isBlank` names its target in a bootstrap argument, so
                 * counting call sites alone would leave the rewritten handle
                 * pointing at a method that was never injected.
                 */
                override fun visitInvokeDynamicInsn(
                    insnName: String?,
                    insnDescriptor: String?,
                    bootstrapMethodHandle: Handle?,
                    vararg bootstrapMethodArguments: Any?
                ) {
                    bootstrapMethodArguments.forEach { argument ->
                        if (argument is Handle) record(argument.owner, argument.name, argument.desc)
                    }
                }

                private fun record(owner: String, name: String, descriptor: String) {
                    val rewrite = forCall(owner, name, descriptor)
                    if (rewrite !is RuntimeRewrite || targetJava >= rewrite.introducedIn) return

                    rewrite.replacementFor(owner, name, descriptor)?.let { needed += it.key }
                }
            }
        }, 0)

        return needed
    }
}
