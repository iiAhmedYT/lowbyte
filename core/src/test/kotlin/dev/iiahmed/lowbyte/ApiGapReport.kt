package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiIndex
import dev.iiahmed.lowbyte.api.ApiRewrites
import java.io.File

/**
 * Every member a module gained between two releases, and what Lowbyte does about it.
 *
 * The data is `ct.sym`, the per-release signature record every JDK ships and
 * `javac --release` reads. So this is the compiler's own account of what was
 * added, not a list anybody has to maintain.
 *
 * Run through the `apiGap` Gradle task, which supplies a JDK 21's `ct.sym`.
 */
private const val USAGE = """
usage: ApiGapReport [--from=8] [--to=21] [--module=java.base] [--ctsym=PATH] [--out=PATH]

  Every argument is optional and named. Gradle's own -PapiGapFrom=8 form is
  accepted too, so pasting a command line from the task works.

  --from    release to compare against, default 8
  --to      release to compare, default whichever JVM this is running on
  --module  default java.base
  --ctsym   default this JVM's lib/ct.sym
  --out     default build/reports/api-gap-<module>-<from>-to-<to>.txt
"""

fun main(arguments: Array<String>) {
    val named = mutableMapOf<String, String>()
    arguments.forEach { argument ->
        // --from=8, -Pfrom=8 and -PapiGapFrom=8 all mean the same thing.
        val body = argument.removePrefix("--").removePrefix("-P")
        val name = body.substringBefore('=').removePrefix("apiGap").replaceFirstChar { it.lowercase() }
        require('=' in body && name.isNotEmpty()) { "not a named argument: $argument\n$USAGE" }
        named[name.lowercase()] = body.substringAfter('=')
    }

    val unknown = named.keys - setOf("from", "to", "module", "ctsym", "out")
    require(unknown.isEmpty()) { "unknown argument(s): $unknown\n$USAGE" }

    fun release(name: String, fallback: Int) = named[name]?.let {
        it.toIntOrNull() ?: throw IllegalArgumentException("--$name wants a release number, got '$it'\n$USAGE")
    } ?: fallback

    val from = release("from", 8)
    val to = release("to", Runtime.version().feature())
    val module = named["module"] ?: "java.base"
    val ctSym = named["ctsym"]?.let(::File)
        ?: ApiIndex.currentJdkCtSym()
        ?: throw IllegalArgumentException("this JVM has no lib/ct.sym, so pass --ctsym\n$USAGE")
    val report = File(
        named["out"] ?: "build/reports/api-gap-$module-$from-to-$to.txt"
    )

    val before = ApiIndex.read(ctSym, from, module)
    check(!before.isEmpty) { "$ctSym has nothing for release $from" }

    // ct.sym carries every release but the current one, so the newest has to be
    // read off the running image, which means running on it.
    val fromCtSym = ApiIndex.read(ctSym, to, module)
    val after = if (!fromCtSym.isEmpty) fromCtSym else ApiIndex.readRunningPlatform(module)
    val source = if (!fromCtSym.isEmpty) "ct.sym" else "the running JVM image"

    check(!after.isEmpty) {
        "$ctSym has nothing for release $to and this JVM is " +
            Runtime.version().feature() + ", so run the task on a JDK $to"
    }
    if (fromCtSym.isEmpty) {
        check(Runtime.version().feature() == to) {
            "release $to is not in $ctSym, so it can only come from a JDK $to, " +
                "but this is a JDK " + Runtime.version().feature()
        }
    }

    // A JVM image holds the module's internals as well, where ct.sym holds only
    // what it exports. Keeping types whose package already existed at the old
    // release drops jdk.internal, sun.* and com.sun.* without a list of them.
    val exported = before.typeNames.mapTo(mutableSetOf()) { it.substringBeforeLast('/') }
    val addedTypes = (after.typeNames - before.typeNames)
        .filter { it.substringBeforeLast('/') in exported }
        .sorted()

    // Only types that existed at both releases. A member of a type the old
    // release never had is not a gap to close, it is a whole type to refuse.
    val shared = after.typeNames.intersect(before.typeNames)

    val handled = mutableListOf<String>()
    val moved = mutableListOf<String>()
    val covariant = mutableListOf<String>()
    val open = sortedMapOf<String, MutableList<String>>()

    shared.forEach { type ->
        (after.declaredMembers(type) - before.declaredMembers(type))
            .filter { '(' in it }                                    // methods, not fields
            .forEach { member ->
            val name = member.substringBefore('(')
            val descriptor = member.substring(name.length)

            when {
                // Declared here now, but reachable at the old release through a
                // supertype. String.chars is the example: new on String in 9,
                // present on CharSequence since 8, and not a gap at all.
                before.hasMember(type, name, descriptor) -> moved += "$type.$member"

                ApiRewrites.forCall(type, name, descriptor) != null -> handled += "$type.$member"

                // Same call, narrower return type. The old one is still there
                // to call, so this is a cast rather than a reimplementation.
                else -> {
                    val older = before.covariantOf(type, name, descriptor)
                    if (older != null) covariant += "$type.$member  was  $older"
                    else open.getOrPut(type) { mutableListOf() } += member
                }
            }
        }
    }

    val openCount = open.values.sumOf { it.size }
    val text = buildString {
        appendLine("# $module, Java $from to Java $to")
        appendLine("# release $to read from $source")
        appendLine()
        appendLine("Types at both releases      ${shared.size}")
        appendLine("Types added since $from        ${addedTypes.size}")
        appendLine("Methods added to those types ${openCount + handled.size + moved.size}")
        appendLine("  rewritten by Lowbyte      ${handled.size}")
        appendLine("  only moved down a hierarchy ${moved.size}")
        appendLine("  only a narrower return type ${covariant.size}")
        appendLine("  left to decide on         $openCount")
        appendLine()
        appendLine("## Left to decide on, by type, most first")
        appendLine()
        open.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
            .forEach { (type, members) ->
                appendLine("$type  (${members.size})")
                members.sorted().forEach { appendLine("    $it") }
                appendLine()
            }
        appendLine("## Only a narrower return type, so a cast to the older call")
        appendLine()
        covariant.sorted().forEach { appendLine("    $it") }
        appendLine()
        appendLine("## Already rewritten")
        appendLine()
        handled.sorted().forEach { appendLine("    $it") }
        appendLine()
        appendLine("## Not new, only moved down a hierarchy")
        appendLine()
        moved.sorted().forEach { appendLine("    $it") }
        appendLine()
        appendLine("## Types that did not exist at $from")
        appendLine()
        addedTypes.forEach { appendLine("    $it") }
    }

    report.parentFile.mkdirs()
    report.writeText(text)

    println("$module Java $from to $to")
    println("  ${openCount + handled.size} methods added to types that already existed")
    println("  ${handled.size} rewritten, $openCount left to decide on")
    println("  ${moved.size} only moved down a hierarchy, ${covariant.size} only a narrower return")
    println("  ${addedTypes.size} types are new outright")
    println("  full report: $report")
}
