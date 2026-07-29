package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.tasks.DowngradeBytecode
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.module.ModuleDescriptor
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The task, over a real jar.
 *
 * Everything else tests one class at a time through
 * [dev.iiahmed.lowbyte.downgrade.ClassDowngrader]. The jar level has its own
 * behaviour that only shows up here: entries that are not classes, the marker
 * classes a bridged constructor adds, exclusions, module descriptors, and what
 * happens to the output when something cannot be downgraded.
 */
class DowngradeBytecodeTaskTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun everyClassIsDowngradedAndEverythingElseIsCopied() {
        val resource = "not a class file".toByteArray()
        val output = run(
            entries = fixtureEntries("NestSample") + mapOf("data/notes.txt" to resource),
            target = 9
        )

        output.filterKeys { it.endsWith(".class") }.forEach { (name, bytes) ->
            assertEquals(9, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)), name)
        }
        assertContentEquals(resource, output.getValue("data/notes.txt"), "a resource was rewritten")
    }

    @Test
    fun markerClassesForBridgedConstructorsReachTheJar() {
        // These are the only entries in the output that had no entry in the
        // input, so nothing outside the task would notice them going missing.
        val output = run(fixtureEntries("NestSample"), target = 9)

        assertContains(output.keys, "NestSample\$lowbyte\$Nest.class")
        assertContains(output.keys, "NestSample\$Secret\$lowbyte\$Nest.class")
        assertEquals(
            9,
            ClassFileVersion.toJavaVersion(
                Fixtures.majorVersionOf(output.getValue("NestSample\$lowbyte\$Nest.class"))
            )
        )
    }

    @Test
    fun aTargetThatKeepsNestsAddsNoMarkers() {
        val output = run(fixtureEntries("NestSample"), target = 11)

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
                fixtureEntries("EnumSample") + mapOf("module-info.class" to moduleInfoClass()),
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
        val output = run(
            fixtureEntries("EnumSample") + mapOf("module-info.class" to moduleInfoClass()),
            target = 8
        )

        assertFalse(output.containsKey("module-info.class"), "the descriptor survived: ${output.keys}")
        assertTrue(
            output.keys.any { it == "EnumSample.class" },
            "dropping the descriptor should not disturb anything else"
        )
    }

    @Test
    fun aVersionedModuleDescriptorIsKeptBelowNine() {
        // Java 8 never resolves a class name out of META-INF/versions, and the
        // copy still describes the module for the JVMs that do read it.
        val moduleInfo = moduleInfoClass()
        val name = "META-INF/versions/11/module-info.class"

        val output = run(fixtureEntries("EnumSample") + mapOf(name to moduleInfo), target = 8)

        assertContentEquals(moduleInfo, output.getValue(name), "the versioned copy was rewritten")
    }

    @Test
    fun versionedModuleDescriptorsAreLoweredAtNineAndAbove() {
        val moduleInfo = moduleInfoClass()
        val name = "META-INF/versions/11/module-info.class"

        val output = run(fixtureEntries("EnumSample") + mapOf(name to moduleInfo), target = 11)

        assertEquals(11, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(output.getValue(name))))
        assertEquals("foo.bar", ModuleDescriptor.read(ByteArrayInputStream(output.getValue(name))).name())
    }

    @Test
    fun excludedClassesAreCopiedUnchanged() {
        val entries = fixtureEntries("EnumSample")
        val output = run(entries, target = 11, excluded = listOf("EnumSample\$Color"))

        assertContentEquals(
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

        assertContentEquals(
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

        assertContentEquals(
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
        val output = run(
            fixtureEntries("EnumSample") + mapOf(
                "META-INF/MANIFEST.MF" to signedManifest(),
                "META-INF/LOWBYTE.SF" to "signature file".toByteArray(),
                "META-INF/LOWBYTE.RSA" to "signature block".toByteArray()
            ),
            target = 11
        )

        assertTrue(
            output.keys.none { it.endsWith(".SF") || it.endsWith(".RSA") },
            "the signature block survived: ${output.keys}"
        )

        val manifest = Manifest(ByteArrayInputStream(output.getValue("META-INF/MANIFEST.MF")))
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
            fixtureEntries("EnumSample") + mapOf("META-INF/MANIFEST.MF" to plain),
            target = 11
        )

        assertContentEquals(plain, output.getValue("META-INF/MANIFEST.MF"), "an unsigned manifest was rewritten")
    }

    @Test
    fun anUnsupportedConstructFailsTheBuildAndDeletesTheJar() {
        val outputFile = File(tempDir, "out.jar")
        val inputFile = jarOf(mapOf("Condy.class" to condyClass()))

        val failure = assertFailsWith<GradleException> {
            task(inputFile, outputFile, target = 9).downgrade()
        }

        assertTrue(failure.message!!.contains("CONSTANT_Dynamic"), failure.message!!)
        assertFalse(outputFile.exists(), "a jar that cannot run must not be left behind")
    }

    @Test
    fun failOnUnsupportedOffKeepsTheJar() {
        val outputFile = File(tempDir, "out.jar")
        val inputFile = jarOf(mapOf("Condy.class" to condyClass()))

        task(inputFile, outputFile, target = 9, failOnUnsupported = false).downgrade()

        assertTrue(outputFile.exists(), "with the check off the jar should survive")
    }

    // helpers

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray, message: String = "") {
        assertTrue(expected.contentEquals(actual), message)
    }

    private fun fixtureEntries(sample: String): Map<String, ByteArray> =
        Fixtures.classNames(sample).associate { "$it.class" to Fixtures.readClass(it) }

    private fun run(
        entries: Map<String, ByteArray>,
        target: Int,
        excluded: List<String> = emptyList()
    ): Map<String, ByteArray> {
        val outputFile = File(tempDir, "out.jar")
        task(jarOf(entries), outputFile, target, excluded = excluded).downgrade()
        return readJar(outputFile)
    }

    private fun task(
        input: File,
        output: File,
        target: Int,
        excluded: List<String> = emptyList(),
        failOnUnsupported: Boolean = true
    ): DowngradeBytecode {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        return project.tasks.create("downgrade${target}${output.name.hashCode()}", DowngradeBytecode::class.java)
            .apply {
                targetJavaVersion.set(target)
                excludedClasses.set(excluded)
                this.failOnUnsupported.set(failOnUnsupported)
                inputJar.set(input)
                outputJar.set(output)
            }
    }

    private fun jarOf(entries: Map<String, ByteArray>): File {
        val file = File(tempDir, "in-${entries.keys.hashCode()}.jar")
        JarOutputStream(file.outputStream()).use { jos ->
            entries.forEach { (name, bytes) ->
                jos.putNextEntry(ZipEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return file
    }

    private fun readJar(file: File): Map<String, ByteArray> = JarFile(file).use { jar ->
        jar.entries().asSequence().associate { it.name to jar.getInputStream(it).readAllBytes() }
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

    /** A class holding something no target below 11 can express. */
    private fun condyClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "Condy",
            null,
            "java/lang/Object",
            null
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
