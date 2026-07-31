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

Only calls whose observable contract can be kept, into a `lowbyte$api$N` method in the same
class. No runtime library is added.

| Call                         | Since | Rebuilt as                                                        |
|------------------------------|-------|-------------------------------------------------------------------|
| `String.repeat`              | 11    | a builder loop, negative counts rejected                          |
| `Optional.isEmpty`           | 11    | `!isPresent()`                                                    |
| `Optional.orElseThrow()`     | 10    | `get()`, which throws the same exception and message              |
| `List.copyOf`, `Set.copyOf`  | 10    | a snapshot, nulls rejected                                        |
| `Map.copyOf`                 | 10    | a snapshot, null keys and values rejected                         |
| `List.of`, every arity       | 9     | an unmodifiable `ArrayList`, nulls rejected                       |
| `Set.of`, every arity        | 9     | an unmodifiable `LinkedHashSet`, nulls and duplicates rejected    |
| `Map.of`                     | 9     | an unmodifiable `LinkedHashMap`, nulls and repeated keys rejected |
| `Map.ofEntries`              | 9     | the same, from an array of entries                                |
| `Map.entry`                  | 9     | `AbstractMap.SimpleImmutableEntry`, nulls rejected                |
| `Objects.requireNonNullElse` | 9     | the ternary, with the same null check on the default              |

### Details worth knowing

**Every arity.** Past ten elements `List.of` and `Set.of` have no fixed-arity overload
left, so javac calls the varargs one, `of(E[])`. That form is rebuilt too. `Map.of` has no
varargs form at all, capping at ten pairs and making you write `Map.ofEntries` beyond that,
which is rebuilt as well.

**`copyOf` is not `of` twice over.** `Set.of` refuses a repeated element with an
`IllegalArgumentException`. `Set.copyOf` quietly keeps one of them, as its javadoc says it
will. The rebuilds follow each rather than sharing an implementation.

**Iteration order.** `Set.of` and `Map.of` document their order as unspecified, and the JDK
deliberately randomises it per run. The rebuilds settle on insertion order, which stays
inside the contract but will not shuffle. Code that depended on the shuffling was already
relying on something it was told not to.

## What only gets reported

Everything else, as a warning naming the call. Between Java 8 and 21 the JDK gained over
three thousand public members, and most cannot be rebuilt faithfully:

- `String.isBlank` and `trim().isEmpty()` disagree about Unicode whitespace
- `Files.readString` throws where `new String(bytes, UTF_8)` silently substitutes
- `Stream.toList()` is unmodifiable where `Collectors.toList()` is not

Swapping any of them quietly would be worse than saying so.

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
