# How Lowbyte works

The header rewrite is the easy half. Most of Lowbyte is the language features a target
release cannot express, each lowered by a `FeatureTransform` in
`src/main/kotlin/dev/iiahmed/lowbyte/transform`.

Transforms are stacked newest-feature-outermost, so whatever one emits still falls through
the older ones beneath it. Every rewritten call site becomes a `private static synthetic`
method in the same class and an `invokestatic`. No feature transform injects a class or
uses reflection.

The one thing that does add a class is the opt-in API rebuild, and only when a jar calls
something it covers. See [APIs.md](APIs.md). Either way nothing outside the jar is
needed at runtime.

For the opt-in JDK API rebuilds, see [APIs.md](APIs.md).

## Feature transforms

| Feature                                                   | Introduced | Status                                                                                   |
|-----------------------------------------------------------|------------|------------------------------------------------------------------------------------------|
| Pattern-matching `switch` (`SwitchBootstraps.typeSwitch`) | 21         | Rewritten to a synthetic static matcher                                                  |
| Enum pattern `switch` (`SwitchBootstraps.enumSwitch`)     | 21         | Rewritten to a synthetic static matcher                                                  |
| Record patterns (JEP 440)                                 | 21         | javac desugars these already; the `java.lang.MatchException` it leaves behind is swapped |
| `sealed` types (`PermittedSubclasses`)                    | 17         | Attribute dropped, which is all sealedness is                                            |
| Records (`Record`, `ObjectMethods`)                       | 16         | Turned into an ordinary final class with generated `equals`/`hashCode`/`toString`        |
| Nestmates (`NestHost`/`NestMembers`)                      | 11         | Attributes dropped and the pre-11 access bridges generated                               |
| `CONSTANT_Dynamic`                                        | 11         | Qualified enum labels are read and lowered, anything else is reported                    |
| `invokedynamic` string concat (`StringConcatFactory`)     | 9          | Rebuilt as a `StringBuilder` chain in a generated static method                          |
| Private interface method call sites                       | 9          | `invokeinterface` corrected to `invokespecial`                                           |

**Java 21 to 8 is covered.**

### Qualified enum labels and `CONSTANT_Dynamic`
javac does emit condy. A qualified enum constant in a pattern switch is not a string label
the way an enum switch's is:

```java
switch (o) {
    case Color.RED -> 1;   // label is a CONSTANT_Dynamic java.lang.Enum$EnumDesc
    ...
}
```

The `typeSwitch` label is an `Enum$EnumDesc` built through `ConstantBootstraps.invoke`,
nested one level deep because the `ClassDesc` handed to `EnumDesc.of` is itself a condy.
Lowbyte reads the enum's name and the constant's name back out of those two constants and
emits a `getstatic` of the field plus an identity comparison. Enum constants are
singletons, so that is the same test the resolved `EnumDesc` would have performed, and it
resolves at link time rather than on first use.

Two consequences worth knowing. Lowering the label drops the last reference to those
constants, so the writer must not inherit the original constant pool: an orphaned condy in
a class file below version 55 is a `ClassFormatError` at load time whether or not anything
still refers to it. Lowbyte therefore rebuilds the pool from what it actually emits.

Condy reached any other way, an `ldc` of one or some other bootstrap argument, is reported
rather than lowered. Lowering it in general is not a rewrite but an evaluation: the
bootstrap would have to run at class-init time into a static field, which is only sound
when it has no side effects and its arguments are themselves representable.
`ConstantBootstraps.invoke` of an arbitrary method handle is neither, so Lowbyte stops the
build instead of guessing. Without that check the class would simply fail to load, since
below Java 11 condy is not a legal constant pool entry at all.

Every rewritten call site gets a `private static synthetic` method in the same class and
the `invokedynamic` turns into an `invokestatic`. Nothing here injects a class or uses
reflection.

### Pattern switches
`lowbyte$typeSwitch$N` (or `lowbyte$enumSwitch$N`) is built out of `instanceof` and
`equals` chains. The two bootstraps differ in one place only: what a `String` label means.
Under `typeSwitch` it is compared to the selector itself, under `enumSwitch` to
`selector.name()`.

### Records
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

### Sealed types
Dropping `PermittedSubclasses` gives up the link-time check, so a class compiled later
against the downgraded jar can extend a type that was sealed. Source still cannot do it
without recompiling against the original.

### String concatenation
Since Java 9 javac compiles `a + b` to an `invokedynamic` against
`java.lang.invoke.StringConcatFactory`, which does not exist on 8, so the call site dies
with a `BootstrapMethodError` the first time it runs. Lowbyte rebuilds the `StringBuilder`
chain javac used to emit, in a `lowbyte$concat$N` method.

