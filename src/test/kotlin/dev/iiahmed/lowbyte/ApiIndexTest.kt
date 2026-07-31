package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiIndex
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The index against APIs whose introducing release is already known, each one
 * confirmed by compiling it with `javac --release`.
 */
class ApiIndexTest {

    private val ctSym = ApiIndex.currentJdkCtSym()

    private fun indexFor(release: Int): ApiIndex {
        assumeTrue(ctSym != null, "this JDK ships no ct.sym")
        val index = ApiIndex.read(ctSym!!, release)
        assumeTrue(!index.isEmpty, "this JDK's ct.sym has no data for release $release")
        return index
    }

    @Test
    fun releaseCharactersFollowTheJdkEncoding() {
        assertEquals('8', ApiIndex.releaseCharacter(8))
        assertEquals('9', ApiIndex.releaseCharacter(9))
        assertEquals('A', ApiIndex.releaseCharacter(10))
        assertEquals('B', ApiIndex.releaseCharacter(11))
        assertEquals('L', ApiIndex.releaseCharacter(21))
    }

    @Test
    fun javaEightKnowsWhatJavaEightHad() {
        val eight = indexFor(8)

        assertTrue(eight.knowsType("java/util/List"), "only ${eight.typeCount} types indexed")
        assertTrue(eight.hasMember("java/util/List", "size", "()I"))
        assertTrue(eight.hasMember("java/util/List", "add", "(Ljava/lang/Object;)Z"))
        assertTrue(eight.hasMember("java/lang/String", "trim", "()Ljava/lang/String;"))
        assertTrue(eight.hasMember("java/util/Optional", "isPresent", "()Z"))
    }

    @Test
    fun javaEightDoesNotKnowWhatCameLater() {
        val eight = indexFor(8)

        // Every one of these was pinned to its release by javac --release.
        assertFalse(eight.hasMember("java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;"), "List.of is 9")
        assertFalse(eight.hasMember("java/util/Set", "of", "(Ljava/lang/Object;)Ljava/util/Set;"), "Set.of is 9")
        assertFalse(eight.hasMember("java/lang/String", "repeat", "(I)Ljava/lang/String;"), "String.repeat is 11")
        assertFalse(eight.hasMember("java/lang/String", "isBlank", "()Z"), "String.isBlank is 11")
        assertFalse(eight.hasMember("java/util/Optional", "isEmpty", "()Z"), "Optional.isEmpty is 11")
        assertFalse(eight.hasMember("java/util/List", "reversed", "()Ljava/util/List;"), "List.reversed is 21")
    }

    @Test
    fun laterReleasesKnowTheirOwnAdditions() {
        // List.of arrives in 9, String.repeat only in 11.
        val nine = indexFor(9)
        assertTrue(nine.hasMember("java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;"))
        assertFalse(nine.hasMember("java/lang/String", "repeat", "(I)Ljava/lang/String;"))

        val eleven = indexFor(11)
        assertTrue(eleven.hasMember("java/lang/String", "repeat", "(I)Ljava/lang/String;"))
        assertTrue(eleven.hasMember("java/util/Optional", "isEmpty", "()Z"))
    }

    @Test
    fun inheritedMembersCountAsPresent() {
        val eight = indexFor(8)

        // A .sig lists what its type declares and nothing more, so every one of
        // these has to be found on a supertype. Missing this reported half the
        // ordinary calls in a program as absent.
        assertTrue(eight.hasMember("java/util/ArrayList", "hashCode", "()I"), "from AbstractList")
        assertTrue(
            eight.hasMember("java/util/ArrayList", "stream", "()Ljava/util/stream/Stream;"),
            "a default method on Collection"
        )
        assertTrue(
            eight.hasMember("java/util/LinkedHashSet", "add", "(Ljava/lang/Object;)Z"),
            "from HashSet"
        )
        assertTrue(
            eight.hasMember("java/util/LinkedHashSet", "contains", "(Ljava/lang/Object;)Z"),
            "from HashSet"
        )
        assertTrue(
            eight.hasMember(
                "java/util/LinkedHashMap", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            ),
            "from HashMap"
        )
        assertTrue(eight.hasMember("java/util/ArrayList", "toString", "()Ljava/lang/String;"), "from Object")
    }

    @Test
    fun walkingSupertypesDoesNotInventMembers() {
        val eight = indexFor(8)

        // The walk must not turn into "anything goes": these are still absent on 8.
        assertFalse(eight.hasMember("java/util/ArrayList", "reversed", "()Ljava/util/List;"))
        assertFalse(eight.hasMember("java/util/LinkedHashSet", "of", "(Ljava/lang/Object;)Ljava/util/Set;"))
        assertFalse(eight.hasMember("java/util/ArrayList", "definitelyNotAMethod", "()V"))
    }

    @Test
    fun everyRebuiltApiArrivedWhenTheTransformSaysItDid() {
        // ApiTransform carries a release per rebuild so it can work without an
        // index. Those numbers are hand-written, so each is checked against
        // ct.sym here: absent the release before, present at it.
        val cases = listOf(
            Triple(9, "java/util/List", "of" to "(Ljava/lang/Object;)Ljava/util/List;"),
            Triple(9, "java/util/List", "of" to "([Ljava/lang/Object;)Ljava/util/List;"),
            Triple(9, "java/util/Set", "of" to "(Ljava/lang/Object;)Ljava/util/Set;"),
            Triple(9, "java/util/Set", "of" to "([Ljava/lang/Object;)Ljava/util/Set;"),
            Triple(9, "java/util/Map", "of" to "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"),
            Triple(9, "java/util/Map", "entry" to "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map${'$'}Entry;"),
            Triple(9, "java/util/Map", "ofEntries" to "([Ljava/util/Map${'$'}Entry;)Ljava/util/Map;"),
            Triple(9, "java/util/Objects", "requireNonNullElse" to
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            Triple(10, "java/util/List", "copyOf" to "(Ljava/util/Collection;)Ljava/util/List;"),
            Triple(10, "java/util/Set", "copyOf" to "(Ljava/util/Collection;)Ljava/util/Set;"),
            Triple(10, "java/util/Map", "copyOf" to "(Ljava/util/Map;)Ljava/util/Map;"),
            Triple(10, "java/util/Optional", "orElseThrow" to "()Ljava/lang/Object;"),
            Triple(11, "java/util/Optional", "isEmpty" to "()Z"),
            Triple(11, "java/lang/String", "repeat" to "(I)Ljava/lang/String;")
        )

        cases.forEach { (introducedIn, owner, member) ->
            val (name, descriptor) = member
            assertTrue(
                indexFor(introducedIn).hasMember(owner, name, descriptor),
                "$owner.$name$descriptor should exist at $introducedIn"
            )
            assertFalse(
                indexFor(introducedIn - 1).hasMember(owner, name, descriptor),
                "$owner.$name$descriptor should not exist at ${introducedIn - 1}"
            )
        }
    }

    @Test
    fun typesTheJdkNeverHadAreNotItsProblem() {
        val eight = indexFor(8)

        assertFalse(eight.knowsType("dev/iiahmed/lowbyte/Whatever"))
        assertFalse(eight.knowsType("org/objectweb/asm/ClassReader"))
    }
}
