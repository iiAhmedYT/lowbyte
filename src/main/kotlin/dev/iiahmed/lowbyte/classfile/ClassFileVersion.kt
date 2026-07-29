package dev.iiahmed.lowbyte.classfile

/** Class file major/minor versions and the Java releases they map to. */
object ClassFileVersion {

    const val MIN_TARGET_JAVA = 8

    /** Bounded by what our ASM version can parse, not by the format itself. */
    const val MAX_SUPPORTED_JAVA = 25

    val SUPPORTED_TARGETS = MIN_TARGET_JAVA..MAX_SUPPORTED_JAVA

    /** 8 -> 52, 17 -> 61, and so on. */
    fun fromJavaVersion(javaVersion: Int): Int {
        require(javaVersion >= 1) { "Invalid Java version: $javaVersion" }
        // Java 1 is 45, every release after adds one.
        return javaVersion + 44
    }

    /** 52 -> 8, 61 -> 17, and so on. */
    fun toJavaVersion(majorVersion: Int): Int = majorVersion - 44

    /** ASM hands you major and minor packed together; this drops the minor half. */
    fun majorOf(version: Int): Int = version and 0xFFFF

    /**
     * Preview classes set minor to 0xFFFF and only load on the exact JDK that
     * compiled them, so no amount of header rewriting makes them portable.
     */
    fun isPreview(version: Int): Boolean = (version ushr 16) == 0xFFFF

    fun requireSupportedTarget(javaVersion: Int) {
        require(javaVersion in SUPPORTED_TARGETS) {
            "Lowbyte: unsupported targetJavaVersion $javaVersion, " +
                "expected $MIN_TARGET_JAVA..$MAX_SUPPORTED_JAVA"
        }
    }
}
