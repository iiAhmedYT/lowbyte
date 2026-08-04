package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiIndex
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The core entry point, used the way something that is not Gradle would use it.
 *
 * The point of [Lowbyte] is that a downgrade needs no build tool, and that claim
 * is only worth anything if something exercises it. Everything else at the jar
 * level goes through the Gradle task, which would keep passing if core had
 * quietly grown a dependency on it.
 */
class LowbyteTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun aTargetIsRequired() {
        val failure = assertFailsWith<IllegalArgumentException> { Lowbyte.builder().build() }

        assertTrue(failure.message!!.contains("target"), failure.message!!)
    }

    @Test
    fun anImpossibleTargetIsRejectedBeforeAnythingIsRead() {
        // Config problems should not wait until the first jar to surface.
        assertFailsWith<IllegalArgumentException> { Lowbyte.targeting(2).build() }
    }

    @Test
    fun aMissingInputIsRefused() {
        val failure = assertFailsWith<LowbyteException> {
            Lowbyte.targeting(11).build().downgrade(File(tempDir, "nope.jar"), File(tempDir, "out.jar"))
        }

        assertTrue(failure.message!!.contains("not found"), failure.message!!)
    }

    @Test
    fun theResultDescribesWhatHappened() {
        val input = jarOf(fixtureEntries("EnumSample") + mapOf("data/notes.txt" to "hello".toByteArray()))
        val output = File(tempDir, "out.jar")

        val result = Lowbyte.targeting(11).build().downgrade(input, output)

        assertEquals(11, result.target)
        assertEquals(fixtureEntries("EnumSample").size, result.downgraded)
        assertEquals(1, result.copied, "the resource should be the only entry copied")
        assertEquals(emptyList(), result.unsupported)
        assertEquals(emptyList(), result.apiFindings)
        assertFalse(result.injected)
        assertNull(result.injectedClass)

        readJar(output).filterKeys { it.endsWith(".class") }.forEach { (name, bytes) ->
            assertEquals(11, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)), name)
        }
    }

    @Test
    fun oneInstanceDowngradesManyJars() {
        // The reason the files are arguments rather than settings: the API index
        // behind them is expensive and is meant to be read once.
        val lowbyte = Lowbyte.targeting(11).api(true).build()

        val first = File(tempDir, "first-out.jar")
        val second = File(tempDir, "second-out.jar")

        lowbyte.downgrade(jarOf(fixtureEntries("EnumSample")), first)
        lowbyte.downgrade(jarOf(fixtureEntries("NestSample")), second)

        listOf(first, second).forEach { file ->
            readJar(file).filterKeys { it.endsWith(".class") }.forEach { (name, bytes) ->
                assertEquals(11, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)), name)
            }
        }
    }

    @Test
    fun theUtilityIsInjectedAndNamedInTheResult() {
        assumeTrue(!Fixtures.apiIndexFor(8).isEmpty, "this JDK's ct.sym has no data for Java 8")

        val input = jarOf(fixtureEntries("RuntimeApiSample"))
        val result = Lowbyte.targeting(8).api(true).build().downgrade(input, File(tempDir, "out.jar"))

        assertTrue(result.injected, "the sample needs the utility")
        assertTrue(
            result.injectedClass!!.startsWith("dev/iiahmed/lowbyte/runtime/"),
            result.injectedClass!!
        )
        assertTrue(result.injectedMethods.isNotEmpty(), "an injected class with no methods")
        assertEquals(emptyList(), result.warnings, "the index resolved, so nothing to say")
    }

    @Test
    fun nothingIsInjectedWithTheFlagOff() {
        val result = Lowbyte.targeting(8).build().downgrade(
            jarOf(fixtureEntries("RuntimeApiSample")), File(tempDir, "out.jar")
        )

        assertFalse(result.injected, "the conversion is opt-in")
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun anExplicitCtSymIsUsed() {
        val ctSym = ApiIndex.currentJdkCtSym()
        assumeTrue(ctSym != null, "this JVM ships no lib/ct.sym")

        val result = Lowbyte.targeting(8).api(true).ctSym(ctSym).build()
            .downgrade(jarOf(fixtureEntries("RuntimeApiSample")), File(tempDir, "out.jar"))

        assertTrue(result.injected)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun aCtSymThatIsNotThereIsRefusedAtBuildTime() {
        val missing = File(tempDir, "nowhere/ct.sym")

        val failure = assertFailsWith<IllegalArgumentException> {
            Lowbyte.targeting(8).api(true).ctSym(missing).build()
        }

        assertTrue(failure.message!!.contains("ct.sym"), failure.message!!)
    }

    @Test
    fun aCtSymThatCannotBeReadIsReportedAgainstItsPath() {
        // Losing the JDK's own record is a warning, because nobody chose it.
        // A path handed to the builder is a choice, so a bad one is an error
        // naming the file rather than a ZipException from somewhere inside.
        val notACtSym = File(tempDir, "empty.zip").apply { writeBytes(ByteArray(0)) }

        val failure = assertFailsWith<LowbyteException> {
            Lowbyte.targeting(8).api(true).ctSym(notACtSym).build()
                .downgrade(jarOf(fixtureEntries("RuntimeApiSample")), File(tempDir, "out.jar"))
        }

        assertTrue(failure.message!!.contains(notACtSym.absolutePath), failure.message!!)
    }

    @Test
    fun anEmptyApiRecordIsAWarningRatherThanAFailure() {
        // The rebuilds go by release alone and carry on. Only the reporting half
        // needs the index, so a ct.sym that reads perfectly well and simply has
        // nothing for the target must not stop the run.
        val emptyCtSym = File(tempDir, "empty-ct.sym").apply {
            JarOutputStream(outputStream()).close()
        }

        val result = Lowbyte.targeting(8).api(true).ctSym(emptyCtSym).build()
            .downgrade(jarOf(fixtureEntries("RuntimeApiSample")), File(tempDir, "out.jar"))

        assertTrue(result.injected, "the rebuilds should not need the index")
        assertEquals(1, result.warnings.size, result.warnings.toString())
        assertTrue(result.warnings.single().contains("no data for Java 8"), result.warnings.single())
    }

    @Test
    fun anUnsupportedConstructDeletesTheJarAndThrows() {
        val output = File(tempDir, "out.jar")

        val failure = assertFailsWith<LowbyteException> {
            Lowbyte.targeting(9).build().downgrade(jarOf(mapOf("Condy.class" to condyClass())), output)
        }

        assertTrue(failure.message!!.contains("CONSTANT_Dynamic"), failure.message!!)
        assertFalse(output.exists(), "a jar that cannot run must not be left behind")
    }

    @Test
    fun withTheCheckOffTheFindingsComeBackInstead() {
        val output = File(tempDir, "out.jar")

        val result = Lowbyte.targeting(9).failOnUnsupported(false).build()
            .downgrade(jarOf(mapOf("Condy.class" to condyClass())), output)

        assertTrue(output.exists(), "with the check off the jar should survive")
        assertEquals(1, result.unsupported.size, result.unsupported.toString())
        assertTrue(result.unsupported.single().contains("CONSTANT_Dynamic"), result.unsupported.single())
    }

    // helpers
    //
    // Exclusions, module descriptors, manifests and the rest of the jar level
    // are LowbyteJarTest's. What is here is the settings surface and the result.

    private fun fixtureEntries(sample: String) = Jars.entriesOf(sample)

    private fun jarOf(entries: Map<String, ByteArray>): File = Jars.of(tempDir, entries)

    private fun readJar(file: File): Map<String, ByteArray> = Jars.read(file)

    /** A class holding something no target below 11 can express. */
    private fun condyClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "Condy", null, "java/lang/Object", null
        )
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "get", "()Ljava/lang/Object;", null, null
        )
        mv.visitCode()
        mv.visitLdcInsn(
            ConstantDynamic(
                "constant",
                "Ljava/lang/Object;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/ConstantBootstraps",
                    "nullConstant",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;)" +
                        "Ljava/lang/Object;",
                    false
                )
            )
        )
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
