# Transformed APIs

Lowbyte rewrites bytecode, not API usage. By default a call to a JDK method the target
release never had sails straight through, verifies fine, and then throws
`NoSuchMethodError` the first time it runs.

`api.set(true)` changes that. It is off by default, because it is the one part of Lowbyte
that touches a call the target could have linked perfectly well: everything else corrects
bytecode that would not verify or would not load.

```kt
lowbyte {
    targetJavaVersion.set(8)
    api.set(true)
}
```

This is the gap a bytecode downgrade cannot close on its own. Language features are lowered
into something the old JVM runs; the JDK library cannot be, because rewriting bytecode does
not add `List.of` to a Java 8 `java.util.List`.

Reaching for `--release 8` instead would not help: it pins the language level too, and
would reject the records and pattern switches Lowbyte is there to lower.

## What gets rebuilt

Only calls whose observable contract can be kept. These go into a `lowbyte$api$N` method in
the same class, with nothing added to the jar; the ones that need more than that are in
[the injected utility](#the-injected-utility) below.

| Call                     | Since | Rebuilt as                                                           |
|--------------------------|-------|----------------------------------------------------------------------|
| `Optional.isEmpty`       | 11    | `!isPresent()`                                                       |
| `Predicate.not`          | 11    | `negate()`, which is its own body and a Java 8 default method        |
| `Path.of`                | 11    | `Paths.get`, the same factory under a newer name, both overloads     |
| `Stream.ofNullable`      | 9     | `t == null ? Stream.empty() : Stream.of(t)`, its own body            |
| `Optional.orElseThrow()` | 10    | `get()`, which throws the same exception and message                 |
| `Optional.stream`        | 9     | `isPresent() ? Stream.of(get()) : Stream.empty()`, its own body      |
| `Stream.toList`          | 16    | `unmodifiableList(new ArrayList<>(asList(toArray())))`, its own body |
| `OptionalInt.stream`     | 9     | the same, over `IntStream`                                           |
| `OptionalLong.stream`    | 9     | the same, over `LongStream`                                          |
| `OptionalDouble.stream`  | 9     | the same, over `DoubleStream`                                        |
| `Map.entry`              | 9     | `AbstractMap.SimpleImmutableEntry`, nulls rejected                   |

### Details worth knowing

**The collection factories are all on the utility.** Every one of them walks its elements
checking for null, and `Set.of` and `Map.of` check for a repeat on top of that. That is a
loop, and a loop is worth writing as Java. `Map.entry` is the exception that stays inline,
being one allocation and two null checks.

**Every arity.** `List.of` and `Set.of` have eleven fixed-arity overloads each, plus the
varargs `of(E[])` javac calls past ten elements. Each is a separate descriptor and so a
separate entry on the utility, and all twelve are covered. `Map.of` has no varargs form at
all, capping at ten pairs and making you write `Map.ofEntries` beyond that.

**`copyOf` is not `of` twice over.** `Set.of` refuses a repeated element with an
`IllegalArgumentException`. `Set.copyOf` quietly keeps one of them, as its javadoc says it
will. `Map.copyOf` has nothing to refuse at all, since a map cannot hold a repeated key.
Each follows its own contract rather than sharing an implementation.

**Iteration order.** `Set.of` and `Map.of` document their order as unspecified, and the JDK
deliberately randomises it per run. The rebuilds settle on insertion order, which stays
inside the contract but will not shuffle. Code that depended on the shuffling was already
relying on something it was told not to.

## The injected utility

Some calls cannot be rebuilt as a generated method at all. `String.isBlank` and `strip` go
by `Character.isWhitespace`, while `trim` cuts everything at or below U+0020 and nothing
above it, so the two disagree on U+00A0, which is not whitespace, and on U+2028, which is.
Getting that right means a loop over code points, and writing loops as hand-emitted
bytecode with hand-written stack frames is how mistakes happen.

So those live in a small utility class, written as ordinary Java in `src/runtime`, compiled
at `--release 8`, and carried inside the plugin. When a jar calls one of them, the class is
copied into that jar and the call site is pointed at it.

| Call                            | Since | Why it is here rather than inline                                       |
|---------------------------------|-------|-------------------------------------------------------------------------|
| `String.isBlank`                | 11    | `Character.isWhitespace` over code points                               |
| `String.strip`                  | 11    | the same, from both ends                                                |
| `String.stripLeading`           | 11    | the front half of it                                                    |
| `String.stripTrailing`          | 11    | the back half                                                           |
| `String.repeat`                 | 11    | a counted loop, negative counts rejected                                |
| `String.lines`                  | 11    | LF, CR and CRLF, with a trailing terminator adding no line              |
| `String.indent`                 | 12    | a walk over lines, re-terminating each one                              |
| `String.stripIndent`            | 13    | the common indent, then a second pass to remove it                      |
| `String.transform`              | 12    | nothing complicated, but the receiver has to move to a parameter        |
| `String.formatted`              | 13    | `String.format` with the receiver as the format                         |
| `String.translateEscapes`       | 13    | a decoder, octal escapes and line continuations included                |
| `Objects.requireNonNullElse`    | 9     | the fallback is null-checked too, under the JDK's own parameter name    |
| `Objects.checkIndex`            | 9     | the message is part of the promise, and building one is a concatenation |
| `Objects.checkFromToIndex`      | 9     | the same, as a half-open range                                          |
| `Objects.checkFromIndexSize`    | 9     | the same, and a comparison written so it cannot overflow                |
| `List.of`, every arity          | 9     | twelve overloads, a null check per element                              |
| `Set.of`, every arity           | 9     | the same, plus a repeated element refused                               |
| `List.copyOf`                   | 10    | a snapshot, null collection and null elements refused                   |
| `Set.copyOf`                    | 10    | the same, but a repeat is kept rather than refused                      |
| `Map.of`                        | 9     | eleven overloads, two null checks a pair, one key check                 |
| `Map.ofEntries`                 | 9     | the same walk, over an array of entries                                 |
| `Map.copyOf`                    | 10    | the same walk again, with no duplicate to refuse                        |
| `Collectors.toUnmodifiableList` | 10    | a `Collector`, and the Java 8 one underneath accepts nulls              |
| `Collectors.toUnmodifiableSet`  | 10    | the same                                                                |
| `Collectors.toUnmodifiableMap`  | 10    | the same, both overloads                                                |
| `Files.readString`              | 11    | a strict decoder, so malformed input throws instead of substituting     |
| `Files.writeString`             | 11    | the same going out, so an unmappable character throws                   |
| `Files.mismatch`                | 12    | a block-at-a-time comparison, so a loop                                 |
| `Reader.transferTo`             | 10    | a loop over `read` and `write`, returning the count                     |

**The bounds checks return their index**, so they read inside an expression rather than
above one, and all three throw `IndexOutOfBoundsException`. Only the message tells them
apart, so the messages are reproduced exactly. `checkFromIndexSize` compares against
`length - fromIndex` rather than summing, because `fromIndex + size` overflows and a naive
sum silently accepts a range that runs off the end. The `long` overloads are Java 16 and
are reported rather than rewritten.

**The `toUnmodifiable` collectors are not `collectingAndThen`.** The obvious swap,
`collectingAndThen(toSet(), Collections::unmodifiableSet)`, is wrong: `toSet`, `toList` and
`toMap` all take a null happily and the Java 10 collectors refuse one. A naive rewrite
would put nulls into collections documented to reject them. The replacements add the check
back, once in the finisher rather than per element.

`String.stripIndent`, `formatted` and `translateEscapes` had an unusual run: deprecated for
removal in 13, behind preview in 14, settled in 15. Thirteen is the lowest release where
they exist at all, so that is where replacing them stops.

Text blocks do not produce calls to any of these. javac strips a text block's indentation
and translates its escapes at compile time, so it reaches the class file as an ordinary
string constant. Only an explicit call is left to rewrite.

### Where the rebuilds are not bit-identical

Inside the contract, but worth knowing if you are transforming a jar whose callers might
lean on the difference.

**Probing with null.** `List.of("a").contains(null)` throws `NullPointerException` on a real
Java 9+. The rebuild returns `false`, because it is a `Collections.unmodifiable*` view over
an ordinary collection. The same goes for `indexOf`, `containsKey` and `containsValue`. Both
are legal: `Collection.contains` documents that `NullPointerException` is optional. Code
that probes an immutable collection with null was relying on the optional half.

**`Stream.toList` copies once, not twice.** The JDK's body wraps the array in a
`new ArrayList<>(...)` before making it unmodifiable, which defends against a hand-written
`Stream` that returns an array it keeps a reference to. The rebuild skips that copy, so such
a `Stream` can mutate the returned list afterwards. Implementing `Stream` by hand takes
about forty-five methods, against two for a `Collection`, so this is a far narrower door
than the one `List.copyOf` guards, and the second copy costs more than half the runtime.
Streams built the normal way, from a collection or `StreamSupport`, are unaffected.

**`Reader.transferTo` is rewritten and `InputStream.transferTo` is not.** Not because of
runtime dispatch: on Java 8 none of these methods exist, so there is no override to lose.
The difference is how many implementations the JDK ships. `ByteArrayInputStream` and
`FileInputStream` specialise the `InputStream` ones, and one generic replacement cannot be
all of them at once, which is measurable: `readNBytes(b, 0, 5)` gives 1 on a real Java 21
and 5 through a rewrite. `Reader.transferTo` is declared once and inherited everywhere, so
one replacement is all of it.

**What a rewrite does cost is your own override.** A rewrite is a static call, so this loses
one:

```java
class MyReader extends Reader { @Override public long transferTo(Writer w) { ... } }

Reader r = new MyReader();   // declared Reader, so the owner is java/io/Reader
r.transferTo(w);             // rewritten, and the override does not run
```

Only that shape. javac writes the declared type as the owner, so `MyReader r` or
`BufferedReader r` are not matched at all and are reported instead.

**U+180E is whitespace on Java 8 and not on Java 9 onwards.** Unicode 6.3 reclassified
MONGOLIAN VOWEL SEPARATOR from a space separator to a format character, and Java 8 predates
that. Every replacement here goes through `Character.isWhitespace`, so at target 8
`isBlank`, `strip`, `stripLeading`, `stripTrailing`, `indent` and `stripIndent` all treat
that one character as whitespace where the original did not. A sweep of all 1,114,112 code
points says it is the only disagreement between the two, and targets 9 and above already
have the newer data.

This is the target JVM's Unicode table, not a rewrite getting it wrong:
`Character.isWhitespace(0x180E)` is already `true` on Java 8 and `false` on Java 9,
whatever Lowbyte does. It is recorded because a downgraded jar behaving differently from the
original is the one thing this project exists to prevent, and `verifyOnJava8` does catch it
if a sample ever contains that character.

**`String.lines` is lazy**, as the JDK's is, and reports the same
`ORDERED | NONNULL | IMMUTABLE`. It carries a nested `Spliterator` class, injected alongside
the utility, which is why `findFirst` on a large string scans one line rather than all of
them.

**Identity and serialized form.** `List.copyOf` of an already-immutable list returns a fresh
copy rather than the same instance, which the spec permits, and the serialized form of every
rebuilt collection is the wrapper's rather than the JDK's.

What you get:

- **Nothing is injected unless it is used.** No call, no class.
- **Only the methods used.** The copy is trimmed to what that jar actually reached for.
- **No annotations.** The `@LowbyteInfo` markers the plugin reads are stripped on the way in.
- **Still self-contained.** The class lands *in* your jar; nothing external is required.

### Its name

The default is content-addressed: `dev/iiahmed/lowbyte/runtime/LowbyteApi_<digest>`, where
the digest covers the utility's own bytes and the methods kept from it.

That matters because the class is trimmed. Two jars built by the same Lowbyte can hold
different methods, so a fixed name would let shading them together drop the ones only one
jar had. With the digest, same methods means same name *and* identical bytes, which is a
harmless duplicate; different methods means different names and both survive.

Override it if you relocate:

```kt
lowbyte {
    api.set(true)
    runtimeClass.set("com.example.shaded.LowbyteApi")
}
```

Then keeping it distinct is your problem.

## What only gets reported

Everything not in either table above, as a warning naming the call. Between Java 8 and 21
the JDK gained over three thousand public members, and most have no faithful replacement:

- `Files.newDirectoryStream` filters differ per provider, so a rebuild would guess
- `Stream.iterate(seed, hasNext, next)` needs a stateful `Spliterator`, not an expression
- `InputStream.readAllBytes`, `readNBytes` and `transferTo` are overridable, and
  `ByteArrayInputStream` overrides all three. A rewrite forwards to one fixed implementation,
  so the override would never run: measured, `readNBytes(b, 0, 5)` returns 1 on a real Java 21
  and 5 through a rewrite
- `Stream.takeWhile` needs a stateful `Spliterator`, not an expression
- `String.describeConstable` and `resolveConstantDesc` return `java.lang.constant` types,
  and that package does not exist before 12 for any replacement to hand back

Those two are the whole of what `String` gained between 8 and 21 and did not get covered.
`chars` and `codePoints` look like a gap and are not: `String` only declared them in 9, but
`CharSequence` has had them as default methods since 8, and `invokevirtual` resolution
finds them there.

Swapping any of them quietly would be worse than saying so. Some are only waiting for
someone to write them into the utility, which is where the ones needing real code go.

API findings are **always warnings**, whatever `failOnUnsupported` says. A call into a
newer JDK often sits behind a runtime version check, in which case the reference really is
in the bytecode, the code really is correct, and it is never reached. Failing that build
would be wrong.

## Where the answers come from

The two halves lean on different things.

A **rebuild** knows which release its API arrived in, so it needs the target and nothing
else.

**Reporting** is open-ended, since anything in the JDK might be missing. For that Lowbyte
reads `lib/ct.sym` from the JDK running Gradle: the same file `javac --release` consults,
holding what each release actually had. No list of APIs is maintained in this repo.

That split matters when the build JDK ships no usable `ct.sym` for the target. The rebuilds
carry on; only the reporting goes quiet, with a warning saying so.

Reading `ct.sym` means walking supertypes, not just the named class. A `.sig` file lists
what its type *declares*, so `LinkedHashSet` has no `add` of its own and `ArrayList` no
`hashCode`. Asking the named type alone reported half the ordinary calls in a program as
missing.

## Adding one

See [CONTRIBUTING.md](CONTRIBUTING.md#adding-an-api-rewrite). Each rebuild is a class under
`api/rewrite` listed in `ApiRewrites.ALL`, and it is a class and a line.
