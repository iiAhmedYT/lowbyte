package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiRewrites
import dev.iiahmed.lowbyte.api.RuntimeApi
import dev.iiahmed.lowbyte.api.RuntimeRewrite
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The injected utility, as the plugin sees it.
 *
 * It is compiled from `src/runtime` at release 8 and carried in the plugin jar,
 * so these check the packaging as much as the code.
 */
class RuntimeApiTest {

    private companion object {
        // Methods are kept by name and descriptor together, since Map.of has
        // eleven overloads and keeping one must not drag the rest in.
        const val ISBLANK = "isBlank(Ljava/lang/String;)Z"
        const val STRIP = "strip(Ljava/lang/String;)Ljava/lang/String;"
    }

    @Test
    fun everyRuntimeRewriteResolvesToARealMethod() {
        // A RuntimeRewrite naming a method the utility does not have matches
        // nothing and silently does nothing, which looks exactly like working
        // until you run it on the JVM that needed it.
        val unresolved = ApiRewrites.ALL
            .filterIsInstance<RuntimeRewrite>()
            .filter { it.introducedIn == Int.MAX_VALUE }
            .map { it.name }

        assertTrue(unresolved.isEmpty(), "these name no method on the utility: $unresolved")
    }

    @Test
    fun theTemplateDescribesItself() {
        val byCall = RuntimeApi.replacements.associateBy { "${it.owner}.${it.name}${it.descriptor}" }

        assertTrue(byCall.isNotEmpty(), "the runtime template was not found in the plugin jar")

        val isBlank = byCall.getValue("java/lang/String.isBlank()Z")
        assertEquals(11, isBlank.introducedIn)
        assertEquals("isBlank", isBlank.method)
        // The receiver of an instance call becomes the first parameter of the
        // utility method, which is what makes forwarding a swap of owner and
        // opcode with nothing generated at the call site.
        assertEquals("(Ljava/lang/String;)Z", isBlank.methodDescriptor)
    }

    @Test
    fun theInjectedClassIsJavaEightAndCarriesNoAnnotation() {
        val bytes = RuntimeApi.inject("com/example/Util", setOf(ISBLANK)).getValue("com/example/Util")

        assertEquals(8, dev.iiahmed.lowbyte.classfile.ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)))
        assertTrue(
            "LowbyteInfo" !in String(bytes, Charsets.ISO_8859_1),
            "the annotation was shipped"
        )
    }

    @Test
    fun keepingOneOverloadDoesNotDragInTheRest() {
        // Map.of has eleven. Asking for the two-argument one must not bring the
        // other ten along.
        val twoArguments = "mapOf(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"
        val methods = Fixtures.shapeOf(RuntimeApi.inject("com/example/Util", setOf(twoArguments)).getValue("com/example/Util")).methods

        assertEquals(1, methods.count { it.startsWith("mapOf") }, methods.toString())
        // ...but the private helper it calls has to come with it.
        assertTrue(methods.any { it.startsWith("newMap") }, "the shared helper was trimmed away: $methods")
    }

    @Test
    fun onlyTheWantedMethodsSurvive() {
        val one = Fixtures.shapeOf(RuntimeApi.inject("com/example/Util", setOf(ISBLANK)).getValue("com/example/Util")).methods
        val both = Fixtures.shapeOf(RuntimeApi.inject("com/example/Util", setOf(ISBLANK, STRIP)).getValue("com/example/Util")).methods

        assertTrue(one.any { it.startsWith("isBlank") }, one.toString())
        assertTrue(one.none { it.startsWith("strip") }, "an unused method was injected: $one")
        assertTrue(both.any { it.startsWith("strip") }, both.toString())
    }

    @Test
    fun theInjectedClassNeverReferencesAMethodItDoesNotHave() {
        // Trimming keeps a transitive closure, and anything the closure fails to
        // follow gets deleted out from under its caller. That does not fail at
        // injection time, or on the JVM the tests run on. It fails on somebody
        // else's Java 8 with a NoSuchMethodError, so it is worth catching here.
        //
        // A lambda is the way this happens: javac puts the body in a synthetic
        // method named only in the invokedynamic bootstrap arguments.
        val className = "com/example/Util"

        RuntimeApi.replacements.forEach { replacement ->
            val bytes = RuntimeApi.inject(className, setOf(replacement.key)).getValue(className)
            val present = Fixtures.shapeOf(bytes).methods.toSet()
            val referenced = mutableSetOf<String>()

            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
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
                        if (owner == className) referenced += "${insnName.orEmpty()}${insnDescriptor.orEmpty()}"
                    }

                    override fun visitInvokeDynamicInsn(
                        insnName: String?,
                        insnDescriptor: String?,
                        bootstrapMethodHandle: Handle?,
                        vararg bootstrapMethodArguments: Any?
                    ) {
                        (bootstrapMethodArguments.toList() + bootstrapMethodHandle).forEach { argument ->
                            if (argument is Handle && argument.owner == className) {
                                referenced += "${argument.name}${argument.desc}"
                            }
                        }
                    }
                }
            }, 0)

            val dangling = referenced - present
            assertTrue(dangling.isEmpty(), "keeping ${replacement.key} left dangling references: $dangling")
        }
    }

    @Test
    fun theNameSeparatesDifferentMethodSets() {
        // Two jars that kept different methods must not land on one name, or
        // shading them together would drop one set of methods.
        val a = RuntimeApi.defaultClassName(setOf(ISBLANK))
        val b = RuntimeApi.defaultClassName(setOf(STRIP))
        val alsoA = RuntimeApi.defaultClassName(setOf(ISBLANK))

        assertTrue(a != b, "different method sets shared a name: $a")
        assertEquals(a, alsoA, "the same method set must be stable across builds")
        assertTrue(a.startsWith("${RuntimeApi.DEFAULT_PACKAGE}/LowbyteApi_"), a)
    }
}
