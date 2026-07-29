# Lowbyte

Lowbyte is a Gradle plugin that downgrades the bytecode version of compiled classes,
so a jar built with a newer JDK can still run on an older JVM.

## ➕ Add to your project
Add this repo to your plugin repositories:
```kt
pluginManagement {
    repositories {
        gradlePluginPortal() // Incase you use other plugins
        maven("https://repo.gravemc.net/releases") // GraveMC's Maven Repository
    }
}
```

and then add this to your plugins block:
```kt
plugins {
    id("dev.iiahmed.lowbyte") version "1.0.0"
}
```

## 🧑‍💻 Usage
In your `build.gradle.kts` file, After you add the plugin, you can use the `lowbyte` block to configure it:

```kt
lowbyte {
    // The Java release to downgrade the bytecode to
    targetJavaVersion.set(8)

    // OPTIONAL:
    // Classes/packages that should be left untouched
    excludedClasses.set(listOf(
        "dev/iiahmed/example/modern"
    ))

    // Fail the build when a class can't be downgraded. Either way the whole jar
    // is scanned first, so one run lists every problem rather than the first one.
    failOnUnsupported.set(true)

    // Set the pattern for the jar file (starting from the `build` directory)
    jarFilePattern.set("libs/${project.name}-${project.version}.jar") // The default is "libs/${project.name}.jar"
}
```

Running `build` produces `build/libs/<project>-downgraded.jar`, which is also exposed
through the `lowbyte` configuration for consumption by other projects.

### Running after shadowJar
Lowbyte reads the thin `jar` by default, so if you shade you need to point it at the
shaded output and add the task dependency yourself:

```kt
lowbyte {
    targetJavaVersion.set(17)
    jarFilePattern.set("libs/MyPlugin-${project.version}.jar")
}

tasks.named("downgradeBytecode") {
    dependsOn(tasks.named("shadowJar"))
}
```

The `dependsOn` is not optional. Without it the task can run before the shaded jar
exists.

## Supported Versions
Targets Java 8 through 25. Any source version in that range can be downgraded to any
lower version in that range, so a Java 21 project can target Java 17, 11 or 8.
Classes already at or below the target are copied through untouched.

Preview-feature classes (minor version `0xFFFF`) cannot be downgraded, since they only
load on the exact JDK that compiled them. They trip `failOnUnsupported`.

### Feature transforms
Beyond the header rewrite, Lowbyte rewrites language features the target cannot express.

| Feature                                                   | Introduced | Status                                                                                   |
|-----------------------------------------------------------|------------|------------------------------------------------------------------------------------------|
| Pattern-matching `switch` (`SwitchBootstraps.typeSwitch`) | 21         | Rewritten to a synthetic static matcher                                                  |
| Enum pattern `switch` (`SwitchBootstraps.enumSwitch`)     | 21         | Rewritten to a synthetic static matcher                                                  |
| Record patterns (JEP 440)                                 | 21         | javac desugars these already; the `java.lang.MatchException` it leaves behind is swapped |
| `sealed` types (`PermittedSubclasses`)                    | 17         | Attribute dropped, which is all sealedness is                                            |
| Records (`Record`, `ObjectMethods`)                       | 16         | Turned into an ordinary final class with generated `equals`/`hashCode`/`toString`        |
| Nestmates (`NestHost`/`NestMembers`)                      | 11         | Not yet handled                                                                          |
| `invokedynamic` string concat, private interface methods  | 9          | Not yet handled                                                                          |

**Java 21 to 11 is covered.** Lower targets are not. Going below 11 still needs the
nestmate work, and below 9 the indified string concat and private interface methods.
`CONSTANT_Dynamic` is not on the list because javac does not emit it.

Every rewritten call site gets a `private static synthetic` method in the same class and
the `invokedynamic` turns into an `invokestatic`. No runtime class is injected and nothing
uses reflection, so the jar stays self-contained.