It goes in a generated method rather than inline for a mechanical reason: by the time the
`invokedynamic` is reached the operands are already on the stack, and a `StringBuilder`
chain needs its `new`/`dup` *before* them. A static call consumes exactly the operands
sitting there, so nothing has to be reordered.

The bootstrap's recipe marks an argument with U+0001 and a constant with U+0002, and
everything else is literal text. Constants exist only so that one of those two characters
appearing in your source cannot be mistaken for a marker; both are known at rewrite time,
so they are folded together into one run of text.

### Private interface methods
`ACC_PRIVATE` on an interface method has been legal since class file 52, so the
declaration needs nothing and a Java 8 JVM loads it happily. The *call* is the problem:
javac emits `invokeinterface`, and Java 8 answers

```
IncompatibleClassChangeError: private interface method requires invokespecial,
not invokeinterface
```

A private method is not virtual, so only the opcode moves and dispatch is unchanged. The
same correction is needed on the method handle behind a lambda declared in an interface,
whose body is itself a private interface method; missing that leaves a
`BootstrapMethodError` at link time rather than a failure at load time.

### Nestmates
Since Java 11 a class may read a private field of another class in its nest directly, and
javac emits exactly that. The permission comes from `NestHost` and `NestMembers`, so
dropping them turns every such access into an `IllegalAccessError`.

Each reached member therefore gets a package-private `static lowbyte$access$N` accessor on
the class that *owns* it, and the call site invokes that instead. Package-private is
enough, because a nest is a top-level class plus its nested classes and those always share
a package. This is what javac itself did before nestmates existed.

Constructors work differently. `new Foo(...)` compiles to `new`, `dup`, arguments,
`invokespecial`, and the `new` can sit arbitrarily far from the `invokespecial`, so
swapping in a static factory would mean pairing them back up by dataflow. Instead the
owner gains a package-private constructor overload taking one extra argument of an
otherwise empty generated type, `Foo$lowbyte$Nest`, and the call site passes null. Those
marker classes are the only entries in the output jar that did not come from an entry in
the input. javac 8 did the same thing with an anonymous class.

This is also the one transform that cannot work from a single class file. A call site
names an owner, a name and a descriptor, but never the access flags, so whether
`Outer.secret` needs a bridge is a fact about a *different* class. Lowbyte therefore reads
every class in the jar before writing any of it, and if a bridge is ever owed by a class
that was excluded or absent, that is reported rather than shipped as a
`NoSuchMethodError`.

## Jar-level behaviour

### Module descriptors
`module-info.class` is lowered like anything else, down to Java 9. It has to be: the
runtime version-checks a module descriptor the same way it checks a class, so one left at
21 in a jar targeting 11 makes that jar unusable on the module path of the very JVM it was
downgraded for.

Targeting Java 8 the root descriptor is **dropped**, with a warning, and the jar stops
being a module. No version helps: left high, anything scanning the jar gets an
`UnsupportedClassVersionError`, and lowered to 8 it gets a `ClassFormatError`, because
`CONSTANT_Module` does not exist before class file 53. To a JVM a module descriptor is
still a class file, and walking every `.class` entry and defining it is ordinary behaviour
for component scanners and shading tools, so the entry is a hazard however it is written.

A copy under `META-INF/versions/` is kept instead of dropped, since Java 8 never resolves
a class name out of that directory and the copy still describes the module for the newer
JVMs that read it.

### Signed jars
A signature covers digests of the entries, so rewriting a class breaks it. Lowbyte drops
the signature block (`META-INF/*.SF`, `*.RSA`, `*.DSA`, `*.EC`, `SIG-*`) and strips the
per-entry digests from `META-INF/MANIFEST.MF`, leaving every other manifest attribute
alone, then warns that it did so. The output is an unsigned jar rather than one that fails
verification, which is the better of the two.

### Exclusions
`excludedClasses` matches on name boundaries, not as a bare prefix. Excluding `com.foo`
covers that package and not `com.foobar` beside it, and excluding a class covers the nested
classes that belong to it. Dots and slashes are both accepted.

### Preview classes
Preview-feature classes carry minor version `0xFFFF` and only load on the exact JDK that
compiled them, so no amount of header rewriting makes them portable. They trip
`failOnUnsupported`.

### The constant pool
The writer does not inherit the original constant pool. Doing so keeps entries nothing
refers to any more, which is fatal rather than merely wasteful: lowering a pattern switch
drops the last reference to javac's `CONSTANT_Dynamic` labels, and a leftover one in a
class file below version 55 is a `ClassFormatError` at load time whether or not anything
still points at it. The pool is rebuilt from what is actually emitted.
