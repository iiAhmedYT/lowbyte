# Contributing

## How correctness is established here

Lowbyte generates bytecode that has to behave exactly like what the JDK would have done.
Reading the spec and believing yourself is not enough, so nothing in this project is
asserted from first principles when it can be measured instead.

**Differential testing.** `src/test/resources/javac21` holds real javac 21 output plus what
each sample printed running on an actual JDK 21, with the bootstraps linked by the JDK
itself. The same classes are downgraded to 17, 11, 9 and 8 and have to print the same thing
every time. Each target adds a layer: 17 lowers the switches, 11 also lowers records and
sealed types, 9 unpicks the nests, 8 rebuilds string concatenation and fixes the private
interface call sites.

That is why the tests assert *behaviour* against a recorded baseline rather than pinning
instruction counts: javac's desugaring is not identical across builds of the same release.

**Mutation checking.** Before trusting a test, break the thing it covers and watch it fail.
Several tests in this repo exist because a mutation slipped through and showed the coverage
was not where it looked.

**Run it on the real JVM.** See below. This is not optional for anything below Java 9.

## Regenerating the fixtures

Only the `*.java.txt` sources in `src/test/resources/javac21` are hand-written. Everything
else there is generated:

```sh
./gradlew regenerateJavac21Fixtures
```

That compiles and runs each sample on a JDK 21 toolchain and rewrites `<Name>.classdata`
(the compiled classes), `<Name>.classes.txt` (which classes belong to the sample) and
`<Name>.baseline.txt` (its output on real Java 21). Add a case to a sample, or a whole new
`.java.txt`, then run it. The tests pick the new fixtures up with no code change.

`toolchain.txt` records which JDK produced the current generation. Regenerating on another
machine can legitimately change the class files, so that shows up in the diff instead of
catching someone out.

A sample must be deterministic. Identity hash codes, `Set.of` iteration order and anything
else the JDK is free to vary have to be masked, sorted, or printed as character codes.

## Verifying on a real Java 8

```sh
./gradlew verifyOnJava8
```

The unit tests run on the Java 17 toolchain, which links `StringConcatFactory` and accepts
`invokeinterface` on a private method without complaint. **A downgrade that would die on
Java 8 passes them in silence.** This task runs every sample downgraded to 8 on an actual
JDK 8 launcher and compares it to the same Java 21 baseline.

It needs a JDK 8 toolchain, so it is contributors-only. Run it for anything touching a
target below 9. It is how the missing rewrite of the method handle behind a lambda in an
interface was found: every in-process test passed.

## Adding a feature transform

A transform lowers one language feature the target cannot express.

1. Implement `FeatureTransform` in `transform/`. `introducedIn` is the release the feature
   arrived in; the transform only runs when the target is below it.
2. Add it to `FeatureTransforms.ALL`. Order within a release matters, since the list is
   folded outermost-first. The comments in that file say which way round.
3. Add a `.java.txt` sample exercising it and regenerate the fixtures.
4. Add the target to `Javac21DowngradeTest.TARGETS` if the feature needs a lower one.

Frames are written by hand. `COMPUTE_FRAMES` would make ASM load the classes being
processed, and those are not on the plugin's classpath, so generated code either stays
simple enough for `Bytecode.sameFrame` or spells its frames out in full. Keeping the
operand stack empty at every branch target is what makes that possible, so park values in a
local rather than leaving them on the stack across a jump.

Anything that cannot be lowered must call `onUnsupported` and leave the construct alone.
Emitting something that will not run is worse than refusing to.

## Adding an API rewrite

An API rewrite rebuilds one JDK call out of things an older release already had. See
[APIs.md](APIs.md) for what is there and why the list is short.

1. Write it in `api/rewrite/`, one file per JDK type: `ListRewrites.kt`, `MapRewrites.kt`,
   `StringRewrites.kt` and so on.
2. Extend `InlineRewrite` when the replacement is a few instructions, or `RuntimeRewrite`
   when it wants a loop, a decoder or a helper type. See below.
3. Add it to `ApiRewrites.ALL`.
4. Add cases to `ApiConversionSample.java.txt`, or `RuntimeApiSample.java.txt` for a
   `RuntimeRewrite`, and regenerate.

An `InlineRewrite` writes a method into the calling class. A `RuntimeRewrite` names a
method on the injected utility and needs nothing else: owner, descriptor and release all
come from that method's `@LowbyteInfo`, so there is nothing in Kotlin to fall out of step
with the Java.

`introducedIn` is the release the API arrived in, and it is what decides whether a call is
rebuilt. The `ct.sym` index is not consulted, so rewrites keep working on a JDK that
cannot supply one. Add the release to `ApiIndexTest.everyRebuiltApiArrivedWhenTheTransformSaysItDid`,
which checks it against `ct.sym` in both directions so a wrong number cannot sit unnoticed.

Only add a rewrite whose observable contract matches. If the nearest older equivalent
differs on nulls, duplicates, mutability, ordering or exceptions, leave it to be reported
instead. `ApiSlots` computes where a rewrite's locals go; do not hardcode a slot, because
the arguments below it differ per call.

Test the boundary at a target *between* releases. Everything is below 9 at target 8, so
target 8 alone cannot tell a correct release from a wrong one.

## Adding a utility method

For calls that cannot be rebuilt as a generated method, meaning anything wanting a loop, a
decoder or a helper type, write it as Java in `src/runtime` instead.

```java
@LowbyteInfo(owner = "java/lang/String", name = "isBlank", descriptor = "()Z",
    introducedIn = 11, instance = true)
public static boolean isBlank(String value) {
    return value.codePoints().allMatch(Character::isWhitespace);
}
```

That source set compiles at `--release 8`, so the method has to be Java 8 code, and the
result loads on every target Lowbyte supports. The annotation is what the plugin matches
call sites against, so the utility describes itself and there is no second list to keep in
step. `instance = true` means the receiver becomes the first parameter, which is already
the order the operands sit in at the call site.

Prefer an inline rewrite when the replacement is a couple of instructions, like
`Optional.isEmpty`. The utility is for the ones that are not.

Add cases to `RuntimeApiSample.java.txt`, regenerate, and run `verifyOnJava8`.

## Tasks

| Task                        | Description                                                             |
|-----------------------------|-------------------------------------------------------------------------|
| `downgradeBytecode`         | Rewrites every class in the input jar to the target class file version. |
| `regenerateJavac21Fixtures` | Rebuilds the checked-in Java 21 test fixtures. Contributors only.       |
| `verifyOnJava8`             | Runs the downgraded fixtures on a real JDK 8. Contributors only.        |