#### Pattern switches
`lowbyte$typeSwitch$N` (or `lowbyte$enumSwitch$N`) is built out of `instanceof` and
`equals` chains. The two bootstraps differ in one place only: what a `String` label means.
Under `typeSwitch` it is compared to the selector itself, under `enumSwitch` to
`selector.name()`.

#### Records
The `Record` attribute goes, `java.lang.Record` becomes `java.lang.Object`, and each
`ObjectMethods` call site becomes `lowbyte$recordToString$N`, `lowbyte$recordHashCode$N`
or `lowbyte$recordEquals$N`. The accessors, fields and canonical constructor javac already
emitted need no help.

The generated bodies match the bootstrap rather than the obvious implementation:
`float` and `double` components compare bitwise, so `NaN` equals itself and `0.0` does not
equal `-0.0`; `hashCode` folds `31 * result + hash(component)` from zero; `toString`
formats as `SimpleName[a=1, b=2]`.

What a downgraded record loses is its reflective identity. `Class.isRecord()` returns
false and `getRecordComponents()` returns null, so anything reading a record generically
at runtime, serialization frameworks especially, will no longer recognise it. Record
patterns already compiled into the jar are unaffected, since javac desugared those into
accessor calls before Lowbyte saw them.

A user's `instanceof Record` becomes `instanceof Object` and stops being a meaningful
test. There is no pre-16 type that would answer it correctly.

#### Sealed types
Dropping `PermittedSubclasses` gives up the link-time check, so a class compiled later
against the downgraded jar can extend a type that was sealed. Source still cannot do it
without recompiling against the original.

Correctness is pinned by a differential test. `src/test/resources/javac21` holds real
javac 21 output, and the expected values are what those samples printed running on an
actual JDK 21, with the bootstraps linked by the JDK itself. The same classes are
downgraded to both 17 and 11 and have to print the same thing either way, so the generated
code is checked against real bootstrap behaviour rather than against a reading of the spec.

#### Regenerating the fixtures
Only the `*.java.txt` sources in `src/test/resources/javac21` are hand-written. Everything
else there is generated:

```sh
./gradlew regenerateJavac21Fixtures
```

That compiles and runs each sample on a JDK 21 toolchain and rewrites `<Name>.classdata`
(the compiled classes), `<Name>.classes.txt` (which classes belong to the sample) and
`<Name>.baseline.txt` (its output on real Java 21). Add a case to a sample, or a whole new
`.java.txt`, then run it. The tests pick the new fixtures up with no code change.

`toolchain.txt` records which JDK produced the current generation. That matters, because
javac's desugaring is not identical across builds of the same release, so regenerating on
another machine can legitimately change the class files. It is also why the tests assert
*behaviour* against the recorded baseline instead of pinning instruction counts.

### What a bytecode downgrade cannot do
Lowbyte rewrites bytecode, not API usage. Code calling a JDK method that does not exist on
the target, say `List.reversed()` or `Thread.ofVirtual()`, produces a class that verifies
fine at the lower version and then throws `NoSuchMethodError` at runtime. Lowbyte does not
detect this and `failOnUnsupported` will not catch it.

Compile against the target's API (`--release 17`, or a matching toolchain) and use Lowbyte
only to lower the class file version and the language features baked into it.
`JdkApiLimitationTest` pins this behaviour so it stays visible.

There is one exception: a JDK *class* that javac references on its own. For record and
sealed pattern switches that is `java.lang.MatchException` (Java 21), which gets remapped
to `java.lang.IllegalStateException`. That is also a `RuntimeException` and has the same
`(String, Throwable)` constructor javac calls. It is only thrown from the impossible branch
of an exhaustive switch, so the change of type is observable just when that branch is
reached, which happens if a sealed hierarchy is recompiled without its switches.

## Tasks
| Task                        | Description                                                             |
|-----------------------------|-------------------------------------------------------------------------|
| `downgradeBytecode`         | Rewrites every class in the input jar to the target class file version. |
| `regenerateJavac21Fixtures` | Rebuilds the checked-in Java 21 test fixtures. Contributors only.       |

## License
This plugin is licensed under the [MIT License](LICENSE)
