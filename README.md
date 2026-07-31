# Lowbyte

Lowbyte is a Gradle plugin that downgrades the bytecode version of compiled classes,
so a jar built with a newer JDK can still run on an older JVM.

It does more than rewrite the version in the header: the language features baked into the
bytecode are lowered too, so pattern switches, records, sealed types and the rest keep
working on a JVM that never had them.

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

    // Rebuild calls to JDK APIs the target never had, and warn about the rest.
    // Off by default. See APIs.md
    api.set(false)

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

## Supported versions
Targets Java 8 through 25. Any source version in that range can be downgraded to any
lower version in that range, so a Java 21 project can target Java 17, 11 or 8. Classes
already at or below the target are copied through untouched.

**Java 21 down to 8 is covered**, including pattern switches, records, sealed types,
nestmates, string concatenation and private interface method call sites. See
[LOGIC.md](LOGIC.md) for what each of those involves.

## Things worth knowing

**Modern syntax is fine. Modern APIs are not.** Language features are the whole point:
write records, sealed types and pattern switches, compile them with a current JDK, and
Lowbyte lowers them into something an old JVM runs. The JDK *library* is the one thing it
cannot lower, because no amount of rewriting bytecode adds `List.of` to a Java 8
`java.util.List`.

So a call to a method the target never had, and `List.reversed()` and `Thread.ofVirtual()`
are both Java 21, produces a class that loads and verifies perfectly well at the lower
version, then fails the first time that line actually runs: `NoSuchMethodError` for a
missing method, `NoClassDefFoundError` for a missing class. By default nothing reports it
at build time, so a branch that rarely runs can ship broken and surface much later.

`api.set(true)` is what to reach for. It rebuilds a short list of those calls and warns
about every other JDK member the target did not have. See [APIs.md](APIs.md). The
warnings never fail the build, since a call sitting behind a runtime version check is
perfectly correct.

Note that `--release 8` is *not* the way to get this. It would reject the records and
pattern switches Lowbyte exists to lower, since it pins the language level as well as the
API.

**Signed jars come out unsigned.** A signature covers digests of the entries, so rewriting
a class breaks it. Lowbyte drops the signature block and warns. Re-sign afterwards if you
need it signed.

**Targeting Java 8 drops `module-info.class`**, with a warning, and the jar stops being a
module. There is no class file version at which a module descriptor is both valid and
loadable on Java 8.

**Preview-feature classes cannot be downgraded.** They only load on the exact JDK that
compiled them, so they trip `failOnUnsupported`.

**Exclusions match on name boundaries.** Excluding `com.foo` covers that package and not
`com.foobar` beside it, and excluding a class covers its nested classes.

## Tasks
| Task                        | Description                                                             |
|-----------------------------|-------------------------------------------------------------------------|
| `downgradeBytecode`         | Rewrites every class in the input jar to the target class file version. |
| `regenerateJavac21Fixtures` | Rebuilds the checked-in Java 21 test fixtures. Contributors only.       |
| `verifyOnJava8`             | Runs the downgraded fixtures on a real JDK 8. Contributors only.        |

## Further reading
| Document                           | What is in it                                                        |
|------------------------------------|----------------------------------------------------------------------|
| [LOGIC.md](LOGIC.md)               | How each feature is lowered, and what a downgrade costs you          |
| [APIs.md](APIs.md)                 | The JDK APIs `api.set(true)` rebuilds, and why the list is short     |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Fixtures, the Java 8 verification, and adding a transform or rewrite |

## License
This plugin is licensed under the [MIT License](LICENSE)
