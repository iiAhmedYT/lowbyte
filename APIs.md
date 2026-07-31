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

| Call                         | Since | Rebuilt as                                                        |
|------------------------------|-------|-------------------------------------------------------------------|
| `Optional.isEmpty`           | 11    | `!isPresent()`                                                    |
| `Stream.ofNullable`          | 9     | `t == null ? Stream.empty() : Stream.of(t)`, its own body         |
| `Optional.orElseThrow()`     | 10    | `get()`, which throws the same exception and message              |
| `List.copyOf`, `Set.copyOf`  | 10    | a snapshot, nulls rejected                                        |
| `List.of`, every arity       | 9     | an unmodifiable `ArrayList`, nulls rejected                       |
| `Set.of`, every arity        | 9     | an unmodifiable `LinkedHashSet`, nulls and duplicates rejected    |
| `Map.entry`                  | 9     | `AbstractMap.SimpleImmutableEntry`, nulls rejected                |

### Details worth knowing

**Every arity.** Past ten elements `List.of` and `Set.of` have no fixed-arity overload
left, so javac calls the varargs one, `of(E[])`. That form is rebuilt too. `Map.of` has no
varargs form at all, capping at ten pairs and making you write `Map.ofEntries` beyond that,
which is handled on the utility.

**Every `Map` factory is on the utility.** `Map.of` has eleven overloads, one per arity up
to ten pairs, and each wants two null checks per pair plus a repeated-key check across the
lot. `ofEntries` and `copyOf` want the same walk. All of it is loops, so all of it is
written as Java. `Map.entry` is the exception and stays inline, being one allocation.

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

| Call                         | Since | Why it is here rather than inline                                    |
|------------------------------|-------|----------------------------------------------------------------------|
| `String.isBlank`             | 11    | `Character.isWhitespace` over code points                            |
| `String.strip`               | 11    | the same, from both ends                                             |
| `String.stripLeading`        | 11    | the front half of it                                                 |
| `String.stripTrailing`       | 11    | the back half                                                        |
| `String.repeat`              | 11    | a counted loop, negative counts rejected                             |
| `String.lines`               | 11    | LF, CR and CRLF, with a trailing terminator adding no line           |
| `String.indent`              | 12    | a walk over lines, re-terminating each one                           |
| `String.stripIndent`         | 13    | the common indent, then a second pass to remove it                   |
| `String.transform`           | 12    | nothing complicated, but the receiver has to move to a parameter     |
| `String.formatted`           | 13    | `String.format` with the receiver as the format                      |
| `String.translateEscapes`    | 13    | a decoder, octal escapes and line continuations included             |
| `Objects.requireNonNullElse` | 9     | the fallback is null-checked too, under the JDK's own parameter name |
| `Map.of`                     | 9     | eleven overloads, two null checks a pair, one key check              |
| `Map.ofEntries`              | 9     | the same walk, over an array of entries                              |
| `Map.copyOf`                 | 10    | the same walk again, with no duplicate to refuse                     |

`String.stripIndent`, `formatted` and `translateEscapes` had an unusual run: deprecated for
removal in 13, behind preview in 14, settled in 15. Thirteen is the lowest release where
they exist at all, so that is where replacing them stops.

Text blocks do not produce calls to any of these. javac strips a text block's indentation
and translates its escapes at compile time, so it reaches the class file as an ordinary
string constant. Only an explicit call is left to rewrite.

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

- `Files.readString` throws where `new String(bytes, UTF_8)` silently substitutes
- `Stream.toList()` is unmodifiable where `Collectors.toList()` is not
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
