package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.module.ModuleDescriptor
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a downgrade does to the jar rather than to a class.
 *
 * Everything else works one class at a time through
 * [dev.iiahmed.lowbyte.downgrade.ClassDowngrader]. The jar level has behaviour
 * of its own that only shows up here: entries that are not classes, the marker
 * classes a bridged constructor adds, exclusions, module descriptors, and what
 * a signed jar loses on the way through.
 *
 * These used to run through the Gradle task, which was the only thing that
 * could open a jar. Since that moved into [Lowbyte] they no longer need a build
 * tool, and testing them through one only obscured which layer they were about.
 */
class LowbyteJarTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun everyClassIsDowngradedAndEverythingElseIsCopied() {
        val resource = "not a class file".toByteArray()
        val output = run(
            entries = Jars.entriesOf("NestSample") + mapOf("data/notes.txt" to resource),
            target = 9
        )

        output.filterKeys { it.endsWith(".class") }.forEach { (name, bytes) ->
            assertEquals(9, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)), name)
        }
        assertSameBytes(resource, output.getValue("data/notes.txt"), "a resource was rewritten")
    }

    @Test
    fun markerClassesForBridgedConstructorsReachTheJar() {
        // These are the only entries in the output that had no entry in the
        // input, so nothing outside this would notice them going missing.
        val output = run(Jars.entriesOf("NestSample"), target = 9)

        assertTrue(output.containsKey("NestSample\$lowbyte\$Nest.class"), output.keys.toString())
        assertTrue(output.containsKey("NestSample\$Secret\$lowbyte\$Nest.class"), output.keys.toString())
        assertEquals(
            9,
            ClassFileVersion.toJavaVersion(
                Fixtures.majorVersionOf(output.getValue("NestSample\$lowbyte\$Nest.class"))
            )
        )
    }

    @Test
    fun aTargetThatKeepsNestsAddsNoMarkers() {
        val output = run(Jars.entriesOf("NestSample"), target = 11)

        assertTrue(
            output.keys.none { it.contains("lowbyte\$Nest") },
            "markers should only appear once the nest is unpicked: ${output.keys}"
        )
    }

    @Test
    fun moduleDescriptorsAreLoweredDownToNine() {
        // The runtime version-checks module-info like any class, so one left at
        // 21 would make the jar unusable on the module path of the very JVM it
        // was downgraded for.
        listOf(17, 11, 9).forEach { target ->
            val output = run(
                Jars.entriesOf("EnumSample") + mapOf("module-info.class" to moduleInfoClass()),
                target = target
            )
            val descriptor = output.getValue("module-info.class")

            assertEquals(
                target, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(descriptor)),
                "module-info was not lowered to $target"
            )
            // The JDK's own reader, which is what rejected version 52 and 65.
            val module = ModuleDescriptor.read(ByteArrayInputStream(descriptor))
            assertEquals("foo.bar", module.name())
        }
    }

    @Test
    fun theRootModuleDescriptorIsDroppedBelowNine() {
        // No version works below 9: left high a scanner gets
        // UnsupportedClassVersionError, lowered it gets ClassFormatError, since
        // CONSTANT_Module does not exist before class file 53. Walking every
        // class entry is ordinary scanner behaviour, so the entry has to go.
        val output = File(tempDir, "dropped.jar")
        val result = Lowbyte.targeting(8).build().downgrade(
            Jars.of(tempDir, Jars.entriesOf("EnumSample") + mapOf("module-info.class" to moduleInfoClass())),
            output
        )

        assertTrue(result.droppedModuleInfo, "the result should say the descriptor went")
        val entries = Jars.read(output)
        assertFalse(entries.containsKey("module-info.class"), "the descriptor survived: ${entries.keys}")
        assertTrue(
            entries.keys.any { it == "EnumSample.class" },
            "dropping the descriptor should not disturb anything else"
        )
    }

    @Test
    fun aVersionedModuleDescriptorIsKeptBelowNine() {
        // Java 8 never resolves a class name out of META-INF/versions, and the
        // copy still describes the module for the JVMs that do read it.
        val moduleInfo = moduleInfoClass()
        val name = "META-INF/versions/11/module-info.class"

        val output = run(Jars.entriesOf("EnumSample") + mapOf(name to moduleInfo), target = 8)

        assertSameBytes(moduleInfo, output.getValue(name), "the versioned copy was rewritten")
    }

    @Test
    fun versionedModuleDescriptorsAreLoweredAtNineAndAbove() {
        val moduleInfo = moduleInfoClass()
        val name = "META-INF/versions/11/module-info.class"

        val output = run(Jars.entriesOf("EnumSample") + mapOf(name to moduleInfo), target = 11)

        assertEquals(11, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(output.getValue(name))))
        assertEquals("foo.bar", ModuleDescriptor.read(ByteArrayInputStream(output.getValue(name))).name())
    }

    @Test
    fun excludedClassesAreCopiedUnchanged() {
        val entries = Jars.entriesOf("EnumSample")
        val output = run(entries, target = 11, excluded = listOf("EnumSample\$Color"))

        assertSameBytes(
            entries.getValue("EnumSample\$Color.class"),
            output.getValue("EnumSample\$Color.class"),
            "an excluded class was rewritten"
        )
        assertEquals(
            11,
            ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(output.getValue("EnumSample.class"))),
            "the rest of the jar should still be downgraded"
        )
    }

    @Test
    fun exclusionsMatchOnNameBoundaries() {
        val entries = mapOf(
            "com/foo/Kept.class" to Fixtures.readClass("EnumSample"),
            "com/foobar/AlsoKept.class" to Fixtures.readClass("EnumSample")
        )
        // `com/foo` is a package, and `com/foobar` is a different one that merely
        // starts with the same characters.
        val output = run(entries, target = 11, excluded = listOf("com/foo"))

        assertSameBytes(
            entries.getValue("com/foo/Kept.class"),
            output.getValue("com/foo/Kept.class"),
            "the excluded package was rewritten"
        )
        assertEquals(
            11,
            ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(output.getValue("com/foobar/AlsoKept.class"))),
            "com/foobar is not inside com/foo and should have been downgraded"
        )
    }

    @Test
    fun excludingAClassExcludesItsNestedClasses() {
        val entries = mapOf(
            "com/foo/Outer.class" to Fixtures.readClass("EnumSample"),
            "com/foo/Outer\$Inner.class" to Fixtures.readClass("EnumSample"),
            "com/foo/OuterOther.class" to Fixtures.readClass("EnumSample")
        )
        val output = run(entries, target = 11, excluded = listOf("com.foo.Outer"))

        assertSameBytes(
            entries.getValue("com/foo/Outer\$Inner.class"),
            output.getValue("com/foo/Outer\$Inner.class"),
            "a nested class of an excluded class was rewritten"
        )
        assertEquals(
            11,
            ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(output.getValue("com/foo/OuterOther.class"))),
            "OuterOther is a different class that merely shares a prefix"
        )
    }

    @Test
    fun signatureFilesAreDroppedAndManifestDigestsGoWithThem() {
        val output = File(tempDir, "signed-out.jar")
        val result = Lowbyte.targeting(11).build().downgrade(
            Jars.of(
                tempDir,
                Jars.entriesOf("EnumSample") + mapOf(
                    "META-INF/MANIFEST.MF" to signedManifest(),
                    "META-INF/LOWBYTE.SF" to "signature file".toByteArray(),
                    "META-INF/LOWBYTE.RSA" to "signature block".toByteArray()
                )
            ),
            output
        )

        assertEquals(2, result.droppedSignatures, "the result should count what it dropped")

        val entries = Jars.read(output)
        assertTrue(
            entries.keys.none { it.endsWith(".SF") || it.endsWith(".RSA") },
            "the signature block survived: ${entries.keys}"
        )

        val manifest = Manifest(ByteArrayInputStream(entries.getValue("META-INF/MANIFEST.MF")))
        assertEquals(
            "1.0", manifest.mainAttributes.getValue("Manifest-Version"),
            "the main section should be left alone"
        )
        assertEquals(
            "dev.iiahmed.Main", manifest.mainAttributes.getValue("Main-Class"),
            "an unrelated main attribute was lost"
        )
        assertTrue(
            manifest.entries.values.none { section -> section.keys.any { it.toString().contains("-Digest") } },
            "a stale digest survived: ${manifest.entries}"
        )
        assertEquals(
            "true", manifest.entries["EnumSample.class"]?.getValue("Sealed"),
            "a non-digest attribute in a signed section was lost"
        )
        assertFalse(
            manifest.entries.containsKey("EnumSample\$Color.class"),
            "a section holding nothing but digests should go"
        )
    }

    @Test
    fun anUnsignedManifestIsCopiedByteForByte() {
        val plain = "Manifest-Version: 1.0\r\nMain-Class: dev.iiahmed.Main\r\n\r\n".toByteArray()
        val output = run(
            Jars.entriesOf("EnumSample") + mapOf("META-INF/MANIFEST.MF" to plain),
            target = 11
        )

        assertSameBytes(plain, output.getValue("META-INF/MANIFEST.MF"), "an unsigned manifest was rewritten")
    }

    @Test
    fun apiFindingsAreReportedAndNeverFatal() {
        // InputStream.readAllBytes is Java 9 and deliberately not rewritten:
        // ByteArrayInputStream specialises it, so one generic replacement would
        // be wrong for some receivers. A call like it may also sit behind a
        // runtime version check, which is correct code, so it comes back as a
        // finding however failOnUnsupported is set.
        assumeTrue(!Fixtures.apiIndexFor(8).isEmpty, "this JDK's ct.sym has no data for Java 8")

        val output = File(tempDir, "bytes-out.jar")
        val result = Lowbyte.targeting(8).api(true).failOnUnsupported(true).build()
            .downgrade(Jars.of(tempDir, mapOf("Bytes.class" to readAllBytesCaller())), output)

        assertTrue(output.exists(), "an API finding must not delete the jar")
        assertEquals(emptyList(), result.unsupported, "an API finding is not an unsupported construct")
        assertTrue(
            result.apiFindings.any { it.contains("readAllBytes") },
            "readAllBytes should have been reported: ${result.apiFindings}"
        )
        assertEquals(
            8,
            ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(Jars.read(output).getValue("Bytes.class")))
        )
    }

    // helpers

    private fun assertSameBytes(expected: ByteArray, actual: ByteArray, message: String) {
        assertTrue(expected.contentEquals(actual), message)
    }

    private fun run(
        entries: Map<String, ByteArray>,
        target: Int,
        excluded: List<String> = emptyList()
    ): Map<String, ByteArray> {
        val output = File(tempDir, "out-${entries.keys.hashCode()}-$target.jar")
        Lowbyte.targeting(target).exclude(excluded).build().downgrade(Jars.of(tempDir, entries), output)
        return Jars.read(output)
    }

    /** What `jarsigner` leaves behind: per-entry digests beside real attributes. */
    private fun signedManifest(): ByteArray {
        val manifest = Manifest()
        manifest.mainAttributes.putValue("Manifest-Version", "1.0")
        manifest.mainAttributes.putValue("Main-Class", "dev.iiahmed.Main")

        // One section mixing a digest with an attribute worth keeping...
        manifest.entries["EnumSample.class"] = Attributes().apply {
            putValue("SHA-256-Digest", "Zm9vYmFy")
            putValue("Sealed", "true")
        }
        // ...and one that is nothing but a digest.
        manifest.entries["EnumSample\$Color.class"] = Attributes().apply {
            putValue("SHA-256-Digest", "YmF6cXV4")
        }

        val out = ByteArrayOutputStream()
        manifest.write(out)
        return out.toByteArray()
    }

    private fun moduleInfoClass(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21), Opcodes.ACC_MODULE, "module-info", null, null, null
        )
        val module = cw.visitModule("foo.bar", 0, null)
        // Every descriptor has this, and ModuleDescriptor.read insists on it.
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null)
        module.visitExport("com/example", 0)
        module.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A Java 21 class calling a Java 9 method Lowbyte declines to rebuild. */
    private fun readAllBytesCaller(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "Bytes", null, "java/lang/Object", null
        )
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "go", "(Ljava/io/InputStream;)[B", null, null
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
